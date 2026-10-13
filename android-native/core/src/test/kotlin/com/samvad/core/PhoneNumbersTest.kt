package com.samvad.core

import com.samvad.core.phone.PhoneNumbers
import com.samvad.core.phone.PhoneValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
  @Test fun `valid Indian mobile becomes E164`() {
    val v = PhoneNumbers.validate("98765 43210") as PhoneValidation.Valid
    assertEquals("+919876543210", v.e164)
    assertTrue(PhoneNumbers.isE164(v.e164))
  }

  @Test fun `accepts an explicit international number regardless of region`() {
    val v = PhoneNumbers.validate("+44 7911 123456", "IN") as PhoneValidation.Valid
    assertEquals("+447911123456", v.e164)
  }

  @Test fun `rejects empty, short, invalid and landline-only numbers`() {
    assertEquals(PhoneValidation.Empty, PhoneNumbers.validate("  "))
    assertEquals(PhoneValidation.TooShort, PhoneNumbers.validate("123"))
    assertEquals(PhoneValidation.Invalid, PhoneNumbers.validate("12345678901234"))
    assertEquals(PhoneValidation.NotMobile, PhoneNumbers.validate("011 2345 6789"))
  }

  @Test fun `normalize returns null for junk`() {
    assertNull(PhoneNumbers.normalize("call me"))
    assertEquals("+919876543210", PhoneNumbers.normalize("+91-98765-43210"))
  }

  @Test fun `India is the first country`() {
    val countries = PhoneNumbers.countries()
    assertEquals("IN", countries.first().regionCode)
    assertEquals(91, countries.first().callingCode)
    assertTrue(countries.size > 200)
  }
}
