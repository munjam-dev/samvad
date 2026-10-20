package com.samvad.core.media

import kotlin.math.max
import kotlin.math.roundToInt

enum class MediaKind(val wire: String) {
  IMAGE("image"), FILE("file"), VOICE("voice"), VIDEO("video");

  companion object {
    fun fromWire(value: String?): MediaKind? = entries.firstOrNull { it.wire == value }
  }
}

/** Description of an attachment, mirrored 1:1 in the message's `media` map. */
data class Media(
  val path: String,
  val name: String,
  val size: Long,
  val mime: String,
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val waveform: List<Int> = emptyList(),
  /** Videos only: Storage path of the small JPEG poster frame (thumb.jpg in the same folder). */
  val thumbPath: String? = null,
)

/** Client-side mirror of the Storage/Firestore media rules, so users get instant, clear errors. */
object MediaRules {
  const val MAX_BYTES = 16L * 1024 * 1024
  const val MAX_IMAGE_EDGE = 1600
  const val JPEG_QUALITY = 82
  const val WAVEFORM_BARS = 48
  const val WAVEFORM_MAX = 31
  const val MAX_VOICE_MS = 15 * 60 * 1000L
  const val VIDEO_MIME = "video/mp4"

  private val documentTypes = setOf(
    "application/pdf",
    "text/plain",
    "application/zip",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
  )
  private val imageTypes = setOf("image/jpeg", "image/png", "image/webp")
  private val voiceTypes = setOf("audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg")

  /** MIME types the document picker offers (must match storage.rules). */
  val pickableDocuments: Array<String> get() = documentTypes.toTypedArray()

  fun isAllowed(kind: MediaKind, mime: String?): Boolean = when (kind) {
    MediaKind.IMAGE -> mime in imageTypes
    MediaKind.VOICE -> mime in voiceTypes
    MediaKind.FILE -> mime in documentTypes || mime in imageTypes
    MediaKind.VIDEO -> mime == VIDEO_MIME
  }

  fun isWithinLimit(size: Long): Boolean = size in 1..MAX_BYTES

  /** Storage object name: letters, digits, dot, dash, underscore; keeps the extension; ≤ 100 chars. */
  fun safeFileName(original: String): String {
    val trimmed = original.substringAfterLast('/').substringAfterLast('\\').trim()
    val ext = trimmed.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
    val base = trimmed.substringBeforeLast('.', trimmed)
      .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
      .joinToString("")
      .trim('_')
      .ifEmpty { "file" }
      .take(80)
    return if (ext.isEmpty()) base else "$base.$ext"
  }

  /** Scales (w, h) to fit inside [maxEdge] keeping the aspect ratio; never upscales. */
  fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_IMAGE_EDGE): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val longest = max(width, height)
    if (longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  /** Largest power-of-two sample size that keeps the decoded image at least [target] on its long edge. */
  fun sampleSize(width: Int, height: Int, target: Int = MAX_IMAGE_EDGE): Int {
    var sample = 1
    while (max(width, height) / (sample * 2) >= target) sample *= 2
    return sample
  }

  /** Reduces raw amplitudes (0..32767) to [bars] values in 0..[WAVEFORM_MAX]. */
  fun waveform(amplitudes: List<Int>, bars: Int = WAVEFORM_BARS): List<Int> {
    if (amplitudes.isEmpty()) return emptyList()
    val peak = amplitudes.maxOrNull()?.coerceAtLeast(1) ?: 1
    return List(bars) { i ->
      val from = i * amplitudes.size / bars
      val to = ((i + 1) * amplitudes.size / bars).coerceAtLeast(from + 1).coerceAtMost(amplitudes.size)
      val slice = amplitudes.subList(from.coerceAtMost(amplitudes.size - 1), to)
      ((slice.max().toDouble() / peak) * WAVEFORM_MAX).roundToInt().coerceIn(0, WAVEFORM_MAX)
    }
  }

  fun formatBytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${(size / 1024.0).roundToInt()} KB"
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
  }

  fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
  }
}
