package com.samvad.core

import com.samvad.core.chat.ConversationIds
import com.samvad.core.security.AppLockPolicy
import com.samvad.core.security.LockTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
  private val t0 = 1_000_000L

  @Test
  fun `never locks when disabled or when the app has not been in the background`() {
    assertFalse(AppLockPolicy.shouldLock(false, LockTimeout.IMMEDIATELY, t0, t0 + 10_000_000))
    assertFalse(AppLockPolicy.shouldLock(true, LockTimeout.IMMEDIATELY, null, t0))
  }

  @Test
  fun `locks once the timeout has passed`() {
    assertTrue(AppLockPolicy.shouldLock(true, LockTimeout.IMMEDIATELY, t0, t0))
    assertFalse(AppLockPolicy.shouldLock(true, LockTimeout.ONE_MINUTE, t0, t0 + 59_999))
    assertTrue(AppLockPolicy.shouldLock(true, LockTimeout.ONE_MINUTE, t0, t0 + 60_000))
    assertFalse(AppLockPolicy.shouldLock(true, LockTimeout.THIRTY_MINUTES, t0, t0 + 29 * 60_000))
    assertTrue(AppLockPolicy.shouldLock(true, LockTimeout.THIRTY_MINUTES, t0, t0 + 30 * 60_000))
  }

  @Test
  fun `a clock that went backwards locks`() {
    assertTrue(AppLockPolicy.shouldLock(true, LockTimeout.THIRTY_MINUTES, t0, t0 - 1))
  }

  @Test
  fun `the app's own pickers get a short grace, but never less than the chosen timeout`() {
    assertFalse(AppLockPolicy.shouldLock(true, LockTimeout.IMMEDIATELY, t0, t0 + 30_000, leftForExternal = true))
    assertTrue(AppLockPolicy.shouldLock(true, LockTimeout.IMMEDIATELY, t0, t0 + AppLockPolicy.EXTERNAL_GRACE_MS, leftForExternal = true))
    assertFalse(AppLockPolicy.shouldLock(true, LockTimeout.THIRTY_MINUTES, t0, t0 + 10 * 60_000, leftForExternal = true))
  }

  @Test
  fun `chat links must name a well-formed direct chat containing the other person`() {
    assertTrue(ConversationIds.isDirectWith("alice_bob", "bob"))
    assertTrue(ConversationIds.isDirectWith("alice_bob", "alice"))
    assertFalse(ConversationIds.isDirectWith("alice_bob", "carol"))
    assertFalse(ConversationIds.isDirectWith("bob_alice", "bob"))
    assertFalse(ConversationIds.isDirectWith("alice_bob_x", "bob"))
    assertFalse(ConversationIds.isDirectWith("../x_bob", "bob"))
    assertFalse(ConversationIds.isDirectWith("", ""))
  }
}
