package com.samvad.core.profile

/**
 * Username format rules. Kept identical to `usernameProblem` in firebase/functions/src/username.ts: the server is
 * the authority and checks again, this only gives instant feedback while typing.
 */
object Usernames {
  const val MIN_LENGTH = 3
  const val MAX_LENGTH = 20

  enum class Problem { TOO_SHORT, TOO_LONG, BAD_START, BAD_CHARS, UNDERSCORES, RESERVED }

  private val RESERVED = setOf(
    "admin", "administrator", "samvad", "support", "help", "official", "staff", "team", "moderator", "mod", "root",
    "system", "security", "safety", "privacy", "legal", "contact", "info", "service", "noreply", "billing",
    "null", "undefined", "me", "you", "everyone", "all", "here", "status", "moments", "settings", "api", "www",
  )

  /** Lower-case, without a leading "@" or surrounding spaces. Usernames are not case sensitive. */
  fun normalize(raw: String): String = raw.trim().removePrefix("@").lowercase()

  /** The first rule [name] breaks (already normalised), or null when it is acceptable. */
  fun problem(name: String): Problem? = when {
    name.length < MIN_LENGTH -> Problem.TOO_SHORT
    name.length > MAX_LENGTH -> Problem.TOO_LONG
    name.first() !in 'a'..'z' -> Problem.BAD_START
    !name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } -> Problem.BAD_CHARS
    "__" in name || name.endsWith("_") -> Problem.UNDERSCORES
    name in RESERVED -> Problem.RESERVED
    else -> null
  }
}
