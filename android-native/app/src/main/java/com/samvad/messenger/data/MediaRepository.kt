package com.samvad.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.samvad.core.media.DownloadPolicy
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.core.media.ImageQuality
import com.samvad.core.media.MediaRules
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A file ready to upload: bytes plus the description the message will carry. */
class PreparedMedia(
  val kind: MediaKind,
  val fileName: String,
  val displayName: String,
  val mime: String,
  val bytes: ByteArray,
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val waveform: List<Int> = emptyList(),
  /** Photos only: whether the photo went out as chosen, at lower quality, or smaller to fit 16 MB. */
  val imageOutcome: ImageQuality.Outcome? = null,
)

sealed class MediaException(message: String) : Exception(message) {
  class TooLarge : MediaException("too large")
  class TypeBlocked : MediaException("type blocked")
  class Unreadable : MediaException("unreadable")
}

/**
 * Prepares, uploads and downloads attachments. Downloads go through the Storage SDK so the
 * membership rules apply to every read; public download URLs (bearer links) are never used.
 */
class MediaRepository(private val context: Context, private val storage: FirebaseStorage) {
  private companion object {
    const val AVATAR_PX = 512
  }

  private val cacheDir = File(context.cacheDir, "media").apply { mkdirs() }

  /**
   * Profile photo: centre-cropped square, 512 px, JPEG. Re-encoding drops EXIF; the server strips any
   * metadata again before storing it.
   */
  suspend fun prepareAvatar(uri: Uri): ByteArray = withContext(Dispatchers.Default) {
    val decoded = ImageCodec.decodeUpright(context.contentResolver, uri, AVATAR_PX * 2) ?: throw MediaException.Unreadable()
    val side = minOf(decoded.width, decoded.height)
    val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    val scaled = if (side > AVATAR_PX) Bitmap.createScaledBitmap(square, AVATAR_PX, AVATAR_PX, true) else square
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    listOf(decoded, square, scaled).distinct().forEach { it.recycle() }
    out.toByteArray()
  }

  /**
   * Upright and re-encoded as JPEG, which also strips EXIF data such as GPS location. By default scaled to
   * 1600 px (smaller with Data saver). With [original] (ignored when [edits] draws on the photo, since the
   * edit is flattened onto a capped-size copy anyway) the photo keeps its size and is encoded at a high
   * quality; if that is over 16 MB the quality, and only then the size, steps down until it fits (see
   * [ImageQuality]). [PreparedMedia.imageOutcome] says which happened.
   */
  suspend fun prepareImage(
    uri: Uri,
    dataSaver: Boolean = false,
    edits: PhotoEdits = PhotoEdits.NONE,
    original: Boolean = false,
  ): PreparedMedia = withContext(Dispatchers.Default) {
    if (!edits.isEmpty) return@withContext prepareEditedImage(uri, dataSaver, edits)
    val attempts = ImageQuality.attempts(original, dataSaver)
    val firstEdge = attempts.first().maxEdge
    var budget = ImageQuality.MAX_DECODE_PIXELS
    var decoded: Bitmap? = null
    while (decoded == null) {
      try {
        decoded = if (firstEdge != null) ImageCodec.decodeUpright(context.contentResolver, uri, firstEdge)
        else ImageCodec.decodeUprightWithin(context.contentResolver, uri, budget)
        if (decoded == null) throw MediaException.Unreadable()
      } catch (e: OutOfMemoryError) {
        // Too big for this phone's memory: decode at a quarter of the pixels and try again.
        if (firstEdge != null || budget < 1_000_000L) throw MediaException.Unreadable()
        budget /= 4
      }
    }
    val source: Bitmap = decoded
    var best: ByteArray? = null
    var bestWidth = 0
    var bestHeight = 0
    // Smallest quality already tried at a given size: a higher quality at the same size cannot fit either.
    val triedAt = HashMap<Pair<Int, Int>, Int>()
    try {
      val choice = ImageQuality.choose(attempts) { attempt ->
        val edge = attempt.maxEdge
        val (w, h) = if (edge == null) source.width to source.height else MediaRules.fitWithin(source.width, source.height, edge)
        val lowest = triedAt[w to h]
        if (lowest != null && attempt.quality >= lowest) return@choose null
        triedAt[w to h] = attempt.quality
        val scaled = if (w == source.width && h == source.height) source else Bitmap.createScaledBitmap(source, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, attempt.quality, out)
        if (scaled !== source) scaled.recycle()
        val bytes = out.toByteArray()
        best = bytes
        bestWidth = w
        bestHeight = h
        bytes.size.toLong()
      } ?: throw MediaException.TooLarge()
      PreparedMedia(
        MediaKind.IMAGE, "photo.jpg", "Photo", "image/jpeg", best!!,
        width = bestWidth, height = bestHeight, imageOutcome = choice.outcome,
      )
    } finally {
      source.recycle()
    }
  }

