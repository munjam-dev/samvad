package com.samvad.core

import com.samvad.core.security.PinProblem
import com.samvad.core.security.TwoStepPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStepTest {
  @Test
  fun `pins must be six digits and not trivial, like the server`() {
    listOf("482915", "120000", "135790", "111112").forEach { assertNull(it, TwoStepPin.problem(it)) }
    listOf("12345", "1234567", "12a456", "", "١٢٣٤٥٦").forEach { assertEquals(it, PinProblem.FORMAT, TwoStepPin.problem(it)) }
    listOf("000000", "999999", "123456", "345678", "987654", "654321").forEach {
      assertEquals(it, PinProblem.TOO_SIMPLE, TwoStepPin.problem(it))
    }
  }

  @Test
  fun `only plain phone sessions without two-step, or matching PIN sessions, are verified`() {
    assertTrue(TwoStepPin.isVerifiedSession("phone", ts = false, tsv = null, pv = null))
    assertFalse(TwoStepPin.isVerifiedSession("phone", ts = true, tsv = "a", pv = null))
    assertTrue(TwoStepPin.isVerifiedSession("custom", ts = true, tsv = "a", pv = "a"))
    assertFalse(TwoStepPin.isVerifiedSession("custom", ts = true, tsv = "b", pv = "a"))
    assertTrue(TwoStepPin.isVerifiedSession("custom", ts = false, tsv = null, pv = "a"))
    assertFalse(TwoStepPin.isVerifiedSession("custom", ts = false, tsv = null, pv = null))
    assertFalse(TwoStepPin.isVerifiedSession("anonymous", ts = false, tsv = null, pv = null))
    assertFalse(TwoStepPin.isVerifiedSession(null, ts = false, tsv = null, pv = null))
  }
}
