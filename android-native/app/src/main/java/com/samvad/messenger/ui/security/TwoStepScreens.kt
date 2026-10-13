package com.samvad.messenger.ui.security

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme

private fun dateTime(context: Context, ms: Long): String =
  DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
private fun ShieldBadge() {
  val colors = SamvadTheme.colors
  Box(Modifier.size(72.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
    Icon(Icons.Outlined.VerifiedUser, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(36.dp))
  }
}

@Composable
private fun pinErrorText(error: PinError, attemptsLeft: Int?, lockedUntilMs: Long?, resetAt: Long?): String {
  val context = LocalContext.current
  return when (error) {
    PinError.WRONG -> if (attemptsLeft != null && attemptsLeft > 0) {
      pluralStringResource(R.plurals.two_step_attempts_left, attemptsLeft, attemptsLeft)
    } else {
      stringResource(R.string.two_step_wrong)
    }
    PinError.LOCKED -> lockedUntilMs?.let { stringResource(R.string.two_step_locked, dateTime(context, it)) }
      ?: stringResource(R.string.two_step_locked_plain)
    PinError.NETWORK -> stringResource(R.string.two_step_network)
    PinError.TOO_SIMPLE -> stringResource(R.string.two_step_too_simple)
    PinError.MISMATCH -> stringResource(R.string.two_step_mismatch)
    PinError.RESET_CANCELLED -> stringResource(R.string.two_step_reset_cancelled)
    PinError.RESET_NOT_READY -> resetAt?.let { stringResource(R.string.two_step_reset_pending, dateTime(context, it)) }
      ?: stringResource(R.string.two_step_network)
  }
}

/** Shown after an SMS sign-in when the account has two-step on. Nothing else works until the PIN is right. */
@Composable
fun TwoStepVerifyScreen(container: AppContainer, phone: String, onSignOut: () -> Unit) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val vm: TwoStepVerifyViewModel = viewModel(factory = TwoStepVerifyViewModel.factory(container.twoStep!!))
  val state = vm.state.collectAsStateWithLifecycle().value
  var confirmReset by remember { mutableStateOf(false) }
  val now = System.currentTimeMillis()

  Column(
    Modifier
      .fillMaxSize()
      .background(colors.background)
      .systemBarsPadding()
      .imePadding()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp, vertical = 32.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    ShieldBadge()
    Text(stringResource(R.string.two_step_verify_title), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
    Text(stringResource(R.string.two_step_verify_body, phone), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    PinInput(
      value = state.pin,
      onValueChange = vm::onPin,
      label = stringResource(R.string.two_step_pin_label),
      error = state.error != null,
      errorKey = state.errorKey,
      enabled = !state.busy,
    )
    AnimatedContent(state.busy to state.error, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pinStatus") { (busy, error) ->
      when {
        busy -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.brand, strokeWidth = 2.5.dp)
        error != null -> Text(
          pinErrorText(error, state.attemptsLeft, state.lockedUntilMs, state.resetAvailableAtMs),
          color = colors.error,
          style = MaterialTheme.typography.bodyMedium,
        )
        else -> Spacer(Modifier.height(24.dp))
      }
    }

    val resetAt = state.resetAvailableAtMs
    Column(
      Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(16.dp)).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      when {
        resetAt == null -> {
          Text(stringResource(R.string.two_step_forgot_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
          QuietButton(stringResource(R.string.two_step_forgot), { confirmReset = true }, enabled = !state.busy)
        }
        resetAt > now -> Text(
          stringResource(R.string.two_step_reset_pending, dateTime(context, resetAt)),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
        )
        else -> {
          Text(stringResource(R.string.two_step_reset_ready), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
          PrimaryButton(stringResource(R.string.two_step_reset_now), vm::completeReset, loading = state.busy)
        }
      }
    }
    QuietButton(stringResource(R.string.two_step_sign_out), onSignOut)
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text(stringResource(R.string.two_step_forgot_title)) },
      text = { Text(stringResource(R.string.two_step_forgot_body)) },
      confirmButton = { TextButton({ confirmReset = false; vm.requestReset() }) { Text(stringResource(R.string.two_step_request_reset)) } },
      dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/** Settings → Account → Two-step verification. */
@Composable
fun TwoStepSettingsScreen(container: AppContainer, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: TwoStepSettingsViewModel = viewModel(factory = TwoStepSettingsViewModel.factory(container.twoStep!!))
  val state = vm.state.collectAsStateWithLifecycle().value
  val snackbar = remember { SnackbarHostState() }
  val doneText = state.done?.let {
    stringResource(
      when (it) {
        TwoStepMode.CREATE -> R.string.two_step_on_done
        TwoStepMode.CONFIRM -> R.string.two_step_changed
        else -> R.string.two_step_off_done
      },
    )
  }
  LaunchedEffect(doneText) {
    if (doneText != null) {
      vm.consumeDone()
      snackbar.showSnackbar(doneText)
    }
  }
  val back = { if (state.mode == TwoStepMode.OVERVIEW) onBack() else vm.back() }
  androidx.activity.compose.BackHandler(enabled = state.mode != TwoStepMode.OVERVIEW) { vm.back() }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize().imePadding()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.two_step_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
      }
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        when {
          state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = colors.brand)
          state.loadFailed -> {
            Text(stringResource(R.string.two_step_network), color = colors.error, style = MaterialTheme.typography.bodyMedium)
            QuietButton(stringResource(R.string.retry), { vm.load() })
          }
          state.mode == TwoStepMode.OVERVIEW -> {
            ShieldBadge()
            Text(
              stringResource(if (state.enabled) R.string.two_step_settings_on else R.string.two_step_settings_off),
              style = MaterialTheme.typography.bodyLarge,
              color = colors.onSurfaceVariant,
            )
            if (state.enabled) {
              PrimaryButton(stringResource(R.string.two_step_change), vm::startCreate)
              QuietButton(stringResource(R.string.two_step_turn_off), vm::startDisable)
            } else {
              PrimaryButton(stringResource(R.string.two_step_turn_on), vm::startCreate)
            }
          }
          else -> {
            val title = when (state.mode) {
              TwoStepMode.CREATE -> R.string.two_step_create
              TwoStepMode.CONFIRM -> R.string.two_step_confirm
              else -> R.string.two_step_disable
            }
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
            if (state.mode == TwoStepMode.CREATE) {
              Text(stringResource(R.string.two_step_create_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            // A new field per step, so focus and the shake start fresh.
            androidx.compose.runtime.key(state.mode) {
              PinInput(
                value = state.pin,
                onValueChange = vm::onPin,
                label = stringResource(R.string.two_step_pin_label),
                error = state.error != null,
                errorKey = state.errorKey,
                enabled = !state.busy,
              )
            }
            when {
              state.busy -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.brand, strokeWidth = 2.5.dp)
              state.error != null -> Text(pinErrorText(state.error, null, null, null), color = colors.error, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
  }
}
