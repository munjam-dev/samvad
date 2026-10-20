package com.samvad.core.notify

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quiet hours (per device): a daily window in which message notifications still arrive but make no sound and
 * no vibration. Times are minutes since midnight on the wall clock of the zone in use, so the window follows the
 * person when they change time zone and when clocks change for daylight saving.
 *
 * The window starts at [startMinute] (included) and ends at [endMinute] (excluded). When the end is earlier than
 * the start the window runs past midnight (22:00 to 07:00). Equal start and end is not a window (never quiet).
 */
data class QuietHours(
  val enabled: Boolean = false,
  val startMinute: Int = DEFAULT_START,
  val endMinute: Int = DEFAULT_END,
) {
  /** Both times are real minutes of a day and they differ. */
  val isValid: Boolean get() = startMinute in 0 until DAY && endMinute in 0 until DAY && startMinute != endMinute

  /** The window crosses midnight. */
  val overnight: Boolean get() = isValid && startMinute > endMinute

  /** How long the window lasts, in minutes (0 when not valid). */
  val lengthMinutes: Int get() = when {
    !isValid -> 0
    overnight -> DAY - startMinute + endMinute
    else -> endMinute - startMinute
  }

  /** Whether a notification arriving at [atMs] should be silent. */
  fun isQuiet(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (!enabled || !isValid) return false
    val local = Instant.ofEpochMilli(atMs).atZone(zone)
    return contains(local.hour * 60 + local.minute)
  }

  /** Whether the wall-clock [minuteOfDay] lies in the window. */
  fun contains(minuteOfDay: Int): Boolean = when {
    !isValid -> false
    overnight -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    else -> minuteOfDay in startMinute until endMinute
  }

  /**
   * The next moment, after [atMs], when quiet turns on or off (epoch ms), or null when quiet hours are off or not
   * valid. Used for "Quiet until 07:00" and to schedule nothing: the decision is always taken at arrival.
   */
  fun nextChange(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (!enabled || !isValid) return null
    val now = Instant.ofEpochMilli(atMs).atZone(zone)
    val candidates = (-1L..2L).flatMap { d ->
      val day = now.toLocalDate().plusDays(d)
      listOf(at(day, startMinute, zone), at(day, endMinute, zone))
    }
    return candidates.map { it.toInstant().toEpochMilli() }.filter { it > atMs }.minOrNull()
  }

  private fun at(day: LocalDate, minute: Int, zone: ZoneId): ZonedDateTime = day.atTime(minute / 60, minute % 60).atZone(zone)

  companion object {
    const val DAY = 24 * 60
    const val DEFAULT_START = 22 * 60
    const val DEFAULT_END = 7 * 60

    fun minuteOf(hour: Int, minute: Int): Int = (hour.coerceIn(0, 23)) * 60 + minute.coerceIn(0, 59)

    /** Stored values that are not a minute of a day fall back to the defaults. */
    fun sanitized(enabled: Boolean, start: Int?, end: Int?): QuietHours = QuietHours(
      enabled = enabled,
      startMinute = start?.takeIf { it in 0 until DAY } ?: DEFAULT_START,
      endMinute = end?.takeIf { it in 0 until DAY } ?: DEFAULT_END,
    )
  }
}
