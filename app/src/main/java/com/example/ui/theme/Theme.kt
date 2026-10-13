package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = SoftBlueTint,
    onPrimaryContainer = DeepCobaltBlue,
    secondary = DeepCobaltBlue,
    onSecondary = Color.White,
    secondaryContainer = SanrakshaSurfaceVariantLight,
    onSecondaryContainer = DeepCobaltBlue,
    tertiary = LightBlueAccent,
    onTertiary = Color.White,
    background = SanrakshaBackgroundLight,
    onBackground = SanrakshaTextPrimaryLight,
    surface = SanrakshaBackgroundLight,
    onSurface = SanrakshaTextPrimaryLight,
    surfaceVariant = SanrakshaSurfaceLight,
    onSurfaceVariant = SanrakshaTextSecondaryLight,
    outline = SanrakshaBorderLight,
    error = ErrorRed,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = LightBlueAccent,
    onPrimary = Color.White,
    primaryContainer = SanrakshaSurfaceVariantDark,
    onPrimaryContainer = Color.White,
    secondary = ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = SanrakshaSurfaceDark,
    onSecondaryContainer = SanrakshaTextPrimaryDark,
    tertiary = SoftBlueTint,
    onTertiary = Color.Black,
    background = SanrakshaBackgroundDark,
    onBackground = SanrakshaTextPrimaryDark,
    surface = SanrakshaBackgroundDark,
    onSurface = SanrakshaTextPrimaryDark,
    surfaceVariant = SanrakshaSurfaceDark,
    onSurfaceVariant = SanrakshaTextSecondaryDark,
    outline = SanrakshaBorderDark,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun SanrakshaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
