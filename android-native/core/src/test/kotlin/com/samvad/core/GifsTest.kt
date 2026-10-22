package com.samvad.core

import com.samvad.core.chat.ExportLabels
import com.samvad.core.chat.ChatExport
import com.samvad.core.chat.Message
import com.samvad.core.chat.PinnedMessages
import com.samvad.core.chat.PinnedPreview
import com.samvad.core.gifs.GifGrid
import com.samvad.core.gifs.GifKind
import com.samvad.core.gifs.GifLibrary
import com.samvad.core.gifs.GifRef
import com.samvad.core.gifs.GifRules
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifsTest {
  private fun ref(id: String, w: Int = 200, h: Int = 100) = GifRef(
    id = id,
    title = "Title $id",
    previewUrl = "https://media2.giphy.com/media/$id/100w.gif",
    url = "https://media2.giphy.com/media/$id/200w.gif?cid=1",
    width = w,
    height = h,
  )

  @Test fun `query is trimmed, cleaned and capped`() {
    assertEquals("happy dance", GifRules.normalizeQuery("  happy \n  dance  "))
    assertEquals("", GifRules.normalizeQuery(null))
    assertEquals("", GifRules.normalizeQuery("   "))
    assertEquals("a b", GifRules.normalizeQuery("a\u0000b"))
    assertEquals(50, GifRules.normalizeQuery("x".repeat(80)).length)
    // Indic text counts by characters, not UTF-16 units.
    assertEquals("नमस्ते", GifRules.normalizeQuery(" नमस्ते "))
  }

  @Test fun `only https links on provider hosts are allowed`() {
    assertTrue(GifRules.isAllowedUrl("https://media0.giphy.com/media/a/200w.gif"))
    assertTrue(GifRules.isAllowedUrl("https://media.giphy.com/media/a/200w.gif?cid=1&ct=g"))
    assertTrue(GifRules.isAllowedUrl("https://i.giphy.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("http://media0.giphy.com/media/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://evil.example.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://media0.giphy.com.evil.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://evilgiphy.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://giphy.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://user:pw@media0.giphy.com/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://media0.giphy.com:8443/a.gif"))
    assertFalse(GifRules.isAllowedUrl("https://media0.giphy.com"))
    assertFalse(GifRules.isAllowedUrl("javascript:alert(1)"))
    assertFalse(GifRules.isAllowedUrl("not a url"))
    assertFalse(GifRules.isAllowedUrl(null))
    assertFalse(GifRules.isAllowedUrl("https://media0.giphy.com/" + "a".repeat(500)))
  }

  @Test fun `a ref is valid only with allowed links, a plain id and sane sizes`() {
    assertTrue(GifRules.isValid(ref("abc123")))
    assertFalse(GifRules.isValid(ref("a/b")))
    assertFalse(GifRules.isValid(ref("abc123", w = 0)))
    assertFalse(GifRules.isValid(ref("abc123", h = 1001)))
    assertFalse(GifRules.isValid(ref("abc123").copy(url = "https://evil.example.com/a.gif")))
    assertFalse(GifRules.isValid(ref("abc123").copy(provider = "other")))
  }

  @Test fun `stickers carry no caption and GIF captions are bounded`() {
    assertEquals("", GifRules.captionFor(GifKind.STICKER, "hello"))
    assertEquals("hello", GifRules.captionFor(GifKind.GIF, "  hello "))
    assertEquals(1000, GifRules.captionFor(GifKind.GIF, "x".repeat(2000)).length)
  }

  @Test fun `kinds map to and from the wire`() {
    assertEquals(GifKind.GIF, GifKind.fromWire("gif"))
    assertEquals(GifKind.STICKER, GifKind.fromWire("sticker"))
    assertNull(GifKind.fromWire("image"))
    assertNull(GifKind.fromWire(null))
  }

  @Test fun `aspect ratio is clamped`() {
    assertEquals(1f, GifGrid.clampAspect(0, 10), 0f)
    assertEquals(2f, GifGrid.clampAspect(200, 100), 0f)
    assertEquals(GifGrid.MAX_ASPECT, GifGrid.clampAspect(1000, 10), 0f)
    assertEquals(GifGrid.MIN_ASPECT, GifGrid.clampAspect(10, 1000), 0f)
  }

  @Test fun `columns grow with the width and tiles stay big enough to tap`() {
    assertEquals(2, GifGrid.columnsFor(360))
    assertEquals(2, GifGrid.columnsFor(411))
    assertEquals(3, GifGrid.columnsFor(600))
    assertEquals(4, GifGrid.columnsFor(840))
    for (w in listOf(320, 360, 411, 600, 840, 1200)) {
      val tile = (w - 16 * 2 - 8 * (GifGrid.columnsFor(w) - 1)) / GifGrid.columnsFor(w)
      assertTrue("tile at ${w}dp is $tile", tile >= GifGrid.MIN_TILE_DP)
    }
    assertEquals(50f, GifGrid.tileHeightDp(100f, 2f), 0f)
  }

  @Test fun `bubble size fits the limits and keeps the shape`() {
    val (w, h) = GifGrid.bubbleSizeDp(ref("a", 400, 200), GifKind.GIF)
    assertEquals(240, w)
    assertEquals(120, h)
    val (sw, sh) = GifGrid.bubbleSizeDp(ref("a", 100, 400), GifKind.STICKER)
    assertTrue(sw <= 160 && sh <= 160)
  }

  @Test fun `recents put the newest first, once, and are capped`() {
    var recents = emptyList<GifRef>()
    for (i in 1..35) recents = GifLibrary.addRecent(recents, ref("id$i"))
    assertEquals(GifLibrary.MAX_RECENTS, recents.size)
    assertEquals("id35", recents.first().id)
    recents = GifLibrary.addRecent(recents, ref("id20"))
    assertEquals("id20", recents.first().id)
    assertEquals(1, recents.count { it.id == "id20" })
    assertEquals(GifLibrary.MAX_RECENTS, recents.size)
  }

  @Test fun `favourites toggle and the your-GIFs view lists them before recents`() {
    var fav = emptyList<GifRef>()
    fav = GifLibrary.toggleFavourite(fav, ref("f1"))
    fav = GifLibrary.toggleFavourite(fav, ref("f2"))
    assertEquals(listOf("f2", "f1"), fav.map { it.id })
    assertTrue(GifLibrary.isFavourite(fav, "f1"))
    fav = GifLibrary.toggleFavourite(fav, ref("f1"))
    assertFalse(GifLibrary.isFavourite(fav, "f1"))
    val full = (1..3).map { ref("g$it") }
    assertEquals(full, GifLibrary.toggleFavourite(full, ref("new"), max = 3))
    val yours = GifLibrary.yours(listOf(ref("f2")), listOf(ref("r1"), ref("f2"), ref("r2")))
    assertEquals(listOf("f2", "r1", "r2"), yours.map { it.id })
  }

  @Test fun `stored lists round-trip and bad lines are dropped`() {
    val items = listOf(ref("abc1").copy(title = "tab\there\nnewline"), ref("abc2"))
    val back = GifLibrary.decode(GifLibrary.encode(items))
    assertEquals(listOf("abc1", "abc2"), back.map { it.id })
    assertEquals("tab here newline", back[0].title)
    assertEquals(emptyList<GifRef>(), GifLibrary.decode(null))
    assertEquals(emptyList<GifRef>(), GifLibrary.decode(""))
    val tampered = GifLibrary.encode(listOf(ref("abc3").copy(url = "https://evil.example.com/a.gif"))) + "\nbroken line\nx\ty\tz\t1\t2\t3"
    assertEquals(emptyList<GifRef>(), GifLibrary.decode(tampered))
  }

  @Test fun `export and pinned banner know GIFs and stickers`() {
    val labels = ExportLabels("h", "deleted", "Photo", "Voice", "File", "(edited)", "?", gif = "GIF", sticker = "Sticker")
    val gif = Message("1", "asha", "so true", 1000L, kind = "gif", gif = ref("abc1"))
    val sticker = Message("2", "asha", "", 2000L, kind = "sticker", gif = ref("abc2"))
    val out = ChatExport.format(listOf(gif, sticker), mapOf("asha" to "Asha"), labels, ZoneId.of("UTC"))
    assertTrue(out.contains("Asha: <GIF> so true"))
    assertTrue(out.contains("Asha: <Sticker>"))
    assertEquals(PinnedPreview.Gif("so true"), PinnedMessages.preview(gif))
    assertEquals(PinnedPreview.Sticker, PinnedMessages.preview(sticker))
  }
}
