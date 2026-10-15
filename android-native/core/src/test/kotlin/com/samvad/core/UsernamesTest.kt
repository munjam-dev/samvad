package com.samvad.core

import com.samvad.core.profile.Usernames
import com.samvad.core.profile.Usernames.Problem
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class UsernamesTest {
  private fun p(n: String) = Usernames.problem(Usernames.normalize(n))

  @Test fun `accepts normal names`() {
    listOf("asha", "asha_k", "a1b", "ravi_kumar_92", "abcdefghijklmnopqrst").forEach { assertNull(it, p(it)) }
  }

  @Test fun `rejects the same names the server rejects`() {
    assertEquals(Problem.TOO_SHORT, p("ab"))
    assertEquals(Problem.TOO_LONG, p("a".repeat(21)))
    assertEquals(Problem.BAD_START, p("1asha"))
    assertEquals(Problem.BAD_START, p("_asha"))
    assertEquals(Problem.BAD_CHARS, p("asha-k"))
    assertEquals(Problem.BAD_CHARS, p("asha.k"))
    assertEquals(Problem.BAD_CHARS, p("asha k"))
    assertEquals(Problem.BAD_CHARS, p("ashá"))
    assertEquals(Problem.BAD_START, p("ásha"))
    assertEquals(Problem.UNDERSCORES, p("asha__k"))
    assertEquals(Problem.UNDERSCORES, p("asha_"))
    assertEquals(Problem.RESERVED, p("admin"))
    assertEquals(Problem.RESERVED, p("Samvad"))
  }

  @Test fun `normalising ignores case, a leading at sign and spaces`() {
    assertEquals("asha_k", Usernames.normalize("  @Asha_K "))
  }
}
