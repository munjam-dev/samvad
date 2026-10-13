package com.samvad.messenger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.core.media.MediaRules
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
  private val cacheDir = File(context.cacheDir, "media").apply { mkdirs() }

  /** Downscales to 1600 px and re-encodes as JPEG, which also strips EXIF data such as GPS location. */
  suspend fun prepareImage(uri: Uri): PreparedMedia = withContext(Dispatchers.Default) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw MediaException.Unreadable()
    val opts = BitmapFactory.Options().apply { inSampleSize = MediaRules.sampleSize(bounds.outWidth, bounds.outHeight) }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
      ?: throw MediaException.Unreadable()
    val (w, h) = MediaRules.fitWithin(decoded.width, decoded.height)
    val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, MediaRules.JPEG_QUALITY, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    val bytes = out.toByteArray()
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

  private fun cacheFile(path: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
      .joinToString("") { "%02x".format(it) }.take(32)
    val ext = path.substringAfterLast('.', "bin").take(8)
    return File(cacheDir, "$digest.$ext")
  }

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
