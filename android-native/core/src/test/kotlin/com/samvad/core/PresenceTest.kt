package com.samvad.core

import com.samvad.core.presence.Presence
import com.samvad.core.presence.PresenceRules
import com.samvad.core.presence.PresenceStatus
import com.samvad.core.presence.TypingThrottle
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
  private val now = at(13, 14, 0)

  @Test fun `typing is fresh for six seconds only`() {
    assertTrue(PresenceRules.isTyping(now - 5_999, now))
    assertFalse(PresenceRules.isTyping(now - 6_000, now))
    assertFalse(PresenceRules.isTyping(null, now))
  }

  @Test fun `status prefers typing, then online, then last seen`() {
    assertEquals(PresenceStatus.Typing, PresenceRules.status(now - 1_000, Presence(false, at(13, 9, 24)), now, zone))
    assertEquals(PresenceStatus.Online, PresenceRules.status(null, Presence(true, now), now, zone))
    assertEquals(PresenceStatus.Unknown, PresenceRules.status(null, null, now, zone))
    assertEquals(
      PresenceStatus.LastSeen("last seen today at 9:24 AM"),
      PresenceRules.status(now - 60_000, Presence(false, at(13, 9, 24)), now, zone),
    )
  }

  @Test fun `last seen labels for yesterday and older`() {
    assertEquals("last seen yesterday at 6:10 PM", PresenceRules.lastSeenLabel(at(12, 18, 10), now, zone))
    assertEquals("last seen 01/10/26", PresenceRules.lastSeenLabel(at(1, 8, 0), now, zone))
  }

  @Test fun `typing writes are throttled to one per three seconds`() {
    var t = 0L
    val throttle = TypingThrottle { t }
    assertTrue(throttle.onKeystroke())
    t = 2_999
    assertFalse(throttle.onKeystroke())
    t = 3_000
    assertTrue(throttle.onKeystroke())
    throttle.reset()
    t = 3_001
    assertTrue(throttle.onKeystroke())
  }
}
