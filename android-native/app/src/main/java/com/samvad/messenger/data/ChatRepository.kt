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
import com.samvad.core.chat.DisplayNames
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.Poll
import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.Profile
import com.samvad.core.media.Media
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

/** Conversations, member state and messages (B2). Firestore's offline cache provides offline mode. */
class ChatRepository(private val db: FirebaseFirestore) {

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

  fun conversations(me: String): Flow<Snapshot<List<Conversation>>> = callbackFlow {
    val reg = db.collection("conversations")
      .whereArrayContains("members", me)
      .orderBy("lastMessageAt", Query.Direction.DESCENDING)
      .limit(200)
      .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
        if (err != null) {
          close(err)
          return@addSnapshotListener
        }
        if (snap != null) trySend(Snapshot(snap.documents.map(::toConversation), snap.metadata.isFromCache))
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

  private suspend fun fetchProfiles(uids: Collection<String>) {
    for (uid in uids) {
      val snap = runCatching { db.document("profiles/$uid").get().await() }.getOrNull() ?: continue
      if (snap.exists()) {
        val p = Profile(uid, snap.getString("displayName") ?: "", snap.getString("about") ?: "", snap.getString("photoUrl"))
        profileCache.update { it + (uid to p) }
        rename()
      }
    }
  }

  /** Returns the direct conversation ID, creating the conversation and member docs if needed. */
  suspend fun openDirect(me: String, other: String): String {
    val id = ConversationIds.direct(me, other)
    val ref = db.document("conversations/$id")
    val exists = runCatching { ref.get().await().exists() }.getOrDefault(false)
    if (!exists) {
      val members = listOf(me, other).sorted()
      val batch = db.batch()
      batch.set(
        ref,
        mapOf(
          "type" to "direct",
          "members" to members,
          "createdBy" to me,
          "createdAt" to FieldValue.serverTimestamp(),
          "lastMessage" to null,
          "lastMessageAt" to FieldValue.serverTimestamp(),
        ),
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
    return id
  }

  /** Newest [limit] messages, ascending. Growing [limit] pages back through history. */
  /**
   * Newest [limit] messages. In groups pass [since] = my joinedAt: the rules only let members read
   * messages from when they joined, so the query must say so.
   */
  fun messages(conversationId: String, limit: Long, since: Long? = null): Flow<MessagesPage> =
    combine(rawMessages(conversationId, limit, since), failed) { page, failedIds ->
      page.copy(messages = page.messages.map { if (it.id in failedIds) it.copy(failed = true) else it })
    }

  private fun rawMessages(conversationId: String, limit: Long, since: Long?): Flow<MessagesPage> = callbackFlow {
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
          val list = snap.documents.map(::toMessage).reversed()
          trySend(MessagesPage(list, hasMore = snap.size() >= limit, fromCache = snap.metadata.isFromCache))
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
  ) {
    failed.update { it - id }
    try {
      val doc = buildMap<String, Any?> {
        put("senderId", me)
        put("kind", "text")
        put("text", text)
        put("replyTo", replyTo)
        put("createdAt", FieldValue.serverTimestamp())
        if (forwarded) put("forwarded", true)
        if (mentions.isNotEmpty()) put("mentions", mentions)
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
    }
    db.document("conversations/$conversationId/messages/$id").set(
      mapOf(
        "senderId" to me,
        "kind" to kind,
        "text" to caption,
        "replyTo" to replyTo,
        "createdAt" to FieldValue.serverTimestamp(),
        "media" to map,
      ) + (if (forwarded) mapOf("forwarded" to true) else emptyMap()),
    ).await()
  }

  suspend fun retry(conversationId: String, me: String, message: Message) {
    val existing = runCatching { db.document("conversations/$conversationId/messages/${message.id}").get().await() }.getOrNull()
    if (existing?.exists() == true && existing.metadata.hasPendingWrites().not()) {
      failed.update { it - message.id }
      return
    }
    send(conversationId, me, message.text, message.replyTo, message.id)
  }

  suspend fun edit(conversationId: String, messageId: String, text: String) {
    db.document("conversations/$conversationId/messages/$messageId")
      .update(mapOf("text" to text, "editedAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun deleteForEveryone(conversationId: String, messageId: String) {
    db.document("conversations/$conversationId/messages/$messageId")
      .update(mapOf("deleted" to true, "text" to "")).await()
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
      .documents.map(::toMessage).reversed()

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
      .getOrNull()?.takeIf { it.exists() }?.let(::toMessage)

  suspend fun setFlag(conversationId: String, me: String, field: String, value: Boolean) {
    require(field in setOf("muted", "pinned", "archived"))
    db.document("conversations/$conversationId/members/$me").update(field, value).await()
  }

  private fun DocumentSnapshot.millis(field: String): Long? =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time

  private fun toConversation(d: DocumentSnapshot): Conversation {
    @Suppress("UNCHECKED_CAST")
    val last = d.get("lastMessage") as? Map<String, Any?>
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
        )
      },
      lastMessageAt = d.millis("lastMessageAt") ?: 0L,
      isGroup = d.getString("type") == "group",
      name = d.getString("name"),
      admins = (d.get("admins") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
      description = d.getString("description") ?: "",
      photoPath = d.getString("photoUrl"),
    )
  }

  private fun toSystem(m: Map<*, *>): SystemInfo? {
    val event = m["event"] as? String ?: return null
    return SystemInfo(event, (m["targets"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(), m["name"] as? String)
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
    )
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
      forwarded = d.getBoolean("forwarded") ?: false,
      mentions = (d.get("mentions") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
      system = (d.get("system") as? Map<*, *>)?.let(::toSystem),
      poll = (d.get("poll") as? Map<*, *>)?.let(::toPoll),
    )
  }
}
