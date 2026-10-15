package com.samvad.core.chat

/**
 * Disappearing messages. The server decides when a message expires and deletes it; the app only hides it the
 * moment it expires, because the server's cleanup can lag by up to a day. Nothing here stops screenshots or copying.
 */
object Disappearing {
  const val OFF = 0L
  const val DAY = 86_400L
  const val WEEK = 604_800L
  const val QUARTER = 7_776_000L

  /** The four timers the server accepts, in seconds. */
  val CHOICES: List<Long> = listOf(OFF, DAY, WEEK, QUARTER)

  fun isValid(seconds: Long): Boolean = seconds in CHOICES

  /** Short English label, used in the settings sheet and the system line. */
  fun label(seconds: Long): String = when (seconds) {
    OFF -> "Off"
    DAY -> "24 hours"
    WEEK -> "7 days"
    QUARTER -> "90 days"
    else -> "Custom"
  }

  /** "Asha turned on disappearing messages: 7 days" or "Asha turned off disappearing messages". */
  fun describe(actorName: String, seconds: Long): String =
    if (seconds <= 0L) "$actorName turned off disappearing messages"
    else "$actorName turned on disappearing messages: ${label(seconds)}"

  /** A message with no expiry never expires. A message is hidden from its expiry time on. */
  fun isExpired(expiresAtMs: Long?, nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs

  /** The soonest future expiry among [messages], or null; the screen re-checks at that moment. */
  fun nextExpiry(messages: List<Message>, nowMs: Long): Long? =
    messages.mapNotNull { it.expiresAt }.filter { it > nowMs }.minOrNull()

  /** True when a chat's current timer should be shown (header icon, empty-chat notice). */
  fun isOn(seconds: Long?): Boolean = (seconds ?: OFF) > OFF
}
