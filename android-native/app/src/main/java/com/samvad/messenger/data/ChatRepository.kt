package com.samvad.messenger.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.ConversationIds
import com.samvad.core.chat.ContactCard
import com.samvad.core.chat.DisplayNames
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.Pin
import com.samvad.core.chat.Poll
import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.Profile
import com.samvad.core.gifs.GifKind
import com.samvad.core.gifs.GifRef
import com.samvad.core.gifs.GifRules
import com.samvad.core.media.Media
import com.samvad.core.e2ee.AttachmentCrypto
import com.samvad.core.e2ee.AttachmentRef
import com.samvad.core.e2ee.E2eePolicy
import com.samvad.core.e2ee.Payload
import com.samvad.messenger.data.e2ee.AttachmentKey
import com.samvad.messenger.data.e2ee.AttachmentKeys
import com.samvad.messenger.data.e2ee.E2eeSendException
import com.samvad.messenger.data.e2ee.E2eeService
import com.samvad.messenger.data.e2ee.Opened
import com.samvad.messenger.data.e2ee.PeerKeys
import com.samvad.messenger.data.e2ee.EncryptedMediaRef
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

data class Snapshot<T>(val value: T, val fromCache: Boolean)

data class MessagesPage(val messages: List<Message>, val hasMore: Boolean, val fromCache: Boolean)

data class StarredRef(val conversationId: String, val messageId: String, val savedAt: Long)

/** [status]: pending, sent, failed or cancelled. [failure]: not_member, blocked, account_deleted or invalid. */
data class ScheduledItem(
  val id: String,
  val conversationId: String,
  val text: String,
  val sendAt: Long,
  val status: String,
  val failure: String?,
)

const val ANNOUNCEMENTS_TYPE = "community_announcements"

