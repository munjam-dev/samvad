package com.samvad.core

import com.samvad.core.chat.Message
import com.samvad.core.chat.Pin
import com.samvad.core.chat.PinBlock
import com.samvad.core.chat.PinnedMessages
import com.samvad.core.chat.PinnedPreview
import com.samvad.core.chat.Poll
import com.samvad.core.media.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedMessagesTest {
  private val now = 1_791_900_000_000L
  private fun pin(id: String, at: Long) = Pin(id, "asha", at)
  private fun msg(id: String, text: String = "hello", kind: String = "text", extra: (Message) -> Message = { it }) =
    extra(Message(id, "asha", text, now - 1_000, kind = kind))

  @Test fun `the limit is three`() {
    assertEquals(3, PinnedMessages.MAX)
  }

  @Test fun `pins are ordered oldest first with one entry per message`() {
    val pins = listOf(pin("c", 30), pin("a", 10), pin("b", 20), pin("a", 40))
    assertEquals(listOf("a", "b", "c"), PinnedMessages.ordered(pins).map { it.messageId })
    assertEquals(10L, PinnedMessages.ordered(pins).first().pinnedAt)
  }

  @Test fun `pins with the same time are ordered by message id so the order never flickers`() {
    assertEquals(listOf("a", "b"), PinnedMessages.ordered(listOf(pin("b", 5), pin("a", 5))).map { it.messageId })
  }

  @Test fun `a message can be added until three are pinned`() {
    val two = listOf(pin("a", 1), pin("b", 2))
    assertNull(PinnedMessages.blockedReason(two, "c"))
    val three = two + pin("c", 3)
    assertEquals(PinBlock.LIMIT_REACHED, PinnedMessages.blockedReason(three, "d"))
  }

  @Test fun `an already pinned message is reported as pinned even when full`() {
    val three = listOf(pin("a", 1), pin("b", 2), pin("c", 3))
    assertEquals(PinBlock.ALREADY_PINNED, PinnedMessages.blockedReason(three, "b"))
    assertTrue(PinnedMessages.isPinned(three, "c"))
    assertFalse(PinnedMessages.isPinned(three, "z"))
  }

  @Test fun `duplicates in the stored list do not count twice towards the limit`() {
    val dup = listOf(pin("a", 1), pin("a", 2), pin("b", 3))
    assertNull(PinnedMessages.blockedReason(dup, "c"))
  }

  @Test fun `direct chats let anyone pin and groups only admins`() {
    assertTrue(PinnedMessages.canChange(isGroup = false, isAdmin = false))
    assertTrue(PinnedMessages.canChange(isGroup = true, isAdmin = true))
    assertFalse(PinnedMessages.canChange(isGroup = true, isAdmin = false))
  }

  @Test fun `deleted system pending and failed messages cannot be pinned`() {
    assertTrue(PinnedMessages.isPinnable(msg("a")))
    assertFalse(PinnedMessages.isPinnable(msg("a") { it.copy(deleted = true) }))
    assertFalse(PinnedMessages.isPinnable(msg("a", kind = "system")))
    assertFalse(PinnedMessages.isPinnable(msg("a") { it.copy(pending = true) }))
    assertFalse(PinnedMessages.isPinnable(msg("a") { it.copy(failed = true) }))
  }

  @Test fun `previews describe the kind of message`() {
    assertEquals(PinnedPreview.Text("hello there"), PinnedMessages.preview(msg("a", "hello\n  there")))
    assertEquals(PinnedPreview.Photo("Diwali"), PinnedMessages.preview(msg("a", "Diwali", "image")))
    assertEquals(PinnedPreview.Photo(""), PinnedMessages.preview(msg("a", "", "image")))
    val file = msg("a", "", "file") { it.copy(media = Media("p", "bill.pdf", 10, "application/pdf")) }
    assertEquals(PinnedPreview.File("bill.pdf"), PinnedMessages.preview(file))
    assertEquals(PinnedPreview.Voice, PinnedMessages.preview(msg("a", "", "voice")))
    val poll = msg("a", "", "poll") { it.copy(poll = Poll("Where to eat?", listOf("x", "y"), listOf("0", "1"), false)) }
    assertEquals(PinnedPreview.Poll("Where to eat?"), PinnedMessages.preview(poll))
  }

  @Test fun `a missing or deleted message has no preview`() {
    assertEquals(PinnedPreview.Unavailable, PinnedMessages.preview(null))
    assertEquals(PinnedPreview.Unavailable, PinnedMessages.preview(msg("a") { it.copy(deleted = true) }))
  }

  @Test fun `long text is cut to one short line`() {
    val preview = PinnedMessages.preview(msg("a", "word ".repeat(100))) as PinnedPreview.Text
    assertTrue(preview.text.length <= 141)
    assertTrue(preview.text.endsWith("…"))
    assertFalse(preview.text.contains("\n"))
  }

  @Test fun `with nothing pinned there is no banner`() {
    assertNull(PinnedMessages.banner(emptyList(), emptyMap(), now))
  }

  @Test fun `the banner shows the newest pin and counts the rest`() {
    val pins = listOf(pin("a", 1), pin("b", 2), pin("c", 3))
    val messages = mapOf("a" to msg("a", "first"), "b" to msg("b", "second"), "c" to msg("c", "third"))
    val banner = PinnedMessages.banner(pins, messages, now)!!
    assertEquals("c", banner.messageId)
    assertEquals(PinnedPreview.Text("third"), banner.preview)
    assertEquals(3, banner.position)
    assertEquals(3, banner.total)
    assertTrue(banner.hasMany)
  }

  @Test fun `a single pin has no count`() {
    val banner = PinnedMessages.banner(listOf(pin("a", 1)), mapOf("a" to msg("a")), now)!!
    assertFalse(banner.hasMany)
    assertEquals(1, banner.total)
  }

  @Test fun `a pin whose message is not loaded yet still shows, without a preview`() {
    val banner = PinnedMessages.banner(listOf(pin("a", 1)), emptyMap(), now)!!
    assertEquals(PinnedPreview.Unavailable, banner.preview)
  }

  @Test fun `deleted and expired messages are left out of the banner`() {
    val pins = listOf(pin("a", 1), pin("b", 2), pin("c", 3))
    val messages = mapOf(
      "a" to msg("a", "kept"),
      "b" to msg("b") { it.copy(deleted = true) },
      "c" to msg("c") { it.copy(expiresAt = now - 1) },
    )
    val banner = PinnedMessages.banner(pins, messages, now)!!
    assertEquals("a", banner.messageId)
    assertEquals(1, banner.total)
  }

  @Test fun `a message that has not expired yet still shows`() {
    val banner = PinnedMessages.banner(listOf(pin("a", 1)), mapOf("a" to msg("a") { it.copy(expiresAt = now + 1) }), now)
    assertEquals("a", banner!!.messageId)
  }

  @Test fun `when every pinned message is gone there is no banner`() {
    val messages = mapOf("a" to msg("a") { it.copy(deleted = true) })
    assertNull(PinnedMessages.banner(listOf(pin("a", 1)), messages, now))
  }

  @Test fun `a chosen index picks that pin and an out of range index falls back to the newest`() {
    val pins = listOf(pin("a", 1), pin("b", 2), pin("c", 3))
    assertEquals("a", PinnedMessages.banner(pins, emptyMap(), now, selected = 0)!!.messageId)
    assertEquals(2, PinnedMessages.banner(pins, emptyMap(), now, selected = 1)!!.position)
    assertEquals("c", PinnedMessages.banner(pins, emptyMap(), now, selected = 7)!!.messageId)
    assertEquals("c", PinnedMessages.banner(pins, emptyMap(), now, selected = -1)!!.messageId)
  }

  @Test fun `tapping steps back through older pins and wraps to the newest`() {
    assertEquals(1, PinnedMessages.nextIndex(2, 3))
    assertEquals(0, PinnedMessages.nextIndex(1, 3))
    assertEquals(2, PinnedMessages.nextIndex(0, 3))
    assertEquals(0, PinnedMessages.nextIndex(0, 1))
    assertEquals(0, PinnedMessages.nextIndex(0, 0))
  }
}
