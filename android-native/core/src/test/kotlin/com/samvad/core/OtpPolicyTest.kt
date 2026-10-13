package com.samvad.core

import com.samvad.core.auth.OtpPolicy
import com.samvad.core.auth.OtpPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpPolicyTest {
  private var now = 1_000_000L
  private val policy = OtpPolicy { now }
  private val n = "+919876543210"

  @Test fun `resend waits 60 seconds`() {
    policy.onCodeSent(n, isResend = false)
    assertEquals(Decision.WaitSeconds(60), policy.canResend())
    now += 59_001
    assertEquals(Decision.WaitSeconds(1), policy.canResend())
    now += 999
    assertEquals(Decision.Allowed, policy.canResend())
  }

  @Test fun `at most 3 resends per session`() {
    policy.onCodeSent(n, isResend = false)
    repeat(3) {
      now += 61_000
      assertEquals(Decision.Allowed, policy.canResend())
      policy.onCodeSent(n, isResend = true)
    }
    now += 61_000
    assertEquals(Decision.SessionResendLimit, policy.canResend())
    assertEquals(0, policy.resendsLeft())
  }

  @Test fun `at most 5 requests per number per hour, then the window slides`() {
    repeat(5) {
      assertEquals(Decision.Allowed, policy.canStart(n))
      policy.onCodeSent(n, isResend = false)
      now += 61_000
    }
    assertEquals(Decision.HourlyLimit, policy.canStart(n))
    assertEquals(Decision.Allowed, policy.canStart("+919876543211"))
    now += 3_600_000
    assertEquals(Decision.Allowed, policy.canStart(n))
  }

  @Test fun `five wrong codes force a new code and a resend resets attempts`() {
    policy.onCodeSent(n, isResend = false)
    repeat(4) { assertFalse(policy.onWrongCode()) }
    assertEquals(1, policy.attemptsLeft())
    assertTrue(policy.onWrongCode())
    now += 61_000
    policy.onCodeSent(n, isResend = true)
    assertEquals(5, policy.attemptsLeft())
  }
}
