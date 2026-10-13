package com.samvad.core

import com.samvad.core.safety.ReportReason
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SafetyTest {
  @Test
  fun `report reasons round-trip through their wire values`() {
    ReportReason.entries.forEach { assertEquals(it, ReportReason.fromWire(it.wire)) }
    assertEquals(listOf("spam", "abuse", "harassment", "impersonation", "other"), ReportReason.entries.map { it.wire })
    assertNull(ReportReason.fromWire("SPAM"))
    assertNull(ReportReason.fromWire(""))
  }
}
