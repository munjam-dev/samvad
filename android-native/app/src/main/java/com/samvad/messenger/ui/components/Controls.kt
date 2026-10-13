package com.samvad.messenger.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samvad.core.design.Radii
import com.samvad.messenger.ui.theme.SamvadTheme

@Composable
fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  val colors = SamvadTheme.colors
  val interaction = remember { MutableInteractionSource() }
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    interactionSource = interaction,
    modifier = modifier.fillMaxWidth().height(56.dp).pressScale(interaction),
    shape = RoundedCornerShape(Radii.BUTTON.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = colors.primary,
      contentColor = colors.onPrimary,
      disabledContainerColor = colors.surfaceContainer,
      disabledContentColor = colors.onSurfaceVariant,
    ),
  ) {
    AnimatedContent(loading, label = "button") { busy ->
      if (busy) {
        CircularProgressIndicator(Modifier.size(22.dp), color = colors.onPrimary, strokeWidth = 2.5.dp)
      } else {
        Text(text, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  TextButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.defaultMinSize(minHeight = 48.dp),
    contentPadding = PaddingValues(horizontal = 12.dp),
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelLarge,
      color = if (enabled) SamvadTheme.colors.primary else SamvadTheme.colors.onSurfaceVariant,
    )
  }
}

@Composable
fun SamvadTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  supportingText: String? = null,
  isError: Boolean = false,
  keyboardType: KeyboardType = KeyboardType.Text,
  singleLine: Boolean = true,
  maxLength: Int? = null,
) {
  val colors = SamvadTheme.colors
  OutlinedTextField(
    value = value,
    onValueChange = { v -> if (maxLength == null || v.length <= maxLength) onValueChange(v) },
    label = { Text(label) },
    placeholder = placeholder?.let { { Text(it) } },
    supportingText = supportingText?.let { { Text(it) } },
    isError = isError,
    singleLine = singleLine,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    shape = RoundedCornerShape(Radii.FIELD.dp),
    textStyle = MaterialTheme.typography.bodyLarge,
    colors = OutlinedTextFieldDefaults.colors(
      focusedBorderColor = colors.primary,
      unfocusedBorderColor = colors.outline,
      focusedLabelColor = colors.primary,
      cursorColor = colors.primary,
      focusedContainerColor = colors.surface,
      unfocusedContainerColor = colors.surface,
    ),
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
fun UnreadBadge(count: Int, contentDescription: String, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  val appear = remember { Animatable(if (reduced) 1f else 0.4f) }
  LaunchedEffect(Unit) { appear.animateTo(1f, Motion.lively()) }
  Box(
    modifier
      .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
      .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
      .background(colors.primary, RoundedCornerShape(11.dp))
      .padding(horizontal = 7.dp)
      .semantics { this.contentDescription = contentDescription },
    contentAlignment = Alignment.Center,
  ) {
    AnimatedContent(
      targetState = count,
      transitionSpec = {
        (slideInVertically(Motion.offsetSpring) { it } + fadeIn()) togetherWith (slideOutVertically(Motion.offsetSpring) { -it } + fadeOut())
      },
      label = "badge",
    ) { n ->
      Text(if (n > 99) "99+" else n.toString(), color = colors.onPrimary, style = MaterialTheme.typography.labelMedium)
    }
  }
}

/** Empty, first-use and coming-soon states: icon tile, title, body, optional action. */
@Composable
fun EmptyState(
  icon: ImageVector,
  title: String,
  body: String,
  modifier: Modifier = Modifier,
  action: String? = null,
  onAction: () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  Column(
    modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, Motion.lively()) }
    Box(
      Modifier.size(72.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.background(colors.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(32.dp))
    }
    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, textAlign = TextAlign.Center)
    Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    if (action != null) {
      PrimaryButton(action, onAction, Modifier.padding(top = 8.dp))
    }
  }
}

/** Inline status banner for offline mode and recoverable errors. */
@Composable
fun StatusBanner(
  visible: Boolean,
  text: String,
  isError: Boolean = false,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  AnimatedVisibility(visible) {
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
        .background(if (isError) colors.tertiaryContainer else colors.surfaceContainer, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudOff,
        contentDescription = null,
        tint = if (isError) colors.onTertiaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) colors.onTertiaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (actionLabel != null) QuietButton(actionLabel, onAction)
    }
  }
}
