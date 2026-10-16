package com.samvad.core

import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.DraftChange
import com.samvad.core.chat.Drafts
import com.samvad.core.chat.LastMessage
import com.samvad.core.chat.MessageRules
import com.samvad.core.chat.Profile
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftsTest {
  @Test fun `max length matches the message limit`() {
    assertEquals(4096, Drafts.MAX_LENGTH)
    assertEquals(MessageRules.MAX_LENGTH, Drafts.MAX_LENGTH)
  }

  @Test fun `blank or missing text is not a draft`() {
    assertNull(Drafts.normalize(null))
    assertNull(Drafts.normalize(""))
    assertNull(Drafts.normalize("   \n\t "))
  }

  @Test fun `surrounding whitespace is trimmed and inner line breaks stay`() {
    assertEquals("hello there", Drafts.normalize("  hello there \n"))
    assertEquals("line one\nline two", Drafts.normalize("\nline one\nline two\n\n"))
  }

  @Test fun `long text is cut to the limit`() {
    val saved = Drafts.normalize("a".repeat(5000))!!
    assertEquals(4096, saved.length)
    assertEquals("a".repeat(4096), Drafts.normalize("a".repeat(4096)))
    // Cutting must not leave trailing spaces behind.
    val cut = Drafts.normalize("a".repeat(4095) + "  b")!!
    assertEquals("a".repeat(4095), cut)
  }

  @Test fun `leaving with text saves it, leaving with a blank composer clears it`() {
    assertEquals(DraftChange.Save("see you at 6"), Drafts.onLeave("see you at 6 ", editing = false))
    assertEquals(DraftChange.Clear, Drafts.onLeave("   ", editing = false))
    assertEquals(DraftChange.Clear, Drafts.onLeave("", editing = false))
  }

  @Test fun `editing a sent message never touches the stored draft`() {
    assertEquals(DraftChange.Keep, Drafts.onLeave("edited text", editing = true))
    assertEquals(DraftChange.Keep, Drafts.onLeave("", editing = true))
  }

  @Test fun `the row shows the draft on one line`() {
    assertEquals("one two three", Drafts.rowText("one\ntwo   three\n"))
    assertNull(Drafts.rowText(null))
    assertNull(Drafts.rowText("  "))
  }

  @Test fun `a chat with a draft shows it in place of the last message, others keep theirs`() {
    val zone = ZoneId.of("UTC")
    val profiles = mapOf("a" to Profile("a", "Asha"), "b" to Profile("b", "Bala"))
    val withDraft = Conversation("a_me", listOf("a", "me"), LastMessage("1", "a", "Lunch?", "text", 1000), 1000)
    val without = Conversation("b_me", listOf("b", "me"), LastMessage("2", "b", "Ok", "text", 2000), 2000)
    val rows = ChatList.build(
      "me", listOf(withDraft, without), emptyMap(), profiles, ChatFilter.ALL, 3000, zone,
      drafts = mapOf("a_me" to "  I am  late\n", "b_me" to "   "),
    ).associateBy { it.conversationId }
    assertEquals("I am late", rows.getValue("a_me").draft)
    assertEquals("Lunch?", rows.getValue("a_me").preview)
    assertNull(rows.getValue("b_me").draft)
    assertEquals("Ok", rows.getValue("b_me").preview)
  }

  @Test fun `a draft does not change the row order`() {
    val zone = ZoneId.of("UTC")
    val profiles = mapOf("a" to Profile("a", "Asha"), "b" to Profile("b", "Bala"))
    val older = Conversation("a_me", listOf("a", "me"), LastMessage("1", "a", "x", "text", 1000), 1000)
    val newer = Conversation("b_me", listOf("b", "me"), LastMessage("2", "b", "y", "text", 2000), 2000)
    val rows = ChatList.build("me", listOf(older, newer), emptyMap(), profiles, ChatFilter.ALL, 3000, zone, drafts = mapOf("a_me" to "hi"))
    assertEquals(listOf("b_me", "a_me"), rows.map { it.conversationId })
  }
}
