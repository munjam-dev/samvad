package com.samvad.core

import com.samvad.core.profile.ProfileRules
import com.samvad.core.profile.ProfileRules.NameProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRulesTest {
  @Test fun `blank name is refused`() {
    assertEquals(NameProblem.BLANK, ProfileRules.nameProblem(""))
    assertEquals(NameProblem.BLANK, ProfileRules.nameProblem("   "))
  }

  @Test fun `name length is checked after trimming`() {
    assertNull(ProfileRules.nameProblem("  " + "a".repeat(64) + "  "))
    assertEquals(NameProblem.TOO_LONG, ProfileRules.nameProblem("a".repeat(65)))
  }

  @Test fun `about is optional and limited to 140`() {
    assertTrue(ProfileRules.aboutValid(""))
    assertTrue(ProfileRules.aboutValid("a".repeat(140)))
    assertFalse(ProfileRules.aboutValid("a".repeat(141)))
  }

  @Test fun `remaining counts down and can go negative`() {
    assertEquals(140, ProfileRules.aboutRemaining(""))
    assertEquals(-1, ProfileRules.aboutRemaining("a".repeat(141)))
  }
}
