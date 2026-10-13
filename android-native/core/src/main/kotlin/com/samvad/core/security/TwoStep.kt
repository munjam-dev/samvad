package com.samvad.core.security

enum class PinProblem { FORMAT, TOO_SIMPLE }

/** Two-step verification PIN rules; the server (functions/src/twoStep.ts) enforces the same ones. */
object TwoStepPin {
  const val LENGTH = 6

  fun problem(pin: String): PinProblem? {
    if (pin.length != LENGTH || !pin.all { it in '0'..'9' }) return PinProblem.FORMAT
    val steps = pin.zipWithNext { a, b -> b - a }
    if (steps.all { it == 0 } || steps.all { it == 1 } || steps.all { it == -1 }) return PinProblem.TOO_SIMPLE
    return null
  }

  /**
   * Whether this session may use the account (mirrors verifiedSession() in the rules): a phone
   * sign-in while two-step is off, or a PIN session whose claim pv matches the account claim tsv.
   */
  fun isVerifiedSession(provider: String?, ts: Boolean, tsv: String?, pv: String?): Boolean = when (provider) {
    "phone" -> !ts
    "custom" -> pv != null && (!ts || pv == tsv)
    else -> false
  }
}
