package com.samvad.core.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

data class Country(val regionCode: String, val callingCode: Int, val name: String)

sealed interface PhoneValidation {
  data class Valid(val e164: String, val international: String) : PhoneValidation
  data object Empty : PhoneValidation
  data object TooShort : PhoneValidation
  data object Invalid : PhoneValidation
  data object NotMobile : PhoneValidation
}

/** Phone parsing and validation (libphonenumber). India (+91) is the default region. */
object PhoneNumbers {
  const val DEFAULT_REGION = "IN"
  private val util: PhoneNumberUtil = PhoneNumberUtil.getInstance()
  private val E164 = Regex("^\\+[1-9][0-9]{6,14}$")

  fun isE164(value: String): Boolean = E164.matches(value)

  fun countryFor(regionCode: String): Country =
    Country(regionCode, util.getCountryCodeForRegion(regionCode), regionDisplayName(regionCode))

  /** Supported countries sorted by name, with India first. */
  fun countries(): List<Country> {
    val all = util.supportedRegions.map { countryFor(it) }.filter { it.callingCode > 0 }
    val india = all.first { it.regionCode == DEFAULT_REGION }
    return listOf(india) + all.filter { it.regionCode != DEFAULT_REGION }.sortedBy { it.name }
  }

  fun validate(input: String, regionCode: String = DEFAULT_REGION): PhoneValidation {
    val digits = input.filter { it.isDigit() || it == '+' }
    if (digits.isEmpty()) return PhoneValidation.Empty
    if (digits.count { it.isDigit() } < 6) return PhoneValidation.TooShort
    val parsed = try {
      util.parse(digits, regionCode)
    } catch (_: NumberParseException) {
      return PhoneValidation.Invalid
    }
    if (!util.isValidNumber(parsed)) return PhoneValidation.Invalid
    val type = util.getNumberType(parsed)
    if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
      return PhoneValidation.NotMobile
    }
    return PhoneValidation.Valid(
      e164 = util.format(parsed, PhoneNumberFormat.E164),
      international = util.format(parsed, PhoneNumberFormat.INTERNATIONAL),
    )
  }

  /** Normalises a contact-book entry to E.164, or null when it cannot be parsed. */
  fun normalize(raw: String, regionCode: String = DEFAULT_REGION): String? =
    (validate(raw, regionCode) as? PhoneValidation.Valid)?.e164

  /**
   * [normalize], then a plain rule for numbers libphonenumber refuses (for example Firebase test numbers such as
   * +91 12345 67890, or a mobile range it does not know yet): "+" or "00" keeps the digits, 10 digits get +91 when the
   * region is India, a leading trunk "0" is dropped. The result is always a well-formed E.164 string or null.
   */
  fun normalizeLenient(raw: String, regionCode: String = DEFAULT_REGION): String? {
    normalize(raw, regionCode)?.let { return it }
    val trimmed = raw.trim()
    val digits = trimmed.filter { it.isDigit() }
    val candidate = when {
      trimmed.startsWith("+") -> digits
      digits.startsWith("00") -> digits.drop(2)
      else -> {
        val code = util.getCountryCodeForRegion(regionCode).toString()
        val national = digits.removePrefix("0")
        if (national.length < 8) return null
        if (national.startsWith(code) && national.length >= code.length + 8) national else code + national
      }
    }
    return "+$candidate".takeIf { isE164(it) }
  }

  /** As-you-type national formatting for the input field. */
  fun formatAsYouType(input: String, regionCode: String = DEFAULT_REGION): String {
    val formatter = util.getAsYouTypeFormatter(regionCode)
    var out = ""
    input.filter { it.isDigit() }.forEach { out = formatter.inputDigit(it) }
    return out
  }

  private fun regionDisplayName(regionCode: String): String =
    java.util.Locale.Builder().setRegion(regionCode).build().getDisplayCountry(java.util.Locale.ENGLISH)
      .ifBlank { regionCode }
}
