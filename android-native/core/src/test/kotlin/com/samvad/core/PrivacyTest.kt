package com.samvad.core

import com.samvad.core.privacy.Appeals
import com.samvad.core.privacy.LinkedDevice
import com.samvad.core.privacy.LinkedDevices
import com.samvad.core.privacy.PrivacyRules
import com.samvad.core.privacy.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {
  @Test
  fun `visibility round-trips through its wire string`() {
    for (v in Visibility.entries) assertEquals(v, Visibility.parse(v.wire))
  }

  @Test
  fun `an unknown or missing wire value falls back to Everyone`() {
    assertEquals(Visibility.EVERYONE, Visibility.parse(null))
    assertEquals(Visibility.EVERYONE, Visibility.parse("friends"))
    assertEquals(Visibility.EVERYONE, Visibility.parse(""))
  }

  @Test
  fun `the owner always sees their own data, whatever the setting`() {
    for (v in Visibility.entries) assertTrue(PrivacyRules.mayShow(v, isPartner = false, isSelf = true))
  }

  @Test
  fun `Everyone shows to anyone, Nobody shows to nobody but the owner`() {
    assertTrue(PrivacyRules.mayShow(Visibility.EVERYONE, isPartner = false, isSelf = false))
    assertTrue(PrivacyRules.mayShow(Visibility.EVERYONE, isPartner = true, isSelf = false))
    assertFalse(PrivacyRules.mayShow(Visibility.NOBODY, isPartner = true, isSelf = false))
    assertFalse(PrivacyRules.mayShow(Visibility.NOBODY, isPartner = false, isSelf = false))
  }

  @Test
  fun `People I chat with shows only to a chat partner`() {
    assertTrue(PrivacyRules.mayShow(Visibility.PARTNERS, isPartner = true, isSelf = false))
    assertFalse(PrivacyRules.mayShow(Visibility.PARTNERS, isPartner = false, isSelf = false))
  }
}

class LinkedDevicesTest {
  private fun device(id: String, mine: Boolean, lastActive: Long) =
    LinkedDevice(id, "android", "1.0", "14", createdAtMs = 0L, lastActiveMs = lastActive, isThisDevice = mine)

  @Test
  fun `this device always comes first`() {
    val sorted = LinkedDevices.sorted(
      listOf(device("a", mine = false, lastActive = 9_000), device("b", mine = true, lastActive = 1_000)),
    )
    assertEquals(listOf("b", "a"), sorted.map { it.id })
  }

  @Test
  fun `otherwise the most recently active device comes first`() {
    val sorted = LinkedDevices.sorted(
      listOf(device("old", mine = false, lastActive = 1_000), device("new", mine = false, lastActive = 5_000)),
    )
    assertEquals(listOf("new", "old"), sorted.map { it.id })
  }

  @Test
  fun `an empty list sorts to an empty list`() {
    assertEquals(emptyList<LinkedDevice>(), LinkedDevices.sorted(emptyList()))
  }
}

class AppealsTest {
  @Test
  fun `accepts 10 to 1000 trimmed characters`() {
    assertFalse(Appeals.messageProblem("a".repeat(Appeals.MIN_MESSAGE)))
    assertFalse(Appeals.messageProblem("a".repeat(Appeals.MAX_MESSAGE)))
    assertFalse(Appeals.messageProblem("  " + "a".repeat(Appeals.MIN_MESSAGE) + "  "))
  }

  @Test
  fun `rejects too short, too long, and whitespace-only`() {
    assertTrue(Appeals.messageProblem("a".repeat(Appeals.MIN_MESSAGE - 1)))
    assertTrue(Appeals.messageProblem("a".repeat(Appeals.MAX_MESSAGE + 1)))
    assertTrue(Appeals.messageProblem("   "))
    assertTrue(Appeals.messageProblem(""))
  }
}
