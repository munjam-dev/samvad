package com.samvad.messenger.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusItem
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** One person who saw a status, and when. */
data class StatusViewer(val uid: String, val viewedAt: Long, val reaction: String?)

/**
 * Status (Phase 4): text and photo posts that last 24 hours. Who can see a post and when it expires are decided
 * by the server (the rules stop clients writing either), so a post appears for others a moment after it is made.
 */
class StatusRepository(private val db: FirebaseFirestore, private val storage: FirebaseStorage) {

  /** Everything people I chat with have shared with me. One listener for the whole feed. */
  fun feed(me: String): Flow<List<StatusItem>> = callbackFlow {
    val reg = db.collectionGroup("items").whereArrayContains("visibleTo", me).addSnapshotListener { snap, err ->
      if (err != null) {
        close(err)
        return@addSnapshotListener
      }
      trySend(snap?.documents.orEmpty().mapNotNull(::toItem))
    }
    awaitClose { reg.remove() }
  }

  /** My own posts, including ones the server has not finished stamping yet. */
  fun mine(me: String): Flow<List<StatusItem>> = callbackFlow {
    val reg = db.collection("statuses/$me/items").addSnapshotListener { snap, err ->
      if (err != null) {
        close(err)
        return@addSnapshotListener
      }
      trySend(snap?.documents.orEmpty().mapNotNull(::toItem))
    }
    awaitClose { reg.remove() }
  }

  suspend fun postText(me: String, text: String, style: String, audience: StatusAudience, list: List<String>) {
    val id = UUID.randomUUID().toString()
    db.document("statuses/$me/items/$id").set(
      mapOf(
        "kind" to "text", "text" to text.trim(), "style" to style,
        "audience" to audience.wire, "list" to list, "createdAt" to FieldValue.serverTimestamp(),
      ),
    ).await()
  }

  /** Uploads one file with progress (0..1) reported as it goes. The rules tie the file to the post id. */
  private suspend fun upload(path: String, bytes: ByteArray, contentType: String, onProgress: (Float) -> Unit) {
    val meta = StorageMetadata.Builder().setContentType(contentType).build()
    val task = storage.reference.child(path).putBytes(bytes, meta)
    task.addOnProgressListener { snap -> if (snap.totalByteCount > 0) onProgress(snap.bytesTransferred.toFloat() / snap.totalByteCount) }
    task.await()
  }

  private suspend fun publish(me: String, id: String, fields: Map<String, Any?>) {
    db.document("statuses/$me/items/$id").set(fields + ("createdAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun postImage(me: String, media: PreparedMedia, caption: String, audience: StatusAudience, list: List<String>, onProgress: (Float) -> Unit = {}) {
    val id = UUID.randomUUID().toString()
    val path = "statuses/$me/$id/photo.jpg"
    upload(path, media.bytes, "image/jpeg", onProgress)
    publish(
      me, id,
      mapOf(
        "kind" to "image", "text" to caption.trim(), "audience" to audience.wire, "list" to list,
        "media" to mapOf("path" to path, "width" to (media.width ?: 0), "height" to (media.height ?: 0), "size" to media.bytes.size),
      ),
    )
  }

  suspend fun postVideo(me: String, video: MediaRepository.PreparedVideo, caption: String, audience: StatusAudience, list: List<String>, onProgress: (Float) -> Unit = {}) {
    val id = UUID.randomUUID().toString()
    val path = "statuses/$me/$id/video.mp4"
    upload(path, video.bytes, "video/mp4", onProgress)
    publish(
      me, id,
      mapOf(
        "kind" to "video", "text" to caption.trim(), "audience" to audience.wire, "list" to list,
        "media" to mapOf("path" to path, "width" to video.width, "height" to video.height, "size" to video.bytes.size, "durationMs" to video.durationMs),
      ),
    )
  }

  suspend fun postVoice(me: String, bytes: ByteArray, durationMs: Long, waveform: List<Int>, audience: StatusAudience, list: List<String>, onProgress: (Float) -> Unit = {}) {
    val id = UUID.randomUUID().toString()
    val path = "statuses/$me/$id/voice.m4a"
    upload(path, bytes, "audio/mp4", onProgress)
    publish(
      me, id,
      mapOf(
        "kind" to "voice", "text" to "", "audience" to audience.wire, "list" to list,
        "media" to mapOf("path" to path, "size" to bytes.size, "durationMs" to durationMs, "waveform" to waveform.take(64)),
      ),
    )
  }

  suspend fun delete(me: String, id: String) {
    db.document("statuses/$me/items/$id").delete().await()
  }

  /** Lets the author see that I looked. Only called when my read-receipts setting is on. */
  suspend fun recordView(author: String, id: String, me: String) {
    db.document("statuses/$author/items/$id/views/$me").set(mapOf("viewedAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun react(author: String, id: String, me: String, emoji: String) {
    db.document("statuses/$author/items/$id/reactions/$me").set(mapOf("emoji" to emoji, "at" to FieldValue.serverTimestamp())).await()
  }

  /** Author only: who saw my post, with any reaction they left. */
  fun viewers(me: String, id: String): Flow<List<StatusViewer>> = callbackFlow {
    var views = emptyMap<String, Long>()
    var reactions = emptyMap<String, String>()
    fun push() = trySend(views.map { (uid, at) -> StatusViewer(uid, at, reactions[uid]) }.sortedByDescending { it.viewedAt })
    val r1 = db.collection("statuses/$me/items/$id/views").addSnapshotListener { snap, _ ->
      views = snap?.documents.orEmpty().associate { it.id to ((it.getTimestamp("viewedAt")?.toDate()?.time) ?: 0L) }
      push()
    }
    val r2 = db.collection("statuses/$me/items/$id/reactions").addSnapshotListener { snap, _ ->
      reactions = snap?.documents.orEmpty().mapNotNull { d -> d.getString("emoji")?.let { d.id to it } }.toMap()
      push()
    }
    awaitClose { r1.remove(); r2.remove() }
  }

  private fun toItem(d: DocumentSnapshot): StatusItem? {
    val author = d.reference.parent.parent?.id ?: return null
    val created = (d.get("createdAt") as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis()
    val media = d.get("media") as? Map<*, *>
    return StatusItem(
      id = d.id,
      authorId = author,
      kind = d.getString("kind") ?: "text",
      text = d.getString("text") ?: "",
      style = d.getString("style"),
      mediaPath = media?.get("path") as? String,
      createdAt = created,
      expiresAt = (d.get("expiresAt") as? Timestamp)?.toDate()?.time,
      durationMs = (media?.get("durationMs") as? Number)?.toLong(),
      waveform = (media?.get("waveform") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
    )
  }
}
