package com.samvad.core.auth

/**
 * Client-side UX guards from the client contract (B1 spec §8). They are courtesy limits only;
 * Firebase quotas and App Check are the real abuse controls.
 */
class OtpPolicy(private val nowMs: () -> Long) {
  companion object {
    const val RESEND_COOLDOWN_MS = 60_000L
    const val MAX_RESENDS_PER_SESSION = 3
    const val MAX_REQUESTS_PER_NUMBER_PER_HOUR = 5
    const val MAX_WRONG_ATTEMPTS = 5
    private const val HOUR_MS = 3_600_000L
  }

  sealed interface Decision {
    data object Allowed : Decision
    data class WaitSeconds(val seconds: Int) : Decision
    data object SessionResendLimit : Decision
    data object HourlyLimit : Decision
  }

  private val requestsByNumber = mutableMapOf<String, MutableList<Long>>()
  private var sessionNumber: String? = null
  private var lastSentAt: Long? = null
  private var resendsUsed = 0
  private var wrongAttempts = 0

  private fun recentRequests(e164: String): MutableList<Long> {
    val now = nowMs()
    return requestsByNumber.getOrPut(e164) { mutableListOf() }.apply { removeAll { now - it >= HOUR_MS } }
  }

  /** May a first code be requested for this number? */
  fun canStart(e164: String): Decision =
    if (recentRequests(e164).size >= MAX_REQUESTS_PER_NUMBER_PER_HOUR) Decision.HourlyLimit else Decision.Allowed

  fun onCodeSent(e164: String, isResend: Boolean) {
    val now = nowMs()
    recentRequests(e164).add(now)
    if (!isResend || sessionNumber != e164) {
      sessionNumber = e164
      resendsUsed = 0
    } else {
      resendsUsed++
    }
    wrongAttempts = 0
    lastSentAt = now
  }

  fun canResend(): Decision {
    val number = sessionNumber ?: return Decision.Allowed
    val sent = lastSentAt ?: return Decision.Allowed
    if (resendsUsed >= MAX_RESENDS_PER_SESSION) return Decision.SessionResendLimit
    if (recentRequests(number).size >= MAX_REQUESTS_PER_NUMBER_PER_HOUR) return Decision.HourlyLimit
    val remaining = RESEND_COOLDOWN_MS - (nowMs() - sent)
    return if (remaining > 0) Decision.WaitSeconds(((remaining + 999) / 1000).toInt()) else Decision.Allowed
  }

  fun resendsLeft(): Int = (MAX_RESENDS_PER_SESSION - resendsUsed).coerceAtLeast(0)

  /** Records a wrong code; returns true when the user must request a new code. */
  fun onWrongCode(): Boolean {
    wrongAttempts++
    return wrongAttempts >= MAX_WRONG_ATTEMPTS
  }

  fun attemptsLeft(): Int = (MAX_WRONG_ATTEMPTS - wrongAttempts).coerceAtLeast(0)

  fun reset() {
    sessionNumber = null
    lastSentAt = null
    resendsUsed = 0
    wrongAttempts = 0
  }
}

/** Typed sign-in failures (client contract §8). The app maps them to localised messages. */
enum class AuthError {
  INVALID_NUMBER,
  INVALID_CODE,
  CODE_EXPIRED,
  TOO_MANY_REQUESTS,
  NETWORK,
  APP_VERIFICATION_FAILED,
  USER_DISABLED,
  NEW_CODE_REQUIRED,
  UNKNOWN,
}
