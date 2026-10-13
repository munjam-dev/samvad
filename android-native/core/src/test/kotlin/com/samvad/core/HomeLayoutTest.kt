package com.samvad.core

import com.samvad.core.design.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
  @Test fun `Bold Header is the default when nothing is stored`() {
    assertEquals(HomeLayout.BOLD, HomeLayout.DEFAULT)
    assertEquals(HomeLayout.BOLD, HomeLayout.parse(null))
  }

  @Test fun `a stored choice is kept`() {
    assertEquals(HomeLayout.BENTO, HomeLayout.parse("BENTO"))
    assertEquals(HomeLayout.CLEAN, HomeLayout.parse("CLEAN"))
  }

  @Test fun `an unknown stored value falls back to the default`() {
    assertEquals(HomeLayout.BOLD, HomeLayout.parse("GLASS"))
  }
}
