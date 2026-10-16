package com.samvad.core

import com.samvad.core.status.TapTarget
import com.samvad.core.status.ViewerNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerNavTest {
  @Test fun `the left half goes back and the right half goes on`() {
    assertEquals(TapTarget.PREVIOUS, ViewerNav.tapTarget(0f, 1000f))
    assertEquals(TapTarget.PREVIOUS, ViewerNav.tapTarget(499f, 1000f))
    assertEquals(TapTarget.NEXT, ViewerNav.tapTarget(500f, 1000f))
    assertEquals(TapTarget.NEXT, ViewerNav.tapTarget(999f, 1000f))
  }

  @Test fun `moving inside the list changes the index by one`() {
    assertEquals(2, ViewerNav.move(1, 4, TapTarget.NEXT))
    assertEquals(0, ViewerNav.move(1, 4, TapTarget.PREVIOUS))
  }

  @Test fun `going past either end closes the viewer`() {
    assertNull(ViewerNav.move(4, 4, TapTarget.NEXT))
    assertNull(ViewerNav.move(0, 4, TapTarget.PREVIOUS))
    assertNull(ViewerNav.move(0, 0, TapTarget.NEXT))
  }
}
