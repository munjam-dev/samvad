package com.samvad.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samvad.messenger.ui.theme.JakartaSans
import com.samvad.messenger.ui.theme.SamvadColors
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlin.math.abs

/** Tonal avatar pairs; the pair is chosen deterministically from the user ID. */
private fun tones(colors: SamvadColors): List<Pair<Color, Color>> = listOf(
  colors.primaryContainer to colors.onPrimaryContainer,
  colors.tertiaryContainer to colors.onTertiaryContainer,
  colors.secondaryContainer to colors.onSecondaryContainer,
  colors.deep to colors.lime,
)

@Composable
fun Avatar(
  initials: String,
  seed: String,
  size: Dp = 50.dp,
  online: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val (bg, fg) = tones(colors)[abs(seed.hashCode()) % 4]
  Box(modifier.size(size)) {
    Box(
      Modifier.size(size).background(bg, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        initials,
        color = fg,
        style = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.32f).sp),
        maxLines = 1,
      )
    }
    if (online) {
      Box(
        Modifier
          .align(Alignment.BottomEnd)
          .size(size * 0.26f)
          .border(2.5.dp, colors.background, CircleShape)
          .padding(2.5.dp)
          .background(colors.brand, CircleShape),
      )
    }
  }
}

/** Avatar inside a story ring: brand green when unread, outline when seen. */
@Composable
fun StoryAvatar(initials: String, seed: String, unread: Boolean, size: Dp = 60.dp) {
  val colors = SamvadTheme.colors
  Box(
    Modifier
      .size(size)
      .border(if (unread) 2.dp else 1.5.dp, if (unread) colors.brand else colors.outlineVariant, CircleShape)
      .padding(3.5.dp),
    contentAlignment = Alignment.Center,
  ) {
    Avatar(initials, seed, size = size - 7.dp)
  }
}
