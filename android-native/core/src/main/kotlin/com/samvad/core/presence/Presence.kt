package com.samvad.core.presence

import com.samvad.core.time.TimeLabels
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A user's presence as stored in RTDB presence/{uid}. Times are epoch milliseconds. */
data class Presence(val online: Boolean, val lastSeen: Long)

/** What the conversation header shows under the contact's name. */
sealed interface PresenceStatus {
  data object Typing : PresenceStatus
  data object Online : PresenceStatus
  data class LastSeen(val label: String) : PresenceStatus
  /** Hidden by the contact's privacy setting, or not yet known. */
  data object Unknown : PresenceStatus
}

object PresenceRules {
  /** A typing mark older than this is treated as stale (the writer stopped or disconnected). */
  const val TYPING_FRESH_MS = 6_000L

  /** Minimum gap between typing writes while the user keeps typing. */
  const val TYPING_THROTTLE_MS = 3_000L

  fun isTyping(typingAt: Long?, nowMs: Long): Boolean =
    typingAt != null && nowMs - typingAt in 0 until TYPING_FRESH_MS

  fun status(typingAt: Long?, presence: Presence?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): PresenceStatus = when {
    isTyping(typingAt, nowMs) -> PresenceStatus.Typing
    presence == null -> PresenceStatus.Unknown
    presence.online -> PresenceStatus.Online
    else -> PresenceStatus.LastSeen(lastSeenLabel(presence.lastSeen, nowMs, zone))
  }

  /** "last seen today at 9:24 AM", "last seen yesterday at 6:10 PM", else "last seen 12/10/26". */
  fun lastSeenLabel(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val then = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val clock = TimeLabels.clock(epochMs, zone)
    return when (ChronoUnit.DAYS.between(then, today)) {
      0L -> "last seen today at $clock"
      1L -> "last seen yesterday at $clock"
      else -> "last seen ${TimeLabels.chatListTime(epochMs, nowMs, zone)}"
    }
  }
}

/** Limits typing writes to one per [PresenceRules.TYPING_THROTTLE_MS] while the user types. */
class TypingThrottle(private val nowMs: () -> Long) {
  private var lastSent: Long? = null

  /** Returns true when a typing mark should be written now. */
  fun onKeystroke(): Boolean {
    val now = nowMs()
    val last = lastSent
    if (last != null && now - last < PresenceRules.TYPING_THROTTLE_MS) return false
    lastSent = now
    return true
  }

  /** Call after clearing the mark (send, empty field, leaving the chat). */
  fun reset() {
    lastSent = null
  }
}
