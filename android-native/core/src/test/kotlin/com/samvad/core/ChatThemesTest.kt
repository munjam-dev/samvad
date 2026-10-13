package com.samvad.core

import com.samvad.core.design.ChatBackground
import com.samvad.core.design.ChatThemes
import com.samvad.core.design.Contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThemesTest {
  @Test fun `there are five built-in themes and Ivory is the default`() {
    assertEquals(listOf("ivory", "sage", "monsoon", "marigold", "night_garden"), ChatThemes.all.map { it.id })
    assertEquals("ivory", ChatThemes.DEFAULT.id)
  }

  @Test fun `every theme meets AAA for text and AA for metadata, light and dark`() {
    val failures = mutableListOf<String>()
    for (theme in ChatThemes.all) for ((mode, c) in listOf("light" to theme.light, "dark" to theme.dark)) {
      fun check(what: String, fg: Long, bg: Long, min: Double) {
        val r = Contrast.ratio(fg, bg)
        if (r < min) failures += "${theme.id}/$mode $what = ${"%.2f".format(r)} < $min"
      }
      check("text on incoming", c.text, c.bubbleIncoming, 7.0)
      check("text on outgoing", c.text, c.bubbleOutgoing, 7.0)
      check("meta on incoming", c.meta, c.bubbleIncoming, 4.5)
      check("meta on outgoing", c.meta, c.bubbleOutgoing, 4.5)
      check("chip text", c.chipText, c.chip, 7.0)
    }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
  }

  @Test fun `a chat's own choice wins over the default, and unknown ids fall back`() {
    val overrides = mapOf("c1" to "sage", "c2" to "nope")
    assertEquals(ChatBackground.Theme("sage"), ChatThemes.resolve("monsoon", overrides, "c1"))
    assertEquals(ChatBackground.Theme("monsoon"), ChatThemes.resolve("monsoon", overrides, "c3"))
    assertEquals(ChatBackground.Theme("ivory"), ChatThemes.resolve("monsoon", overrides, "c2"))
    assertEquals(ChatBackground.Theme("ivory"), ChatThemes.resolve(null, emptyMap(), "c1"))
  }

  @Test fun `my photo is a choice too`() {
    assertEquals(ChatBackground.Photo("c1"), ChatThemes.resolve("ivory", mapOf("c1" to ChatThemes.PHOTO), "c1"))
    assertEquals(ChatBackground.Photo(ChatThemes.DEFAULT_SLOT), ChatThemes.resolve(ChatThemes.PHOTO, emptyMap(), "c9"))
  }

  @Test fun `dim is clamped to 0 to 60 percent`() {
    assertEquals(0, ChatThemes.clampDim(-5))
    assertEquals(60, ChatThemes.clampDim(90))
    assertEquals(35, ChatThemes.clampDim(35))
  }
}
