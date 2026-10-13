package com.samvad.core.design

/**
 * Single source of truth for Samvad colours ("Botanical Modernism", v2 green identity).
 * Values are ARGB longs so they can be tested on the JVM; the app maps them to Compose colours.
 * Every foreground/background pair the UI uses is listed in [ContrastPairs] and tested.
 */
data class SamvadPalette(
  val background: Long,
  val surface: Long,
  val surfaceContainer: Long,
  val surfaceContainerHigh: Long,
  val onSurface: Long,
  val onSurfaceVariant: Long,
  val outline: Long,
  val outlineVariant: Long,
  /** Brand green for fills, icons, rings and large type. */
  val brand: Long,
  /** Text-safe green: green text, and fills that carry small white text. */
  val primary: Long,
  val onPrimary: Long,
  val primaryContainer: Long,
  val onPrimaryContainer: Long,
  val secondaryContainer: Long,
  val onSecondaryContainer: Long,
  val tertiaryContainer: Long,
  val onTertiaryContainer: Long,
  /** Deep green architectural surfaces (bold header, welcome card). */
  val deep: Long,
  val onDeep: Long,
  val onDeepMuted: Long,
  /** Lime accent: only on [deep], never on light surfaces. */
  val lime: Long,
  val onLime: Long,
  val bubbleIncoming: Long,
  val bubbleOutgoing: Long,
  val onBubble: Long,
  val bubbleMeta: Long,
  val success: Long,
  val warning: Long,
  val error: Long,
  val onError: Long,
  val notificationDot: Long,
  val isDark: Boolean,
)

object SamvadPalettes {
  val Light = SamvadPalette(
    background = 0xFFF7F9F8,
    surface = 0xFFFFFFFF,
    surfaceContainer = 0xFFEEF2EF,
    surfaceContainerHigh = 0xFFFFFFFF,
    onSurface = 0xFF14201A,
    onSurfaceVariant = 0xFF5A6B62,
    outline = 0xFF7A8B82,
    outlineVariant = 0xFFE4EAE6,
    brand = 0xFF12A150,
    primary = 0xFF0F8443,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFE7F3EC,
    onPrimaryContainer = 0xFF0B5D33,
    secondaryContainer = 0xFFEAF5D9,
    onSecondaryContainer = 0xFF3D5A0E,
    tertiaryContainer = 0xFFF3EBDD,
    onTertiaryContainer = 0xFF6B4E1F,
    deep = 0xFF0B3B2C,
    onDeep = 0xFFFFFFFF,
    onDeepMuted = 0xFFB9D4C6,
    lime = 0xFFC6F432,
    onLime = 0xFF0B3B2C,
    bubbleIncoming = 0xFFFFFFFF,
    bubbleOutgoing = 0xFFDDF0E4,
    onBubble = 0xFF14201A,
    bubbleMeta = 0xFF3F5A47,
    success = 0xFF0F8443,
    warning = 0xFF98601B,
    error = 0xFFB42332,
    onError = 0xFFFFFFFF,
    notificationDot = 0xFFD92D3A,
    isDark = false,
  )

  val Dark = SamvadPalette(
    background = 0xFF0A1410,
    surface = 0xFF111D17,
    surfaceContainer = 0xFF17251E,
    surfaceContainerHigh = 0xFF1D2D25,
    onSurface = 0xFFF1F5F2,
    onSurfaceVariant = 0xFFA9B8B0,
    outline = 0xFF6E8077,
    outlineVariant = 0xFF26362E,
    brand = 0xFF3CC97A,
    primary = 0xFF3CC97A,
    onPrimary = 0xFF0A1410,
    primaryContainer = 0xFF1A4A33,
    onPrimaryContainer = 0xFFBDEBCF,
    secondaryContainer = 0xFF2C3A14,
    onSecondaryContainer = 0xFFC7E59A,
    tertiaryContainer = 0xFF4A3326,
    onTertiaryContainer = 0xFFF2C99B,
    deep = 0xFF0F2E23,
    onDeep = 0xFFF1F5F2,
    onDeepMuted = 0xFFA9B8B0,
    lime = 0xFFC6F432,
    onLime = 0xFF0B3B2C,
    bubbleIncoming = 0xFF1D2D25,
    bubbleOutgoing = 0xFF1A4A33,
    onBubble = 0xFFF1F5F2,
    bubbleMeta = 0xFFBDEBCF,
    success = 0xFF3CC97A,
    warning = 0xFFE8B66B,
    error = 0xFFFF9AA2,
    onError = 0xFF0A1410,
    notificationDot = 0xFFFF6B75,
    isDark = true,
  )

