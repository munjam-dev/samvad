package com.samvad.core

import com.samvad.core.chat.Message
import com.samvad.core.chat.Selection
import com.samvad.core.chat.StarAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
  private val me = "me"

  private fun msg(
    id: String,
    sender: String = me,
    text: String = "text $id",
    at: Long = 1000,
    kind: String = "text",
    deleted: Boolean = false,
    pending: Boolean = false,
    failed: Boolean = false,
  ) = Message(id, sender, text, at, kind = kind, deleted = deleted, pending = pending, failed = failed)

  @Test fun `toggle adds then removes`() {
    val one = Selection.toggle(emptySet(), "a")
    assertEquals(setOf("a"), one)
    val two = Selection.toggle(one, "b")
    assertEquals(setOf("a", "b"), two)
    assertEquals(setOf("b"), Selection.toggle(two, "a"))
    assertEquals(emptySet<String>(), Selection.toggle(setOf("b"), "b"))
  }

  @Test fun `deleted, pending and system messages cannot be selected`() {
    assertTrue(Selection.canSelect(msg("a")))
    assertTrue(Selection.canSelect(msg("f", failed = true)))
    assertFalse(Selection.canSelect(msg("d", deleted = true)))
    assertFalse(Selection.canSelect(msg("p", pending = true)))
    assertFalse(Selection.canSelect(msg("s", kind = "system")))
  }

  @Test fun `selected messages come back oldest first`() {
    val all = listOf(msg("c", at = 3000), msg("a", at = 1000), msg("b", at = 2000), msg("x", at = 500))
    assertEquals(listOf("a", "b", "c"), Selection.ordered(all, setOf("c", "a", "b")).map { it.id })
  }

  @Test fun `equal times keep a stable order`() {
    val all = listOf(msg("b", at = 1000), msg("a", at = 1000))
    assertEquals(listOf("a", "b"), Selection.ordered(all, setOf("a", "b")).map { it.id })
  }

  @Test fun `selection drops messages that were deleted meanwhile`() {
    val all = listOf(msg("a"), msg("b", deleted = true))
    assertEquals(listOf("a"), Selection.ordered(all, setOf("a", "b")).map { it.id })
  }

  @Test fun `copy joins texts with newlines, oldest first`() {
    val sel = listOf(msg("c", text = "third", at = 3000), msg("a", text = "first", at = 1000), msg("b", text = "second", at = 2000))
    assertEquals("first\nsecond\nthird", Selection.copyText(sel))
  }

  @Test fun `copy skips media without text and blank texts`() {
    val sel = listOf(
      msg("a", text = "hello", at = 1),
      msg("b", text = "", kind = "image", at = 2),
      msg("c", text = "   ", at = 3),
      msg("d", text = "bye", at = 4),
    )
    assertEquals("hello\nbye", Selection.copyText(sel))
  }

  @Test fun `copy of a single message is its text without a trailing newline`() {
    assertEquals("only", Selection.copyText(listOf(msg("a", text = "only"))))
  }

  @Test fun `copy is offered only when something has text`() {
    assertTrue(Selection.actions(listOf(msg("a"), msg("b", kind = "image", text = "")), me, emptySet()).copy)
    assertFalse(Selection.actions(listOf(msg("b", kind = "image", text = "")), me, emptySet()).copy)
  }

  @Test fun `delete for everyone needs every selected message to be mine`() {
    val mine = listOf(msg("a"), msg("b"))
    assertTrue(Selection.actions(mine, me, emptySet()).deleteForEveryone)
    val mixed = listOf(msg("a"), msg("b", sender = "them"))
    assertFalse(Selection.actions(mixed, me, emptySet()).deleteForEveryone)
    assertFalse(Selection.actions(listOf(msg("b", sender = "them")), me, emptySet()).deleteForEveryone)
  }

  @Test fun `delete for everyone is hidden for messages that are not delivered yet`() {
    assertFalse(Selection.actions(listOf(msg("a", failed = true)), me, emptySet()).deleteForEveryone)
    assertFalse(Selection.actions(listOf(msg("a", pending = true)), me, emptySet()).deleteForEveryone)
  }

  @Test fun `star toggles by the whole selection`() {
    val sel = listOf(msg("a"), msg("b"))
    assertEquals(StarAction.STAR, Selection.actions(sel, me, emptySet()).star)
    assertEquals(StarAction.STAR, Selection.actions(sel, me, setOf("a")).star)
    assertEquals(StarAction.UNSTAR, Selection.actions(sel, me, setOf("a", "b", "z")).star)
  }

  @Test fun `star targets are only the messages that change`() {
    val sel = listOf(msg("a"), msg("b"), msg("c"))
    val starred = setOf("b")
    assertEquals(listOf("a", "c"), Selection.starTargets(sel, StarAction.STAR, starred).map { it.id })
    assertEquals(listOf("b"), Selection.starTargets(sel, StarAction.UNSTAR, starred).map { it.id })
    assertTrue(Selection.starTargets(sel, StarAction.NONE, starred).isEmpty())
  }

  @Test fun `star is hidden when a message cannot be starred`() {
    assertEquals(StarAction.NONE, Selection.actions(listOf(msg("a"), msg("b", failed = true)), me, emptySet()).star)
    assertEquals(StarAction.NONE, Selection.actions(listOf(msg("a", deleted = true)), me, emptySet()).star)
  }

  @Test fun `forward needs every message to be sendable`() {
    assertTrue(Selection.actions(listOf(msg("a"), msg("b", sender = "them")), me, emptySet()).forward)
    assertFalse(Selection.actions(listOf(msg("a"), msg("b", failed = true)), me, emptySet()).forward)
  }

  @Test fun `forward is limited to a small batch`() {
    val ok = (1..Selection.MAX_FORWARD_MESSAGES).map { msg("m$it") }
    assertTrue(Selection.actions(ok, me, emptySet()).forward)
    val tooMany = (1..Selection.MAX_FORWARD_MESSAGES + 1).map { msg("m$it") }
    assertFalse(Selection.actions(tooMany, me, emptySet()).forward)
  }

  @Test fun `the full message menu is only for a single selection`() {
    assertTrue(Selection.actions(listOf(msg("a")), me, emptySet()).moreOptions)
    assertFalse(Selection.actions(listOf(msg("a"), msg("b")), me, emptySet()).moreOptions)
  }

  @Test fun `an empty selection offers nothing`() {
    val none = Selection.actions(emptyList(), me, emptySet())
    assertFalse(none.copy)
    assertFalse(none.forward)
    assertFalse(none.deleteForEveryone)
    assertFalse(none.moreOptions)
    assertEquals(StarAction.NONE, none.star)
  }
}
