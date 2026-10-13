package com.samvad.messenger.ui.security

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samvad.core.security.TwoStepPin
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.SamvadTheme
import com.samvad.messenger.ui.theme.motionSpec

/**
 * Six masked boxes for a two-step PIN. Digits only; the field never shows the PIN itself.
 * [errorKey] changes on each rejection to replay the shake.
 */
@Composable
fun PinInput(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  error: Boolean = false,
  errorKey: Int = 0,
  enabled: Boolean = true,
) {
  val colors = SamvadTheme.colors
  val focus = remember { FocusRequester() }
  val reduced = LocalReducedMotion.current
  val shake = remember { Animatable(0f) }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  LaunchedEffect(errorKey) {
    if (errorKey > 0 && !reduced) for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, tween(45))
  }
  BasicTextField(
    value = value,
    onValueChange = { raw -> onValueChange(raw.filter { it in '0'..'9' }.take(TwoStepPin.LENGTH)) },
    enabled = enabled,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    modifier = modifier
      .focusRequester(focus)
      .graphicsLayer { translationX = shake.value * density }
      .semantics { contentDescription = label },
    decorationBox = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(TwoStepPin.LENGTH) { i ->
          val filled = i < value.length
          val active = i == value.length
          val edge by animateColorAsState(
            when {
              error -> colors.error
              active -> colors.primary
              else -> colors.outline
            },
            motionSpec(Motion.standard()),
            label = "pinEdge",
          )
          val edgeWidth by animateDpAsState(if (active) 2.dp else 1.dp, motionSpec(Motion.standard()), label = "pinWidth")
          val dot by animateDpAsState(if (filled) 14.dp else 0.dp, motionSpec(Motion.lively()), label = "pinDot")
          Box(
            Modifier
              .weight(1f)
              .height(60.dp)
              .background(colors.surface, RoundedCornerShape(14.dp))
              .border(edgeWidth, edge, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Box(Modifier.size(dot).background(colors.onSurface, CircleShape))
          }
        }
      }
    },
  )
}
