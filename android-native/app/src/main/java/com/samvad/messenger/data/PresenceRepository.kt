package com.samvad.messenger.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.samvad.core.presence.Presence
import kotlin.math.min
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Online / last seen, typing and the privacy switch, in the Realtime Database (Phase 1.1).
 * Writes are best-effort: presence must never block or crash the UI.
 */
class PresenceRepository(private val rtdb: FirebaseDatabase) {
  @Volatile private var serverOffsetMs = 0L

  init {
    rtdb.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
      override fun onDataChange(snapshot: DataSnapshot) {
        serverOffsetMs = snapshot.getValue(Long::class.java) ?: 0L
      }
      override fun onCancelled(error: DatabaseError) = Unit
    })
  }

  /** Device time corrected to server time, so freshness checks survive a wrong phone clock. */
  fun serverNow(): Long = System.currentTimeMillis() + serverOffsetMs

  private fun state(online: Boolean) = mapOf("online" to online, "lastSeen" to ServerValue.TIMESTAMP)

  suspend fun goOnline(uid: String) {
    runCatching {
      val ref = rtdb.getReference("presence/$uid")
      ref.onDisconnect().setValue(state(false)).await()
      ref.setValue(state(true)).await()
    }
  }

  suspend fun goOffline(uid: String) {
    runCatching { rtdb.getReference("presence/$uid").setValue(state(false)).await() }
  }

  /** Emits null when the presence is hidden (permission denied) or unknown. */
  fun presence(uid: String): Flow<Presence?> = valueFlow("presence/$uid") { s ->
    val online = s.child("online").getValue(Boolean::class.java)
    val lastSeen = s.child("lastSeen").getValue(Long::class.java)
    if (online == null || lastSeen == null) null else Presence(online, lastSeen)
  }

  fun typing(conversationId: String, uid: String): Flow<Long?> =
    valueFlow("typing/$conversationId/$uid") { it.getValue(Long::class.java) }

  /** Group chats: everyone currently marked typing (uid → server time of the mark). */
  fun typingIn(conversationId: String): Flow<Map<String, Long>> =
    valueFlow("typing/$conversationId") { s -> s.children.mapNotNull { c -> c.getValue(Long::class.java)?.let { c.key!! to it } }.toMap() }
      .map { it ?: emptyMap() }

  suspend fun setTyping(conversationId: String, uid: String) {
    runCatching {
      val ref = rtdb.getReference("typing/$conversationId/$uid")
      ref.onDisconnect().removeValue().await()
      ref.setValue(ServerValue.TIMESTAMP).await()
    }
  }

  suspend fun clearTyping(conversationId: String, uid: String) {
    runCatching { rtdb.getReference("typing/$conversationId/$uid").removeValue().await() }
  }

  fun hideLastSeen(uid: String): Flow<Boolean> =
    valueFlow("privacy/$uid/hideLastSeen") { it.getValue(Boolean::class.java) }.map { it ?: false }

  suspend fun setHideLastSeen(uid: String, hide: Boolean) {
    rtdb.getReference("privacy/$uid/hideLastSeen").setValue(if (hide) true else null).await()
  }

  /**
   * A denied listen is cancelled for good, but access can arrive later: the membership mirror is
   * written by a trigger just after a chat is created, and blocks or privacy can be lifted. So a
   * cancelled listener is re-attached with backoff for as long as the flow is collected.
   */
  private fun <T> valueFlow(path: String, map: (DataSnapshot) -> T?): Flow<T?> = callbackFlow {
    val ref = rtdb.getReference(path)
    var listener: ValueEventListener? = null
    var failures = 0

    fun attach() {
      val l = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
          failures = 0
          trySend(if (snapshot.exists()) map(snapshot) else null)
        }
        override fun onCancelled(error: DatabaseError) {
          trySend(null)
          val wait = min(RETRY_MAX_MS, RETRY_BASE_MS shl min(failures++, 6))
          launch {
            delay(wait)
            attach()
          }
        }
      }
      listener = l
      ref.addValueEventListener(l)
    }

    attach()
    awaitClose { listener?.let(ref::removeEventListener) }
  }

  private companion object {
    const val RETRY_BASE_MS = 1_000L
    const val RETRY_MAX_MS = 60_000L
  }
}
