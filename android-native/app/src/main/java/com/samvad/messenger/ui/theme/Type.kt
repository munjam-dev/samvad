package com.samvad.messenger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.samvad.messenger.R

/** Plus Jakarta Sans (SIL OFL 1.1, bundled); Devanagari falls back to the system Noto family. */
val JakartaSans = FontFamily(
  Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
  Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
  Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
  Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
  Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
  fontFamily = JakartaSans,
  fontWeight = weight,
  fontSize = size.sp,
  lineHeight = line.sp,
  letterSpacing = tracking.em,
)

/** Type roles from spec rev 4 §3.3, all in sp so they follow the system font scale. */
val SamvadTypography = Typography(
  displaySmall = style(32, 38, FontWeight.ExtraBold, -0.035),
  headlineMedium = style(28, 32, FontWeight.ExtraBold, -0.035),
  headlineSmall = style(24, 30, FontWeight.ExtraBold, -0.03),
  titleLarge = style(20, 26, FontWeight.Bold, -0.02),
  titleMedium = style(16, 22, FontWeight.Bold, -0.01),
  titleSmall = style(14, 20, FontWeight.SemiBold),
  bodyLarge = style(16, 22, FontWeight.Normal),
  bodyMedium = style(14, 20, FontWeight.Normal),
  bodySmall = style(13, 18, FontWeight.Normal),
  labelLarge = style(15, 20, FontWeight.SemiBold),
  labelMedium = style(12, 16, FontWeight.SemiBold, 0.02),
  labelSmall = style(11, 14, FontWeight.Medium),
)
