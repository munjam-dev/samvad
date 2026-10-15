package com.samvad.core

import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRulesTest {
  private val now = 1_791_900_000_000L
  private val hour = 3_600_000L

  private fun item(id: String, author: String, ageH: Long, expiresInH: Long? = 24 - ageH) =
    StatusItem(id, author, "text", "hi", "forest", null, createdAt = now - ageH * hour, expiresAt = expiresInH?.let { now + it * hour })

  @Test fun `text must be non-empty and at most 700 characters`() {
    assertTrue(StatusRules.validText("Good morning"))
    assertFalse(StatusRules.validText("   "))
    assertFalse(StatusRules.validText(""))
    assertTrue(StatusRules.validText("x".repeat(700)))
    assertFalse(StatusRules.validText("x".repeat(701)))
  }

  @Test fun `an unknown style falls back to the default`() {
    assertEquals("sage", StatusRules.style("sage"))
    assertEquals(StatusRules.DEFAULT_STYLE, StatusRules.style("neon"))
    assertEquals(StatusRules.DEFAULT_STYLE, StatusRules.style(null))
  }

  @Test fun `an unknown audience falls back to everyone`() {
    assertEquals(StatusAudience.ONLY, StatusAudience.parse("only"))
    assertEquals(StatusAudience.EXCEPT, StatusAudience.parse("except"))
    assertEquals(StatusAudience.ALL, StatusAudience.parse("whatever"))
    assertEquals(StatusAudience.ALL, StatusAudience.parse(null))
  }

  @Test fun `a status is live until its expiry`() {
    assertTrue(StatusRules.isLive(item("a", "x", 1), now))
    assertFalse(StatusRules.isLive(item("b", "x", 25, expiresInH = -1), now))
    assertFalse(StatusRules.isLive(item("c", "x", 24, expiresInH = 0), now))
  }

  @Test fun `before the server stamps an expiry a new post counts as live for 24 hours`() {
    assertTrue(StatusRules.isLive(item("a", "x", 0, expiresInH = null), now))
    assertTrue(StatusRules.isLive(item("b", "x", 23, expiresInH = null), now))
    assertFalse(StatusRules.isLive(item("c", "x", 25, expiresInH = null), now))
  }

  @Test fun `the feed hides my own posts, muted people and expired posts`() {
    val items = listOf(item("1", "me", 1), item("2", "asha", 1), item("3", "ravi", 1), item("4", "meena", 30, expiresInH = -6))
    val groups = StatusRules.feed(items, emptySet(), me = "me", nowMs = now, muted = setOf("ravi"))
    assertEquals(listOf("asha"), groups.map { it.authorId })
  }

  @Test fun `people with something unseen come first, then the rest by newest`() {
    val items = listOf(
      item("a1", "asha", 5), item("r1", "ravi", 2), item("m1", "meena", 1), item("m2", "meena", 8),
    )
    val groups = StatusRules.feed(items, seen = setOf("r1"), me = "me", nowMs = now)
    // ravi has seen-all and drops to the end; meena's newest is 1 h old, asha's is 5 h old
    assertEquals(listOf("meena", "asha", "ravi"), groups.map { it.authorId })
    assertTrue(groups.last().allSeen)
  }

  @Test fun `items within a person run oldest to newest`() {
    val groups = StatusRules.feed(listOf(item("new", "asha", 1), item("old", "asha", 9)), emptySet(), "me", now)
    assertEquals(listOf("old", "new"), groups.single().items.map { it.id })
  }

  @Test fun `playback starts at the first unseen item, or the beginning when all are seen`() {
    val g = StatusRules.feed(listOf(item("a", "asha", 9), item("b", "asha", 5), item("c", "asha", 1)), emptySet(), "me", now).single()
    assertEquals(0, StatusRules.startIndex(g, emptySet()))
    assertEquals(1, StatusRules.startIndex(g, setOf("a")))
    assertEquals(0, StatusRules.startIndex(g, setOf("a", "b", "c")))
  }

  @Test fun `my own live statuses come back oldest first`() {
    val mine = StatusRules.mine(listOf(item("2", "me", 1), item("1", "me", 5), item("x", "asha", 1)), "me", now)
    assertEquals(listOf("1", "2"), mine.map { it.id })
  }

  @Test fun `the next expiry is the soonest one still ahead`() {
    val items = listOf(item("a", "x", 1, expiresInH = 3), item("b", "x", 1, expiresInH = 1), item("c", "x", 30, expiresInH = -2))
    assertEquals(now + hour, StatusRules.nextExpiry(items, now))
    assertNull(StatusRules.nextExpiry(emptyList(), now))
  }

  @Test fun `time ago reads plainly`() {
    assertEquals("Just now", StatusRules.ago(now - 20_000, now))
    assertEquals("12 min ago", StatusRules.ago(now - 12 * 60_000, now))
    assertEquals("3 h ago", StatusRules.ago(now - 3 * hour - 5 * 60_000, now))
    assertEquals("Just now", StatusRules.ago(now + 5_000, now))
  }
}
