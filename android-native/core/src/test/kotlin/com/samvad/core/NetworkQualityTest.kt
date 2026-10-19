package com.samvad.core

import com.samvad.core.media.NetworkInfo
import com.samvad.core.media.NetworkLevel
import com.samvad.core.media.NetworkQuality
import com.samvad.core.media.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkQualityTest {
  private fun level(connected: Boolean = true, validated: Boolean = true, metered: Boolean = false, kbps: Int = 0) =
    NetworkQuality.classify(NetworkInfo(connected, validated, metered, kbps))

  @Test fun `no network is offline`() {
    assertEquals(NetworkLevel.OFFLINE, level(connected = false))
    assertFalse(NetworkQuality.isOnline(NetworkInfo(connected = false)))
  }

  @Test fun `a network the system could not validate counts as offline`() {
    assertEquals(NetworkLevel.OFFLINE, level(validated = false))
  }

  @Test fun `an unknown bandwidth is never called slow`() {
    assertEquals(NetworkLevel.GOOD, level(kbps = 0))
    assertEquals(NetworkLevel.GOOD, level(kbps = -1))
  }

  @Test fun `a low estimate is slow`() {
    assertEquals(NetworkLevel.SLOW, level(kbps = 240))
    assertEquals(NetworkLevel.SLOW, level(kbps = NetworkQuality.SLOW_KBPS - 1))
    assertEquals(NetworkLevel.GOOD, level(kbps = NetworkQuality.SLOW_KBPS))
  }

  @Test fun `slow wins over metered`() {
    assertEquals(NetworkLevel.SLOW, level(metered = true, kbps = 500))
  }

  @Test fun `metered but fast is metered`() {
    assertEquals(NetworkLevel.METERED, level(metered = true, kbps = 30_000))
    assertEquals(NetworkLevel.METERED, level(metered = true))
  }

  @Test fun `offline is the only level that shows the waiting banner`() {
    for (l in NetworkLevel.entries) assertEquals(l == NetworkLevel.OFFLINE, NetworkQuality.showOfflineBanner(l))
  }

  @Test fun `data saver is suggested on slow or metered networks only`() {
    assertTrue(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.SLOW, dataSaverOn = false, alreadyAnswered = false))
    assertTrue(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.METERED, dataSaverOn = false, alreadyAnswered = false))
    assertFalse(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.GOOD, dataSaverOn = false, alreadyAnswered = false))
    assertFalse(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.OFFLINE, dataSaverOn = false, alreadyAnswered = false))
  }

  @Test fun `data saver is not suggested when it is on or after it was answered`() {
    assertFalse(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.SLOW, dataSaverOn = true, alreadyAnswered = false))
    assertFalse(NetworkQuality.shouldSuggestDataSaver(NetworkLevel.METERED, dataSaverOn = false, alreadyAnswered = true))
  }

  @Test fun `progress is a fraction between 0 and 1`() {
    assertEquals(0.5f, TransferProgress.fraction(50, 100)!!, 0.0001f)
    assertEquals(1f, TransferProgress.fraction(120, 100)!!, 0f)
    assertEquals(0f, TransferProgress.fraction(-5, 100)!!, 0f)
  }

  @Test fun `progress is unknown until the total is known`() {
    assertNull(TransferProgress.fraction(10, 0))
    assertNull(TransferProgress.fraction(10, -1))
    assertNull(TransferProgress.percent(10, 0))
  }

  @Test fun `percent is whole and never above 100`() {
    assertEquals(33, TransferProgress.percent(1, 3))
    assertEquals(100, TransferProgress.percent(5, 4))
    assertEquals(0, TransferProgress.percent(0, 10))
  }
}
