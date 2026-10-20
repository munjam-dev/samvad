package com.samvad.core

import com.samvad.core.media.FailureKind
import com.samvad.core.media.ResumeAction
import com.samvad.core.media.RetryState
import com.samvad.core.media.RetryStatus
import com.samvad.core.media.UploadRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRetryTest {
  private val now = 1_000_000L

  @Test fun `delay doubles from 2 seconds`() {
    assertEquals(2_000L, UploadRetry.delayMs(1))
    assertEquals(4_000L, UploadRetry.delayMs(2))
    assertEquals(8_000L, UploadRetry.delayMs(3))
    assertEquals(16_000L, UploadRetry.delayMs(4))
  }

  @Test fun `delay is capped at five minutes even for huge attempt counts`() {
    assertEquals(UploadRetry.MAX_DELAY_MS, UploadRetry.delayMs(20))
    assertEquals(UploadRetry.MAX_DELAY_MS, UploadRetry.delayMs(10_000))
  }

  @Test fun `jitter adds at most a quarter and never passes the cap`() {
    assertTrue(UploadRetry.delayMs(1, jitter = 0.999999) in 2_400..2_500)
    assertTrue(UploadRetry.delayMs(3, jitter = 0.5) in 8_000..10_000)
    assertEquals(UploadRetry.MAX_DELAY_MS, UploadRetry.delayMs(15, jitter = 0.9))
    // Out-of-range jitter is clamped instead of throwing.
    assertEquals(UploadRetry.delayMs(2), UploadRetry.delayMs(2, jitter = -3.0))
  }

  @Test fun `a transient failure schedules the next try with a growing delay`() {
    val s1 = UploadRetry.onFailure(RetryState(), FailureKind.TRANSIENT, now)
    assertEquals(1, s1.attempts)
    assertEquals(now + 2_000, s1.nextAttemptAtMs)
    assertEquals(RetryStatus.ACTIVE, s1.status)
    val s2 = UploadRetry.onFailure(s1, FailureKind.TRANSIENT, now + 2_000)
    assertEquals(2, s2.attempts)
    assertEquals(now + 2_000 + 4_000, s2.nextAttemptAtMs)
  }

  @Test fun `after the maximum number of attempts it stops and waits for the person`() {
    var state = RetryState()
    repeat(UploadRetry.MAX_AUTO_ATTEMPTS - 1) { state = UploadRetry.onFailure(state, FailureKind.TRANSIENT, now) }
    assertEquals(RetryStatus.ACTIVE, state.status)
    state = UploadRetry.onFailure(state, FailureKind.TRANSIENT, now)
    assertEquals(RetryStatus.FAILED, state.status)
    assertEquals(UploadRetry.MAX_AUTO_ATTEMPTS, state.attempts)
  }

  @Test fun `a permanent failure stops at once`() {
    val state = UploadRetry.onFailure(RetryState(), FailureKind.PERMANENT, now)
    assertEquals(RetryStatus.FAILED, state.status)
    assertEquals(ResumeAction.NeedsPerson, UploadRetry.resume(state, now, now))
  }

  @Test fun `being offline does not use up an attempt`() {
    val before = RetryState(attempts = 3, nextAttemptAtMs = now + 10_000)
    val after = UploadRetry.onFailure(before, FailureKind.OFFLINE, now)
    assertEquals(3, after.attempts)
    assertEquals(now, after.nextAttemptAtMs)
    assertEquals(RetryStatus.ACTIVE, after.status)
    // Even after many offline spells it never gives up by itself.
    var state = RetryState()
    repeat(100) { state = UploadRetry.onFailure(state, FailureKind.OFFLINE, now) }
    assertEquals(RetryStatus.ACTIVE, state.status)
  }

  @Test fun `manual retry starts clean`() {
    val state = UploadRetry.manualRetry(now)
    assertEquals(0, state.attempts)
    assertEquals(RetryStatus.ACTIVE, state.status)
    assertEquals(ResumeAction.RunNow, UploadRetry.resume(state, now - 1000, now))
  }

  @Test fun `resume runs a due upload and waits for one that is not due yet`() {
    assertEquals(ResumeAction.RunNow, UploadRetry.resume(RetryState(2, now - 1), now - 5_000, now))
    assertEquals(ResumeAction.RunNow, UploadRetry.resume(RetryState(0, 0), now - 5_000, now))
    assertEquals(ResumeAction.WaitUntil(now + 4_000), UploadRetry.resume(RetryState(2, now + 4_000), now - 5_000, now))
  }

  @Test fun `a failed upload is never resumed by itself`() {
    assertEquals(ResumeAction.NeedsPerson, UploadRetry.resume(RetryState(8, 0, RetryStatus.FAILED), now, now))
  }

  @Test fun `an upload older than a day is not resumed by itself`() {
    val created = now - UploadRetry.MAX_AUTO_AGE_MS - 1
    assertEquals(ResumeAction.NeedsPerson, UploadRetry.resume(RetryState(), created, now))
    assertEquals(ResumeAction.RunNow, UploadRetry.resume(RetryState(), now - UploadRetry.MAX_AUTO_AGE_MS, now))
  }
}
