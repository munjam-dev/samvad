package com.samvad.core

import com.samvad.core.media.DownloadPolicy
import com.samvad.core.media.ImageQuality
import com.samvad.core.media.ImageQuality.Attempt
import com.samvad.core.media.ImageQuality.Outcome
import com.samvad.core.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityTest {
  private val mb = 1024L * 1024

  @Test fun `standard quality is the single existing attempt`() {
    assertEquals(
      listOf(Attempt(MediaRules.MAX_IMAGE_EDGE, MediaRules.JPEG_QUALITY)),
      ImageQuality.attempts(original = false, dataSaver = false),
    )
  }

  @Test fun `data saver keeps the smaller standard attempt`() {
    val attempt = ImageQuality.attempts(original = false, dataSaver = true).single()
    assertEquals(DownloadPolicy.SAVER_EDGE_PX, attempt.maxEdge)
    assertEquals(DownloadPolicy.SAVER_JPEG_QUALITY, attempt.quality)
  }

  @Test fun `original quality starts unscaled at the highest quality`() {
    val first = ImageQuality.attempts(original = true, dataSaver = false).first()
    assertNull(first.maxEdge)
    assertEquals(95, first.quality)
  }

  @Test fun `original quality asked for with data saver on is still original`() {
    assertEquals(
      ImageQuality.attempts(original = true, dataSaver = false),
      ImageQuality.attempts(original = true, dataSaver = true),
    )
  }

  @Test fun `original ladder only ever gets smaller`() {
    val list = ImageQuality.attempts(original = true, dataSaver = false)
    val unscaled = list.filter { it.maxEdge == null }.map { it.quality }
    assertEquals(unscaled.sortedDescending(), unscaled)
    val edges = list.mapNotNull { it.maxEdge }
    assertEquals(edges.sortedDescending(), edges)
    // The last resort is the standard size, which always fits in practice.
    assertEquals(Attempt(MediaRules.MAX_IMAGE_EDGE, MediaRules.JPEG_QUALITY), list.last())
  }

  @Test fun `a photo that fits at the top quality is chosen as it is`() {
    val choice = ImageQuality.choose(ImageQuality.attempts(true, false)) { 5 * mb }
    assertNotNull(choice)
    assertEquals(Outcome.AS_CHOSEN, choice!!.outcome)
    assertNull(choice.attempt.maxEdge)
    assertEquals(95, choice.attempt.quality)
  }

  @Test fun `the search stops at the first fit`() {
    var calls = 0
    ImageQuality.choose(ImageQuality.attempts(true, false)) { calls++; 1 * mb }
    assertEquals(1, calls)
  }

  @Test fun `too big at the top quality falls back to the largest quality that fits`() {
    val sizes = mapOf(95 to 20 * mb, 90 to 17 * mb, 85 to 15 * mb, 80 to 12 * mb, 75 to 10 * mb)
    val choice = ImageQuality.choose(ImageQuality.attempts(true, false)) { a -> if (a.maxEdge == null) sizes[a.quality] else 3 * mb }
    assertEquals(85, choice!!.attempt.quality)
    assertNull(choice.attempt.maxEdge)
    assertEquals(Outcome.LOWER_QUALITY, choice.outcome)
    assertEquals(15 * mb, choice.size)
  }

  @Test fun `when no quality fits the photo is scaled down`() {
    val choice = ImageQuality.choose(ImageQuality.attempts(true, false)) { a -> if (a.maxEdge == null) 40 * mb else 9 * mb }
    assertEquals(4096, choice!!.attempt.maxEdge)
    assertEquals(Outcome.SMALLER_SIZE, choice.outcome)
  }

  @Test fun `exactly 16 MB fits and one byte more does not`() {
    val fits = ImageQuality.choose(listOf(Attempt(null, 95))) { MediaRules.MAX_BYTES }
    assertNotNull(fits)
    val tooBig = ImageQuality.choose(listOf(Attempt(null, 95))) { MediaRules.MAX_BYTES + 1 }
    assertNull(tooBig)
  }

  @Test fun `nothing fits gives no choice so the caller can say it is too large`() {
    assertNull(ImageQuality.choose(ImageQuality.attempts(true, false)) { 99 * mb })
    assertNull(ImageQuality.choose(ImageQuality.attempts(false, false)) { 99 * mb })
  }

  @Test fun `a failed encode is skipped, not fatal`() {
    val choice = ImageQuality.choose(ImageQuality.attempts(true, false)) { a -> if (a.quality == 95 && a.maxEdge == null) null else 2 * mb }
    assertEquals(90, choice!!.attempt.quality)
    assertEquals(Outcome.LOWER_QUALITY, choice.outcome)
  }

  @Test fun `standard attempt is reported as chosen`() {
    val choice = ImageQuality.choose(ImageQuality.attempts(false, false)) { 300 * 1024L }
    assertEquals(Outcome.AS_CHOSEN, choice!!.outcome)
  }

  @Test fun `decode sample size keeps the pixel count in budget`() {
    assertEquals(1, ImageQuality.decodeSampleSize(4000, 3000))
    assertEquals(1, ImageQuality.decodeSampleSize(6000, 4000))
    // 48 MP is over the 24 MP budget, so it is decoded at half each way.
    assertEquals(2, ImageQuality.decodeSampleSize(8000, 6000))
    assertEquals(4, ImageQuality.decodeSampleSize(16000, 12000))
    for ((w, h) in listOf(8000 to 6000, 12000 to 9000, 30000 to 200)) {
      val s = ImageQuality.decodeSampleSize(w, h)
      assertTrue((w / s).toLong() * (h / s) <= ImageQuality.MAX_DECODE_PIXELS)
    }
  }

  @Test fun `only sources over the limit are flagged as likely to be reduced`() {
    assertFalse(ImageQuality.sourceMayBeReduced(MediaRules.MAX_BYTES))
    assertTrue(ImageQuality.sourceMayBeReduced(MediaRules.MAX_BYTES + 1))
  }
}
