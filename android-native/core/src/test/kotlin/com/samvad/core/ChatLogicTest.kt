package com.samvad.core

import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.ConversationIds
import com.samvad.core.chat.DeliveryState
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.core.chat.Profile
import com.samvad.core.chat.TextValidation
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import com.samvad.core.time.TimeLabels
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
    LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
  private val now = at(2026, 10, 13, 14, 0)

  @Test fun `direct conversation ids are order independent`() {
    assertEquals("alice_bob", ConversationIds.direct("bob", "alice"))
    assertEquals("alice_bob", ConversationIds.direct("alice", "bob"))
  }

  @Test fun `text validation mirrors the server rule`() {
    assertEquals(TextValidation.Empty, MessageRules.validate("  \n "))
    assertEquals(TextValidation.Ok("hi"), MessageRules.validate("hi  \n"))
    assertEquals(TextValidation.TooLong(4097), MessageRules.validate("a".repeat(4097)))
    assertEquals(TextValidation.Ok("a".repeat(4096)), MessageRules.validate("a".repeat(4096)))
  }

  @Test fun `delivery state follows the other member's marks`() {
    val m = Message("m", "me", "hi", createdAt = 1000)
    assertEquals(DeliveryState.SENDING, MessageRules.deliveryState(m.copy(pending = true), null))
    assertEquals(DeliveryState.FAILED, MessageRules.deliveryState(m.copy(failed = true), null))
    assertEquals(DeliveryState.SENT, MessageRules.deliveryState(m, MemberState("you")))
    assertEquals(DeliveryState.DELIVERED, MessageRules.deliveryState(m, MemberState("you", lastDeliveredAt = 1000)))
    assertEquals(DeliveryState.READ, MessageRules.deliveryState(m, MemberState("you", lastReadAt = 2000, lastDeliveredAt = 2000)))
  }

  @Test fun `edit window is 15 minutes and sender only`() {
    val m = Message("m", "me", "hi", createdAt = 0)
    assertTrue(MessageRules.canEdit(m, "me", 14 * 60 * 1000L))
    assertFalse(MessageRules.canEdit(m, "me", 15 * 60 * 1000L))
    assertFalse(MessageRules.canEdit(m, "you", 0))
  }

  private val profiles = mapOf(
    "aarav" to Profile("aarav", "Aarav Sharma"),
    "priya" to Profile("priya", "Priya Deshmukh"),
    "rahul" to Profile("rahul", "Rahul Patil"),
  )

  private fun conv(other: String, last: LastMessage?) =
    Conversation(ConversationIds.direct("me", other), listOf("me", other).sorted(), last, last?.createdAt ?: 0)

  @Test fun `chat list pins first, then newest with previews, filters and summary`() {
    val conversations = listOf(
      conv("priya", LastMessage("1", "priya", "That photo looks amazing! ✨", "text", at(2026, 10, 13, 9, 12))),
      conv("rahul", LastMessage("2", "me", "Okay 👍", "text", at(2026, 10, 12, 18, 0))),
      conv("aarav", LastMessage("3", "aarav", "Hey! Are we still on for today?", "text", at(2026, 10, 13, 9, 24))),
      conv("ghost", LastMessage("4", "ghost", "", "text", at(2026, 10, 1, 9, 0))),
    )
    val states = mapOf(
      ConversationIds.direct("me", "aarav") to MemberState("me", unreadCount = 2),
      ConversationIds.direct("me", "rahul") to MemberState("me", pinned = true),
    )
    val rows = ChatList.build("me", conversations, states, profiles, ChatFilter.ALL, now, zone)
    assertEquals(listOf("Rahul Patil", "Aarav Sharma", "Priya Deshmukh", ChatList.DELETED_ACCOUNT), rows.map { it.title })
    assertEquals("You: Okay 👍", rows[0].preview)
    assertEquals("Yesterday", rows[0].timeLabel)
    assertEquals("9:24 AM", rows[1].timeLabel)
    assertEquals(ChatList.DELETED_MESSAGE, rows[3].preview)
    assertEquals("01/10/26", rows[3].timeLabel)
    assertEquals(listOf("Aarav Sharma"), ChatList.build("me", conversations, states, profiles, ChatFilter.UNREAD, now, zone).map { it.title })
    assertEquals(listOf("Rahul Patil"), ChatList.build("me", conversations, states, profiles, ChatFilter.FAVORITES, now, zone).map { it.title })
    val summary = ChatList.unreadSummary(rows)
    assertEquals(2, summary.messages)
    assertEquals(1, summary.chats)
  }

  @Test fun `archived chats are hidden and initials handle edge cases`() {
    val c = conv("aarav", null)
    val rows = ChatList.build("me", listOf(c), mapOf(c.id to MemberState("me", archived = true)), profiles, ChatFilter.ALL, now, zone)
    assertTrue(rows.isEmpty())
    assertEquals("AS", ChatList.initials("Aarav Sharma"))
    assertEquals("P", ChatList.initials("  priya "))
    assertEquals("?", ChatList.initials(""))
    assertEquals("सस", ChatList.initials("संवाद संगीत"))
  }

  @Test fun `timeline inserts day separators and groups consecutive messages`() {
    val msgs = listOf(
      Message("a", "you", "hi", at(2026, 10, 12, 20, 0)),
      Message("b", "you", "there", at(2026, 10, 13, 9, 0)),
      Message("c", "you", "still?", at(2026, 10, 13, 9, 1)),
      Message("d", "me", "yes", at(2026, 10, 13, 9, 5)),
    )
    val items = Timeline.build(msgs, "me", MemberState("you", lastReadAt = at(2026, 10, 13, 9, 6)), now, zone)
    val labels = items.filterIsInstance<TimelineItem.DateSeparator>().map { it.label }
    assertEquals(listOf("Yesterday", "Today"), labels)
    val bubbles = items.filterIsInstance<TimelineItem.Bubble>()
    assertEquals(listOf(false, false, true, false), bubbles.map { it.groupedWithPrevious })
    assertEquals(DeliveryState.READ, bubbles.last().delivery)
    assertEquals(null, bubbles.first().delivery)
  }

  @Test fun `greeting follows the time of day`() {
    assertEquals("Good morning", TimeLabels.greeting(at(2026, 10, 13, 8, 0), zone))
    assertEquals("Good afternoon", TimeLabels.greeting(at(2026, 10, 13, 14, 0), zone))
    assertEquals("Good evening", TimeLabels.greeting(at(2026, 10, 13, 21, 0), zone))
    assertEquals("Yesterday", TimeLabels.dateSeparator(at(2026, 10, 12, 1, 0), now, zone))
    assertEquals("Monday", TimeLabels.dateSeparator(at(2026, 10, 5, 1, 0), at(2026, 10, 7, 1, 0), zone))
    assertEquals("1 October 2026", TimeLabels.dateSeparator(at(2026, 10, 1, 1, 0), now, zone))
  }
}
