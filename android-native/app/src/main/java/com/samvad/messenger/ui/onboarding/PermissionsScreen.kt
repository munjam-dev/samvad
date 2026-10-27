package com.samvad.messenger.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.samvad.core.permissions.AllowAction
import com.samvad.core.permissions.OnboardingStep
import com.samvad.core.permissions.PermissionOnboarding
import com.samvad.core.permissions.StepAnswer
import com.samvad.messenger.R
import com.samvad.messenger.data.PermissionOnboardingStore
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

/**
 * Page pattern: form (single-question wizard). Shown once after sign-in and profile setup, before the main screen.
 * Each step explains first, then asks. "Not now" is always there and the answer is remembered; nothing blocks the app.
 * Microphone and camera are asked later, when a call or a photo needs them.
 */
@Composable
fun PermissionsScreen(store: PermissionOnboardingStore, onDone: () -> Unit) {
  val context = LocalContext.current
  val colors = SamvadTheme.colors
  val steps = remember { PermissionOnboarding.pending(store.state()) }
  var index by remember { mutableIntStateOf(0) }
  var waitingForSettings by remember { mutableStateOf(false) }
  var blocked by remember { mutableStateOf(false) }

  fun advance() {
    blocked = false
    waitingForSettings = false
    if (index + 1 >= steps.size) onDone() else index++
  }

  fun finish(step: OnboardingStep, granted: Boolean) {
    store.markAsked(step)
    store.answer(step, PermissionOnboarding.answerAfter(granted))
    advance()
  }

  if (steps.isEmpty()) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
    return
  }
  val step = steps[index]

  val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) finish(step, true) else {
      store.markAsked(step)
      // The system dialog may not appear again: from here "Allow" opens settings instead, and "Not now" is still available.
      blocked = true
    }
  }
  val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    finish(step, store.isGranted(OnboardingStep.BATTERY))
  }

  // Back from the system settings: carry on if the person turned the permission on there.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, step, waitingForSettings) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && waitingForSettings) {
        waitingForSettings = false
        if (store.isGranted(step)) finish(step, true)
      }
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }

  fun openSettings() {
    waitingForSettings = true
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
  }

  fun allow() {
    if (step == OnboardingStep.BATTERY) {
      battery.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
      return
    }
    val permission = if (step == OnboardingStep.CONTACTS) Manifest.permission.READ_CONTACTS else Manifest.permission.POST_NOTIFICATIONS
    val activity = context.findActivity()
    val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true
    when (PermissionOnboarding.allowAction(store.isGranted(step), store.wasAsked(step), rationale)) {
      AllowAction.REQUEST -> request.launch(permission)
      AllowAction.OPEN_SETTINGS -> openSettings()
      AllowAction.NONE -> finish(step, true)
    }
  }

  val (icon, title, body) = when (step) {
    OnboardingStep.CONTACTS -> Triple(Icons.Outlined.Contacts, R.string.perm_contacts_title, R.string.perm_contacts_body)
    OnboardingStep.NOTIFICATIONS -> Triple(Icons.Outlined.Notifications, R.string.perm_notifications_title, R.string.perm_notifications_body)
    OnboardingStep.BATTERY -> Triple(Icons.Outlined.BatteryChargingFull, R.string.perm_battery_title, R.string.perm_battery_body)
  }

  Column(
    Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.perm_step, index + 1, steps.size), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Icon(icon, null, tint = colors.primary, modifier = Modifier.size(40.dp))
    Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    if (blocked) Text(stringResource(R.string.perm_blocked_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    Spacer(Modifier.height(12.dp))
    PrimaryButton(
      stringResource(if (blocked) R.string.perm_open_settings else R.string.perm_allow),
      { if (blocked) openSettings() else allow() },
      modifier = Modifier.fillMaxWidth(),
    )
    QuietButton(stringResource(R.string.perm_not_now), { finish(step, false) }, modifier = Modifier.fillMaxWidth().padding(top = 0.dp).align(Alignment.CenterHorizontally))
  }
}
