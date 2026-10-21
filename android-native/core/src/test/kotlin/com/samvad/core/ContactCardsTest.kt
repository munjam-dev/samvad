package com.samvad.core

import com.samvad.core.chat.ChatExport
import com.samvad.core.chat.ContactCard
import com.samvad.core.chat.ContactCardResult
import com.samvad.core.chat.ContactCards
import com.samvad.core.chat.ExportLabels
import com.samvad.core.chat.Message
import com.samvad.core.chat.PinnedMessages
import com.samvad.core.chat.PinnedPreview
import com.samvad.core.media.Media
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardsTest {
  private fun ok(r: ContactCardResult) = r as ContactCardResult.Ok

  @Test
  fun `numbers are normalised to E164 using the default region`() {
    val r = ok(ContactCards.build("Asha Rao", listOf("98765 43210", "+1 (415) 555-0132"), null))
    assertEquals(listOf("+919876543210", "+14155550132"), r.card.phones)
    assertEquals(0, r.dropped)
  }

  @Test
  fun `landlines are kept because a contact card is not a sign-up`() {
    assertEquals("+912223456789", ContactCards.normalizePhone("022 2345 6789"))
  }

  @Test
  fun `invalid and repeated numbers are dropped and counted`() {
    val r = ok(ContactCards.build("Asha", listOf("12", "98765 43210", "+91 98765-43210", "abc"), null))
    assertEquals(listOf("+919876543210"), r.card.phones)
    assertEquals(3, r.dropped)
  }

  @Test
  fun `only the first three numbers are shared`() {
    val r = ok(ContactCards.build("Asha", listOf("9876543210", "9876543211", "9876543212", "9876543213"), null))
    assertEquals(3, r.card.phones.size)
    assertEquals(1, r.dropped)
  }

  @Test
  fun `a card needs a name and a usable number`() {
    assertEquals(ContactCardResult.NoName, ContactCards.build("  ", listOf("9876543210"), null))
    assertEquals(ContactCardResult.NoName, ContactCards.build(null, listOf("9876543210"), "  "))
    assertEquals(ContactCardResult.NoPhone, ContactCards.build("Asha", emptyList(), null))
    assertEquals(ContactCardResult.NoPhone, ContactCards.build("Asha", listOf("not a number"), null))
  }

  @Test
  fun `the organisation stands in for a missing name and is not repeated`() {
    val r = ok(ContactCards.build(null, listOf("9876543210"), "Rao Hardware"))
    assertEquals("Rao Hardware", r.card.name)
    assertNull(r.card.organisation)
    val both = ok(ContactCards.build("Asha Rao", listOf("9876543210"), "Rao Hardware"))
    assertEquals("Rao Hardware", both.card.organisation)
  }

  @Test
  fun `names and organisations are tidied and capped at 100`() {
    val r = ok(ContactCards.build("  Asha \n  Rao ", listOf("9876543210"), "x".repeat(150)))
    assertEquals("Asha Rao", r.card.name)
    assertEquals(100, r.card.organisation!!.length)
    assertEquals(100, ok(ContactCards.build("n".repeat(300), listOf("9876543210"), null)).card.name.length)
  }

  @Test
  fun `Indian language names are kept whole`() {
    assertEquals("आशा राव", ok(ContactCards.build("आशा राव", listOf("9876543210"), null)).card.name)
    assertEquals("అశ్విని", ok(ContactCards.build("అశ్విని", listOf("9876543210"), null)).card.name)
  }

  @Test
  fun `isValid matches the server rule`() {
    assertTrue(ContactCards.isValid(ContactCard("Asha", listOf("+919876543210"))))
    assertFalse(ContactCards.isValid(ContactCard("", listOf("+919876543210"))))
    assertFalse(ContactCards.isValid(ContactCard("Asha", emptyList())))
    assertFalse(ContactCards.isValid(ContactCard("Asha", listOf("9876543210"))))
    assertFalse(ContactCards.isValid(ContactCard("Asha", listOf("+919876543210", "+919876543210"))))
    assertFalse(ContactCards.isValid(ContactCard("Asha", List(4) { "+91987654321$it" })))
    assertFalse(ContactCards.isValid(ContactCard("Asha", listOf("+919876543210"), "o".repeat(101))))
  }

  @Test
  fun `display formats internationally and falls back to the raw text`() {
    assertEquals("+91 98765 43210", ContactCards.display("+919876543210"))
    assertEquals("garbage", ContactCards.display("garbage"))
  }

  @Test
  fun `export and pinned banner describe contacts and videos`() {
    val labels = ExportLabels("h", "deleted", "Photo", "Voice", "File", "(edited)", "?", video = "Video", contact = "Contact")
    val card = ContactCard("Asha Rao", listOf("+919876543210", "+14155550132"))
    val messages = listOf(
      Message("1", "me", "", 1_791_880_500_000L, kind = "contact", contact = card),
      Message("2", "me", "", 1_791_880_560_000L, kind = "video", media = Media("p", "Video", 10, "video/mp4", durationMs = 5000, thumbPath = "t")),
    )
    val out = ChatExport.format(messages, mapOf("me" to "You"), labels, ZoneId.of("Asia/Kolkata"))
    assertTrue(out.contains("You: <Contact: Asha Rao, +919876543210, +14155550132>"))
    assertTrue(out.contains("You: <Video>"))
    assertEquals(PinnedPreview.Contact("Asha Rao"), PinnedMessages.preview(messages[0]))
    assertEquals(PinnedPreview.Video(""), PinnedMessages.preview(messages[1]))
  }
}
