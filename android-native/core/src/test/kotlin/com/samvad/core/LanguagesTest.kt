package com.samvad.core

import com.samvad.core.profile.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
  @Test fun `resolves a regional tag to the base language`() {
    assertEquals("hi", Languages.resolve("hi-IN").tag)
    assertEquals("hi", Languages.resolve("HI").tag)
    assertEquals("en", Languages.resolve("en-GB").tag)
  }

  @Test fun `falls back to English for anything not offered`() {
    assertEquals("en", Languages.resolve("ta").tag)
    assertEquals("en", Languages.resolve(null).tag)
    assertEquals("en", Languages.resolve("").tag)
  }

  @Test fun `English is always offered, first, and no language is listed twice`() {
    assertEquals("en", Languages.SUPPORTED.first().tag)
    assertTrue(Languages.SUPPORTED.map { it.tag }.toSet().size == Languages.SUPPORTED.size)
  }
}
