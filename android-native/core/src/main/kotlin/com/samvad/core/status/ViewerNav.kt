package com.samvad.core.status

enum class TapTarget { PREVIOUS, NEXT }

/** Moving around inside the moment viewer: tap the left half to go back, the right half to go on. */
object ViewerNav {
  /** Which way a tap at [x] goes in a viewer that is [width] wide. The left half goes back. */
  fun tapTarget(x: Float, width: Float): TapTarget = if (x < width / 2f) TapTarget.PREVIOUS else TapTarget.NEXT

  /**
   * The moment to show after going [target] from [index], or null when the viewer should close:
   * past the last moment, or back from the first one.
   */
  fun move(index: Int, lastIndex: Int, target: TapTarget): Int? = when (target) {
    TapTarget.PREVIOUS -> if (index > 0) index - 1 else null
    TapTarget.NEXT -> if (index < lastIndex) index + 1 else null
  }
}
