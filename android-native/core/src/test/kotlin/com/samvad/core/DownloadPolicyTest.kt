package com.samvad.core

import com.samvad.core.media.AutoDownload
import com.samvad.core.media.DownloadPolicy
import com.samvad.core.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
  private fun should(s: AutoDownload, saver: Boolean = false, metered: Boolean = false, cached: Boolean = false) =
    DownloadPolicy.shouldAutoDownload(s, saver, metered, cached)

  @Test fun `a photo already on the phone always shows`() {
    for (s in AutoDownload.entries) assertTrue(should(s, saver = true, metered = true, cached = true))
  }

  @Test fun `always downloads on any network when data saver is off`() {
    assertTrue(should(AutoDownload.ALWAYS, metered = false))
    assertTrue(should(AutoDownload.ALWAYS, metered = true))
  }

  @Test fun `wifi only blocks metered networks`() {
    assertTrue(should(AutoDownload.WIFI_ONLY, metered = false))
    assertFalse(should(AutoDownload.WIFI_ONLY, metered = true))
  }

  @Test fun `never blocks everything that is not cached`() {
    assertFalse(should(AutoDownload.NEVER, metered = false))
    assertFalse(should(AutoDownload.NEVER, metered = true))
  }

  @Test fun `data saver tightens always to wifi only`() {
    assertTrue(should(AutoDownload.ALWAYS, saver = true, metered = false))
    assertFalse(should(AutoDownload.ALWAYS, saver = true, metered = true))
  }

  @Test fun `data saver never loosens never`() {
    assertFalse(should(AutoDownload.NEVER, saver = true, metered = false))
  }

  @Test fun `data saver uploads smaller photos`() {
    assertEquals(MediaRules.MAX_IMAGE_EDGE, DownloadPolicy.uploadEdgePx(false))
    assertEquals(MediaRules.JPEG_QUALITY, DownloadPolicy.uploadQuality(false))
    assertTrue(DownloadPolicy.uploadEdgePx(true) < MediaRules.MAX_IMAGE_EDGE)
    assertTrue(DownloadPolicy.uploadQuality(true) < MediaRules.JPEG_QUALITY)
  }

  @Test fun `an unknown stored value falls back to always`() {
    assertEquals(AutoDownload.ALWAYS, AutoDownload.parse(null))
    assertEquals(AutoDownload.ALWAYS, AutoDownload.parse("nonsense"))
    assertEquals(AutoDownload.NEVER, AutoDownload.parse("NEVER"))
  }
}
