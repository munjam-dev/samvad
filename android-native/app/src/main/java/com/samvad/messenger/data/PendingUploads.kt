package com.samvad.messenger.data

import android.content.Context
import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import com.samvad.core.media.FailureKind
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.core.media.ResumeAction
import com.samvad.core.media.RetryState
import com.samvad.core.media.RetryStatus
import com.samvad.core.media.TransferProgress
import com.samvad.core.media.UploadRetry
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Which half of sending an attachment is still to do. */
enum class UploadStage {
  /** The file is not in Storage yet. */
  UPLOAD,

  /** The file is in Storage; the message that points at it is still to be written. */
  WRITE,
}

/**
 * Everything needed to finish sending an attachment after the app was closed. The file itself is a private
 * copy in the app's own storage (the picked URI may stop being readable). The message ID is the client ID
 * made when the person tapped send, so a retry writes the same message document and never a second one.
 */
data class PendingUploadRecord(
  val id: String,
  val conversationId: String,
  val me: String,
  val kind: MediaKind,
  /** Object name inside the message's Storage folder. */
  val storageName: String,
  val displayName: String,
  val mime: String,
  val size: Long,
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val waveform: List<Int> = emptyList(),
  val caption: String = "",
  val replyTo: String? = null,
  val createdAtMs: Long,
  val retry: RetryState = RetryState(),
  val stage: UploadStage = UploadStage.UPLOAD,
  /** Resumable-upload session of the last attempt, so the next attempt continues instead of restarting. */
  val sessionUri: String? = null,
  /** Videos: object name of the small poster frame (thumb.jpg), uploaded before the video itself. */
  val thumbName: String? = null,
  /** End-to-end encrypted chat: the private copy and the poster are ciphertext, and Storage gets opaque bytes. */
  val e2ee: Boolean = false,
  /** Encrypted chat: the file keys and digests, sealed with the Keystore-backed key (never stored in the clear). */
  val secret: String? = null,
) {
  val storagePath: String get() = "conversations/$conversationId/$id/$storageName"
  val thumbPath: String? get() = thumbName?.let { "conversations/$conversationId/$id/$it" }
}

/** Small JSON file in the app's private storage; there is at most a handful of records at a time. */
class PendingUploadStore(context: Context) {
  private val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
  private val index = File(dir, "index.json")
  private val lock = Any()

  /** The private copy of an upload's bytes. The ID is checked, so it can never point outside [dir]. */
  fun mediaFile(id: String): File {
    require(ID.matches(id)) { "bad id" }
    return File(dir, "$id.bin")
  }

  /** The private copy of a video's poster frame. */
  fun thumbFile(id: String): File {
    require(ID.matches(id)) { "bad id" }
    return File(dir, "$id.thumb")
  }

  fun load(): List<PendingUploadRecord> = synchronized(lock) { read() }

  fun get(id: String): PendingUploadRecord? = synchronized(lock) { read().firstOrNull { it.id == id } }

  fun put(record: PendingUploadRecord) = synchronized(lock) {
    write(read().filterNot { it.id == record.id } + record)
  }

  /** Applies [change] to one record and returns the new version, or null if it is gone. */
  fun update(id: String, change: (PendingUploadRecord) -> PendingUploadRecord): PendingUploadRecord? = synchronized(lock) {
    val all = read()
    val old = all.firstOrNull { it.id == id } ?: return null
    val new = change(old)
    write(all.map { if (it.id == id) new else it })
    new
  }

  /** Removes the record and its private copy of the file. */
  fun remove(id: String) = synchronized(lock) {
    write(read().filterNot { it.id == id })
    runCatching { mediaFile(id).delete() }
    runCatching { thumbFile(id).delete() }
    Unit
  }

  fun clear() = synchronized(lock) {
    write(emptyList())
    dir.listFiles()?.forEach { if (it != index) it.delete() }
  }

  /** Deletes files left behind by a crash between writing a file and its record. */
  fun sweepOrphans() = synchronized(lock) {
    val known = read().flatMap { listOf("${it.id}.bin", "${it.id}.thumb") }.toSet()
    dir.listFiles()?.forEach { f -> if (f != index && f.name !in known) f.delete() }
  }

