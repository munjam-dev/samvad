package com.samvad.messenger.ui.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Covers the whole app while it is locked. It swallows every touch so nothing underneath can be
 * operated, and asks for the unlock as soon as it appears.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit, onLeave: () -> Unit) {
  val colors = SamvadTheme.colors
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  LaunchedEffect(Unit) {
    focus.clearFocus(force = true)
    keyboard?.hide()
    onUnlock()
  }
  BackHandler(onBack = onLeave)
  Box(
    Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.background)))
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
      }
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(24.dp),
  ) {
    Column(
      Modifier.align(Alignment.Center),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SamvadMark(88.dp)
      Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
      Text(
        stringResource(R.string.lock_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    PrimaryButton(stringResource(R.string.lock_unlock), onUnlock, Modifier.align(Alignment.BottomCenter))
  }
}
