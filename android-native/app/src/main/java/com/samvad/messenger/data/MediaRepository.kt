package com.samvad.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.core.media.DownloadPolicy
import com.samvad.core.media.MediaRules
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
  /** Video only: the small JPEG poster frame, uploaded alongside as thumb.jpg. */
  val thumbBytes: ByteArray? = null,
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

  /** Upright, downscaled to 1600 px and re-encoded as JPEG, which also strips EXIF data such as GPS location. */
  suspend fun prepareImage(uri: Uri, dataSaver: Boolean = false, edits: PhotoEdits = PhotoEdits.NONE): PreparedMedia = withContext(Dispatchers.Default) {
    // A photo moment with text or pen on it is drawn on a bitmap of at most MAX_EDGE_PX, so memory stays small.
    val edge = if (edits.isEmpty) DownloadPolicy.uploadEdgePx(dataSaver) else minOf(DownloadPolicy.uploadEdgePx(dataSaver), PhotoEditRules.MAX_EDGE_PX)
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
      if (!edits.isEmpty) {
        // The pen and text are drawn into a copy, so the JPEG carries them and nobody can lift them off again.
        val copy = try {
          source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: OutOfMemoryError) {
          null
        } ?: throw MediaException.Unreadable()
        flattened = copy
        PhotoEditRenderer.draw(Canvas(copy), copy.width, copy.height, edits)
        source = copy
      }
      val out = ByteArrayOutputStream()
      source.compress(Bitmap.CompressFormat.JPEG, DownloadPolicy.uploadQuality(dataSaver), out)
      bytes = out.toByteArray()
    } finally {
      flattened?.recycle()
      if (scaled != null && scaled !== decoded) scaled.recycle()
      decoded.recycle()
    }
    if (!MediaRules.isWithinLimit(bytes.size.toLong())) throw MediaException.TooLarge()
    PreparedMedia(MediaKind.IMAGE, "photo.jpg", "Photo", "image/jpeg", bytes, width = w, height = h)
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
  suspend fun upload(conversationId: String, messageId: String, me: String, media: PreparedMedia): String =
    uploadOne(conversationId, messageId, me, media.fileName, media.mime, media.bytes)

  /** A video's file, then its poster frame (thumb.jpg) right after. Returns (video path, thumbnail path). */
  suspend fun uploadVideo(conversationId: String, messageId: String, me: String, media: PreparedMedia): Pair<String, String> {
    val thumb = media.thumbBytes ?: throw IllegalArgumentException("video needs a thumbnail")
    val path = uploadOne(conversationId, messageId, me, media.fileName, media.mime, media.bytes)
    val thumbPath = uploadOne(conversationId, messageId, me, "thumb.jpg", "image/jpeg", thumb)
    return path to thumbPath
  }

  private suspend fun uploadOne(conversationId: String, messageId: String, me: String, fileName: String, mime: String, bytes: ByteArray): String {
    val path = "conversations/$conversationId/$messageId/$fileName"
    val meta = StorageMetadata.Builder()
      .setContentType(mime)
      .setCustomMetadata("senderId", me)
      .build()
    var attempt = 0
    while (true) {
      try {
        storage.reference.child(path).putBytes(bytes, meta).await()
        cacheFile(path).writeBytes(bytes)
        return path
      } catch (e: Exception) {
        if (++attempt >= 3) throw e
        delay(1000L * attempt)
      }
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

  class ChatVideoException(val problem: com.samvad.core.media.ChatVideoProblem) : Exception(problem.name)

  /**
   * Reads a video chosen for a chat (system photo/video picker): checks it against the chat limits
   * (60 s, 16 MB, MP4) and extracts a small JPEG poster frame on device. Throws [ChatVideoException] when
   * it does not qualify, so the caller can explain why instead of silently shrinking or re-encoding it.
   */
  suspend fun prepareChatVideo(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
    val mime = context.contentResolver.getType(uri)
    var durationMs = 0L
    var width = 0
    var height = 0
    var poster: Bitmap? = null
    val retriever = android.media.MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      if (rotation == 90 || rotation == 270) { val t = width; width = height; height = t }
      poster = retriever.getFrameAtTime(com.samvad.core.media.ChatVideo.posterTimeUs(durationMs), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
      throw ChatVideoException(com.samvad.core.media.ChatVideoProblem.UNREADABLE)
    } finally {
      runCatching { retriever.release() }
    }
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
    }
    com.samvad.core.media.ChatVideo.problem(mime, if (size < 0) 1L else size, durationMs)?.let { throw ChatVideoException(it) }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ChatVideoException(com.samvad.core.media.ChatVideoProblem.UNREADABLE)
    com.samvad.core.media.ChatVideo.problem(mime, bytes.size.toLong(), durationMs)?.let { throw ChatVideoException(it) }
    val (tw, th) = com.samvad.core.media.ChatVideo.thumbSize(width, height)
    val thumbBytes = try {
      val base = poster ?: throw MediaException.Unreadable()
      val scaled = if (base.width != tw || base.height != th) Bitmap.createScaledBitmap(base, tw, th, true) else base
      val out = ByteArrayOutputStream()
      scaled.compress(Bitmap.CompressFormat.JPEG, com.samvad.core.media.ChatVideo.THUMB_QUALITY, out)
      if (scaled !== base) scaled.recycle()
      base.recycle()
      out.toByteArray()
    } catch (_: Exception) {
      throw ChatVideoException(com.samvad.core.media.ChatVideoProblem.UNREADABLE)
    }
    PreparedMedia(
      MediaKind.VIDEO, com.samvad.core.media.ChatVideo.FILE_NAME, "Video",
      com.samvad.core.media.MediaRules.VIDEO_MIME, bytes, width, height, durationMs, thumbBytes = thumbBytes,
    )
  }

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
