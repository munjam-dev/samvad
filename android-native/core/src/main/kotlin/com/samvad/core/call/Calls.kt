package com.samvad.core.call

/** Pure call logic (Phase 7): states, history rows, durations and the meeting window. No Android types. */

enum class CallKind { AUDIO, VIDEO;
  companion object { fun parse(raw: String?): CallKind = if (raw == "video") VIDEO else AUDIO }
}

enum class CallStatus { RINGING, ACTIVE, ENDED, DECLINED, MISSED;
  val isLive: Boolean get() = this == RINGING || this == ACTIVE
  companion object {
    fun parse(raw: String?): CallStatus = when (raw) {
      "ringing" -> RINGING
      "active" -> ACTIVE
      "declined" -> DECLINED
      "missed" -> MISSED
      else -> ENDED
    }
  }
}

/** What a history row says, from the point of view of the person looking at it. */
enum class CallDirection { OUTGOING, INCOMING, MISSED }

data class CallRecord(
  val id: String,
  val callerUid: String,
  val participants: List<String>,
  val kind: CallKind,
  val status: CallStatus,
  val createdAtMs: Long,
  val answeredAtMs: Long?,
  val endedAtMs: Long?,
  val expiresAtMs: Long,
)

object CallRules {
  const val RING_MS = 45_000L

  fun otherParty(call: CallRecord, me: String): String? = call.participants.firstOrNull { it != me }

  fun direction(call: CallRecord, me: String): CallDirection = when {
    call.callerUid == me -> CallDirection.OUTGOING
    call.status == CallStatus.MISSED -> CallDirection.MISSED
    else -> CallDirection.INCOMING
  }

  /** A ring that was never answered and is past its expiry reads as missed even before the server sweep runs. */
  fun effectiveStatus(call: CallRecord, nowMs: Long): CallStatus =
    if (call.status == CallStatus.RINGING && nowMs >= call.expiresAtMs) CallStatus.MISSED else call.status

  /** Talk time in whole seconds; 0 when the call was never answered. */
  fun durationSeconds(call: CallRecord): Long {
    val start = call.answeredAtMs ?: return 0
    val end = call.endedAtMs ?: return 0
    return ((end - start).coerceAtLeast(0)) / 1000
  }

  /** "0:07", "12:05", "1:02:09". */
  fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
  }
}

/** Consecutive calls with the same person, same direction and same kind collapse into one row ("(3)"). */
data class CallHistoryRow(val latest: CallRecord, val other: String, val direction: CallDirection, val count: Int)

object CallHistory {
  fun group(calls: List<CallRecord>, me: String): List<CallHistoryRow> {
    val sorted = calls.sortedByDescending { it.createdAtMs }
    val rows = mutableListOf<CallHistoryRow>()
    for (c in sorted) {
      val other = CallRules.otherParty(c, me) ?: continue
      val dir = CallRules.direction(c, me)
      val last = rows.lastOrNull()
      if (last != null && last.other == other && last.direction == dir && last.latest.kind == c.kind) {
        rows[rows.lastIndex] = last.copy(count = last.count + 1)
      } else {
        rows += CallHistoryRow(c, other, dir, 1)
      }
    }
    return rows
  }
}

enum class MeetingPhase { UPCOMING, OPEN, ENDED }

/** Group meetings: joinable from 30 minutes before the start to 4 hours after (mirrors the server). */
object MeetingWindow {
  const val OPEN_BEFORE_MS = 30 * 60 * 1000L
  const val OPEN_AFTER_MS = 4 * 60 * 60 * 1000L
  const val MAX_PARTICIPANTS = 50

  fun isOpen(startsAtMs: Long, nowMs: Long): Boolean =
    nowMs >= startsAtMs - OPEN_BEFORE_MS && nowMs <= startsAtMs + OPEN_AFTER_MS

  fun phase(startsAtMs: Long, nowMs: Long, endedByHost: Boolean): MeetingPhase = when {
    endedByHost || nowMs > startsAtMs + OPEN_AFTER_MS -> MeetingPhase.ENDED
    nowMs >= startsAtMs - OPEN_BEFORE_MS -> MeetingPhase.OPEN
    else -> MeetingPhase.UPCOMING
  }

  fun minutesUntilOpen(startsAtMs: Long, nowMs: Long): Long =
    ((startsAtMs - OPEN_BEFORE_MS - nowMs).coerceAtLeast(0) + 59_999) / 60_000
}

/** The in-call connection banner: shown only when quality is poor or lost. */
enum class LinkQuality { GOOD, POOR, LOST;
  companion object {
    fun fromName(name: String?): LinkQuality = when (name?.uppercase()) {
      "POOR" -> POOR
      "LOST" -> LOST
      else -> GOOD
    }
  }
}