  /**
   * A photo moment with text or pen on it: drawn on a bitmap of at most [PhotoEditRules.MAX_EDGE_PX] so memory
   * stays small, then flattened into the JPEG so nobody can lift the drawing off again.
   */
  private suspend fun prepareEditedImage(uri: Uri, dataSaver: Boolean, edits: PhotoEdits): PreparedMedia {
    val edge = minOf(DownloadPolicy.uploadEdgePx(dataSaver), PhotoEditRules.MAX_EDGE_PX)
    val decoded = ImageCodec.decodeUpright(context.contentResolver, uri, edge) ?: throw MediaException.Unreadable()
    var scaled: Bitmap? = null
    var flattened: Bitmap? = null
    val bytes: ByteArray
    val w: Int
    val h: Int
    try {
      val fitted = MediaRules.fitWithin(decoded.width, decoded.height, edge)
      w = fitted.first
      h = fitted.second
      scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
      var source: Bitmap = scaled
      val copy = try {
        source.copy(Bitmap.Config.ARGB_8888, true)
      } catch (_: OutOfMemoryError) {
        null
      } ?: throw MediaException.Unreadable()
      flattened = copy
      PhotoEditRenderer.draw(Canvas(copy), copy.width, copy.height, edits)
      source = copy
      val out = ByteArrayOutputStream()
      source.compress(Bitmap.CompressFormat.JPEG, DownloadPolicy.uploadQuality(dataSaver), out)
      bytes = out.toByteArray()
    } finally {
      flattened?.recycle()
      if (scaled != null && scaled !== decoded) scaled.recycle()
      decoded.recycle()
    }
    if (!MediaRules.isWithinLimit(bytes.size.toLong())) throw MediaException.TooLarge()
    return PreparedMedia(MediaKind.IMAGE, "photo.jpg", "Photo", "image/jpeg", bytes, width = w, height = h)
  }

