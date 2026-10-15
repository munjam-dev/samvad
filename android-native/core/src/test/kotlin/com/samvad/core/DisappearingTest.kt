package com.samvad.core

import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.Disappearing
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.SystemText
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearingTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private val t0 = 1_791_900_000_000L

  private fun msg(id: String, at: Long, expires: Long? = null) = Message(id, "asha", "text $id", at, expiresAt = expires)
  private fun bubbleIds(items: List<TimelineItem>) = items.filterIsInstance<TimelineItem.Bubble>().map { it.message.id }

  @Test fun `the four choices are valid and nothing else is`() {
    assertEquals(listOf(0L, 86_400L, 604_800L, 7_776_000L), Disappearing.CHOICES)
    assertTrue(Disappearing.isValid(604_800L))
    assertFalse(Disappearing.isValid(3_600L))
    assertFalse(Disappearing.isValid(-1L))
  }

  @Test fun `labels are plain`() {
    assertEquals("Off", Disappearing.label(0))
    assertEquals("24 hours", Disappearing.label(86_400))
    assertEquals("7 days", Disappearing.label(604_800))
    assertEquals("90 days", Disappearing.label(7_776_000))
  }

  @Test fun `a message without an expiry never expires`() {
    assertFalse(Disappearing.isExpired(null, Long.MAX_VALUE))
  }

  @Test fun `a message is hidden from its expiry time on`() {
    assertFalse(Disappearing.isExpired(t0 + 1000, t0 + 999))
    assertTrue(Disappearing.isExpired(t0 + 1000, t0 + 1000))
    assertTrue(Disappearing.isExpired(t0 + 1000, t0 + 5000))
  }

  @Test fun `the next expiry is the soonest one still in the future`() {
    val list = listOf(msg("a", t0, expires = t0 - 5), msg("b", t0, expires = t0 + 9000), msg("c", t0, expires = t0 + 4000), msg("d", t0))
    assertEquals(t0 + 4000, Disappearing.nextExpiry(list, t0))
    assertNull(Disappearing.nextExpiry(listOf(msg("e", t0)), t0))
  }

  @Test fun `the timeline hides expired messages and keeps the rest`() {
    val list = listOf(msg("old", t0, expires = t0 + 1000), msg("fresh", t0 + 500, expires = t0 + 90_000), msg("plain", t0 + 600))
    val now = t0 + 2000
    val items = Timeline.build(list, me = "me", other = null, nowMs = now, zone = zone, otherName = "Asha")
    assertEquals(setOf("fresh", "plain"), bubbleIds(items).toSet())
  }

  @Test fun `an expired message is visible just before it expires`() {
    val list = listOf(msg("soon", t0, expires = t0 + 1000))
    assertEquals(listOf("soon"), bubbleIds(Timeline.build(list, "me", null, t0 + 999, zone, otherName = "Asha")))
    assertTrue(bubbleIds(Timeline.build(list, "me", null, t0 + 1000, zone, otherName = "Asha")).isEmpty())
  }

  @Test fun `the system line says who turned the timer on or off`() {
    val on = SystemInfo("disappearing", seconds = 604_800)
    val off = SystemInfo("disappearing", seconds = 0)
    assertEquals("You turned on disappearing messages: 7 days", SystemText.describe(on, "me", "me") { "x" })
    assertEquals("Asha turned off disappearing messages", SystemText.describe(off, "asha", "me") { "Asha" })
  }

  @Test fun `the chat list never shows the text of an expired last message`() {
    val last = LastMessage("m", "asha", "secret plan", "text", t0, expiresAt = t0 + 1000)
    val conv = Conversation("asha_me", listOf("asha", "me"), last, t0, isGroup = false)
    fun preview(now: Long) = ChatList.build("me", listOf(conv), emptyMap(), emptyMap(), ChatFilter.ALL, now, zone).single().preview
    assertEquals("secret plan", preview(t0 + 500))
    assertEquals(ChatList.EXPIRED_MESSAGE, preview(t0 + 1000))
  }

  @Test fun `the timer indicator is on only for a positive timer`() {
    assertFalse(Disappearing.isOn(null))
    assertFalse(Disappearing.isOn(0))
    assertTrue(Disappearing.isOn(86_400))
  }
}
