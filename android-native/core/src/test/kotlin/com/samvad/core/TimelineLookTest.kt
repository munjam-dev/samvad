package com.samvad.core

import com.samvad.core.chat.Message
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Photo beside the first incoming message of a run, name above it, in group and personal chats. */
class TimelineLookTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private val t0 = 1_791_900_000_000L
  private fun msg(id: String, from: String, at: Long) = Message(id, from, "text $id", at)
  private fun bubbles(items: List<TimelineItem>) = items.filterIsInstance<TimelineItem.Bubble>().associateBy { it.message.id }

  @Test fun `personal chat - first incoming of a run shows photo and name, the rest do not`() {
    val items = Timeline.build(
      listOf(msg("1", "asha", t0), msg("2", "asha", t0 + 30_000), msg("3", "me", t0 + 60_000), msg("4", "asha", t0 + 90_000)),
      me = "me", other = null, nowMs = t0, zone = zone, otherName = "Asha",
    )
    val b = bubbles(items)
    assertTrue(b.getValue("1").showAvatar); assertEquals("Asha", b.getValue("1").senderName)
    assertFalse(b.getValue("2").showAvatar); assertNull(b.getValue("2").senderName)
    assertFalse(b.getValue("3").showAvatar); assertNull(b.getValue("3").senderName)
    assertTrue(b.getValue("4").showAvatar); assertEquals("Asha", b.getValue("4").senderName)
  }

  @Test fun `group - each new sender starts a run with photo and name`() {
    val names = mapOf("asha" to "Asha", "ravi" to "~ Ravi")
    val items = Timeline.build(
      listOf(msg("1", "asha", t0), msg("2", "ravi", t0 + 10_000), msg("3", "ravi", t0 + 20_000)),
      me = "me", other = null, nowMs = t0, zone = zone, groupNames = { names.getValue(it) },
    )
    val b = bubbles(items)
    assertTrue(b.getValue("1").showAvatar); assertEquals("Asha", b.getValue("1").senderName)
    assertTrue(b.getValue("2").showAvatar); assertEquals("~ Ravi", b.getValue("2").senderName)
    assertFalse(b.getValue("3").showAvatar)
  }

  @Test fun `a gap of more than two minutes starts a new run`() {
    val items = Timeline.build(
      listOf(msg("1", "asha", t0), msg("2", "asha", t0 + 3 * 60_000)),
      me = "me", other = null, nowMs = t0, zone = zone, otherName = "Asha",
    )
    assertTrue(bubbles(items).getValue("2").showAvatar)
  }

  @Test fun `my own messages never show a photo or name`() {
    val items = Timeline.build(listOf(msg("1", "me", t0)), me = "me", other = null, nowMs = t0, zone = zone, otherName = "Asha")
    val b = bubbles(items).getValue("1")
    assertFalse(b.showAvatar); assertNull(b.senderName)
  }

  @Test fun `without a known name the photo still shows`() {
    val items = Timeline.build(listOf(msg("1", "asha", t0)), me = "me", other = null, nowMs = t0, zone = zone)
    val b = bubbles(items).getValue("1")
    assertTrue(b.showAvatar); assertNull(b.senderName)
  }
}
