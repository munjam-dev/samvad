package com.samvad.core

import com.samvad.core.media.MediaKind
import com.samvad.core.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRulesTest {
  @Test fun `allow-list matches the storage rules and blocks executables`() {
    assertTrue(MediaRules.isAllowed(MediaKind.IMAGE, "image/jpeg"))
    assertFalse(MediaRules.isAllowed(MediaKind.IMAGE, "image/gif"))
    assertTrue(MediaRules.isAllowed(MediaKind.FILE, "application/pdf"))
    assertFalse(MediaRules.isAllowed(MediaKind.FILE, "application/vnd.android.package-archive"))
    assertFalse(MediaRules.isAllowed(MediaKind.FILE, "application/octet-stream"))
    assertFalse(MediaRules.isAllowed(MediaKind.FILE, null))
    assertTrue(MediaRules.isAllowed(MediaKind.VOICE, "audio/mp4"))
  }

  @Test fun `size limit is 16 MB and empty files are rejected`() {
    assertTrue(MediaRules.isWithinLimit(16L * 1024 * 1024))
    assertFalse(MediaRules.isWithinLimit(16L * 1024 * 1024 + 1))
    assertFalse(MediaRules.isWithinLimit(0))
  }

  @Test fun `file names are made storage-safe`() {
    assertEquals("Trip_notes.pdf", MediaRules.safeFileName("Trip notes.pdf"))
    assertEquals("passwd", MediaRules.safeFileName("../../etc/passwd"))
    assertEquals("file.jpg", MediaRules.safeFileName("संवाद.JPG"))
    assertEquals("file", MediaRules.safeFileName(""))
    assertTrue(MediaRules.safeFileName("a".repeat(300) + ".pdf").length <= 100)
    assertTrue(Regex("^[A-Za-z0-9._-]{1,100}$").matches(MediaRules.safeFileName("weird name (1) [final].docx")))
  }

  @Test fun `images are scaled down, never up`() {
    assertEquals(1600 to 1200, MediaRules.fitWithin(4000, 3000))
    assertEquals(900 to 1600, MediaRules.fitWithin(1800, 3200))
    assertEquals(800 to 600, MediaRules.fitWithin(800, 600))
    assertEquals(2, MediaRules.sampleSize(4000, 3000))
    assertEquals(1, MediaRules.sampleSize(1000, 800))
  }

  @Test fun `waveform is normalised to 48 bars`() {
    val wave = MediaRules.waveform(List(480) { (it % 100) * 300 })
    assertEquals(48, wave.size)
    assertTrue(wave.all { it in 0..31 })
    assertEquals(31, wave.max())
    assertEquals(emptyList<Int>(), MediaRules.waveform(emptyList()))
    assertEquals(48, MediaRules.waveform(listOf(5, 10)).size)
  }

  @Test fun `human sizes and durations`() {
    assertEquals("512 B", MediaRules.formatBytes(512))
    assertEquals("120 KB", MediaRules.formatBytes(120 * 1024))
    assertEquals("2.5 MB", MediaRules.formatBytes((2.5 * 1024 * 1024).toLong()))
    assertEquals("0:09", MediaRules.formatDuration(9_400))
    assertEquals("12:05", MediaRules.formatDuration(725_000))
  }
}
