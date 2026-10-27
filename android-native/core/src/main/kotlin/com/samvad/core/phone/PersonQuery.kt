package com.samvad.core.phone

import com.samvad.core.profile.Usernames

/** What a person typed to find someone who is not in their address book. */
sealed interface PersonQuery {
  data class Phone(val e164: String) : PersonQuery
  data class Username(val name: String) : PersonQuery
  data object Invalid : PersonQuery
}

/**
 * Turns typed text into an exact lookup: letters or a leading "@" mean a username, digits mean a phone number
 * normalised to E.164 with +91 as the default country. Never a partial or name search.
 */
object PersonQueries {
  fun parse(input: String, region: String = PhoneNumbers.DEFAULT_REGION): PersonQuery {
    val text = input.trim()
    if (text.isEmpty()) return PersonQuery.Invalid
    if (text.startsWith("@") || text.any { it.isLetter() }) {
      val name = Usernames.normalize(text)
      return if (Usernames.problem(name) == null) PersonQuery.Username(name) else PersonQuery.Invalid
    }
    return PhoneNumbers.normalizeLenient(text, region)?.let { PersonQuery.Phone(it) } ?: PersonQuery.Invalid
  }
}
