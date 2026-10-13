package com.samvad.core.design

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.x contrast ratio between two opaque ARGB colours. */
object Contrast {
  fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
      val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
      return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
  }

  fun ratio(foreground: Long, background: Long): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
  }
}
