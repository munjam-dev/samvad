package com.samvad.core.media

/**
 * Sizing for photos before they are sent. The default keeps the existing behaviour (long edge 1600 px,
 * JPEG 82, or smaller with Data saver). "Original quality" skips the downscale and uses a high JPEG
 * quality; if that does not fit the 16 MB Storage limit it steps down to the largest quality that does.
 *
 * Every attempt is a full re-encode of the decoded pixels, which also drops EXIF data such as GPS
 * location, so original quality never sends location metadata.
 */
object ImageQuality {
  /** JPEG quality tried first for original quality, then lower ones until the file fits. */
  val ORIGINAL_QUALITIES: List<Int> = listOf(95, 90, 85, 80, 75)

  /** If lowering the quality is not enough, the long edge steps down (at the standard quality). */
  val FALLBACK_EDGES: List<Int> = listOf(4096, 3072, 2400, MediaRules.MAX_IMAGE_EDGE)

  /**
   * Largest number of pixels decoded at once. A 48 MP photo would need about 190 MB as a bitmap, which
   * can crash small phones, so very large photos are decoded at a power-of-two fraction of their size.
   */
  const val MAX_DECODE_PIXELS = 24_000_000L

  /** One encode to try. [maxEdge] null means "do not scale". */
  data class Attempt(val maxEdge: Int?, val quality: Int)

  enum class Outcome {
    /** Sent as chosen: the standard size, or the original at the highest quality. */
    AS_CHOSEN,

    /** Original size kept, but the JPEG quality was lowered to fit the limit. */
    LOWER_QUALITY,

    /** The photo was scaled down to fit the limit. */
    SMALLER_SIZE,
  }

  data class Choice(val attempt: Attempt, val size: Long, val outcome: Outcome)

  /** Encodes to try, best first. Without [original] this is the single standard attempt. */
  fun attempts(original: Boolean, dataSaver: Boolean): List<Attempt> {
    if (!original) return listOf(Attempt(DownloadPolicy.uploadEdgePx(dataSaver), DownloadPolicy.uploadQuality(dataSaver)))
    return ORIGINAL_QUALITIES.map { Attempt(null, it) } +
      FALLBACK_EDGES.map { Attempt(it, MediaRules.JPEG_QUALITY) }
  }

  /**
   * Picks the first attempt whose encoded size fits the limit. [encodedSize] returns the size in bytes of
   * encoding with that attempt, or null if it failed. Returns null when nothing fits.
   * Attempts are tried in order and the search stops at the first fit, so a small photo is encoded once.
   */
  fun choose(attempts: List<Attempt>, encodedSize: (Attempt) -> Long?): Choice? {
    attempts.forEachIndexed { index, attempt ->
      val size = encodedSize(attempt) ?: return@forEachIndexed
      if (MediaRules.isWithinLimit(size)) return Choice(attempt, size, outcomeFor(attempts, index))
    }
    return null
  }

  private fun outcomeFor(attempts: List<Attempt>, index: Int): Outcome {
    val first = attempts.first()
    val chosen = attempts[index]
    return when {
      index == 0 -> Outcome.AS_CHOSEN
      first.maxEdge == null && chosen.maxEdge == null -> Outcome.LOWER_QUALITY
      else -> Outcome.SMALLER_SIZE
    }
  }

  /** Power-of-two sample size that keeps a decoded image at or under [maxPixels]. */
  fun decodeSampleSize(width: Int, height: Int, maxPixels: Long = MAX_DECODE_PIXELS): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
    return sample
  }

  /** True when the source file alone is over the limit, so original quality will have to be reduced. */
  fun sourceMayBeReduced(sourceBytes: Long): Boolean = sourceBytes > MediaRules.MAX_BYTES
}
