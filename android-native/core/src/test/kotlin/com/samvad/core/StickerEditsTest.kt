package com.samvad.core

import com.samvad.core.status.EmojiPacks
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerEditsTest {
  private val rules = PhotoEditRules
  private val star = "⭐"

  @Test fun `adding a sticker makes the edits non empty and stops at the limit`() {
    var e = PhotoEdits.NONE
    assertTrue(e.isEmpty)
    repeat(PhotoEditRules.MAX_STICKERS + 3) { e = rules.addSticker(e, star) }
    assertFalse(e.isEmpty)
    assertEquals(PhotoEditRules.MAX_STICKERS, e.stickers.size)
  }

  @Test fun `a blank emoji is ignored`() {
    val e = PhotoEdits.NONE
    assertSame(e, rules.addSticker(e, "  "))
  }

  @Test fun `transform clamps position and scale and wraps rotation`() {
    var e = rules.addSticker(PhotoEdits.NONE, star)
    e = rules.transformSticker(e, 0, 5f, -5f, 100f, 190f)
    val s = e.stickers[0]
    assertEquals(1f, s.cx, 0f)
    assertEquals(0f, s.cy, 0f)
    assertEquals(PhotoEditRules.MAX_STICKER_SCALE, s.scale, 0f)
    assertEquals(-170f, s.rotation, 0.001f)
    e = rules.transformSticker(e, 0, 0f, 0f, 0.0001f, 0f)
    assertEquals(PhotoEditRules.MIN_STICKER_SCALE, e.stickers[0].scale, 0f)
  }

  @Test fun `transform of a missing sticker changes nothing`() {
    val e = rules.addSticker(PhotoEdits.NONE, star)
    assertSame(e, rules.transformSticker(e, 4, 0.1f, 0.1f, 2f, 10f))
  }

  @Test fun `remove drops only the chosen sticker`() {
    var e = rules.addSticker(rules.addSticker(PhotoEdits.NONE, star), "❤")
    e = rules.removeSticker(e, 0)
    assertEquals(listOf("❤"), e.stickers.map { it.emoji })
    assertSame(e, rules.removeSticker(e, 7))
  }

  @Test fun `hit test picks the top most sticker and misses empty space`() {
    var e = rules.addSticker(PhotoEdits.NONE, star)
    e = rules.addSticker(e, "❤")
    e = e.copy(stickers = e.stickers.map { it.copy(cx = 0.5f, cy = 0.5f) })
    assertEquals(1, rules.hitSticker(e, 0.5f, 0.5f, 1000f, 1000f))
    assertEquals(-1, rules.hitSticker(e, 0.95f, 0.05f, 1000f, 1000f))
  }

  @Test fun `encode and decode keep stickers`() {
    var e = rules.addSticker(PhotoEdits.NONE, String(Character.toChars(0x1F600)))
    e = rules.transformSticker(e, 0, 0.1f, -0.1f, 2f, 45f)
    val back = rules.decode(rules.encode(e))
    assertEquals(1, back.stickers.size)
    val a = e.stickers[0]
    val b = back.stickers[0]
    assertEquals(a.emoji, b.emoji)
    assertEquals(a.cx, b.cx, 0.001f)
    assertEquals(a.scale, b.scale, 0.001f)
    assertEquals(a.rotation, b.rotation, 0.001f)
  }

  @Test fun `four packs with distinct non empty emoji`() {
    assertEquals(4, EmojiPacks.ALL.size)
    EmojiPacks.ALL.forEach { p ->
      assertTrue(p.emoji.isNotEmpty())
      assertEquals(p.emoji.size, p.emoji.toSet().size)
    }
  }
}
