package com.samvad.core

import com.samvad.core.call.CallDirection
import com.samvad.core.call.CallHistory
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallRecord
import com.samvad.core.call.CallRules
import com.samvad.core.call.CallStatus
import com.samvad.core.call.LinkQuality
import com.samvad.core.call.MeetingPhase
import com.samvad.core.call.MeetingWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
  private fun call(
    id: String,
    caller: String = "me",
    other: String = "bina",
    status: CallStatus = CallStatus.ENDED,
    at: Long = 1_000_000,
    answered: Long? = null,
    ended: Long? = null,
    kind: CallKind = CallKind.AUDIO,
  ) = CallRecord(id, caller, listOf("me", other).sorted(), kind, status, at, answered, ended, at + CallRules.RING_MS)

  @Test fun `status and kind parse with safe defaults`() {
    assertEquals(CallStatus.RINGING, CallStatus.parse("ringing"))
    assertEquals(CallStatus.ENDED, CallStatus.parse("something-new"))
    assertEquals(CallKind.VIDEO, CallKind.parse("video"))
    assertEquals(CallKind.AUDIO, CallKind.parse(null))
    assertTrue(CallStatus.ACTIVE.isLive)
    assertFalse(CallStatus.MISSED.isLive)
  }

  @Test fun `direction is read from the viewer's side`() {
    assertEquals(CallDirection.OUTGOING, CallRules.direction(call("1", caller = "me"), "me"))
    assertEquals(CallDirection.INCOMING, CallRules.direction(call("2", caller = "bina"), "me"))
    assertEquals(CallDirection.MISSED, CallRules.direction(call("3", caller = "bina", status = CallStatus.MISSED), "me"))
    // The caller's own unanswered call is outgoing for them, not missed.
    assertEquals(CallDirection.OUTGOING, CallRules.direction(call("4", caller = "me", status = CallStatus.MISSED), "me"))
  }

  @Test fun `an expired ring reads as missed before the server sweep`() {
    val c = call("1", status = CallStatus.RINGING, at = 0)
    assertEquals(CallStatus.RINGING, CallRules.effectiveStatus(c, 44_999))
    assertEquals(CallStatus.MISSED, CallRules.effectiveStatus(c, 45_000))
    assertEquals(CallStatus.ACTIVE, CallRules.effectiveStatus(call("2", status = CallStatus.ACTIVE, at = 0), 99_999))
  }

  @Test fun `duration is talk time and zero when never answered`() {
    assertEquals(0, CallRules.durationSeconds(call("1")))
    assertEquals(65, CallRules.durationSeconds(call("2", answered = 10_000, ended = 75_500)))
    assertEquals(0, CallRules.durationSeconds(call("3", answered = 10_000, ended = 5_000)))
  }

  @Test fun `durations format as m ss or h mm ss`() {
    assertEquals("0:07", CallRules.formatDuration(7))
    assertEquals("12:05", CallRules.formatDuration(725))
    assertEquals("1:02:09", CallRules.formatDuration(3729))
    assertEquals("0:00", CallRules.formatDuration(-4))
  }

  @Test fun `history groups consecutive calls with the same person and direction, newest first`() {
    val rows = CallHistory.group(
      listOf(
        call("a", caller = "bina", status = CallStatus.MISSED, at = 3_000),
        call("b", caller = "bina", status = CallStatus.MISSED, at = 2_000),
        call("c", caller = "me", at = 1_000),
        call("d", caller = "bina", status = CallStatus.MISSED, at = 500),
      ),
      "me",
    )
    assertEquals(listOf(2, 1, 1), rows.map { it.count })
    assertEquals("a", rows[0].latest.id)
    assertEquals(CallDirection.OUTGOING, rows[1].direction)
  }

  @Test fun `history keeps video and audio calls on separate rows`() {
    val rows = CallHistory.group(
      listOf(call("a", at = 2_000, kind = CallKind.VIDEO), call("b", at = 1_000, kind = CallKind.AUDIO)),
      "me",
    )
    assertEquals(2, rows.size)
  }

  @Test fun `meeting window opens 30 minutes before and closes 4 hours after the start`() {
    val start = 10_000_000_000L
    assertFalse(MeetingWindow.isOpen(start, start - MeetingWindow.OPEN_BEFORE_MS - 1))
    assertTrue(MeetingWindow.isOpen(start, start - MeetingWindow.OPEN_BEFORE_MS))
    assertTrue(MeetingWindow.isOpen(start, start + MeetingWindow.OPEN_AFTER_MS))
    assertFalse(MeetingWindow.isOpen(start, start + MeetingWindow.OPEN_AFTER_MS + 1))
  }

  @Test fun `meeting phase follows the clock and the host`() {
    val start = 10_000_000_000L
    assertEquals(MeetingPhase.UPCOMING, MeetingWindow.phase(start, start - 3_600_000, false))
    assertEquals(MeetingPhase.OPEN, MeetingWindow.phase(start, start - 60_000, false))
    assertEquals(MeetingPhase.OPEN, MeetingWindow.phase(start, start + 3_600_000, false))
    assertEquals(MeetingPhase.ENDED, MeetingWindow.phase(start, start + 3_600_000, true))
    assertEquals(MeetingPhase.ENDED, MeetingWindow.phase(start, start + MeetingWindow.OPEN_AFTER_MS + 1, false))
  }

  @Test fun `minutes until a meeting opens round up`() {
    val start = 100_000_000L
    assertEquals(0, MeetingWindow.minutesUntilOpen(start, start))
    assertEquals(1, MeetingWindow.minutesUntilOpen(start, start - MeetingWindow.OPEN_BEFORE_MS - 1))
    assertEquals(10, MeetingWindow.minutesUntilOpen(start, start - MeetingWindow.OPEN_BEFORE_MS - 600_000))
  }

  @Test fun `link quality maps SDK names and defaults to good`() {
    assertEquals(LinkQuality.POOR, LinkQuality.fromName("poor"))
    assertEquals(LinkQuality.LOST, LinkQuality.fromName("LOST"))
    assertEquals(LinkQuality.GOOD, LinkQuality.fromName("EXCELLENT"))
    assertEquals(LinkQuality.GOOD, LinkQuality.fromName(null))
  }
}