/** Conversations, member state and messages (B2). Firestore's offline cache provides offline mode. */
class ChatRepository(
  private val db: FirebaseFirestore,
  /** End-to-end encryption; null when the build has no Firebase. Chats the server marks encrypted never fall back to plaintext. */
  private val e2ee: E2eeService? = null,
  private val attachmentKeys: AttachmentKeys? = null,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Conversation id to "the server says it is end-to-end encrypted", learned from the conversation documents. */
  private val e2eeChats = ConcurrentHashMap<String, Boolean>()
  private val previewTick = MutableStateFlow(0)
  private val prefetching = ConcurrentHashMap.newKeySet<String>()
  private val attempted = ConcurrentHashMap.newKeySet<String>()

  init {
    // A trust decision can unlock messages that were held back, so previews are tried again.
    e2ee?.let { service -> scope.launch { service.trustVersion.collect { attempted.clear(); previewTick.update { it + 1 } } } }
  }

  /** True when the chat is end-to-end encrypted. Reads the document once when it has not been seen yet. */
  suspend fun isE2ee(conversationId: String): Boolean {
    if (e2ee == null || !e2ee.enabled) return e2eeChats[conversationId] == true
    e2eeChats[conversationId]?.let { return it }
    val snap = runCatching { db.document("conversations/$conversationId").get().await() }.getOrNull()
    val value = snap?.getBoolean("e2ee") == true
    if (snap?.exists() == true) e2eeChats[conversationId] = value
    return value
  }

  fun isKnownE2ee(conversationId: String): Boolean = e2eeChats[conversationId] == true

  private fun otherOf(conversationId: String, me: String): String =
    conversationId.split('_').firstOrNull { it != me } ?: throw IllegalArgumentException("not a direct chat")

  private val profileCache = MutableStateFlow<Map<String, Profile>>(emptyMap())

  /** Profiles as their owners wrote them. Use for text others will read, such as @mentions. */
  val rawProfiles: StateFlow<Map<String, Profile>> = profileCache.asStateFlow()

  private val named = MutableStateFlow<Map<String, Profile>>(emptyMap())

  /** Profiles named for this device: saved contact name, else "~ Profile name" (US-002). */
  val profiles: StateFlow<Map<String, Profile>> = named.asStateFlow()

  @Volatile private var savedNames = SavedNames()
  @Volatile private var me: String? = null

  /** Called when the signed-in user or the saved-names map changes. */
  fun useNames(me: String?, saved: SavedNames) {
    this.me = me
    savedNames = saved
    rename()
  }

  private fun rename() {
    val saved = savedNames
    named.value = profileCache.value.mapValues { (uid, p) ->
      if (uid == me) p else p.copy(displayName = DisplayNames.of(p.displayName, saved.byUid[uid], saved.available))
    }
  }

  private val failed = MutableStateFlow<Set<String>>(emptySet())

  fun conversations(me: String): Flow<Snapshot<List<Conversation>>> =
    combine(rawConversations(me), previewTick) { snap, _ -> snap }
      .mapLatest { snap -> Snapshot(resolveLastMessages(me, snap.value), snap.fromCache) }

  /**
   * An encrypted chat's last message has no preview on the server. Show what this phone has decrypted; for a
   * message it has not opened yet, fetch and decrypt that one message in the background and refresh the list.
   */
  private fun resolveLastMessages(me: String, list: List<Conversation>): List<Conversation> = list.map { c ->
    val last = c.lastMessage
    if (e2ee == null || !c.e2ee || last == null || !last.encrypted) return@map c
    val preview = e2ee.cachedPreview(c.id, last.id)
    if (preview != null) return@map c.copy(lastMessage = last.copy(preview = preview))
    val key = "${c.id}/${last.id}"
    if (attempted.add(key) && prefetching.add(key)) {
      scope.launch {
        try {
          val doc = db.document("conversations/${c.id}/messages/${last.id}").get().await()
          if (doc.exists() && doc.get("enc") is Map<*, *>) {
            val opened = e2ee.open(me, c.id, last.id, doc.getString("senderId") ?: "", doc.get("enc") as Map<*, *>)
            if (opened is Opened.Ready) previewTick.update { it + 1 }
          }
        } catch (_: Exception) {
          // The list simply keeps its generic "Encrypted message" line.
        } finally {
          prefetching.remove(key)
        }
      }
    }
    c
  }

  private fun rawConversations(me: String): Flow<Snapshot<List<Conversation>>> = callbackFlow {
    val reg = db.collection("conversations")
      .whereArrayContains("members", me)
      .orderBy("lastMessageAt", Query.Direction.DESCENDING)
      .limit(200)
      .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
        if (err != null) {
          close(err)
          return@addSnapshotListener
        }
        // A community's announcements conversation lives in Communities, not in the chat list.
        if (snap != null) trySend(Snapshot(snap.documents.filter { it.getString("type") != ANNOUNCEMENTS_TYPE }.map(::toConversation), snap.metadata.isFromCache))
      }
    awaitClose { reg.remove() }
  }

  /** My member doc for each conversation, as one map keyed by conversation ID. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun memberStates(me: String, conversationIds: Flow<List<String>>): Flow<Map<String, MemberState>> =
    conversationIds.flatMapLatest { ids ->
      if (ids.isEmpty()) flowOf(emptyMap())
      else combine(ids.map { id -> memberState(id, me) }) { states ->
        ids.zip(states.toList()).associate { (id, s) -> id to (s ?: MemberState(me)) }
      }
    }

  fun memberState(conversationId: String, uid: String): Flow<MemberState?> = callbackFlow {
    val reg = db.document("conversations/$conversationId/members/$uid").addSnapshotListener { snap, _ ->
      trySend(snap?.takeIf { it.exists() }?.let(::toMemberState))
    }
    awaitClose { reg.remove() }
  }

  suspend fun loadProfiles(uids: Collection<String>) = fetchProfiles(uids.filter { it !in profileCache.value })

  /** Re-reads profiles even when cached, so a new name or photo shows up (one read each). */
  suspend fun refreshProfiles(uids: Collection<String>) = fetchProfiles(uids)

  /**
   * "About" and the photo path (Phase 3) live in profiles/{uid}/private/about and /photo, each readable only
   * per that person's privacy setting; a denied or missing read is simply treated as blank/absent, same as
   * someone who never set them.
   */
  private suspend fun fetchProfiles(uids: Collection<String>) {
    for (uid in uids) {
      val snap = runCatching { db.document("profiles/$uid").get().await() }.getOrNull() ?: continue
      if (snap.exists()) {
        val about = runCatching { db.document("profiles/$uid/private/about").get().await() }.getOrNull()?.getString("about") ?: ""
        val photo = runCatching { db.document("profiles/$uid/private/photo").get().await() }.getOrNull()?.getString("photoUrl")
        val p = Profile(uid, snap.getString("displayName") ?: "", about, photo)
        profileCache.update { it + (uid to p) }
        rename()
      }
    }
  }

  /** Returns the direct conversation ID, creating the conversation and member docs if needed. */
  suspend fun openDirect(me: String, other: String, onCreated: suspend (String) -> Unit = {}): String {
    val id = ConversationIds.direct(me, other)
    val ref = db.document("conversations/$id")
    val exists = runCatching { ref.get().await().exists() }.getOrDefault(false)
    if (!exists) {
      val members = listOf(me, other).sorted()
      // A new chat is end-to-end encrypted from its first message when this build can encrypt and both people have
      // published keys. Otherwise it is created as an ordinary chat and says so (see EncryptionBanner).
      val encrypted = e2ee != null && E2eePolicy.newChatShouldBeEncrypted(
        e2ee.enabled, isGroup = false, peerHasKeys = e2ee.ensureReady(me) && e2ee.peerHasKeys(me, other),
      )
      suspend fun create(withEncryption: Boolean) {
        val batch = db.batch()
        batch.set(
          ref,
          buildMap<String, Any?> {
            put("type", "direct")
            put("members", members)
            put("createdBy", me)
            put("createdAt", FieldValue.serverTimestamp())
            put("lastMessage", null)
            put("lastMessageAt", FieldValue.serverTimestamp())
            if (withEncryption) put("e2ee", true)
          },
        )
        members.forEach { uid ->
          batch.set(
            db.document("conversations/$id/members/$uid"),
            mapOf(
              "uid" to uid, "lastReadAt" to null, "lastDeliveredAt" to null, "unreadCount" to 0,
              "muted" to false, "pinned" to false, "archived" to false,
            ),
          )
        }
        batch.commit().await()
      }
      try {
        create(encrypted)
        if (encrypted) e2eeChats[id] = true
      } catch (e: FirebaseFirestoreException) {
        // The server refused the encryption flag (the other person's keys were removed in the meantime): plain chat.
        if (!encrypted || e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
        create(false)
      }
      onCreated(id)
    }
    return id
  }

  /**
   * Switches an existing chat to end-to-end encryption. Only messages sent from now on are protected; history stays
   * as it is. Returns null on success, otherwise what stands in the way.
   */
  suspend fun upgradeToE2ee(conversationId: String, me: String): PeerKeys? {
    val service = e2ee ?: return PeerKeys.UNAVAILABLE
    if (!service.enabled) return PeerKeys.UNAVAILABLE
    val other = otherOf(conversationId, me)
    if (!service.ensureReady(me)) return PeerKeys.UNAVAILABLE
    val peer = service.ensureSession(me, other)
    if (peer != PeerKeys.READY) return peer
    return if (service.upgradeConversation(conversationId)) null else PeerKeys.UNAVAILABLE
  }

  /** Newest [limit] messages, ascending. Growing [limit] pages back through history. */
  /**
   * Newest [limit] messages. In groups pass [since] = my joinedAt: the rules only let members read
   * messages from when they joined, so the query must say so.
   */
  fun messages(conversationId: String, limit: Long, since: Long? = null): Flow<MessagesPage> =
    combine(
      rawMessages(conversationId, limit, since).mapLatest { raw ->
        // Oldest first, so the first message of a new session is opened before the ones that follow it.
        val resolved = raw.docs.map { toMessageResolved(conversationId, it) }
        // A server-confirmed page is the truth about which encrypted messages still exist: the phone's decrypted copy
        // of anything that disappeared or was deleted is removed too.
        if (e2ee != null && !raw.fromCache && isKnownE2ee(conversationId) && resolved.isNotEmpty()) {
          e2ee.pruneChat(conversationId, resolved.map { it.id }.toSet(), resolved.minOf { it.createdAt })
        }
        MessagesPage(resolved, raw.hasMore, raw.fromCache)
      },
      failed,
    ) { page, failedIds ->
      page.copy(messages = page.messages.map { if (it.id in failedIds) it.copy(failed = true) else it })
    }

  private class RawPage(val docs: List<DocumentSnapshot>, val hasMore: Boolean, val fromCache: Boolean)

  private fun rawMessages(conversationId: String, limit: Long, since: Long?): Flow<RawPage> = callbackFlow {
    var query: Query = db.collection("conversations/$conversationId/messages")
    if (since != null) query = query.whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(since)))
    val reg = query
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .limit(limit)
      .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
        if (err != null) {
          close(err)
          return@addSnapshotListener
        }
        if (snap != null) {
          trySend(RawPage(snap.documents.reversed(), hasMore = snap.size() >= limit, fromCache = snap.metadata.isFromCache))
        }
      }
    awaitClose { reg.remove() }
  }

  /** Sends with a client-generated ID so a retry can never duplicate the message. */
  suspend fun send(
    conversationId: String,
    me: String,
    text: String,
    replyTo: String?,
    id: String = UUID.randomUUID().toString(),
    forwarded: Boolean = false,
    mentions: List<String> = emptyList(),
    /** A reply to a moment: (author, status id). The rules refuse it when replies are off or the moment is not visible to me. */
    momentRef: Pair<String, String>? = null,
  ) {
    failed.update { it - id }
    try {
      if (isE2ee(conversationId)) {
        // Moment replies, @mentions and forwarding labels would be readable by the server, so they are not carried.
        if (momentRef != null) throw E2eeUnsupportedException()
        writeEncrypted(conversationId, me, id, "text", Payload.Text(text, replyTo, forwarded), null)
        return
      }
      val doc = buildMap<String, Any?> {
        put("senderId", me)
        put("kind", "text")
        put("text", text)
        put("replyTo", replyTo)
        put("createdAt", FieldValue.serverTimestamp())
        if (forwarded) put("forwarded", true)
        if (mentions.isNotEmpty()) put("mentions", mentions)
        momentRef?.let { put("momentRef", mapOf("author" to it.first, "sid" to it.second)) }
      }
      db.document("conversations/$conversationId/messages/$id").set(doc).await()
    } catch (e: Exception) {
      failed.update { it + id }
      throw e
    }
  }

  /** Writes a media message after its file is uploaded (same client ID as the Storage folder). */
  suspend fun sendMedia(
    conversationId: String,
    me: String,
    id: String,
    kind: String,
    media: Media,
    caption: String,
    replyTo: String?,
    forwarded: Boolean = false,
    momentRef: Pair<String, String>? = null,
  ) {
    val map = buildMap<String, Any> {
      put("path", media.path)
      put("name", media.name)
      put("size", media.size)
      put("mime", media.mime)
      media.width?.let { put("width", it) }
      media.height?.let { put("height", it) }
      media.durationMs?.let { put("durationMs", it) }
      if (media.waveform.isNotEmpty()) put("waveform", media.waveform)
      media.thumbPath?.let { put("thumbPath", it) }
    }
    db.document("conversations/$conversationId/messages/$id").set(
      mapOf(
        "senderId" to me,
        "kind" to kind,
        "text" to caption,
        "replyTo" to replyTo,
        "createdAt" to FieldValue.serverTimestamp(),
        "media" to map,
      ) + (if (forwarded) mapOf("forwarded" to true) else emptyMap()) +
        (momentRef?.let { mapOf("momentRef" to mapOf("author" to it.first, "sid" to it.second)) } ?: emptyMap()),
    ).await()
  }

  /**
   * Sends a GIF or sticker by link (nothing is uploaded). Throws IllegalArgumentException for a link the rules would
   * refuse. A client ID makes a retry harmless.
   */
  suspend fun sendGif(
    conversationId: String,
    me: String,
    kind: GifKind,
    gif: GifRef,
    caption: String = "",
    replyTo: String? = null,
    forwarded: Boolean = false,
    id: String = UUID.randomUUID().toString(),
  ) {
    require(GifRules.isValid(gif)) { "GIF link is not allowed" }
    db.document("conversations/$conversationId/messages/$id").set(
      mapOf(
        "senderId" to me,
        "kind" to kind.wire,
        "text" to GifRules.captionFor(kind, caption),
        "replyTo" to replyTo,
        "createdAt" to FieldValue.serverTimestamp(),
        "gif" to mapOf(
          "url" to gif.url, "previewUrl" to gif.previewUrl, "width" to gif.width, "height" to gif.height,
          "provider" to gif.provider, "id" to gif.id,
        ),
      ) + (if (forwarded) mapOf("forwarded" to true) else emptyMap()),
    ).await()
  }

  /**
   * Writes an encrypted attachment message. The files are already uploaded as ciphertext ([SealedMedia] holds the keys);
   * the document carries only the envelope and the two storage paths. Name, type, size and dimensions are inside the envelope.
   */
  suspend fun sendEncryptedMedia(
    conversationId: String,
    me: String,
    id: String,
    sealed: EncryptedMediaRef,
    caption: String,
    replyTo: String?,
    forwarded: Boolean = false,
  ) {
    val folder = "conversations/$conversationId/$id"
    val b64 = java.util.Base64.getEncoder()
    val file = AttachmentRef("$folder/blob.bin", b64.encodeToString(sealed.fileKey), sealed.fileDigest, sealed.fileSize)
    val thumb = sealed.thumbKey?.let { AttachmentRef("$folder/thumb.bin", b64.encodeToString(it), sealed.thumbDigest!!, sealed.thumbSize!!) }
    val payload = Payload.Media(
      kind = sealed.kind, name = sealed.name, mime = sealed.mime, caption = caption, file = file, thumb = thumb,
      width = sealed.width, height = sealed.height, durationMs = sealed.durationMs, waveform = sealed.waveform,
      replyTo = replyTo, forwarded = forwarded,
    )
    val media = buildMap<String, Any> {
      put("path", file.path)
      put("size", file.size)
      thumb?.let { put("thumbPath", it.path) }
    }
    writeEncrypted(conversationId, me, id, sealed.kind, payload, media)
  }

  /**
   * Encrypts [payload] for the other person and writes the message document. Never writes plaintext: when
   * encryption is impossible this throws [E2eeSendException]. If the server refuses the envelope because the other
   * person's keys changed (a new phone), the session is rebuilt once; a changed security code then waits for the user.
   */
  private suspend fun writeEncrypted(conversationId: String, me: String, id: String, kind: String, payload: Payload, media: Map<String, Any>?) {
    val service = e2ee ?: throw E2eeSendException(PeerKeys.UNAVAILABLE)
    val other = otherOf(conversationId, me)
    val path = "conversations/$conversationId/messages/$id"
    fun document(env: com.samvad.core.e2ee.Envelope) = buildMap<String, Any?> {
      put("senderId", me)
      put("kind", kind)
      put("text", "")
      put("replyTo", null)
      put("createdAt", FieldValue.serverTimestamp())
      put("enc", env.toMap())
      media?.let { put("media", it) }
    }
    val first = service.seal(me, other, conversationId, id, payload)
    try {
      db.document(path).set(document(first)).await()
    } catch (e: FirebaseFirestoreException) {
      if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
      val peer = service.ensureSession(me, other, rebuild = true)
      if (peer != PeerKeys.READY) throw E2eeSendException(peer)
      db.document(path).set(document(service.seal(me, other, conversationId, id, payload))).await()
    }
  }

  /**
   * True when the message document already exists (written, or queued locally by an earlier attempt), so a
   * resumed upload never creates a second message with the same client ID.
   */
  suspend fun messageExists(conversationId: String, id: String): Boolean =
    db.document("conversations/$conversationId/messages/$id").get().await().exists()

  suspend fun retry(conversationId: String, me: String, message: Message) {
    val existing = runCatching { db.document("conversations/$conversationId/messages/${message.id}").get().await() }.getOrNull()
    if (existing?.exists() == true && existing.metadata.hasPendingWrites().not()) {
      failed.update { it - message.id }
      return
    }
    val gif = message.gif
    val gifKind = GifKind.fromWire(message.kind)
    if (gif != null && gifKind != null) {
      sendGif(conversationId, me, gifKind, gif, message.text, message.replyTo, id = message.id)
      return
    }
    send(conversationId, me, message.text, message.replyTo, message.id)
  }

  /** [me] and [original] are needed in an encrypted chat: the edit is a new ciphertext that keeps the reply link. */
  suspend fun edit(conversationId: String, messageId: String, text: String, me: String? = null, original: Message? = null) {
    if (isE2ee(conversationId) && original?.encrypted == true) {
      val self = me ?: throw IllegalArgumentException("edit needs the sender")
      val other = otherOf(conversationId, self)
      val payload = Payload.Text(text, original.replyTo, original.forwarded)
      var envelope = e2ee!!.seal(self, other, conversationId, messageId, payload)
      try {
        db.document("conversations/$conversationId/messages/$messageId")
          .update(mapOf("enc" to envelope.toMap(), "editedAt" to FieldValue.serverTimestamp())).await()
      } catch (e: FirebaseFirestoreException) {
        if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
        val peer = e2ee.ensureSession(self, other, rebuild = true)
        if (peer != PeerKeys.READY) throw E2eeSendException(peer)
        envelope = e2ee.seal(self, other, conversationId, messageId, payload)
        db.document("conversations/$conversationId/messages/$messageId")
          .update(mapOf("enc" to envelope.toMap(), "editedAt" to FieldValue.serverTimestamp())).await()
      }
      return
    }
    if (isE2ee(conversationId)) throw E2eeUnsupportedException()
    db.document("conversations/$conversationId/messages/$messageId")
      .update(mapOf("text" to text, "editedAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun deleteForEveryone(conversationId: String, messageId: String) {
    val changes = buildMap<String, Any> {
      put("deleted", true)
      put("text", "")
      // An encrypted message loses its ciphertext too; nothing of it is kept on the server.
      if (isE2ee(conversationId)) put("enc", FieldValue.delete())
    }
    db.document("conversations/$conversationId/messages/$messageId").update(changes).await()
    e2ee?.forgetMessage(conversationId, messageId)
  }

  suspend fun react(conversationId: String, messageId: String, me: String, emoji: String?) {
    db.document("conversations/$conversationId/messages/$messageId")
      .update("reactions.$me", emoji ?: FieldValue.delete()).await()
  }

  /** With [sendReceipt] off (read receipts disabled), the unread count resets but lastReadAt is not shared. */
  suspend fun markRead(conversationId: String, me: String, sendReceipt: Boolean = true) {
    runCatching {
      val update = buildMap<String, Any> {
        if (sendReceipt) put("lastReadAt", FieldValue.serverTimestamp())
        put("lastDeliveredAt", FieldValue.serverTimestamp())
        put("unreadCount", 0)
      }
      db.document("conversations/$conversationId/members/$me").update(update).await()
    }
  }

  /** Phase 2: posts a poll (validated by Polls.create and again by the rules). */
  suspend fun sendPoll(conversationId: String, me: String, poll: Poll) {
    if (isE2ee(conversationId)) throw E2eeUnsupportedException()
    db.document("conversations/$conversationId/messages/${UUID.randomUUID()}").set(
      mapOf(
        "senderId" to me,
        "kind" to "poll",
        "text" to "",
        "replyTo" to null,
        "createdAt" to FieldValue.serverTimestamp(),
        "poll" to mapOf("question" to poll.question, "options" to poll.options, "ids" to poll.ids, "multi" to poll.multi),
      ),
    ).await()
  }

  /** Phase 2: shares a contact card (validated by ContactCards.build and again by the rules). */
  suspend fun sendContact(conversationId: String, me: String, card: ContactCard, replyTo: String? = null, id: String = UUID.randomUUID().toString()) {
    if (isE2ee(conversationId)) {
      writeEncrypted(conversationId, me, id, "contact", Payload.Contact(card, replyTo), null)
      return
    }
    db.document("conversations/$conversationId/messages/$id").set(
      mapOf(
        "senderId" to me,
        "kind" to "contact",
        "text" to "",
        "replyTo" to replyTo,
        "createdAt" to FieldValue.serverTimestamp(),
        "contact" to buildMap<String, Any> {
          put("name", card.name)
          put("phones", card.phones)
          card.organisation?.let { put("organisation", it) }
        },
      ),
    ).await()
  }

  /** Everyone's votes on a poll: voter → chosen option ids. */
  fun votes(conversationId: String, messageId: String): Flow<Map<String, List<String>>> = callbackFlow {
    val reg = db.collection("conversations/$conversationId/messages/$messageId/votes").addSnapshotListener { snap, _ ->
      if (snap != null) {
        trySend(snap.documents.associate { d -> d.id to ((d.get("choices") as? List<*>)?.filterIsInstance<String>() ?: emptyList()) })
      }
    }
    awaitClose { reg.remove() }
  }

  /** Sets my vote; an empty choice set withdraws it. */
  suspend fun vote(conversationId: String, messageId: String, me: String, choices: Set<String>) {
    val ref = db.document("conversations/$conversationId/messages/$messageId/votes/$me")
    if (choices.isEmpty()) ref.delete().await()
    else ref.set(mapOf("choices" to choices.sorted(), "updatedAt" to FieldValue.serverTimestamp())).await()
  }

  /**
   * Group unread count: messages after [since] (one aggregation read per 1,000 matches) instead of a
   * server counter per member.
   */
  suspend fun countSince(conversationId: String, since: Long): Int =
    db.collection("conversations/$conversationId/messages")
      .whereGreaterThan("createdAt", Timestamp(Date(since)))
      .count().get(AggregateSource.SERVER).await().count.toInt()

  /** One conversation document, live (group name, members, admins, photo). */
  fun conversation(conversationId: String): Flow<Conversation?> = callbackFlow {
    val reg = db.document("conversations/$conversationId").addSnapshotListener { snap, _ ->
      trySend(snap?.takeIf { it.exists() }?.let(::toConversation))
    }
    awaitClose { reg.remove() }
  }

  /** Up to [limit] newest messages, oldest first, for a chat export (one read per message). */
  suspend fun exportMessages(conversationId: String, limit: Int, since: Long? = null): List<Message> =
    db.collection("conversations/$conversationId/messages")
      .let { if (since != null) it.whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(since))) else it }
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .limit(limit.toLong())
      .get().await()
      .documents.reversed().map { toMessageResolved(conversationId, it) }

  suspend fun markDelivered(conversationId: String, me: String) {
    runCatching {
      db.document("conversations/$conversationId/members/$me")
        .update("lastDeliveredAt", FieldValue.serverTimestamp()).await()
    }
  }

  /** My starred messages, newest first (Phase 3). References only; the message is read on demand. */
  fun starred(me: String): Flow<List<StarredRef>> = callbackFlow {
    val reg = db.collection("users/$me/savedMessages")
      .orderBy("savedAt", Query.Direction.DESCENDING)
      .limit(500)
      .addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        if (snap != null) {
          trySend(
            snap.documents.mapNotNull { d ->
              val cid = d.getString("conversationId") ?: return@mapNotNull null
              val mid = d.getString("messageId") ?: return@mapNotNull null
              StarredRef(cid, mid, d.millis("savedAt") ?: System.currentTimeMillis())
            },
          )
        }
      }
    awaitClose { reg.remove() }
  }

  suspend fun star(me: String, conversationId: String, messageId: String) {
    db.document("users/$me/savedMessages/${conversationId}_$messageId").set(
      mapOf("conversationId" to conversationId, "messageId" to messageId, "savedAt" to FieldValue.serverTimestamp()),
    ).await()
  }

  suspend fun unstar(me: String, conversationId: String, messageId: String) {
    db.document("users/$me/savedMessages/${conversationId}_$messageId").delete().await()
  }

  /** My scheduled messages, soonest first (Phase 3). The server sends them; see sendScheduledMessages. */
  fun scheduled(me: String): Flow<List<ScheduledItem>> = callbackFlow {
    val reg = db.collection("scheduledMessages")
      .whereEqualTo("senderId", me)
      .orderBy("sendAt")
      .limit(300)
      .addSnapshotListener { snap, err ->
        if (err != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        if (snap != null) {
          trySend(
            snap.documents.mapNotNull { d ->
              ScheduledItem(
                id = d.id,
                conversationId = d.getString("conversationId") ?: return@mapNotNull null,
                text = d.getString("text").orEmpty(),
                sendAt = d.millis("sendAt") ?: return@mapNotNull null,
                status = d.getString("status") ?: "pending",
                failure = d.getString("failure"),
              )
            },
          )
        }
      }
    awaitClose { reg.remove() }
  }

  suspend fun schedule(me: String, conversationId: String, text: String, replyTo: String?, sendAtMs: Long) {
    // The server would have to write the text in the clear, which an encrypted chat refuses.
    if (isE2ee(conversationId)) throw E2eeUnsupportedException()
    db.collection("scheduledMessages").document().set(
      mapOf(
        "senderId" to me, "conversationId" to conversationId, "text" to text, "replyTo" to replyTo,
        "sendAt" to Timestamp(Date(sendAtMs)), "status" to "pending",
        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
      ),
    ).await()
  }

  suspend fun updateScheduled(id: String, text: String? = null, sendAtMs: Long? = null) {
    val changes = buildMap<String, Any> {
      text?.let { put("text", it) }
      sendAtMs?.let { put("sendAt", Timestamp(Date(it))) }
      put("updatedAt", FieldValue.serverTimestamp())
    }
    db.document("scheduledMessages/$id").update(changes).await()
  }

  suspend fun cancelScheduled(id: String) {
    db.document("scheduledMessages/$id").update(mapOf("status" to "cancelled", "updatedAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun deleteScheduled(id: String) {
    db.document("scheduledMessages/$id").delete().await()
  }

  /** One message, or null when it no longer exists or I may no longer read it (e.g. left the group). */
  suspend fun message(conversationId: String, messageId: String): Message? =
    runCatching { db.document("conversations/$conversationId/messages/$messageId").get().await() }
      .getOrNull()?.takeIf { it.exists() }?.let { toMessageResolved(conversationId, it) }

  suspend fun setFlag(conversationId: String, me: String, field: String, value: Boolean) {
    require(field in setOf("muted", "pinned", "archived"))
    db.document("conversations/$conversationId/members/$me").update(field, value).await()
  }

  private fun DocumentSnapshot.millis(field: String): Long? =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time

  private fun toConversation(d: DocumentSnapshot): Conversation {
    @Suppress("UNCHECKED_CAST")
    val last = d.get("lastMessage") as? Map<String, Any?>
    e2eeChats[d.id] = d.getBoolean("e2ee") == true
    return Conversation(
      id = d.id,
      members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
      lastMessage = last?.let {
        LastMessage(
          id = it["id"] as? String ?: "",
          senderId = it["senderId"] as? String ?: "",
          preview = it["preview"] as? String ?: "",
          kind = it["kind"] as? String ?: "text",
          createdAt = (it["createdAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L,
          system = (it["system"] as? Map<*, *>)?.let(::toSystem),
          expiresAt = (it["expiresAt"] as? com.google.firebase.Timestamp)?.toDate()?.time,
          encrypted = it["encrypted"] == true,
        )
      },
      lastMessageAt = d.millis("lastMessageAt") ?: 0L,
      isGroup = d.getString("type") == "group",
      name = d.getString("name"),
      admins = (d.get("admins") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
      description = d.getString("description") ?: "",
      photoPath = d.getString("photoUrl"),
      disappearAfterSec = d.getLong("disappearAfterSec") ?: 0L,
      onlyAdminsSend = d.getBoolean("onlyAdminsSend") ?: false,
      approveMembers = d.getBoolean("approveMembers") ?: false,
      eventsByMembers = d.getBoolean("eventsByMembers") ?: false,
      communityId = d.getString("communityId"),
      e2ee = d.getBoolean("e2ee") == true,
      e2eeSince = d.millis("e2eeSince"),
      pinnedMessages = (d.get("pinnedMessages") as? List<*>).orEmpty().mapNotNull { raw ->
        val m = raw as? Map<*, *> ?: return@mapNotNull null
        Pin(
          messageId = m["messageId"] as? String ?: return@mapNotNull null,
          pinnedBy = m["pinnedBy"] as? String ?: "",
          pinnedAt = (m["pinnedAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L,
        )
      },
    )
  }

  private fun toSystem(m: Map<*, *>): SystemInfo? {
    val event = m["event"] as? String ?: return null
    return SystemInfo(event, (m["targets"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(), m["name"] as? String, (m["seconds"] as? Number)?.toLong())
  }

  private fun toContact(m: Map<*, *>): ContactCard? {
    val name = m["name"] as? String ?: return null
    val phones = (m["phones"] as? List<*>)?.filterIsInstance<String>() ?: return null
    return ContactCard(name, phones, m["organisation"] as? String)
  }

  private fun toPoll(m: Map<*, *>): Poll? {
    val question = m["question"] as? String ?: return null
    val options = (m["options"] as? List<*>)?.filterIsInstance<String>() ?: return null
    val ids = (m["ids"] as? List<*>)?.filterIsInstance<String>() ?: return null
    if (options.size != ids.size) return null
    return Poll(question, options, ids, m["multi"] == true)
  }

  private fun toMemberState(d: DocumentSnapshot) = MemberState(
    uid = d.getString("uid") ?: d.id,
    lastReadAt = d.millis("lastReadAt"),
    lastDeliveredAt = d.millis("lastDeliveredAt"),
    unreadCount = (d.getLong("unreadCount") ?: 0L).toInt(),
    muted = d.getBoolean("muted") ?: false,
    pinned = d.getBoolean("pinned") ?: false,
    archived = d.getBoolean("archived") ?: false,
    joinedAt = d.millis("joinedAt"),
  )

  private fun toMedia(m: Map<*, *>): Media? {
    val path = m["path"] as? String ?: return null
    return Media(
      path = path,
      name = m["name"] as? String ?: "file",
      size = (m["size"] as? Number)?.toLong() ?: 0L,
      mime = m["mime"] as? String ?: "",
      width = (m["width"] as? Number)?.toInt(),
      height = (m["height"] as? Number)?.toInt(),
      durationMs = (m["durationMs"] as? Number)?.toLong(),
      waveform = (m["waveform"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
      thumbPath = m["thumbPath"] as? String,
    )
  }

  /** A GIF or sticker link; one that is not on the provider allow-list is ignored, so it is never loaded. */
  private fun toGif(m: Map<*, *>): GifRef? {
    val ref = GifRef(
      id = m["id"] as? String ?: return null,
      title = "",
      previewUrl = m["previewUrl"] as? String ?: return null,
      url = m["url"] as? String ?: return null,
      width = (m["width"] as? Number)?.toInt() ?: return null,
      height = (m["height"] as? Number)?.toInt() ?: return null,
      provider = m["provider"] as? String ?: "",
    )
    return ref.takeIf(GifRules::isValid)
  }

  private fun toMeetingCard(m: Map<*, *>): com.samvad.core.chat.MeetingCard? {
    val id = m["meetingId"] as? String ?: return null
    val startsAt = (m["startsAt"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: return null
    return com.samvad.core.chat.MeetingCard(id, m["title"] as? String ?: "", startsAt, m["kind"] as? String ?: "audio")
  }

  /**
   * A message as the screens use it. Encrypted ones (they carry an `enc` envelope) are opened on this phone: the
   * server never held their text. A message that cannot be opened comes back with [Message.lock] set and empty text.
   */
  private suspend fun toMessageResolved(conversationId: String, d: DocumentSnapshot): Message {
    val enc = d.get("enc") as? Map<*, *> ?: return toMessage(d)
    val base = toMessage(d).copy(encrypted = true)
    if (base.deleted) return base
    val service = e2ee ?: return base.copy(lock = com.samvad.core.e2ee.LockReason.DISABLED.name)
    val self = me ?: return base.copy(lock = com.samvad.core.e2ee.LockReason.UNREADABLE.name)
    return when (val opened = service.open(self, conversationId, d.id, base.senderId, enc)) {
      is Opened.Locked -> base.copy(lock = opened.reason.name)
      is Opened.Ready -> when (val p = opened.payload) {
        is Payload.Text -> base.copy(text = p.text, replyTo = p.replyTo, forwarded = p.forwarded)
        is Payload.Contact -> base.copy(contact = ContactCard(p.card.name, p.card.phones, p.card.organisation), replyTo = p.replyTo, forwarded = p.forwarded)
        is Payload.Media -> {
          val ext = extensionFor(p.name, p.mime)
          attachmentKeys?.put(p.file.path, AttachmentKey(p.file.keyBytes(), p.file.digest, ext))
          p.thumb?.let { attachmentKeys?.put(it.path, AttachmentKey(it.keyBytes(), it.digest, "jpg")) }
          base.copy(
            text = p.caption,
            replyTo = p.replyTo,
            forwarded = p.forwarded,
            media = Media(
              path = p.file.path,
              name = p.name,
              size = (p.file.size - AttachmentCrypto.OVERHEAD).coerceAtLeast(0),
              mime = p.mime,
              width = p.width,
              height = p.height,
              durationMs = p.durationMs,
              waveform = p.waveform,
              thumbPath = p.thumb?.path,
            ),
          )
        }
      }
    }
  }

  private fun extensionFor(name: String, mime: String): String {
    name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }?.let { return it }
    return when (mime) {
      "image/jpeg" -> "jpg"
      "image/png" -> "png"
      "image/webp" -> "webp"
      "video/mp4" -> "mp4"
      "audio/mp4" -> "m4a"
      "audio/aac" -> "aac"
      "application/pdf" -> "pdf"
      else -> "bin"
    }
  }

  private fun toMessage(d: DocumentSnapshot): Message {
    @Suppress("UNCHECKED_CAST")
    val reactions = (d.get("reactions") as? Map<String, Any?>)?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }?.toMap()
    return Message(
      id = d.id,
      senderId = d.getString("senderId") ?: "",
      text = d.getString("text") ?: "",
      createdAt = d.millis("createdAt") ?: System.currentTimeMillis(),
      replyTo = d.getString("replyTo"),
      editedAt = d.millis("editedAt"),
      deleted = d.getBoolean("deleted") ?: false,
      reactions = reactions ?: emptyMap(),
      pending = d.metadata.hasPendingWrites(),
      kind = d.getString("kind") ?: "text",
      media = (d.get("media") as? Map<*, *>)?.let(::toMedia),
      gif = (d.get("gif") as? Map<*, *>)?.let(::toGif),
      forwarded = d.getBoolean("forwarded") ?: false,
      mentions = (d.get("mentions") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
      system = (d.get("system") as? Map<*, *>)?.let(::toSystem),
      expiresAt = d.millis("expiresAt"),
      poll = (d.get("poll") as? Map<*, *>)?.let(::toPoll),
      eventId = d.getString("eventId"),
      eventTitle = d.getString("eventTitle"),
      contact = (d.get("contact") as? Map<*, *>)?.let(::toContact),
      meeting = (d.get("meeting") as? Map<*, *>)?.let(::toMeetingCard),
    )
  }

  /** Phase 6: one group event, live. */
  fun groupEvent(conversationId: String, eventId: String): Flow<com.samvad.core.chat.GroupEvent?> = callbackFlow {
    val reg = db.document("conversations/$conversationId/events/$eventId").addSnapshotListener { snap, _ ->
      trySend(snap?.takeIf { it.exists() }?.let(::toGroupEvent))
    }
    awaitClose { reg.remove() }
  }

  /** Phase 6: every event in a group, live (used by the "Events" list in group info). */
  fun groupEvents(conversationId: String): Flow<List<com.samvad.core.chat.GroupEvent>> = callbackFlow {
    val reg = db.collection("conversations/$conversationId/events")
      .addSnapshotListener { snap, _ -> if (snap != null) trySend(snap.documents.map(::toGroupEvent)) }
    awaitClose { reg.remove() }
  }

  /** My RSVP answer on one event, live. */
  fun myRsvp(conversationId: String, eventId: String, me: String): Flow<com.samvad.core.chat.Rsvp?> = callbackFlow {
    val reg = db.document("conversations/$conversationId/events/$eventId/rsvps/$me").addSnapshotListener { snap, _ ->
      trySend(com.samvad.core.chat.Rsvp.fromWire(snap?.getString("status")))
    }
    awaitClose { reg.remove() }
  }

  private fun toGroupEvent(d: DocumentSnapshot) = com.samvad.core.chat.GroupEvent(
    id = d.id,
    title = d.getString("title") ?: "",
    description = d.getString("description") ?: "",
    place = d.getString("place") ?: "",
    startsAt = d.millis("startsAt") ?: 0L,
    createdBy = d.getString("createdBy") ?: "",
    cancelled = d.getBoolean("cancelled") ?: false,
    counts = (d.get("counts") as? Map<*, *>)?.let {
      com.samvad.core.chat.RsvpCounts(
        going = (it["going"] as? Number)?.toInt() ?: 0,
        maybe = (it["maybe"] as? Number)?.toInt() ?: 0,
        no = (it["no"] as? Number)?.toInt() ?: 0,
      )
    } ?: com.samvad.core.chat.RsvpCounts(),
  )

  /** My own pending requests to join groups that ask admins to approve new members, live. */
  fun myJoinRequests(me: String): Flow<List<com.samvad.core.chat.JoinRequest>> = callbackFlow {
    val reg = db.collection("groupJoinRequests")
      .whereEqualTo("uid", me)
      .whereEqualTo("status", "pending")
      .addSnapshotListener { snap, err ->
        if (err != null) { trySend(emptyList()); return@addSnapshotListener }
        if (snap != null) {
          trySend(
            snap.documents.map { d ->
              com.samvad.core.chat.JoinRequest(
                uid = me,
                cid = d.getString("cid") ?: "",
                groupName = d.getString("groupName") ?: "",
                requestedAt = d.millis("requestedAt") ?: 0L,
              )
            }.filter { it.cid.isNotEmpty() }.sortedByDescending { it.requestedAt },
          )
        }
      }
    awaitClose { reg.remove() }
  }

  /** Phase 6: pending join requests for a group (admins only; the rules enforce that). */
  fun joinRequests(conversationId: String): Flow<List<com.samvad.core.chat.JoinRequest>> = callbackFlow {
    val reg = db.collection("groupJoinRequests")
      .whereEqualTo("cid", conversationId)
      .whereEqualTo("status", "pending")
      .addSnapshotListener { snap, err ->
        if (err != null) { trySend(emptyList()); return@addSnapshotListener }
        if (snap != null) {
          trySend(
            snap.documents.map { d ->
              com.samvad.core.chat.JoinRequest(
                uid = d.getString("uid") ?: "",
                cid = d.getString("cid") ?: conversationId,
                groupName = d.getString("groupName") ?: "",
                requestedAt = d.millis("requestedAt") ?: 0L,
              )
            },
          )
        }
      }
    awaitClose { reg.remove() }
  }
}

/** The action needs the server to read message content, which an end-to-end encrypted chat does not allow. */
class E2eeUnsupportedException : Exception("not available in an encrypted chat")
