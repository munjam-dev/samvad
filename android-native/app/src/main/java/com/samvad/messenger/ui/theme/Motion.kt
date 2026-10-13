package com.samvad.messenger.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/**
 * Motion tokens. Springs give the physical, responsive feel; everything collapses to instant
 * changes when the user has turned animations off (Settings › Accessibility › Remove animations).
 */
object Motion {
  /** Default UI spring: quick and settled, no visible bounce. */
  fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

  /** Playful spring for small, celebratory moments (badges, reactions, the FAB). */
  fun <T> lively(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)

  /** Offsets use their own spring type. */
  val offsetSpring = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

  const val STAGGER_MS = 35L
  const val MAX_STAGGERED = 12
}

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
  val context = LocalContext.current
  return remember {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }
}

/** Instant when reduced motion is on, otherwise [spec]. */
@Composable
fun <T> motionSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
  if (LocalReducedMotion.current) snap() else spec

/** Gentle press feedback: scales down slightly while pressed, springs back on release. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
  val pressed by interaction.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) pressedScale else 1f,
    animationSpec = motionSpec(Motion.lively()),
    label = "press",
  )
  graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Entrance for list items: fades in and rises 16dp, staggered by [index] (capped so long
 * lists don't wait). Runs once per item; scrolling back never replays it.
 */
fun Modifier.enterOnce(index: Int, seen: MutableSet<Any>, key: Any): Modifier = composed {
  val reduced = LocalReducedMotion.current
  val first = remember(key) { reduced.not() && seen.add(key) }
  val progress = remember(key) { Animatable(if (first) 0f else 1f) }
  LaunchedEffect(key) {
    if (first) {
      delay(Motion.STAGGER_MS * index.coerceAtMost(Motion.MAX_STAGGERED))
      progress.animateTo(1f, tween(durationMillis = 320))
    }
  }
  graphicsLayer {
    alpha = progress.value
    translationY = (1f - progress.value) * 16f * density
  }
}
