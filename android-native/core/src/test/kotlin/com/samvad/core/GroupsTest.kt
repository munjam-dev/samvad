package com.samvad.core

import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Mentions
import com.samvad.core.chat.Message
import com.samvad.core.chat.Polls
import com.samvad.core.chat.Profile
import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.SystemText
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import com.samvad.core.chat.GroupRules
import com.samvad.core.chat.DeliveryState
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsTest {
  private val names = mapOf("me" to "Me", "asha" to "Asha", "ravi" to "Ravi", "meena" to "Meena")
  private val nameOf = { uid: String -> names[uid] ?: "?" }

  @Test
  fun `polls are trimmed, deduplicated and bounded`() {
    val poll = Polls.create("  Lunch? ", listOf(" Dosa", "", "dosa", "Thali "), multi = false)!!
    assertEquals("Lunch?", poll.question)
    assertEquals(listOf("Dosa", "Thali"), poll.options)
    assertEquals(listOf("0", "1"), poll.ids)
    assertNull(Polls.create("Q", listOf("only"), false))
    assertNull(Polls.create("", listOf("a", "b"), false))
    assertNull(Polls.create("Q", (1..13).map { "o$it" }, false))
    assertNull(Polls.create("Q", listOf("a", "x".repeat(101)), false))
  }

  @Test
  fun `tallies count voters and options, ignore unknown ids, and find the leaders`() {
    val poll = Polls.create("Q", listOf("A", "B", "C"), multi = true)!!
    val r = Polls.tally(poll, mapOf("me" to listOf("0", "1"), "asha" to listOf("1"), "ravi" to listOf("9"), "meena" to emptyList()), "me")
    assertEquals(mapOf("0" to 1, "1" to 2, "2" to 0), r.counts)
    assertEquals(2, r.voters)
    assertEquals(setOf("0", "1"), r.mine)
    assertEquals(setOf("1"), r.leading)
    assertEquals(emptySet<String>(), Polls.tally(poll, emptyMap(), "me").leading)
  }

  @Test
  fun `single choice switches or clears, multiple choice toggles`() {
    val single = Polls.create("Q", listOf("A", "B"), multi = false)!!
    assertEquals(setOf("1"), Polls.toggle(single, setOf("0"), "1"))
    assertEquals(emptySet<String>(), Polls.toggle(single, setOf("1"), "1"))
    val multi = single.copy(multi = true)
    assertEquals(setOf("0", "1"), Polls.toggle(multi, setOf("0"), "1"))
    assertEquals(setOf("1"), Polls.toggle(multi, setOf("0", "1"), "0"))
    assertEquals(setOf("0"), Polls.toggle(multi, setOf("0"), "7"))
  }

  @Test
  fun `mentions are found while typing, inserted, resolved and highlighted`() {
    assertEquals("as", Mentions.activeQuery("hi @as"))
    assertEquals("", Mentions.activeQuery("@"))
    assertNull(Mentions.activeQuery("mail me@home"))
    assertNull(Mentions.activeQuery("hi @ asha"))
    assertEquals("hi @Asha Rao ", Mentions.insert("hi @as", "Asha Rao"))
    val picked = mapOf("asha" to "Asha Rao", "ravi" to "Ravi")
    assertEquals(listOf("asha"), Mentions.resolve("hi @Asha Rao, see this", picked))
    assertEquals(listOf(3..11), Mentions.spans("hi @Asha Rao!", listOf("Asha Rao", "")))
  }

  @Test
  fun `group events read naturally`() {
    assertEquals("Asha added Ravi and Meena", SystemText.describe(SystemInfo("added", listOf("ravi", "meena")), "asha", "me", nameOf))
    assertEquals("You removed Ravi", SystemText.describe(SystemInfo("removed", listOf("ravi")), "me", "me", nameOf))
    assertEquals("You are now an admin", SystemText.describe(SystemInfo("promoted", listOf("me")), "asha", "me", nameOf))
    assertEquals("Ravi is now an admin", SystemText.describe(SystemInfo("promoted", listOf("ravi")), "asha", "me", nameOf))
    assertEquals("Asha created the group \"Trip\"", SystemText.describe(SystemInfo("created", name = "Trip"), "asha", "me", nameOf))
    assertEquals("Asha added Ravi, Meena and You", SystemText.describe(SystemInfo("added", listOf("ravi", "meena", "me")), "asha", "me", nameOf))
  }

  @Test
  fun `group rows use the group name, sender-prefixed previews and device-side unread counts`() {
    val g = Conversation(
      "g1", listOf("me", "asha"), LastMessage("m1", "asha", "Dinner?", "text", 2000), 2000,
      isGroup = true, name = "Family", photoPath = "groupPhotos/g1/x.jpg",
    )
    val profiles = mapOf("asha" to Profile("asha", "Asha"))
    val row = ChatList.build("me", listOf(g), mapOf("g1" to MemberState("me")), profiles, ChatFilter.ALL, 3000, ZoneId.of("UTC"), mapOf("g1" to 4)).single()
    assertEquals("Family", row.title)
    assertEquals("Asha: Dinner?", row.preview)
    assertEquals(4, row.unreadCount)
    assertNull(row.otherUid)
    assertEquals("groupPhotos/g1/x.jpg", row.photoPath)
    val event = g.copy(lastMessage = LastMessage("m2", "asha", "", "system", 2500, SystemInfo("added", listOf("me"))))
    assertEquals("Asha added You", ChatList.build("me", listOf(event), emptyMap(), profiles, ChatFilter.ALL, 3000).single().preview)
    assertEquals(1, ChatList.build("me", listOf(event), emptyMap(), profiles, ChatFilter.GROUPS, 3000).size)
    assertTrue(GroupRules.isAdmin(g.copy(admins = listOf("me")), "me"))
  }

  @Test
  fun `group timelines label senders once per run, show event lines and only sent ticks`() {
    val messages = listOf(
      Message("s", "asha", "", 1_000, kind = "system", system = SystemInfo("added", listOf("me"))),
      Message("1", "asha", "hi", 2_000),
      Message("2", "asha", "there", 3_000),
      Message("3", "ravi", "hello", 4_000),
      Message("4", "me", "hey", 5_000),
    )
    val items = Timeline.build(messages, "me", MemberState("x", lastReadAt = 9_000), 10_000, ZoneId.of("UTC"), groupNames = nameOf)
      .filter { it !is TimelineItem.DateSeparator }
    assertEquals("Asha added You", (items[0] as TimelineItem.SystemLine).text)
    val bubbles = items.drop(1).map { it as TimelineItem.Bubble }
    assertEquals(listOf("Asha", null, "Ravi", null), bubbles.map { it.senderName })
    assertEquals(DeliveryState.SENT, bubbles.last().delivery)
  }
}
