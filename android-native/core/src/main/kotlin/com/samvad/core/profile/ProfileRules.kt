package com.samvad.core.profile

/** Display name and About rules, shared by setup and the Profile page. The server rules are the authority. */
object ProfileRules {
  const val NAME_MAX = 64
  const val ABOUT_MAX = 140

  enum class NameProblem { BLANK, TOO_LONG }

  /** The first rule [raw] breaks once trimmed, or null when it can be saved. */
  fun nameProblem(raw: String): NameProblem? {
    val n = raw.trim()
    return when {
      n.isEmpty() -> NameProblem.BLANK
      n.length > NAME_MAX -> NameProblem.TOO_LONG
      else -> null
    }
  }

  /** About is optional; only the length is limited. */
  fun aboutValid(raw: String): Boolean = raw.trim().length <= ABOUT_MAX

  /** Characters left in About for the counter (can go negative when over). */
  fun aboutRemaining(raw: String): Int = ABOUT_MAX - raw.length
}