  /** Dark palette on true black for OLED screens. */
  val Amoled = Dark.copy(
    background = 0xFF000000,
    surface = 0xFF0A0F0C,
    surfaceContainer = 0xFF101813,
    surfaceContainerHigh = 0xFF16211B,
    deep = 0xFF0B2219,
    bubbleIncoming = 0xFF16211B,
  )
}

/** A foreground/background pair and the minimum contrast it must meet. */
data class ContrastPair(val name: String, val foreground: Long, val background: Long, val minimum: Double)

object ContrastPairs {
  const val TEXT = 4.5
  const val NON_TEXT = 3.0

  fun of(p: SamvadPalette): List<ContrastPair> = listOf(
    ContrastPair("onSurface/background", p.onSurface, p.background, TEXT),
    ContrastPair("onSurface/surface", p.onSurface, p.surface, TEXT),
    ContrastPair("onSurface/surfaceContainer", p.onSurface, p.surfaceContainer, TEXT),
    ContrastPair("onSurfaceVariant/background", p.onSurfaceVariant, p.background, TEXT),
    ContrastPair("onSurfaceVariant/surface", p.onSurfaceVariant, p.surface, TEXT),
    ContrastPair("onSurfaceVariant/surfaceContainer", p.onSurfaceVariant, p.surfaceContainer, TEXT),
    ContrastPair("onSurfaceVariant/surfaceContainerHigh", p.onSurfaceVariant, p.surfaceContainerHigh, TEXT),
    ContrastPair("primary/background (green text)", p.primary, p.background, TEXT),
    ContrastPair("primary/surface (green text)", p.primary, p.surface, TEXT),
    ContrastPair("onPrimary/primary (button text)", p.onPrimary, p.primary, TEXT),
    ContrastPair("onPrimaryContainer/primaryContainer", p.onPrimaryContainer, p.primaryContainer, TEXT),
    ContrastPair("onSecondaryContainer/secondaryContainer", p.onSecondaryContainer, p.secondaryContainer, TEXT),
    ContrastPair("onTertiaryContainer/tertiaryContainer", p.onTertiaryContainer, p.tertiaryContainer, TEXT),
    ContrastPair("onDeep/deep", p.onDeep, p.deep, TEXT),
    ContrastPair("onDeepMuted/deep", p.onDeepMuted, p.deep, TEXT),
    ContrastPair("lime/deep", p.lime, p.deep, TEXT),
    ContrastPair("onLime/lime", p.onLime, p.lime, TEXT),
    ContrastPair("onBubble/bubbleIncoming", p.onBubble, p.bubbleIncoming, TEXT),
    ContrastPair("onBubble/bubbleOutgoing", p.onBubble, p.bubbleOutgoing, TEXT),
    ContrastPair("bubbleMeta/bubbleOutgoing", p.bubbleMeta, p.bubbleOutgoing, TEXT),
    ContrastPair("onSurfaceVariant/bubbleIncoming (meta)", p.onSurfaceVariant, p.bubbleIncoming, TEXT),
    ContrastPair("warning/background", p.warning, p.background, TEXT),
    ContrastPair("error/background", p.error, p.background, TEXT),
    ContrastPair("error/surface", p.error, p.surface, TEXT),
    ContrastPair("onError/error", p.onError, p.error, TEXT),
    ContrastPair("outline/surface (inputs)", p.outline, p.surface, NON_TEXT),
    ContrastPair("outline/background (inputs)", p.outline, p.background, NON_TEXT),
    ContrastPair("brand/surface (icons, FAB)", p.brand, p.surface, NON_TEXT),
    ContrastPair("brand/background (icons)", p.brand, p.background, NON_TEXT),
  )
}
