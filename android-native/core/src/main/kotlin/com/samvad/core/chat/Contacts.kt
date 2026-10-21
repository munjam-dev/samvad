package com.samvad.core.chat

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.samvad.core.phone.PhoneNumbers

/** A shared contact: a name, one to three phone numbers in E.164, and an optional organisation. */
data class ContactCard(val name: String, val phones: List<String>, val organisation: String? = null)

sealed interface ContactCardResult {
  /** [dropped] counts numbers left out because they were invalid, repeated, or beyond the limit of three. */
  data class Ok(val card: ContactCard, val dropped: Int) : ContactCardResult
  data object NoName : ContactCardResult
  data object NoPhone : ContactCardResult
}

/**
 * Building a contact card from what the phone's contact picker returned. Mirrors the server rule in
 * firestore.rules (validContact): name 1 to 100 characters, 1 to 3 different E.164 numbers, organisation up to 100.
 * Every number is normalised here, so the card always carries the form the server accepts.
 */
object ContactCards {
  const val MAX_PHONES = 3
  const val MAX_NAME = 100
  const val MAX_ORGANISATION = 100

  private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()
  private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")

  /** Any valid number (mobile or landline) as E.164, or null. Numbers without a country code use [region]. */
  fun normalizePhone(raw: String, region: String = PhoneNumbers.DEFAULT_REGION): String? {
    val cleaned = raw.filter { it.isDigit() || it == '+' }
    if (cleaned.count { it.isDigit() } < 6) return null
    val parsed = try {
      util.parse(cleaned, region)
    } catch (_: NumberParseException) {
      return null
    }
    if (!util.isValidNumber(parsed)) return null
    return util.format(parsed, PhoneNumberFormat.E164).takeIf { E164.matches(it) }
  }

  fun build(name: String?, rawPhones: List<String>, organisation: String?, region: String = PhoneNumbers.DEFAULT_REGION): ContactCardResult {
    val org = organisation?.let(::clean)?.take(MAX_ORGANISATION)?.takeIf { it.isNotEmpty() }
    val cleanName = name?.let(::clean)?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: org ?: return ContactCardResult.NoName
    val valid = rawPhones.mapNotNull { normalizePhone(it, region) }
    val unique = valid.distinct()
    if (unique.isEmpty()) return ContactCardResult.NoPhone
    val kept = unique.take(MAX_PHONES)
    return ContactCardResult.Ok(ContactCard(cleanName, kept, org.takeIf { it != cleanName }), dropped = rawPhones.size - kept.size)
  }

  /** The same check the server makes, for cards read back from a message. */
  fun isValid(card: ContactCard): Boolean =
    card.name.isNotBlank() && card.name.length <= MAX_NAME &&
      card.phones.size in 1..MAX_PHONES && card.phones.distinct().size == card.phones.size && card.phones.all(E164::matches) &&
      (card.organisation?.length ?: 0) <= MAX_ORGANISATION

  /** "+91 98765 43210" for display; falls back to the E.164 text. */
  fun display(e164: String): String = try {
    util.format(util.parse(e164, null), PhoneNumberFormat.INTERNATIONAL)
  } catch (_: NumberParseException) {
    e164
  }

  private fun clean(value: String): String = value.trim().replace(Regex("\\s+"), " ")
}
