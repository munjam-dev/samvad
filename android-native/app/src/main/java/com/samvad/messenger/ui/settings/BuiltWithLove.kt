package com.samvad.messenger.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.JakartaSans
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.SamvadTheme

private const val COMPANY = "Arlo Technologies"
private val Saffron = Color(0xFFFF9933)
private val Leaf = Color(0xFF138808)

/**
 * The closing line of Settings: a tricolour accent, a softly beating heart and the maker's name. It fades and rises into
 * view once; the heart only beats when the system allows motion.
 */
@Composable
fun BuiltWithLove(modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  val enter = remember { Animatable(if (reduced) 1f else 0f) }
  LaunchedEffect(Unit) { if (!reduced) enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
  val beat = if (reduced) 1f else rememberInfiniteTransition(label = "beat").animateFloat(
    1f, 1.22f, infiniteRepeatable(tween(780, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "heart",
  ).value
  val line = stringResource(R.string.footer_made_for)
  val by = stringResource(R.string.footer_by, COMPANY)

  Column(
    modifier
      .fillMaxWidth()
      .padding(top = 8.dp, bottom = 28.dp)
      .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 24.dp.toPx() }
      .semantics(mergeDescendants = true) { contentDescription = "$line. $by" },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Bar(Saffron)
      Bar(colors.onSurfaceVariant.copy(alpha = 0.35f))
      Bar(Leaf)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        line,
        style = TextStyle(
          fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 0.sp,
          brush = Brush.horizontalGradient(listOf(Saffron, colors.brand, Leaf)),
        ),
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f, fill = false),
      )
      Icon(
        Icons.Filled.Favorite, null, tint = Color(0xFFE5484D),
        modifier = Modifier.size(18.dp).scale(beat),
      )
    }
    Text(
      by,
      style = MaterialTheme.typography.labelMedium.copy(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
      color = colors.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun Bar(color: Color) {
  Box(Modifier.width(26.dp).height(4.dp).background(color, RoundedCornerShape(2.dp)))
}
