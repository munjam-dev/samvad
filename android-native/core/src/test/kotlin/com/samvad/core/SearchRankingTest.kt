package com.samvad.core

import com.samvad.core.chat.Message
import com.samvad.core.media.Media
import com.samvad.core.search.SearchEntries
import com.samvad.core.search.SearchEntry
import com.samvad.core.search.SearchFilter
import com.samvad.core.search.SearchQuery
import com.samvad.core.search.SearchRanking
import com.samvad.core.search.Snippets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {
  private fun entry(id: String, text: String, kind: String = "text", createdAt: Long = 0L, flags: Int = 0, expiresAt: Long? = null) =
    SearchEntry("c1", id, "me", createdAt, expiresAt, kind, flags, text)

  @Test
  fun `query needs at least two characters and real words`() {
    assertNull(SearchQuery.parse(""))
    assertNull(SearchQuery.parse("a"))
    assertNull(SearchQuery.parse("  "))
    assertNull(SearchQuery.parse("!!"))
    assertNotNull(SearchQuery.parse("hi"))
  }

  @Test
  fun `every query word must match as a word start`() {
    val q = SearchQuery.parse("sun down")!!
    val candidates = listOf(entry("1", "the sun goes down"), entry("2", "sun downtown walk"), entry("3", "the sun rises"))
    val hits = SearchRanking.rank(candidates, q, SearchFilter.ALL, nowMs = 0L)
    assertEquals(setOf("1", "2"), hits.map { it.entry.messageId }.toSet())
  }

  @Test
  fun `prefix matching finds partial words`() {
    val q = SearchQuery.parse("pho")!!
    val hits = SearchRanking.rank(listOf(entry("1", "send me the photo please")), q, SearchFilter.ALL, 0L)
    assertEquals(1, hits.size)
  }

  @Test
  fun `exact word and phrase matches rank above prefix only matches`() {
    val q = SearchQuery.parse("good morning")!!
    val exactPhrase = entry("1", "good morning everyone", createdAt = 0L)
    val wordsOutOfOrder = entry("2", "morning routines are good", createdAt = 0L)
    val prefixOnly = entry("3", "goodies and morningstar", createdAt = 0L)
    val hits = SearchRanking.rank(listOf(prefixOnly, wordsOutOfOrder, exactPhrase), q, SearchFilter.ALL, 0L)
    assertEquals(listOf("1", "2", "3"), hits.map { it.entry.messageId })
  }

  @Test
  fun `newer messages rank above older ones when otherwise equal`() {
    val q = SearchQuery.parse("hello")!!
    val older = entry("1", "hello there", createdAt = 1_000L)
    val newer = entry("2", "hello there", createdAt = 50_000L)
    val hits = SearchRanking.rank(listOf(older, newer), q, SearchFilter.ALL, nowMs = 100_000L)
    assertEquals(listOf("2", "1"), hits.map { it.entry.messageId })
  }

  @Test
  fun `filters keep only the matching kind`() {
    val q = SearchQuery.parse("trip")!!
    val photo = entry("1", "trip photo", flags = SearchEntry.PHOTO)
    val doc = entry("2", "trip itinerary", flags = SearchEntry.DOCUMENT)
    val link = entry("3", "trip link", flags = SearchEntry.LINK)
    val plain = entry("4", "trip plans")
    val all = listOf(photo, doc, link, plain)
    assertEquals(setOf("1"), SearchRanking.rank(all, q, SearchFilter.PHOTOS, 0L).map { it.entry.messageId }.toSet())
    assertEquals(setOf("2"), SearchRanking.rank(all, q, SearchFilter.DOCUMENTS, 0L).map { it.entry.messageId }.toSet())
    assertEquals(setOf("3"), SearchRanking.rank(all, q, SearchFilter.LINKS, 0L).map { it.entry.messageId }.toSet())
    assertEquals(4, SearchRanking.rank(all, q, SearchFilter.ALL, 0L).size)
  }

  @Test
  fun `expired messages (disappearing timer) are left out`() {
    val q = SearchQuery.parse("secret")!!
    val gone = entry("1", "secret plan", expiresAt = 500L)
    val hits = SearchRanking.rank(listOf(gone), q, SearchFilter.ALL, nowMs = 1000L)
    assertTrue(hits.isEmpty())
  }

  @Test
  fun `groupByChat orders chats by their best hit and keeps hit order inside a chat`() {
    val q = SearchQuery.parse("hi")!!
    val a1 = SearchEntry("a", "1", "me", 10, null, "text", 0, "hi there")
    val a2 = SearchEntry("a", "2", "me", 20, null, "text", 0, "hi hi exact")
    val b1 = SearchEntry("b", "3", "me", 5, null, "text", 0, "hi")
    val hits = SearchRanking.rank(listOf(a1, a2, b1), q, SearchFilter.ALL, 0L)
    val grouped = SearchRanking.groupByChat(hits)
    assertEquals(listOf("a", "b"), grouped.map { it.conversationId })
    assertEquals(2, grouped[0].total)
  }

  @Test
  fun `SearchEntries skips voice notes, deleted and pending messages`() {
    assertNull(SearchEntries.from("c", Message("1", "me", "", 0, kind = "voice", media = Media("p", "v.m4a", 1, "audio/mp4", durationMs = 1))))
    assertNull(SearchEntries.from("c", Message("1", "me", "hi", 0, deleted = true)))
    assertNull(SearchEntries.from("c", Message("1", "me", "hi", 0, pending = true)))
    assertNull(SearchEntries.from("c", Message("1", "me", "", 0, kind = "system")))
    assertNotNull(SearchEntries.from("c", Message("1", "me", "hello there", 0)))
  }

  @Test
  fun `SearchEntries flags photos, documents and links and finds no words in a bare photo`() {
    val photo = SearchEntries.from("c", Message("1", "me", "", 0, kind = "image", media = Media("p", "x.jpg", 1, "image/jpeg")))
    assertNull(photo) // no caption, nothing to search
    val captioned = SearchEntries.from("c", Message("1", "me", "sunset", 0, kind = "image", media = Media("p", "x.jpg", 1, "image/jpeg")))!!
    assertTrue((captioned.flags and SearchEntry.PHOTO) != 0)
    val doc = SearchEntries.from("c", Message("1", "me", "", 0, kind = "file", media = Media("p", "Trip notes.pdf", 1, "application/pdf")))!!
    assertTrue((doc.flags and SearchEntry.DOCUMENT) != 0)
    val link = SearchEntries.from("c", Message("1", "me", "see https://example.com/x", 0))!!
    assertTrue((link.flags and SearchEntry.LINK) != 0)
    val plain = SearchEntries.from("c", Message("1", "me", "hello", 0))!!
    assertFalse((plain.flags and SearchEntry.LINK) != 0)
  }

  @Test
  fun `Snippets highlights the match and trims long text around it`() {
    val q = SearchQuery.parse("evening")!!
    val short = Snippets.build("see you this evening", q)
    assertEquals("see you this evening", short.text)
    assertEquals(1, short.highlights.size)
    assertEquals("evening", short.text.substring(short.highlights[0].first, short.highlights[0].last + 1))

    val long = "word ".repeat(40) + "evening " + "word ".repeat(40)
    val snip = Snippets.build(long, q, maxChars = 60)
    assertTrue(snip.text.length <= 64)
    assertTrue(snip.text.contains("evening"))
    assertTrue(snip.text.startsWith("…"))
  }
}