  private fun read(): List<PendingUploadRecord> {
    if (!index.exists()) return emptyList()
    return runCatching {
      val array = JSONArray(index.readText())
      (0 until array.length()).mapNotNull { decode(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
  }

  private fun write(records: List<PendingUploadRecord>) {
    val tmp = File(dir, "index.json.tmp")
    tmp.writeText(JSONArray().apply { records.forEach { put(encode(it)) } }.toString())
    if (!tmp.renameTo(index)) {
      index.delete()
      tmp.renameTo(index)
    }
  }

  private fun encode(r: PendingUploadRecord) = JSONObject().apply {
    put("id", r.id)
    put("conversationId", r.conversationId)
    put("me", r.me)
    put("kind", r.kind.wire)
    put("storageName", r.storageName)
    put("displayName", r.displayName)
    put("mime", r.mime)
    put("size", r.size)
    r.width?.let { put("width", it) }
    r.height?.let { put("height", it) }
    r.durationMs?.let { put("durationMs", it) }
    put("waveform", JSONArray(r.waveform))
    put("caption", r.caption)
    r.replyTo?.let { put("replyTo", it) }
    put("createdAtMs", r.createdAtMs)
    put("attempts", r.retry.attempts)
    put("nextAttemptAtMs", r.retry.nextAttemptAtMs)
    put("failed", r.retry.status == RetryStatus.FAILED)
    put("stage", r.stage.name)
    r.sessionUri?.let { put("sessionUri", it) }
    r.thumbName?.let { put("thumbName", it) }
    if (r.e2ee) put("e2ee", true)
    r.secret?.let { put("secret", it) }
  }

  private fun decode(o: JSONObject): PendingUploadRecord? = runCatching {
    val id = o.getString("id")
    if (!ID.matches(id)) return@runCatching null
    PendingUploadRecord(
      id = id,
      conversationId = o.getString("conversationId"),
      me = o.getString("me"),
      kind = MediaKind.fromWire(o.getString("kind")) ?: return@runCatching null,
      storageName = o.getString("storageName"),
      displayName = o.getString("displayName"),
      mime = o.getString("mime"),
      size = o.getLong("size"),
      width = if (o.has("width")) o.getInt("width") else null,
      height = if (o.has("height")) o.getInt("height") else null,
      durationMs = if (o.has("durationMs")) o.getLong("durationMs") else null,
      waveform = o.optJSONArray("waveform")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
      caption = o.optString("caption", ""),
      replyTo = if (o.has("replyTo")) o.getString("replyTo") else null,
      createdAtMs = o.getLong("createdAtMs"),
      retry = RetryState(
        attempts = o.optInt("attempts", 0),
        nextAttemptAtMs = o.optLong("nextAttemptAtMs", 0L),
        status = if (o.optBoolean("failed", false)) RetryStatus.FAILED else RetryStatus.ACTIVE,
      ),
      stage = runCatching { UploadStage.valueOf(o.getString("stage")) }.getOrDefault(UploadStage.UPLOAD),
      sessionUri = if (o.has("sessionUri")) o.getString("sessionUri") else null,
      thumbName = if (o.has("thumbName")) o.getString("thumbName") else null,
      e2ee = o.optBoolean("e2ee", false),
      secret = if (o.has("secret")) o.getString("secret") else null,
    )
  }.getOrNull()

  private companion object {
    val ID = Regex("[A-Za-z0-9-]{1,64}")
  }
}

enum class UploadPhase { SENDING, WAITING, FAILED }

/** Why an upload is waiting. */
enum class WaitReason { NETWORK, RETRY }

/** What the chat shows for one pending attachment. */
data class UploadItem(
  val id: String,
  val conversationId: String,
  val kind: MediaKind,
  val label: String,
  val phase: UploadPhase,
  /** 0..1, or null while the total size is not known yet. */
  val progress: Float? = null,
  val waitReason: WaitReason = WaitReason.NETWORK,
  /** False once the file is uploaded and only the message write is left. */
  val canCancel: Boolean = true,
  val createdAtMs: Long = 0L,
)

/**
 * Sends attachments in the background, outside any screen, and finishes them after the app was closed.
 *
 * - Each attachment is a [PendingUploadRecord] saved to disk before anything is sent.
 * - An attempt waits for a working connection, uploads with progress (continuing the previous resumable
 *   session when there is one), then writes the message. Failures back off exponentially (see
 *   [UploadRetry]); after repeated or permanent failures the item shows as failed and the person decides.
 * - The message ID is made once, when the person taps send. Storage path and message document both use it,
 *   and the message is only written if it does not exist yet, so no retry can create a second message.
 *
 * It resumes when the app starts and when a chat is opened. It does not promise delivery: a phone that
 * stays offline, or whose app is not opened again, delivers nothing.
 */
class PendingUploadManager(
  private val context: Context,
  private val media: MediaRepository,
  private val chats: ChatRepository,
  private val network: NetworkMonitor,
  private val e2ee: com.samvad.messenger.data.e2ee.E2eeService?,
  private val currentUid: () -> String?,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val store = PendingUploadStore(context)

  // Only touched on the main thread.
  private val jobs = mutableMapOf<String, Job>()
  private val _items = MutableStateFlow<Map<String, UploadItem>>(emptyMap())

  /** Every pending attachment of the signed-in person, oldest first. */
  val items: Flow<List<UploadItem>> = _items.map { it.values.sortedBy { item -> item.createdAtMs } }

  fun itemsFor(conversationId: String): Flow<List<UploadItem>> =
    items.map { list -> list.filter { it.conversationId == conversationId } }

  /** Reads the saved records and continues them. Safe to call again; running uploads are left alone. */
  fun resumeAll() {
    scope.launch {
      val uid = currentUid() ?: return@launch
      val all = withContext(Dispatchers.IO) {
        store.sweepOrphans()
        store.load()
      }
      val now = System.currentTimeMillis()
      for (record in all) {
        if (record.me != uid) {
          // Left over from another account: never sent under this one.
          withContext(Dispatchers.IO) { store.remove(record.id) }
          continue
        }
        if (jobs.containsKey(record.id)) continue
        when (UploadRetry.resume(record.retry, record.createdAtMs, now)) {
          ResumeAction.NeedsPerson -> {
            val failed = if (record.retry.status == RetryStatus.FAILED) record else withContext(Dispatchers.IO) {
              store.update(record.id) { it.copy(retry = it.retry.copy(status = RetryStatus.FAILED)) }
            } ?: continue
            publish(failed, UploadPhase.FAILED)
          }
          else -> start(record)
        }
      }
    }
  }

  /**
   * Saves [prepared] as a pending upload and starts it. Returns the client message ID. The caller must
   * not send the message itself.
   */
  suspend fun enqueue(
    conversationId: String,
    me: String,
    prepared: PreparedMedia,
    caption: String,
    replyTo: String?,
  ): String {
    val id = UUID.randomUUID().toString()
    // In an end-to-end encrypted chat the file is encrypted here, before anything is saved or uploaded.
    val sealed = if (e2ee != null && e2ee.enabled && chats.isE2ee(conversationId)) {
      withContext(Dispatchers.Default) { e2ee.sealMedia(prepared) }
    } else {
      null
    }
    val fileBytes = sealed?.file?.cipherBytes ?: prepared.bytes
    val thumbBytes = if (sealed != null) sealed.thumb?.cipherBytes else prepared.thumbBytes
    val record = PendingUploadRecord(
      id = id,
      conversationId = conversationId,
      me = me,
      kind = prepared.kind,
      storageName = if (sealed != null) "blob.bin" else prepared.fileName,
      displayName = prepared.displayName,
      mime = prepared.mime,
      size = fileBytes.size.toLong(),
      width = prepared.width,
      height = prepared.height,
      durationMs = prepared.durationMs,
      waveform = prepared.waveform,
      caption = caption,
      replyTo = replyTo,
      createdAtMs = System.currentTimeMillis(),
      thumbName = if (thumbBytes != null) (if (sealed != null) "thumb.bin" else "thumb.jpg") else null,
      e2ee = sealed != null,
      secret = sealed?.let { sealSecret(id, it.ref()) },
    )
    withContext(Dispatchers.IO) {
      store.mediaFile(id).writeBytes(fileBytes)
      thumbBytes?.let { store.thumbFile(id).writeBytes(it) }
      store.put(record)
    }
    start(record)
    return id
  }

  /** Tap on Retry: tries again from the start, at once. */
  fun retry(id: String) {
    scope.launch {
      val record = withContext(Dispatchers.IO) {
        store.update(id) { it.copy(retry = UploadRetry.manualRetry(System.currentTimeMillis())) }
      } ?: return@launch
      if (!jobs.containsKey(id)) start(record)
    }
  }

  /** Tap on Cancel: stops the transfer and forgets the attachment. Nothing is sent. */
  fun cancel(id: String) {
    jobs.remove(id)?.cancel()
    _items.update { it - id }
    scope.launch { withContext(Dispatchers.IO) { store.remove(id) } }
  }

  /** Forgets everything, for example on sign-out. Pending attachments are not sent. */
  fun discardAll() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
    _items.value = emptyMap()
    scope.launch { withContext(Dispatchers.IO) { store.clear() } }
  }

  private fun start(record: PendingUploadRecord) {
    publish(record, UploadPhase.SENDING)
    // Lazy, so the job is in [jobs] before it can finish and remove itself.
    val job = scope.launch(start = CoroutineStart.LAZY) {
      val self = coroutineContext[Job]
      try {
        run(record.id)
      } finally {
        if (jobs[record.id] === self) jobs.remove(record.id)
      }
    }
    jobs[record.id] = job
    job.start()
  }

  private suspend fun run(id: String) {
    while (true) {
      val record = withContext(Dispatchers.IO) { store.get(id) } ?: return
      if (record.retry.status == RetryStatus.FAILED) {
        publish(record, UploadPhase.FAILED)
        return
      }
      val waitMs = record.retry.nextAttemptAtMs - System.currentTimeMillis()
      if (waitMs > 0) {
        publish(record, UploadPhase.WAITING, WaitReason.RETRY)
        delay(waitMs)
      }
      if (!network.isOnline) {
        publish(record, UploadPhase.WAITING, WaitReason.NETWORK)
        network.awaitOnline()
      }
      publish(record, UploadPhase.SENDING)
      val failure = try {
        attempt(record)
        null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        e
      }
      if (failure == null) {
        withContext(Dispatchers.IO) { store.remove(id) }
        _items.update { it - id }
        return
      }
      val kind = classify(failure)
      val updated = withContext(Dispatchers.IO) {
        store.update(id) { current ->
          val next = UploadRetry.onFailure(current.retry, kind, System.currentTimeMillis(), Random.nextDouble())
          // After a couple of failed continuations, start the upload over instead of reusing the session.
          val dropSession = kind == FailureKind.PERMANENT || (kind == FailureKind.TRANSIENT && next.attempts >= 2)
          current.copy(retry = next, sessionUri = if (dropSession) null else current.sessionUri)
        }
      } ?: return
      if (updated.retry.status == RetryStatus.FAILED) {
        publish(updated, UploadPhase.FAILED)
        return
      }
    }
  }

  /** One try: upload (if still needed), then write the message unless it already exists. */
  private suspend fun attempt(record: PendingUploadRecord) {
    val id = record.id
    val file = store.mediaFile(id)
    if (record.stage == UploadStage.UPLOAD) {
      if (!file.exists() || file.length() == 0L) throw FileNotFoundException("pending file is gone")
      var lastSession = record.sessionUri
      var lastPercent = -1
      val thumb = store.thumbFile(id)
      val thumbPath = record.thumbPath
      if (thumbPath != null) {
        if (!thumb.exists() || thumb.length() == 0L) throw FileNotFoundException("pending poster is gone")
        // The poster is small, so it goes first without a resumable session; a retry simply sends it again.
        media.uploadFile(
          path = thumbPath,
          file = thumb,
          mime = if (record.e2ee) OPAQUE else "image/jpeg",
          me = record.me,
          sessionUri = null,
          onSession = {},
          onProgress = { _, _ -> },
        )
      }
      media.uploadFile(
        path = record.storagePath,
        file = file,
        mime = if (record.e2ee) OPAQUE else record.mime,
        me = record.me,
        sessionUri = record.sessionUri?.let(Uri::parse),
        onSession = { uri ->
          val text = uri.toString()
          if (text != lastSession) {
            lastSession = text
            scope.launch(Dispatchers.IO) { store.update(id) { it.copy(sessionUri = text) } }
          }
        },
        onProgress = { done, total ->
          val fraction = TransferProgress.fraction(done, if (total > 0) total else record.size)
          val percent = ((fraction ?: 0f) * 100).toInt()
          if (percent != lastPercent) {
            lastPercent = percent
            _items.update { map -> map[id]?.let { map + (id to it.copy(progress = fraction)) } ?: map }
          }
        },
      )
      // An encrypted file on disk is ciphertext: it must not be mistaken for the photo in the media cache.
      if (!record.e2ee) media.adoptLocal(record.storagePath, file)
      withContext(Dispatchers.IO) { store.update(id) { it.copy(stage = UploadStage.WRITE, sessionUri = null) } }
    }
    _items.update { map -> map[id]?.let { map + (id to it.copy(progress = 1f, canCancel = false)) } ?: map }
    if (chats.messageExists(record.conversationId, id)) return
    try {
      if (record.e2ee) {
        val ref = openSecret(record) ?: throw FileNotFoundException("encryption keys of this attachment are gone")
        chats.sendEncryptedMedia(record.conversationId, record.me, id, ref, record.caption, record.replyTo)
      } else {
        chats.sendMedia(
          record.conversationId, record.me, id, record.kind.wire,
          Media(record.storagePath, record.displayName, record.size, record.mime, record.width, record.height, record.durationMs, record.waveform, record.thumbPath),
          record.caption, record.replyTo,
        )
      }
    } catch (e: FirebaseFirestoreException) {
      // A write queued by an earlier run can land first and make this one look like a forbidden update.
      val alreadyThere = e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
        runCatching { chats.messageExists(record.conversationId, id) }.getOrDefault(false)
      if (!alreadyThere) throw e
    }
  }

  /** Keys and digests of an encrypted attachment, sealed so they never sit on disk in the clear. */
  private fun sealSecret(id: String, ref: com.samvad.messenger.data.e2ee.EncryptedMediaRef): String {
    val b64 = java.util.Base64.getEncoder()
    val json = JSONObject().apply {
      put("fk", b64.encodeToString(ref.fileKey))
      put("fd", ref.fileDigest)
      put("fs", ref.fileSize)
      ref.thumbKey?.let { put("tk", b64.encodeToString(it)) }
      ref.thumbDigest?.let { put("td", it) }
      ref.thumbSize?.let { put("ts", it) }
    }
    val sealed = e2ee!!.sealLocal(json.toString().toByteArray(Charsets.UTF_8), "pending:$id")
    return b64.encodeToString(sealed)
  }

  private fun openSecret(r: PendingUploadRecord): com.samvad.messenger.data.e2ee.EncryptedMediaRef? = runCatching {
    val b64 = java.util.Base64.getDecoder()
    val json = JSONObject(String(e2ee!!.openLocal(b64.decode(r.secret!!), "pending:${r.id}"), Charsets.UTF_8))
    com.samvad.messenger.data.e2ee.EncryptedMediaRef(
      kind = r.kind.wire, name = r.displayName, mime = r.mime, width = r.width, height = r.height,
      durationMs = r.durationMs, waveform = r.waveform,
      fileKey = b64.decode(json.getString("fk")), fileDigest = json.getString("fd"), fileSize = json.getLong("fs"),
      thumbKey = if (json.has("tk")) b64.decode(json.getString("tk")) else null,
      thumbDigest = if (json.has("td")) json.getString("td") else null,
      thumbSize = if (json.has("ts")) json.getLong("ts") else null,
    )
  }.getOrNull()

  private fun classify(e: Throwable): FailureKind {
    if (!network.isOnline) return FailureKind.OFFLINE
    return when (e) {
      is com.samvad.messenger.data.e2ee.E2eeSendException ->
        // A changed security code or missing keys need the person; a network hiccup does not.
        if (e.peer == com.samvad.messenger.data.e2ee.PeerKeys.UNAVAILABLE) FailureKind.TRANSIENT else FailureKind.PERMANENT
      is FileNotFoundException -> FailureKind.PERMANENT
      is StorageException -> when (e.errorCode) {
        StorageException.ERROR_NOT_AUTHORIZED,
        StorageException.ERROR_BUCKET_NOT_FOUND,
        StorageException.ERROR_PROJECT_NOT_FOUND,
        StorageException.ERROR_QUOTA_EXCEEDED,
        -> FailureKind.PERMANENT
        else -> FailureKind.TRANSIENT
      }
      is FirebaseFirestoreException -> when (e.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        FirebaseFirestoreException.Code.INVALID_ARGUMENT,
        FirebaseFirestoreException.Code.NOT_FOUND,
        -> FailureKind.PERMANENT
        else -> FailureKind.TRANSIENT
      }
      else -> FailureKind.TRANSIENT
    }
  }

  private fun publish(record: PendingUploadRecord, phase: UploadPhase, reason: WaitReason = WaitReason.NETWORK) {
    _items.update { map ->
      val old = map[record.id]
      map + (record.id to UploadItem(
        id = record.id,
        conversationId = record.conversationId,
        kind = record.kind,
        label = record.displayName,
        phase = phase,
        progress = if (phase == UploadPhase.SENDING) old?.progress else null,
        waitReason = reason,
        canCancel = record.stage == UploadStage.UPLOAD,
        createdAtMs = record.createdAtMs,
      ))
    }
  }
}

/** Content type of encrypted files in Storage: the real type is inside the encrypted message. */
private const val OPAQUE = "application/octet-stream"