  /** Size of a picked file in bytes, or -1 when the provider does not say. */
  suspend fun sourceSize(uri: Uri): Long = withContext(Dispatchers.IO) {
    var size = -1L
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
      }
    }
    size
  }

  /** A small upright copy of a picked photo for the preview before sending. */
  suspend fun loadPreview(uri: Uri, maxEdgePx: Int = 1400): Bitmap? = withContext(Dispatchers.Default) {
    runCatching { ImageCodec.decodeUpright(context.contentResolver, uri, maxEdgePx) }.getOrNull()
  }

  suspend fun prepareDocument(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)
    if (!MediaRules.isAllowed(MediaKind.FILE, mime)) throw MediaException.TypeBlocked()
    var name = "file"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        name = c.getString(0) ?: name
        size = if (c.isNull(1)) -1L else c.getLong(1)
      }
    }
    if (size > MediaRules.MAX_BYTES) throw MediaException.TooLarge()
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw MediaException.Unreadable()
    if (!MediaRules.isWithinLimit(bytes.size.toLong())) throw MediaException.TooLarge()
    PreparedMedia(MediaKind.FILE, MediaRules.safeFileName(name), name.take(200), mime!!, bytes)
  }

  fun prepareVoice(file: File, durationMs: Long, waveform: List<Int>): PreparedMedia {
    val bytes = file.readBytes()
    file.delete()
    if (!MediaRules.isWithinLimit(bytes.size.toLong())) throw MediaException.TooLarge()
    return PreparedMedia(MediaKind.VOICE, "voice.m4a", "Voice message", "audio/mp4", bytes, durationMs = durationMs, waveform = waveform)
  }

  /**
   * Prepares an existing attachment for forwarding. Storage rules tie each file to its chat, so a
   * forwarded copy is uploaded again into every target chat from the local (downloaded) copy.
   */
  suspend fun prepareForward(media: Media, kind: MediaKind): PreparedMedia {
    val bytes = withContext(Dispatchers.IO) { localFile(media.path).readBytes() }
    return PreparedMedia(
      kind, media.path.substringAfterLast('/'), media.name, media.mime, bytes,
      media.width, media.height, media.durationMs, media.waveform,
    )
  }

  /** Uploads with up to 3 attempts (1 s, 2 s back-off). Returns the storage path. */
  suspend fun upload(conversationId: String, messageId: String, me: String, media: PreparedMedia): String {
    val path = "conversations/$conversationId/$messageId/${media.fileName}"
    val meta = StorageMetadata.Builder()
      .setContentType(media.mime)
      .setCustomMetadata("senderId", me)
      .build()
    var attempt = 0
    while (true) {
      try {
        storage.reference.child(path).putBytes(media.bytes, meta).await()
        cacheFile(path).writeBytes(media.bytes)
        return path
      } catch (e: Exception) {
        if (++attempt >= 3) throw e
        delay(1000L * attempt)
      }
    }
  }

  /**
   * Uploads a local file with progress, for the pending-upload queue. Pass the session URI reported by an
   * earlier attempt (through [onSession]) to continue that upload where it stopped instead of starting over.
   * Cancelling the coroutine cancels the transfer.
   */
  suspend fun uploadFile(
    path: String,
    file: File,
    mime: String,
    me: String,
    sessionUri: Uri?,
    onSession: (Uri) -> Unit,
    onProgress: (transferred: Long, total: Long) -> Unit,
  ): Unit = suspendCancellableCoroutine { cont ->
    val meta = StorageMetadata.Builder()
      .setContentType(mime)
      .setCustomMetadata("senderId", me)
      .build()
    val ref = storage.reference.child(path)
    val source = Uri.fromFile(file)
    val task = if (sessionUri != null) ref.putFile(source, meta, sessionUri) else ref.putFile(source, meta)
    task.addOnProgressListener { snap ->
      snap.uploadSessionUri?.let(onSession)
      onProgress(snap.bytesTransferred, snap.totalByteCount)
    }
    task.addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
    task.addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    cont.invokeOnCancellation { task.cancel() }
  }

  /** Puts a copy of an uploaded file in the media cache, so the sender's own photo opens without downloading it again. */
  suspend fun adoptLocal(path: String, source: File) = withContext(Dispatchers.IO) {
    runCatching { source.copyTo(cacheFile(path), overwrite = true) }
    Unit
  }

  /**
   * Downloads an attachment into the media cache with progress; returns at once when it is already there. Goes
   * through the Storage SDK so the membership rules apply. Cancelling the coroutine cancels the transfer and
   * removes the partial file.
   */
  suspend fun download(path: String, onProgress: (transferred: Long, total: Long) -> Unit): File {
    val file = cacheFile(path)
    if (file.exists() && file.length() > 0L) return file
    val tmp = File(file.path + ".part")
    withContext(Dispatchers.IO) { tmp.delete() }
    try {
      suspendCancellableCoroutine<Unit> { cont ->
        val task = storage.reference.child(path).getFile(tmp)
        task.addOnProgressListener { snap -> onProgress(snap.bytesTransferred, snap.totalByteCount) }
        task.addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
        task.addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        cont.invokeOnCancellation { task.cancel() }
      }
    } catch (e: Throwable) {
      withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
      throw e
    }
    return withContext(Dispatchers.IO) {
      if (!tmp.renameTo(file)) throw java.io.IOException("could not store download")
      file
    }
  }

  private fun cacheFile(path: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
      .joinToString("") { "%02x".format(it) }.take(32)
    val ext = path.substringAfterLast('.', "bin").take(8)
    return File(cacheDir, "$digest.$ext")
  }

  /** A video chosen for a moment, already checked against the length and size limits. */
  class PreparedVideo(val bytes: ByteArray, val width: Int, val height: Int, val durationMs: Long)

  class VideoProblemException(val problem: com.samvad.core.status.VideoProblem) : Exception(problem.name)

  /** Reads a chosen video. Throws [VideoProblemException] when it is longer than 30 seconds, over 16 MB, or unreadable. */
  suspend fun prepareVideo(uri: Uri): PreparedVideo = withContext(Dispatchers.IO) {
    var durationMs = 0L
    var width = 0
    var height = 0
    val retriever = android.media.MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      if (rotation == 90 || rotation == 270) { val t = width; width = height; height = t }
    } catch (_: Exception) {
      throw VideoProblemException(com.samvad.core.status.VideoProblem.UNREADABLE)
    } finally {
      runCatching { retriever.release() }
    }
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
    }
    com.samvad.core.status.StatusRules.videoProblem(if (size < 0) 1L else size, durationMs)?.let { throw VideoProblemException(it) }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      ?: throw VideoProblemException(com.samvad.core.status.VideoProblem.UNREADABLE)
    com.samvad.core.status.StatusRules.videoProblem(bytes.size.toLong(), durationMs)?.let { throw VideoProblemException(it) }
    PreparedVideo(bytes, width, height, durationMs)
  }

  /** True when the attachment is already on the phone, so showing it costs no data. */
  fun isCached(path: String): Boolean = cacheFile(path).let { it.exists() && it.length() > 0L }

  /** Local copy of an attachment, downloaded once through the SDK (rules-checked) and cached. */
  suspend fun localFile(path: String): File = withContext(Dispatchers.IO) {
    val file = cacheFile(path)
    if (!file.exists() || file.length() == 0L) {
      val tmp = File(file.path + ".part")
      storage.reference.child(path).getFile(tmp).await()
      tmp.renameTo(file)
    }
    file
  }

  /** Decodes a cached image at a size suited to [maxEdgePx]. */
  suspend fun loadBitmap(path: String, maxEdgePx: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
      val file = localFile(path)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.path, bounds)
      val opts = BitmapFactory.Options().apply { inSampleSize = MediaRules.sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx) }
      BitmapFactory.decodeFile(file.path, opts)
    }.getOrNull()
  }
}
