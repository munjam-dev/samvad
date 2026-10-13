package com.samvad.messenger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.samvad.core.design.ChatThemeColors
import com.samvad.core.design.Radii
import com.samvad.core.design.SamvadPalette
import com.samvad.core.design.SamvadPalettes
import com.samvad.core.design.ThemeMode

/** Compose colours derived from the tested [SamvadPalette]; screens never use hex literals. */
@Immutable
data class SamvadColors(
  val background: Color,
  val surface: Color,
  val surfaceContainer: Color,
  val surfaceContainerHigh: Color,
  val onSurface: Color,
  val onSurfaceVariant: Color,
  val outline: Color,
  val outlineVariant: Color,
  val brand: Color,
  val primary: Color,
  val onPrimary: Color,
  val primaryContainer: Color,
  val onPrimaryContainer: Color,
  val secondaryContainer: Color,
  val onSecondaryContainer: Color,
  val tertiaryContainer: Color,
  val onTertiaryContainer: Color,
  val deep: Color,
  val onDeep: Color,
  val onDeepMuted: Color,
  val lime: Color,
  val onLime: Color,
  val bubbleIncoming: Color,
  val bubbleOutgoing: Color,
  val onBubble: Color,
  val bubbleMeta: Color,
  val success: Color,
  val warning: Color,
  val error: Color,
  val onError: Color,
  val notificationDot: Color,
  val isDark: Boolean,
  /** Date and event chips in a conversation; a chat theme may override them. */
  val chip: Color = surfaceContainer,
  val onChip: Color = onSurfaceVariant,
)

private fun c(argb: Long) = Color(argb)

fun SamvadPalette.toColors() = SamvadColors(
  background = c(background), surface = c(surface), surfaceContainer = c(surfaceContainer),
  surfaceContainerHigh = c(surfaceContainerHigh), onSurface = c(onSurface), onSurfaceVariant = c(onSurfaceVariant),
  outline = c(outline), outlineVariant = c(outlineVariant), brand = c(brand), primary = c(primary),
  onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
  secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
  tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer), deep = c(deep),
  onDeep = c(onDeep), onDeepMuted = c(onDeepMuted), lime = c(lime), onLime = c(onLime),
  bubbleIncoming = c(bubbleIncoming), bubbleOutgoing = c(bubbleOutgoing), onBubble = c(onBubble),
  bubbleMeta = c(bubbleMeta), success = c(success), warning = c(warning), error = c(error), onError = c(onError),
  notificationDot = c(notificationDot), isDark = isDark,
)

private fun SamvadColors.toMaterial(): ColorScheme {
  val base = if (isDark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = brand, onSecondary = onPrimary,
    secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
    tertiary = deep, onTertiary = onDeep,
    tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
    background = background, onBackground = onSurface,
    surface = surface, onSurface = onSurface,
    surfaceVariant = surfaceContainer, onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = surface, surfaceContainerLow = background,
    surfaceContainer = surfaceContainer, surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHigh,
    outline = outline, outlineVariant = outlineVariant,
    error = error, onError = onError,
  )
}

val SamvadShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(Radii.BUTTON.dp),
  large = RoundedCornerShape(Radii.CARD.dp),
  extraLarge = RoundedCornerShape(Radii.SHEET.dp),
)

private val LocalSamvadColors = staticCompositionLocalOf { SamvadPalettes.Light.toColors() }

object SamvadTheme {
  val colors: SamvadColors
    @Composable @ReadOnlyComposable get() = LocalSamvadColors.current
}

/**
 * Applies a chat theme's bubble, metadata and chip colours to a conversation (US-008). Everything
 * else keeps the app theme. Contrast of every theme is verified in ChatThemesTest.
 */
@Composable
fun ProvideChatTheme(theme: ChatThemeColors, content: @Composable () -> Unit) {
  fun c(argb: Long) = Color(argb)
  val colors = SamvadTheme.colors.copy(
    bubbleIncoming = c(theme.bubbleIncoming), bubbleOutgoing = c(theme.bubbleOutgoing),
    onBubble = c(theme.text), bubbleMeta = c(theme.meta),
    chip = c(theme.chip), onChip = c(theme.chipText),
  )
  CompositionLocalProvider(LocalSamvadColors provides colors, content = content)
}

@Composable
fun SamvadTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
  val dark = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
  }
  val palette = when {
    mode == ThemeMode.AMOLED -> SamvadPalettes.Amoled
    dark -> SamvadPalettes.Dark
    else -> SamvadPalettes.Light
  }
  val colors = palette.toColors()
  CompositionLocalProvider(LocalSamvadColors provides colors, LocalReducedMotion provides rememberReducedMotion()) {
    MaterialTheme(
      colorScheme = colors.toMaterial(),
      typography = SamvadTypography,
      shapes = SamvadShapes,
      content = content,
    )
  }
}
