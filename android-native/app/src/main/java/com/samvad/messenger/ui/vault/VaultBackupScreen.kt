package com.samvad.messenger.ui.vault

import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.vault.BackupFailure
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.vault.backup.BackupOperation
import com.samvad.messenger.data.vault.backup.VaultBackupStatus
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

/** Launches Google's consent screen when the view model asks for it and reports the answer back. */
@Composable
internal fun ConsentEffect(vm: DriveAccessViewModel) {
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    vm.onConsentResult(result.resultCode == Activity.RESULT_OK, result.data)
  }
  LaunchedEffect(vm) {
    vm.consent.collect { launcher.launch(IntentSenderRequest.Builder(it).build()) }
  }
}

/**
 * Vault backup settings. Page pattern: Settings / Configuration (status and schedule choices for one feature, with
 * a few actions). Setting it up for the first time is a Form page: see VaultBackupSetupScreen.
 */
@Composable
internal fun VaultBackupScreen(container: AppContainer, onBack: () -> Unit, onSetUp: () -> Unit) {
  VaultGate(container, onBack) {
    BackupContent(container, onBack, onSetUp)
  }
}

@Composable
private fun BackupContent(container: AppContainer, onBack: () -> Unit, onSetUp: () -> Unit) {
  val vm: VaultBackupViewModel = viewModel(factory = VaultBackupViewModel.factory(container))
  ConsentEffect(vm)
  val colors = SamvadTheme.colors
  val status = vm.status.collectAsStateWithLifecycle().value
  val restore = vm.restore.collectAsStateWithLifecycle().value
  val turnOff = vm.turnOff.collectAsStateWithLifecycle().value
  val snackbar = remember { SnackbarHostState() }
  val finished = stringResource(R.string.vbk_backup_done)

  val noteTexts = BackupNoteTexts.rememberAll()
  LaunchedEffect(vm) {
    vm.notes.collect { note ->
      snackbar.showSnackbar(
        when (note) {
          is BackupNote.Text -> noteTexts.text(note.res)
          is BackupNote.Restored -> noteTexts.restored(note.count)
        },
      )
    }
  }
  // Tell the person when a backup they started finishes well.
  var wasBackingUp by remember { mutableStateOf(false) }
  LaunchedEffect(status.operation) {
    val running = status.operation == BackupOperation.BACKING_UP
    if (wasBackingUp && !running && status.enabled && status.lastFailure == null) snackbar.showSnackbar(finished)
    wasBackingUp = running
  }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      VaultTopBar(title = stringResource(R.string.vbk_title), onBack = onBack)
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (!status.enabled) {
          OffSection(onSetUp = onSetUp, onRestore = vm::askRestore, busy = status.operation != BackupOperation.IDLE)
        } else {
          OnSection(vm, status)
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }

  when (restore) {
    RestoreUi.Hidden -> Unit
    is RestoreUi.Asking -> RestoreDialog(restore.error, onRestore = vm::restore, onCancel = vm::cancelRestore)
    RestoreUi.Working -> WorkingDialog(
      if (status.progress != null && status.operation == BackupOperation.RESTORING) {
        stringResource(R.string.vbk_restoring_percent, (status.progress * 100).toInt())
      } else {
        stringResource(R.string.vbk_restoring)
      },
      stringResource(R.string.vbk_restore_working),
      status.progress,
    )
  }
  when (turnOff) {
    TurnOffUi.Hidden -> Unit
    TurnOffUi.Confirm -> AlertDialog(
      onDismissRequest = vm::cancelTurnOff,
      title = { Text(stringResource(R.string.vbk_off_dialog_title)) },
      text = { Text(stringResource(R.string.vbk_off_dialog_body)) },
      confirmButton = {
        TextButton(onClick = { vm.turnOff(deleteFromDrive = true) }) {
          Text(stringResource(R.string.vbk_off_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton(onClick = vm::cancelTurnOff) { Text(stringResource(R.string.cancel)) } },
    )
    is TurnOffUi.Failed -> AlertDialog(
      onDismissRequest = vm::cancelTurnOff,
      title = { Text(stringResource(R.string.vbk_off_anyway_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(turnOff.reason))
          Text(stringResource(R.string.vbk_off_anyway_body))
        }
      },
      confirmButton = {
        TextButton(onClick = { vm.turnOff(deleteFromDrive = false) }) {
          Text(stringResource(R.string.vbk_off_anyway), color = colors.error)
        }
      },
      dismissButton = { TextButton(onClick = vm::cancelTurnOff) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/** Texts resolved in composition so the snackbar collector (a coroutine) can use them. */
private class BackupNoteTexts(private val resolved: Map<Int, String>, private val restoredFormat: (Int) -> String) {
  fun text(res: Int): String = resolved.getValue(res)

  fun restored(count: Int): String = restoredFormat(count)

  companion object {
    @Composable
    fun rememberAll(): BackupNoteTexts {
      val resources = LocalContext.current.resources
      val ids = BackupFailure.entries.map { it.messageRes() } +
        listOf(R.string.vbk_connected, R.string.vbk_restored_nothing, R.string.vbk_off_done, R.string.vbk_off_local_done)
      val map = ids.associateWith { resources.getString(it) }
      return BackupNoteTexts(map) { count -> resources.getQuantityString(R.plurals.vbk_restored, count, count) }
    }
  }
}

@Composable
private fun OffSection(onSetUp: () -> Unit, onRestore: () -> Unit, busy: Boolean) {
  val colors = SamvadTheme.colors
  Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
    Text(stringResource(R.string.vbk_off_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    Text(stringResource(R.string.vbk_off_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    Text(stringResource(R.string.vbk_privacy_note), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    PrimaryButton(stringResource(R.string.vbk_setup), onSetUp, enabled = !busy, modifier = Modifier.padding(top = 4.dp))
  }
  Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.vbk_restore_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
      Text(stringResource(R.string.vbk_restore))
    }
  }
}

@Composable
private fun OnSection(vm: VaultBackupViewModel, status: VaultBackupStatus) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val running = status.operation != BackupOperation.IDLE

  Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
      Text(
        status.account?.let { stringResource(R.string.vbk_account, it) } ?: stringResource(R.string.vbk_account_unknown),
        style = MaterialTheme.typography.titleMedium,
        color = colors.onSurface,
      )
    }
    if (status.lastSuccessAt > 0) {
      val whenText = DateUtils.formatDateTime(
        context,
        status.lastSuccessAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
      )
      Text(stringResource(R.string.vbk_last, whenText), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
      if (status.lastSizeBytes > 0) {
        Text(
          stringResource(
            R.string.vbk_last_detail,
            Formatter.formatShortFileSize(context, status.lastSizeBytes),
            pluralStringResource(R.plurals.vbk_item_count, status.lastItemCount, status.lastItemCount),
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
        )
      }
    } else {
      Text(stringResource(R.string.vbk_never), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
  }

  val failure = status.lastFailure
  if (failure != null && !running) {
    ProblemCard(failure, onConnect = vm::reconnect)
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val label = when (status.operation) {
      BackupOperation.BACKING_UP ->
        status.progress?.let { stringResource(R.string.vbk_backing_up_percent, (it * 100).toInt()) } ?: stringResource(R.string.vbk_backing_up)
      BackupOperation.RESTORING -> stringResource(R.string.vbk_restoring)
      BackupOperation.TURNING_OFF -> stringResource(R.string.vbk_turning_off)
      BackupOperation.IDLE -> stringResource(R.string.vbk_now)
    }
    PrimaryButton(label, onClick = vm::backUpNow, enabled = !running)
    if (running) {
      val p = status.progress
      if (p != null) {
        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceContainer)
      } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.surfaceContainer)
      }
    }
  }

  Column {
    ToggleRow(
      title = stringResource(R.string.vbk_auto),
      body = stringResource(R.string.vbk_auto_body),
      checked = status.autoEnabled,
      onChange = { vm.setSchedule(it, status.wifiOnly, status.chargingOnly) },
    )
    ToggleRow(
      title = stringResource(R.string.vbk_wifi),
      body = null,
      checked = status.wifiOnly,
      enabled = status.autoEnabled,
      onChange = { vm.setSchedule(status.autoEnabled, it, status.chargingOnly) },
    )
    ToggleRow(
      title = stringResource(R.string.vbk_charging),
      body = null,
      checked = status.chargingOnly,
      enabled = status.autoEnabled,
      onChange = { vm.setSchedule(status.autoEnabled, status.wifiOnly, it) },
    )
  }

  Text(stringResource(R.string.vbk_privacy_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

  OutlinedButton(onClick = vm::askRestore, enabled = !running, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
    Text(stringResource(R.string.vbk_restore))
  }
  TextButton(onClick = vm::askTurnOff, enabled = !running, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
    Text(stringResource(R.string.vbk_turn_off), color = if (running) colors.onSurfaceVariant else colors.error)
  }
}

@Composable
private fun ProblemCard(failure: BackupFailure, onConnect: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier.fillMaxWidth().background(colors.tertiaryContainer, RoundedCornerShape(14.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
      Text(stringResource(R.string.vbk_problem_title), style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
    }
    Text(stringResource(failure.messageRes()), style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
    if (failure == BackupFailure.AUTH_REVOKED) {
      TextButton(onClick = onConnect, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text(stringResource(R.string.vbk_connect_again), color = colors.onTertiaryContainer)
      }
    }
  }
}

@Composable
private fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 56.dp)
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
      if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    Switch(
      checked = checked,
      onCheckedChange = null,
      enabled = enabled,
      colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
    )
  }
}

@Composable
private fun RestoreDialog(error: Int?, onRestore: (String) -> Unit, onCancel: () -> Unit) {
  var secret by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(stringResource(R.string.vbk_restore_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vbk_restore_body))
        OutlinedTextField(
          value = secret,
          onValueChange = { secret = it.take(300) },
          label = { Text(stringResource(R.string.vbk_restore_field)) },
          isError = error != null,
          supportingText = error?.let { { Text(stringResource(it)) } },
          singleLine = false,
          minLines = 2,
          // No suggestions or learning of the recovery code by the keyboard.
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onRestore(secret) }, enabled = secret.isNotBlank()) { Text(stringResource(R.string.vbk_restore_go)) }
    },
    dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
private fun WorkingDialog(title: String, body: String, progress: Float?) {
  AlertDialog(
    onDismissRequest = {},
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(body)
        if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        else CircularProgressIndicator(Modifier.size(32.dp))
      }
    },
    confirmButton = {},
  )
}

/** Used by the setup screen for its password fields. */
internal val BackupPasswordTransformation = PasswordVisualTransformation()
