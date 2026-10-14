package com.samvad.core

import com.samvad.core.chat.ScheduleRules
import com.samvad.core.chat.ScheduleCheck
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleRulesTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

  @Test fun `times must be at least a minute and at most 30 days ahead`() {
    val now = at(2026, 10, 14, 10, 0)
    assertEquals(ScheduleCheck.TOO_SOON, ScheduleRules.check(now + 30_000, now))
    assertEquals(ScheduleCheck.OK, ScheduleRules.check(now + 60_000, now))
    assertEquals(ScheduleCheck.OK, ScheduleRules.check(now + 30L * 24 * 3_600_000, now))
    assertEquals(ScheduleCheck.TOO_FAR, ScheduleRules.check(now + 31L * 24 * 3_600_000, now))
  }

  @Test fun `tonight means 9 PM today, or tomorrow at 9 PM once that has passed`() {
    assertEquals(at(2026, 10, 14, 21, 0), ScheduleRules.tonight(at(2026, 10, 14, 10, 0), zone))
    assertEquals(at(2026, 10, 15, 21, 0), ScheduleRules.tonight(at(2026, 10, 14, 20, 59), zone))
  }

  @Test fun `tomorrow morning is 9 AM the next day`() {
    assertEquals(at(2026, 10, 15, 9, 0), ScheduleRules.tomorrowMorning(at(2026, 10, 14, 23, 30), zone))
  }
}
