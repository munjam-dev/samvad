package com.samvad.core

import com.samvad.core.notify.QuietHours
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
  private val ist = ZoneId.of("Asia/Kolkata")
  private val newYork = ZoneId.of("America/New_York")
  private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
  private val m = QuietHours::minuteOf
  private val night = QuietHours(true, m(22, 0), m(7, 0))

  @Test
  fun `an overnight window is quiet late evening and early morning, not during the day`() {
    assertTrue(night.overnight)
    assertTrue(night.isQuiet(ms(ist, 2026, 10, 17, 22, 0), ist))
    assertTrue(night.isQuiet(ms(ist, 2026, 10, 17, 23, 59), ist))
    assertTrue(night.isQuiet(ms(ist, 2026, 10, 18, 0, 0), ist))
    assertTrue(night.isQuiet(ms(ist, 2026, 10, 18, 6, 59), ist))
    assertFalse(night.isQuiet(ms(ist, 2026, 10, 18, 7, 0), ist)) // end is excluded
    assertFalse(night.isQuiet(ms(ist, 2026, 10, 18, 12, 0), ist))
    assertFalse(night.isQuiet(ms(ist, 2026, 10, 17, 21, 59), ist))
  }

  @Test
  fun `a same-day window includes its start and excludes its end`() {
    val lunch = QuietHours(true, m(13, 0), m(14, 30))
    assertFalse(lunch.overnight)
    assertFalse(lunch.isQuiet(ms(ist, 2026, 10, 17, 12, 59), ist))
    assertTrue(lunch.isQuiet(ms(ist, 2026, 10, 17, 13, 0), ist))
    assertTrue(lunch.isQuiet(ms(ist, 2026, 10, 17, 14, 29), ist))
    assertFalse(lunch.isQuiet(ms(ist, 2026, 10, 17, 14, 30), ist))
  }

  @Test
  fun `off, or a window with equal start and end, is never quiet`() {
    val at = ms(ist, 2026, 10, 17, 23, 0)
    assertFalse(night.copy(enabled = false).isQuiet(at, ist))
    val empty = QuietHours(true, m(8, 0), m(8, 0))
    assertFalse(empty.isValid)
    assertFalse(empty.isQuiet(ms(ist, 2026, 10, 17, 8, 0), ist))
    assertEquals(0, empty.lengthMinutes)
    assertFalse(QuietHours(true, -5, 100).isValid)
    assertFalse(QuietHours(true, 100, 1440).isValid)
  }

  @Test
  fun `the same instant is quiet or not depending on the time zone`() {
    val instant = ms(ist, 2026, 10, 17, 23, 30) // 23:30 in Kolkata is 14:00 in New York
    assertTrue(night.isQuiet(instant, ist))
    assertFalse(night.isQuiet(instant, newYork))
  }

  @Test
  fun `the window follows the wall clock across daylight saving changes`() {
    // New York clocks go forward on 8 March 2026 (02:00 becomes 03:00) and back on 1 November 2026.
    val window = QuietHours(true, m(1, 0), m(4, 0))
    assertTrue(window.isQuiet(ms(newYork, 2026, 3, 8, 3, 30), newYork)) // 3:30 exists, inside
    assertFalse(window.isQuiet(ms(newYork, 2026, 3, 8, 4, 0), newYork))
    val repeated = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(newYork)
    assertTrue(window.isQuiet(repeated.withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), newYork))
    assertTrue(window.isQuiet(repeated.withLaterOffsetAtOverlap().toInstant().toEpochMilli(), newYork))
    assertFalse(window.isQuiet(ms(newYork, 2026, 11, 1, 4, 0), newYork))
  }

  @Test
  fun `window length counts across midnight`() {
    assertEquals(9 * 60, night.lengthMinutes)
    assertEquals(90, QuietHours(true, m(13, 0), m(14, 30)).lengthMinutes)
    assertEquals(23 * 60 + 59, QuietHours(true, m(0, 1), m(0, 0)).lengthMinutes)
  }

  @Test
  fun `the next change is the end while quiet and the start while not`() {
    val atNight = ms(ist, 2026, 10, 17, 23, 0)
    assertEquals(ms(ist, 2026, 10, 18, 7, 0), night.nextChange(atNight, ist))
    val atEarly = ms(ist, 2026, 10, 18, 3, 0)
    assertEquals(ms(ist, 2026, 10, 18, 7, 0), night.nextChange(atEarly, ist))
    val atNoon = ms(ist, 2026, 10, 18, 12, 0)
    assertEquals(ms(ist, 2026, 10, 18, 22, 0), night.nextChange(atNoon, ist))
    assertNull(night.copy(enabled = false).nextChange(atNoon, ist))
    assertNull(QuietHours(true, 60, 60).nextChange(atNoon, ist))
  }

  @Test
  fun `stored values that are not a minute of a day fall back to the defaults`() {
    assertEquals(QuietHours(true, 22 * 60, 7 * 60), QuietHours.sanitized(true, null, null))
    assertEquals(QuietHours(false, 22 * 60, 6 * 60), QuietHours.sanitized(false, 99_999, 360))
    assertEquals(QuietHours(true, 480, 540), QuietHours.sanitized(true, 480, 540))
    assertEquals(23 * 60 + 59, QuietHours.minuteOf(99, 99))
    assertEquals(0, QuietHours.minuteOf(-3, -3))
  }
}
