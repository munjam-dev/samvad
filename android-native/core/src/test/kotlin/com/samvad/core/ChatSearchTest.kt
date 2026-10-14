package com.samvad.core

import com.samvad.core.chat.ChatSearch
import com.samvad.core.chat.DeliveryState
import com.samvad.core.chat.Message
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private val t0 = 1_791_900_000_000L

  private fun items(vararg texts: String, deleted: Set<Int> = emptySet()): List<TimelineItem> =
    Timeline.build(
      texts.mapIndexed { i, t -> Message("m$i", "asha", t, t0 + i * 60_000L, deleted = i in deleted) },
      me = "me", other = null, nowMs = t0, zone = zone, otherName = "Asha",
    )

  @Test fun `finds messages ignoring case`() {
    val found = ChatSearch.matches(items("Dinner at 8", "see you", "DINNER later?"), "dinner")
    assertEquals(2, found.size)
  }

  @Test fun `ignores accents both ways`() {
    assertEquals(1, ChatSearch.matches(items("Let's go to the Café"), "cafe").size)
    assertEquals(1, ChatSearch.matches(items("the cafe is shut"), "café").size)
  }

  @Test fun `a query shorter than the minimum matches nothing`() {
    assertTrue(ChatSearch.matches(items("a b c"), "a").isEmpty())
    assertTrue(ChatSearch.matches(items("a b c"), "  ").isEmpty())
  }

  @Test fun `deleted messages never match`() {
    assertTrue(ChatSearch.matches(items("secret plan", deleted = setOf(0)), "secret").isEmpty())
  }

  @Test fun `results keep the list order, newest first`() {
    val list = items("tea one", "coffee", "tea two") // Timeline puts the newest first
    val keys = ChatSearch.matches(list, "tea")
    val order = list.filterIsInstance<TimelineItem.Bubble>().map { it.key }
    assertEquals(order.filter { it in keys }, keys)
  }

  @Test fun `stepping wraps at both ends`() {
    assertEquals(1, ChatSearch.step(0, 3, forward = true))
    assertEquals(0, ChatSearch.step(2, 3, forward = true))
    assertEquals(2, ChatSearch.step(0, 3, forward = false))
    assertEquals(0, ChatSearch.step(0, 0, forward = true))
  }

  @Test fun `devanagari text is searchable`() {
    assertEquals(1, ChatSearch.matches(items("आज रात खाना घर पर?"), "खाना").size)
  }
}
