package com.samvad.core.chat

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class ScheduleCheck { OK, TOO_SOON, TOO_FAR }

/** Scheduled messages (Phase 3): the window the server rules accept, and the quick choices. */
object ScheduleRules {
  /** The rules allow 50 s; the app asks for a full minute so a slow network never trips the check. */
  const val MIN_AHEAD_MS = 60_000L
  const val MAX_AHEAD_MS = 30L * 24 * 60 * 60 * 1000
  /** A quick choice closer than this rolls over to the next day. */
  private const val QUICK_MARGIN_MS = 5 * 60_000L

  fun check(sendAtMs: Long, nowMs: Long): ScheduleCheck = when {
    sendAtMs - nowMs < MIN_AHEAD_MS -> ScheduleCheck.TOO_SOON
    sendAtMs - nowMs > MAX_AHEAD_MS -> ScheduleCheck.TOO_FAR
    else -> ScheduleCheck.OK
  }

  /** 9 PM today, or 9 PM tomorrow when that is less than five minutes away or already past. */
  fun tonight(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    nextAt(LocalTime.of(21, 0), nowMs, zone)

  /** 9 AM tomorrow. */
  fun tomorrowMorning(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return today.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
  }

  private fun nextAt(time: LocalTime, nowMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val today = now.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
    return if (today - nowMs >= QUICK_MARGIN_MS) today else now.toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
  }
}
