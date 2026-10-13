package com.samvad.messenger.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.ConversationIds
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.Profile
import com.samvad.core.media.Media
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

/** Conversations, member state and messages (B2). Firestore's offline cache provides offline mode. */
class ChatRepository(private val db: FirebaseFirestore) {

  private val profileCache = MutableStateFlow<Map<String, Profile>>(emptyMap())
  val profiles: StateFlow<Map<String, Profile>> = profileCache.asStateFlow()

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

  suspend fun loadProfiles(uids: Collection<String>) {
    val missing = uids.filter { it !in profileCache.value }
    for (uid in missing) {
      val snap = runCatching { db.document("profiles/$uid").get().await() }.getOrNull() ?: continue
      if (snap.exists()) {
        val p = Profile(uid, snap.getString("displayName") ?: "", snap.getString("about") ?: "")
        profileCache.update { it + (uid to p) }
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
  fun messages(conversationId: String, limit: Long): Flow<MessagesPage> =
    combine(rawMessages(conversationId, limit), failed) { page, failedIds ->
      page.copy(messages = page.messages.map { if (it.id in failedIds) it.copy(failed = true) else it })
    }

  private fun rawMessages(conversationId: String, limit: Long): Flow<MessagesPage> = callbackFlow {
    val reg = db.collection("conversations/$conversationId/messages")
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
  suspend fun send(conversationId: String, me: String, text: String, replyTo: String?, id: String = UUID.randomUUID().toString()) {
    failed.update { it - id }
    try {
      db.document("conversations/$conversationId/messages/$id").set(
        mapOf(
          "senderId" to me,
          "kind" to "text",
          "text" to text,
          "replyTo" to replyTo,
          "createdAt" to FieldValue.serverTimestamp(),
        ),
      ).await()
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
      ),
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

  suspend fun markRead(conversationId: String, me: String) {
    runCatching {
      db.document("conversations/$conversationId/members/$me").update(
        mapOf(
          "lastReadAt" to FieldValue.serverTimestamp(),
          "lastDeliveredAt" to FieldValue.serverTimestamp(),
          "unreadCount" to 0,
        ),
      ).await()
    }
  }

  suspend fun markDelivered(conversationId: String, me: String) {
    runCatching {
      db.document("conversations/$conversationId/members/$me")
        .update("lastDeliveredAt", FieldValue.serverTimestamp()).await()
    }
  }

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
        )
      },
      lastMessageAt = d.millis("lastMessageAt") ?: 0L,
      isGroup = d.getString("type") == "group",
    )
  }

  private fun toMemberState(d: DocumentSnapshot) = MemberState(
    uid = d.getString("uid") ?: d.id,
    lastReadAt = d.millis("lastReadAt"),
    lastDeliveredAt = d.millis("lastDeliveredAt"),
    unreadCount = (d.getLong("unreadCount") ?: 0L).toInt(),
    muted = d.getBoolean("muted") ?: false,
    pinned = d.getBoolean("pinned") ?: false,
    archived = d.getBoolean("archived") ?: false,
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
    )
  }
}
