package com.samvad.core

import com.samvad.core.chat.Conversation
import com.samvad.core.chat.EventCheck
import com.samvad.core.chat.EventRules
import com.samvad.core.chat.GroupEvent
import com.samvad.core.chat.GroupRules
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.core.chat.Rsvp
import com.samvad.core.chat.RsvpCounts
import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.SystemText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAdvancedTest {
  private fun group(announce: Boolean = false, membersCreate: Boolean = false) = Conversation(
    id = "g", members = listOf("ravi", "sita", "tara"), lastMessage = null, lastMessageAt = 0L, isGroup = true,
    admins = listOf("ravi"), onlyAdminsSend = announce, eventsByMembers = membersCreate,
  )

  private fun event(id: String = "e", startsAt: Long, cancelled: Boolean = false, by: String = "sita") =
    GroupEvent(id, "Picnic", "", "", startsAt, by, cancelled, RsvpCounts())

  @Test
  fun `everyone sends normally and only admins send in announcement mode`() {
    assertTrue(GroupRules.canSend(group(), "sita"))
    assertTrue(GroupRules.canSend(group(announce = true), "ravi"))
    assertFalse(GroupRules.canSend(group(announce = true), "sita"))
    assertFalse(GroupRules.canSend(group(announce = true), "outsider"))
    assertTrue(GroupRules.canSend(null, "anyone")) // direct chats and a group that has not loaded yet
  }

  @Test
  fun `members create events only when allowed and never in announcement mode`() {
    assertTrue(GroupRules.canCreateEvent(group(), "ravi"))
    assertFalse(GroupRules.canCreateEvent(group(), "sita"))
    assertTrue(GroupRules.canCreateEvent(group(membersCreate = true), "sita"))
    assertFalse(GroupRules.canCreateEvent(group(membersCreate = true, announce = true), "sita"))
    assertTrue(GroupRules.canCreateEvent(group(membersCreate = true, announce = true), "ravi"))
    assertFalse(GroupRules.canCreateEvent(group(membersCreate = true), "outsider"))
  }

  @Test
  fun `event form is checked like the server`() {
    val now = 1_000_000L
    val day = 86_400_000L
    fun check(title: String = "Picnic", desc: String = "", place: String = "", at: Long? = now + day) = EventRules.check(title, desc, place, at, now)
    assertEquals(EventCheck.Ok("Picnic", "Bring food", "Park", now + day), check(title = "  Picnic ", desc = " Bring food ", place = " Park "))
    assertEquals(EventCheck.TitleMissing, check(title = "   "))
    assertEquals(EventCheck.TitleTooLong, check(title = "x".repeat(81)))
    assertTrue(check(title = "x".repeat(80)) is EventCheck.Ok)
    assertEquals(EventCheck.DescriptionTooLong, check(desc = "x".repeat(301)))
    assertTrue(check(desc = "x".repeat(300)) is EventCheck.Ok)
    assertEquals(EventCheck.PlaceTooLong, check(place = "x".repeat(81)))
    assertEquals(EventCheck.StartMissing, check(at = null))
    assertEquals(EventCheck.StartInPast, check(at = now))
    assertEquals(EventCheck.StartInPast, check(at = now - 5))
    assertEquals(EventCheck.StartTooFar, check(at = now + 3 * 365 * day))
    assertTrue(check(at = now + 1) is EventCheck.Ok)
  }

  @Test
  fun `answers close when an event starts or is cancelled`() {
    val now = 5_000L
    assertTrue(EventRules.canAnswer(event(startsAt = now + 1), now))
    assertFalse(EventRules.canAnswer(event(startsAt = now), now))
    assertFalse(EventRules.canAnswer(event(startsAt = now + 100, cancelled = true), now))
  }

  @Test
  fun `the creator or an admin may cancel, nobody else, and not twice`() {
    val g = group()
    assertTrue(EventRules.canCancel(event(startsAt = 9), g, "sita")) // creator
    assertTrue(EventRules.canCancel(event(startsAt = 9), g, "ravi")) // admin
    assertFalse(EventRules.canCancel(event(startsAt = 9), g, "tara"))
    assertFalse(EventRules.canCancel(event(startsAt = 9, cancelled = true), g, "ravi"))
  }

  @Test
  fun `editing is for the creator or an admin until the event starts`() {
    val g = group()
    assertTrue(EventRules.canEdit(event(startsAt = 9), g, "sita", 5))
    assertTrue(EventRules.canEdit(event(startsAt = 9), g, "ravi", 5))
    assertFalse(EventRules.canEdit(event(startsAt = 9), g, "tara", 5))
    assertFalse(EventRules.canEdit(event(startsAt = 9), g, "sita", 9))
    assertFalse(EventRules.canEdit(event(startsAt = 9, cancelled = true), g, "sita", 5))
  }

  @Test
  fun `events split into upcoming soonest first and earlier newest first`() {
    val now = 100L
    val list = listOf(
      event("late", 500), event("soon", 200), event("past", 50), event("older", 10),
      event("gone", 300, cancelled = true),
    )
    val (upcoming, earlier) = EventRules.split(list, now)
    assertEquals(listOf("soon", "late"), upcoming.map { it.id })
    assertEquals(listOf("gone", "past", "older"), earlier.map { it.id })
    assertEquals(2, EventRules.upcomingCount(list, now))
  }

  @Test
  fun `counts follow a changed answer and never go below zero`() {
    val start = RsvpCounts(going = 2, maybe = 1, no = 0)
    assertEquals(RsvpCounts(3, 1, 0), EventRules.adjust(start, null, Rsvp.GOING))
    assertEquals(RsvpCounts(1, 2, 0), EventRules.adjust(start, Rsvp.GOING, Rsvp.MAYBE))
    assertEquals(RsvpCounts(2, 0, 0), EventRules.adjust(start, Rsvp.MAYBE, null))
    assertEquals(start, EventRules.adjust(start, Rsvp.GOING, Rsvp.GOING))
    assertEquals(RsvpCounts(0, 0, 1), EventRules.adjust(RsvpCounts(), Rsvp.GOING, Rsvp.NO))
    assertEquals(4, RsvpCounts(2, 1, 1).total)
  }

  @Test
  fun `answers parse from and to the wire`() {
    assertEquals(Rsvp.GOING, Rsvp.fromWire("going"))
    assertEquals(Rsvp.NO, Rsvp.fromWire("no"))
    assertNull(Rsvp.fromWire("definitely"))
    assertNull(Rsvp.fromWire(null))
  }

  @Test
  fun `event cards cannot be forwarded and new group lines read naturally`() {
    val card = Message(id = "m", senderId = "ravi", text = "", createdAt = 1, kind = "event", eventId = "e", eventTitle = "Picnic")
    assertFalse(MessageRules.canForward(card))
    assertTrue(MessageRules.canForward(card.copy(kind = "text", text = "hi")))
    val name = { uid: String -> uid.replaceFirstChar { it.uppercase() } }
    assertEquals("Ravi made this group admin-only: only admins can send messages", SystemText.describe(SystemInfo("announce_on"), "ravi", "me", name))
    assertEquals("You allowed everyone to send messages", SystemText.describe(SystemInfo("announce_off"), "me", "me", name))
    assertEquals("Ravi cancelled the event \"Picnic\"", SystemText.describe(SystemInfo("event_cancelled", name = "Picnic"), "ravi", "me", name))
  }
}
