package com.samvad.core

import com.samvad.core.design.Contrast
import com.samvad.core.design.ContrastPairs
import com.samvad.core.design.SamvadPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
  @Test fun `ratio matches known WCAG values`() {
    assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
    assertEquals(1.0, Contrast.ratio(0xFF12A150, 0xFF12A150), 0.001)
    assertEquals(4.77, Contrast.ratio(0xFFFFFFFF, 0xFF0F8443), 0.01)
  }

  @Test fun `every declared pair meets its minimum in light, dark and AMOLED`() {
    val failures = mapOf("light" to SamvadPalettes.Light, "dark" to SamvadPalettes.Dark, "amoled" to SamvadPalettes.Amoled)
      .flatMap { (scheme, palette) ->
        ContrastPairs.of(palette).mapNotNull { pair ->
          val r = Contrast.ratio(pair.foreground, pair.background)
          if (r + 1e-9 < pair.minimum) "$scheme ${pair.name}: ${"%.2f".format(r)} < ${pair.minimum}" else null
        }
      }
    assertTrue(failures.joinToString("\n"), failures.isEmpty())
  }

  @Test fun `brand green is never used for small white text`() {
    // #12A150 with white is 3.37:1, so buttons carrying white labels use the text-safe primary.
    assertTrue(Contrast.ratio(0xFFFFFFFF, SamvadPalettes.Light.brand) < 4.5)
    assertTrue(Contrast.ratio(SamvadPalettes.Light.onPrimary, SamvadPalettes.Light.primary) >= 4.5)
  }
}
