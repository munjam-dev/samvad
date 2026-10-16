package com.samvad.core

import com.samvad.core.status.OverlaySize
import com.samvad.core.status.PhotoEditPalette
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditsTest {
  private val rules = PhotoEditRules

  // ---- sizes and layout ------------------------------------------------------------------------

  @Test fun `text size grows with the choice and scales with the photo width`() {
    val s = rules.textSizePx(1000, OverlaySize.SMALL)
    val m = rules.textSizePx(1000, OverlaySize.MEDIUM)
    val l = rules.textSizePx(1000, OverlaySize.LARGE)
    assertTrue(s < m && m < l)
    assertEquals(2 * m, rules.textSizePx(2000, OverlaySize.MEDIUM), 0.001f)
  }

  @Test fun `text never wider than the photo and the pen is never hair thin`() {
    assertTrue(rules.maxTextWidthPx(1000) < 1000f)
    assertEquals(2f, rules.penWidthPx(40), 0.001f)
    assertTrue(rules.penWidthPx(1600) > 10f)
  }

  @Test fun `a box that fits stays centred on the chosen point`() {
    val p = rules.placeBox(0.5f, 0.5f, 400f, 100f, 1000f, 1000f)
    assertEquals(300f, p.left, 0.001f)
    assertEquals(450f, p.top, 0.001f)
  }

  @Test fun `a box near an edge is pulled back inside with a margin`() {
    val left = rules.placeBox(0.0f, 0.5f, 400f, 100f, 1000f, 1000f)
    assertEquals(20f, left.left, 0.001f)
    val right = rules.placeBox(1.0f, 0.5f, 400f, 100f, 1000f, 1000f)
    assertEquals(1000f - 400f - 20f, right.left, 0.001f)
    val top = rules.placeBox(0.5f, 0.0f, 400f, 100f, 1000f, 1000f)
    assertEquals(20f, top.top, 0.001f)
    val bottom = rules.placeBox(0.5f, 1.0f, 400f, 100f, 1000f, 1000f)
    assertEquals(1000f - 100f - 20f, bottom.top, 0.001f)
  }

  @Test fun `a box too big for the photo is centred instead of cut off on both sides`() {
    val p = rules.placeBox(0.1f, 0.9f, 1200f, 1500f, 1000f, 1000f)
    assertEquals(-100f, p.left, 0.001f)
    assertEquals(-250f, p.top, 0.001f)
  }

  @Test fun `the same share places the box the same way at preview and upload size`() {
    val small = rules.placeBox(0.3f, 0.7f, 100f, 30f, 500f, 500f)
    val big = rules.placeBox(0.3f, 0.7f, 200f, 60f, 1000f, 1000f)
    assertEquals(small.left * 2, big.left, 0.001f)
    assertEquals(small.top * 2, big.top, 0.001f)
  }

  // ---- text ------------------------------------------------------------------------------------

  @Test fun `text is trimmed, flattened to one line, limited, and blank text removes it`() {
    val long = rules.withText(PhotoEdits.NONE, "  hello\nworld  " + "x".repeat(200), 0.5f, 0.5f, OverlaySize.MEDIUM, 0)
    val t = long.text!!
    assertTrue(t.text.startsWith("hello world"))
    assertEquals(PhotoEditRules.MAX_TEXT_CHARS, t.text.length)
    assertNull(rules.withText(long, "   ", 0.5f, 0.5f, OverlaySize.MEDIUM, 0).text)
  }

  @Test fun `text position and colour are clamped to valid values`() {
    val e = rules.withText(PhotoEdits.NONE, "Hi", -3f, 9f, OverlaySize.LARGE, 99)
    assertEquals(0f, e.text!!.cx, 0f)
    assertEquals(1f, e.text!!.cy, 0f)
    assertEquals(PhotoEditPalette.TEXT_COLORS.lastIndex, e.text!!.color)
    val moved = rules.moveText(e, Float.NaN, 0.25f)
    assertEquals(0.5f, moved.text!!.cx, 0f)
    assertEquals(0.25f, moved.text!!.cy, 0f)
  }

  @Test fun `size and colour changes keep the text and do nothing without text`() {
    val e = rules.withText(PhotoEdits.NONE, "Hi", 0.5f, 0.5f, OverlaySize.SMALL, 0)
    assertEquals(OverlaySize.LARGE, rules.resizeText(e, OverlaySize.LARGE).text!!.size)
    assertEquals(2, rules.recolorText(e, 2).text!!.color)
    assertEquals("Hi", rules.recolorText(e, 2).text!!.text)
    assertSame(PhotoEdits.NONE, rules.resizeText(PhotoEdits.NONE, OverlaySize.LARGE))
    assertNull(rules.removeText(e).text)
  }

  // ---- pen -------------------------------------------------------------------------------------

  @Test fun `a stroke collects points and drops ones that barely moved`() {
    var e = rules.beginStroke(PhotoEdits.NONE, 1, 0.1f, 0.1f)
    e = rules.extendStroke(e, 0.1005f, 0.1005f)
    assertEquals(1, e.strokes.single().points.size)
    e = rules.extendStroke(e, 0.2f, 0.2f)
    assertEquals(2, e.strokes.single().points.size)
    assertEquals(1, e.strokes.single().color)
  }

  @Test fun `points outside the photo are pulled onto its edge`() {
    var e = rules.beginStroke(PhotoEdits.NONE, 0, -0.5f, 1.5f)
    e = rules.extendStroke(e, 2f, -2f)
    val pts = e.strokes.single().points
    assertEquals(0f, pts[0].x, 0f)
    assertEquals(1f, pts[0].y, 0f)
    assertEquals(1f, pts[1].x, 0f)
    assertEquals(0f, pts[1].y, 0f)
  }

  @Test fun `extending with no stroke does nothing`() {
    assertSame(PhotoEdits.NONE, rules.extendStroke(PhotoEdits.NONE, 0.5f, 0.5f))
  }

  @Test fun `undo removes the last stroke and clear removes them all but keeps the text`() {
    var e = rules.withText(PhotoEdits.NONE, "Hi", 0.5f, 0.5f, OverlaySize.MEDIUM, 0)
    e = rules.beginStroke(e, 0, 0.1f, 0.1f)
    e = rules.beginStroke(e, 1, 0.5f, 0.5f)
    assertEquals(2, e.strokes.size)
    e = rules.undoStroke(e)
    assertEquals(listOf(0), e.strokes.map { it.color })
    e = rules.clearStrokes(rules.beginStroke(e, 2, 0.9f, 0.9f))
    assertTrue(e.strokes.isEmpty())
    assertEquals("Hi", e.text!!.text)
    assertTrue(rules.undoStroke(PhotoEdits.NONE).strokes.isEmpty())
  }

  @Test fun `the number of strokes is limited`() {
    var e = PhotoEdits.NONE
    repeat(PhotoEditRules.MAX_STROKES + 10) { e = rules.beginStroke(e, 0, 0.1f, 0.1f) }
    assertEquals(PhotoEditRules.MAX_STROKES, e.strokes.size)
  }

  @Test fun `the number of points is limited across all strokes`() {
    var e = rules.beginStroke(PhotoEdits.NONE, 0, 0f, 0f)
    for (i in 1..PhotoEditRules.MAX_POINTS + 500) {
      e = rules.extendStroke(e, (i % 2) * 0.5f, ((i / 2) % 2) * 0.5f + (i % 7) * 0.05f)
    }
    assertEquals(PhotoEditRules.MAX_POINTS, rules.pointCount(e))
    val again = rules.beginStroke(e, 0, 0.3f, 0.3f)
    assertSame(e, again)
  }

  // ---- state kept across rotation and process death ----------------------------------------------

  @Test fun `edits survive being saved and read back`() {
    var e = rules.withText(PhotoEdits.NONE, "Happy, \"Diwali\"; 100% नमस्ते", 0.25f, 0.75f, OverlaySize.LARGE, 3)
    e = rules.beginStroke(e, 2, 0.1f, 0.2f)
    e = rules.extendStroke(e, 0.3f, 0.4f)
    e = rules.extendStroke(e, 0.5f, 0.6f)
    e = rules.beginStroke(e, 0, 0.9f, 0.9f)
    val back = rules.decode(rules.encode(e))
    assertEquals(e, back)
  }

  @Test fun `empty edits save as an empty string and read back as none`() {
    assertEquals("", rules.encode(PhotoEdits.NONE))
    assertEquals(PhotoEdits.NONE, rules.decode(""))
    assertEquals(PhotoEdits.NONE, rules.decode(null))
  }

  @Test fun `saved state that is damaged gives no edits instead of a crash`() {
    assertEquals(PhotoEdits.NONE, rules.decode("T:abc"))
    assertEquals(PhotoEdits.NONE, rules.decode("S:0:0.1,zzz"))
    assertEquals(PhotoEdits.NONE, rules.decode("S:x"))
  }

  @Test fun `saved state read back is clamped and limited`() {
    val tooMany = (0..60).joinToString(";") { "S:0:0.100,0.100" }
    assertEquals(PhotoEditRules.MAX_STROKES, rules.decode(tooMany).strokes.size)
    val wild = rules.decode("T:9.0,-4.0,7,99,Hi")
    assertEquals(1f, wild.text!!.cx, 0f)
    assertEquals(0f, wild.text!!.cy, 0f)
    assertEquals(OverlaySize.MEDIUM, wild.text!!.size)
    assertEquals(PhotoEditPalette.TEXT_COLORS.lastIndex, wild.text!!.color)
  }

  // ---- state and palette -----------------------------------------------------------------------

  @Test fun `edits are empty only when there is no text and no stroke`() {
    assertTrue(PhotoEdits.NONE.isEmpty)
    assertFalse(rules.withText(PhotoEdits.NONE, "Hi", 0.5f, 0.5f, OverlaySize.SMALL, 0).isEmpty)
    assertFalse(rules.beginStroke(PhotoEdits.NONE, 0, 0.5f, 0.5f).isEmpty)
  }

  @Test fun `there are four text colours and three pen colours, all different and opaque`() {
    assertEquals(4, PhotoEditPalette.TEXT_COLORS.size)
    assertEquals(3, PhotoEditPalette.PEN_COLORS.size)
    assertEquals(4, PhotoEditPalette.TEXT_COLORS.toSet().size)
    assertEquals(3, PhotoEditPalette.PEN_COLORS.toSet().size)
    (PhotoEditPalette.TEXT_COLORS + PhotoEditPalette.PEN_COLORS).forEach { assertEquals(0xFFL, it ushr 24) }
    assertNotEquals(PhotoEditPalette.textColor(0), PhotoEditPalette.textColor(1))
    assertEquals(PhotoEditPalette.penColor(2), PhotoEditPalette.penColor(50))
    assertEquals(PhotoEditPalette.textColor(0), PhotoEditPalette.textColor(-4))
  }
}
