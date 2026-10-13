package com.samvad.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Human time labels. English for now; strings move to resources when Hindi ships. */
object TimeLabels {
  private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
  private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
  private val longWeekday = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
  private val shortDate = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH)
  private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

  fun clock(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(clock)

  private fun daysAgo(epochMs: Long, nowMs: Long, zone: ZoneId): Long {
    val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(then, today)
  }

  /** "9:24 AM" today, "Yesterday", "Mon" within a week, else "12/10/26". */
  fun chatListTime(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    return when (val d = daysAgo(epochMs, nowMs, zone)) {
      0L -> at.format(clock)
      1L -> "Yesterday"
      in 2L..6L -> at.format(weekday)
      else -> if (d < 0) at.format(clock) else at.format(shortDate)
    }
  }

  /** "Today", "Yesterday", "Monday" within a week, else "12 October 2026". */
  fun dateSeparator(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    return when (daysAgo(epochMs, nowMs, zone)) {
      0L -> "Today"
      1L -> "Yesterday"
      in 2L..6L -> at.format(longWeekday)
      else -> at.format(longDate)
    }
  }

  fun greeting(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    when (Instant.ofEpochMilli(nowMs).atZone(zone).hour) {
      in 5..11 -> "Good morning"
      in 12..16 -> "Good afternoon"
      else -> "Good evening"
    }
}
