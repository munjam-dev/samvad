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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
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

private enum class Phase { INTRO, FINAL }

/**
 * Page pattern: form (single intro, then system dialogs back to back). Shown once after sign-in and profile setup.
 * One intro screen explains Contacts and Notifications; Continue fires both system dialogs in one go. Denial never
 * blocks or loops. The optional battery card comes last and is never auto-fired. Camera, microphone and Bluetooth are
 * asked later, in context.
 */
@Composable
fun PermissionsScreen(store: PermissionOnboardingStore, onDone: () -> Unit) {
  val context = LocalContext.current
  val colors = SamvadTheme.colors
  val pending = remember { PermissionOnboarding.pending(store.state()) }
  val auto = remember { PermissionOnboarding.autoSteps(pending) }
  val showBattery = remember {
    PermissionOnboarding.showBatteryCard(pending, android.os.Build.VERSION.SDK_INT, store.isGranted(OnboardingStep.BATTERY))
  }
  var phase by rememberSaveable { mutableStateOf(if (auto.isEmpty()) Phase.FINAL else Phase.INTRO) }
  var deniedForever by rememberSaveable { mutableStateOf(false) }

  fun permissionOf(step: OnboardingStep) =
    if (step == OnboardingStep.CONTACTS) Manifest.permission.READ_CONTACTS else Manifest.permission.POST_NOTIFICATIONS

  fun rationale(step: OnboardingStep): Boolean {
    val activity = context.findActivity() ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permissionOf(step))
  }

  fun finishRun() {
    if (!showBattery && !deniedForever) onDone() else phase = Phase.FINAL
  }

  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    auto.forEach { step ->
      val granted = store.isGranted(step)
      if (result.containsKey(permissionOf(step)) || granted) {
        store.markAsked(step)
        store.answer(step, PermissionOnboarding.answerAfter(granted))
      }
    }
    finishRun()
  }
  val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    store.markAsked(OnboardingStep.BATTERY)
    store.answer(OnboardingStep.BATTERY, PermissionOnboarding.answerAfter(store.isGranted(OnboardingStep.BATTERY)))
    onDone()
  }

  fun cont() {
    val toAsk = mutableListOf<String>()
    auto.forEach { step ->
      when {
        store.isGranted(step) -> store.answer(step, StepAnswer.ALLOWED)
        PermissionOnboarding.canRequest(store.wasAsked(step), rationale(step)) -> toAsk += permissionOf(step)
        else -> {
          deniedForever = true
          store.answer(step, StepAnswer.NOT_NOW)
        }
      }
    }
    if (toAsk.isEmpty()) finishRun() else launcher.launch(toAsk.toTypedArray())
  }

  fun notNow() {
    auto.forEach { store.answer(it, StepAnswer.NOT_NOW) }
    finishRun()
  }

  fun openSettings() {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
  }

  if (pending.isEmpty()) {
    LaunchedEffect(Unit) { onDone() }
    return
  }

  Column(
    Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (phase == Phase.INTRO) {
      Spacer(Modifier.height(16.dp))
      Box(Modifier.size(112.dp).clip(CircleShape).background(colors.primaryContainer).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Forum, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(56.dp))
      }
      Text(stringResource(R.string.perm_intro_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, textAlign = TextAlign.Center)
      Spacer(Modifier.height(8.dp))
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (OnboardingStep.CONTACTS in auto) Benefit(Icons.Outlined.Contacts, R.string.perm_contacts_title, R.string.perm_contacts_body)
        if (OnboardingStep.NOTIFICATIONS in auto) Benefit(Icons.Outlined.Notifications, R.string.perm_notifications_title, R.string.perm_notifications_body)
      }
      Text(stringResource(R.string.perm_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
      Spacer(Modifier.height(8.dp))
      PrimaryButton(stringResource(R.string.perm_continue), { cont() }, modifier = Modifier.fillMaxWidth())
      QuietButton(stringResource(R.string.perm_not_now), { notNow() }, modifier = Modifier.fillMaxWidth())
    } else {
      if (showBattery) {
        Spacer(Modifier.height(16.dp))
        Benefit(Icons.Outlined.BatteryChargingFull, R.string.perm_battery_title, R.string.perm_battery_body)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
          stringResource(R.string.perm_allow),
          { battery.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
          modifier = Modifier.fillMaxWidth(),
        )
        QuietButton(stringResource(R.string.perm_not_now), {
          store.answer(OnboardingStep.BATTERY, StepAnswer.NOT_NOW)
          onDone()
        }, modifier = Modifier.fillMaxWidth())
      }
      if (deniedForever) {
        Text(stringResource(R.string.perm_denied_forever), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        QuietButton(stringResource(R.string.perm_open_settings), { openSettings() }, modifier = Modifier.fillMaxWidth())
      }
      if (!showBattery) PrimaryButton(stringResource(R.string.perm_continue), { onDone() }, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun Benefit(icon: ImageVector, title: Int, body: Int) {
  val colors = SamvadTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, null, tint = colors.primary, modifier = Modifier.size(32.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
      Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
  }
}
