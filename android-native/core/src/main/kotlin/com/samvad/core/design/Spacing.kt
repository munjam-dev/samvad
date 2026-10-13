package com.samvad.core.design

/** 4dp spacing scale (values in dp). */
object Spacing {
  const val XXS = 2
  const val XS = 4
  const val S = 8
  const val M = 12
  const val L = 16
  const val XL = 24
  const val XXL = 32
  const val XXXL = 48
  const val SCREEN_GUTTER = 20
  const val MIN_TOUCH_TARGET = 48
}

/** Corner radii (dp). */
object Radii {
  const val CHIP = 18
  const val BUTTON = 16
  const val FIELD = 16
  const val CARD = 24
  const val BUBBLE = 20
  const val BUBBLE_TAIL = 6
  const val SHEET = 28
  const val FAB = 20
  const val NAV_BAR = 24
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** The three home compositions explored on the design canvas. */
enum class HomeLayout { CLEAN, BOLD, BENTO }
