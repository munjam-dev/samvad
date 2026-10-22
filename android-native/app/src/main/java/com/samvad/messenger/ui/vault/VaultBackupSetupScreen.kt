package com.samvad.messenger.ui.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.vault.ArchiveKdf
import com.samvad.core.vault.BackupPassphrase
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * First-time backup setup. Page pattern: Form (a short stepped flow: explain and consent, choose the protection,
 * save the recovery secret, prove it was saved). Nothing is stored and nothing is uploaded until the last step.
 */
@Composable
internal fun VaultBackupSetupScreen(container: AppContainer, onBack: () -> Unit, onDone: () -> Unit) {
  VaultGate(container, onBack) {
    SetupContent(container, onBack, onDone)
  }
}

@Composable
private fun SetupContent(container: AppContainer, onBack: () -> Unit, onDone: () -> Unit) {
  val vm: VaultBackupSetupViewModel = viewModel(factory = VaultBackupSetupViewModel.factory(container))
  ConsentEffect(vm)
  val state = vm.state.collectAsStateWithLifecycle().value
  LaunchedEffect(vm) { vm.done.collect { onDone() } }
  val goBack: () -> Unit = { if (!vm.back()) onBack() }
  BackHandler(enabled = state.step != SetupStep.EXPLAIN) { goBack() }

  Column(Modifier.fillMaxSize()) {
    VaultTopBar(title = stringResource(R.string.vbk_setup_title), onBack = goBack)
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when {
        state.unavailable -> EmptyState(
          Icons.Outlined.CloudOff,
          stringResource(R.string.vbk_unavailable_title),
          stringResource(R.string.vbk_unavailable_body),
        )
        state.step == SetupStep.EXPLAIN -> ExplainStep(state, vm::allowAccess)
        state.step == SetupStep.METHOD -> MethodStep(state, vm::chooseMethod, vm::continueFromMethod)
        state.step == SetupStep.SECRET && state.method == ArchiveKdf.RECOVERY_KEY -> CodeStep(state, vm::codeSaved)
        state.step == SetupStep.SECRET -> PassphraseStep(state, vm::enableWithPassphrase)
        state.step == SetupStep.CONFIRM -> ConfirmStep(state, vm::confirmCode, vm::showCodeAgain)
        else -> WorkingStep(passphrase = state.method == ArchiveKdf.PASSPHRASE_PBKDF2)
      }
    }
  }
}

@Composable
private fun StepTitle(text: String) {
  Text(text, style = MaterialTheme.typography.titleLarge, color = SamvadTheme.colors.onSurface)
}

@Composable
private fun ErrorText(res: Int?) {
  if (res != null) Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = SamvadTheme.colors.error)
}

@Composable
private fun Point(icon: ImageVector, text: String) {
  val colors = SamvadTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun ExplainStep(state: SetupState, onAllow: () -> Unit) {
  StepTitle(stringResource(R.string.vbk_s1_title))
  Point(Icons.Outlined.CloudUpload, stringResource(R.string.vbk_s1_uploads))
  Point(Icons.Outlined.Lock, stringResource(R.string.vbk_s1_encrypted))
  Point(Icons.Outlined.FolderOpen, stringResource(R.string.vbk_s1_where))
  Point(Icons.Outlined.Key, stringResource(R.string.vbk_s1_lose))
  Point(Icons.Outlined.DeleteOutline, stringResource(R.string.vbk_s1_off))
  ErrorText(state.error)
  PrimaryButton(stringResource(R.string.vbk_s1_allow), onAllow, loading = state.working, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MethodStep(state: SetupState, onChoose: (ArchiveKdf) -> Unit, onContinue: () -> Unit) {
  StepTitle(stringResource(R.string.vbk_s2_title))
  MethodOption(
    stringResource(R.string.vbk_s2_code),
    stringResource(R.string.vbk_s2_code_body),
    selected = state.method == ArchiveKdf.RECOVERY_KEY,
    onSelect = { onChoose(ArchiveKdf.RECOVERY_KEY) },
  )
  MethodOption(
    stringResource(R.string.vbk_s2_pass),
    stringResource(R.string.vbk_s2_pass_body),
    selected = state.method == ArchiveKdf.PASSPHRASE_PBKDF2,
    onSelect = { onChoose(ArchiveKdf.PASSPHRASE_PBKDF2) },
  )
  PrimaryButton(stringResource(R.string.vbk_continue), onContinue, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun MethodOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 64.dp)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
      .background(if (selected) colors.primaryContainer else colors.surfaceContainer, RoundedCornerShape(14.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RadioButton(selected = selected, onClick = null)
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
      Text(body, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeStep(state: SetupState, onSaved: () -> Unit) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val code = state.code.orEmpty()
  val groups = code.split(" ").filter { it.isNotEmpty() }
  val description = stringResource(R.string.vbk_code_description, code)
  val copiedText = stringResource(R.string.vbk_copied)
  val shareText = stringResource(R.string.vbk_share_text, code)
  val shareTitle = stringResource(R.string.vbk_share)
  var copied by remember { mutableStateOf(false) }

  StepTitle(stringResource(R.string.vbk_s3_title))
  Text(stringResource(R.string.vbk_s3_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  FlowRow(
    Modifier
      .fillMaxWidth()
      .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
      .padding(16.dp)
      .clearAndSetSemantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    groups.forEach { group ->
      Text(group, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = colors.onSurface)
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(
      onClick = { copied = copySensitive(context, code) },
      modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
    ) {
      Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(stringResource(R.string.vbk_copy), Modifier.padding(start = 8.dp))
    }
    OutlinedButton(
      onClick = {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
        runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
      },
      modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
    ) {
      Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(stringResource(R.string.vbk_share), Modifier.padding(start = 8.dp))
    }
  }
  if (copied) {
    Text(
      copiedText,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
  ErrorText(state.error)
  PrimaryButton(stringResource(R.string.vbk_s3_saved), onSaved, modifier = Modifier.padding(top = 8.dp))
}

private fun copySensitive(context: Context, text: String): Boolean = runCatching {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  val clip = ClipData.newPlainText("", text)
  // Keeps the code out of clipboard previews where the phone supports it (Android 13 and later).
  clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
  clipboard.setPrimaryClip(clip)
  true
}.getOrDefault(false)

@Composable
private fun PassphraseStep(state: SetupState, onSubmit: (String) -> Unit) {
  val colors = SamvadTheme.colors
  var first by remember { mutableStateOf("") }
  var second by remember { mutableStateOf("") }
  var tried by remember { mutableStateOf(false) }
  val tooShort = !BackupPassphrase.isAcceptable(first)
  val mismatch = first != second

  StepTitle(stringResource(R.string.vbk_pass_title))
  Text(stringResource(R.string.vbk_pass_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  OutlinedTextField(
    value = first,
    onValueChange = { first = it.take(BackupPassphrase.MAX_LENGTH) },
    label = { Text(stringResource(R.string.vbk_pass_field)) },
    isError = tried && tooShort,
    supportingText = if (tried && tooShort) ({ Text(stringResource(R.string.vbk_pass_short)) }) else null,
    singleLine = true,
    visualTransformation = BackupPasswordTransformation,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
    modifier = Modifier.fillMaxWidth(),
  )
  OutlinedTextField(
    value = second,
    onValueChange = { second = it.take(BackupPassphrase.MAX_LENGTH) },
    label = { Text(stringResource(R.string.vbk_pass_again)) },
    isError = tried && !tooShort && mismatch,
    supportingText = if (tried && !tooShort && mismatch) ({ Text(stringResource(R.string.vbk_pass_mismatch)) }) else null,
    singleLine = true,
    visualTransformation = BackupPasswordTransformation,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
    modifier = Modifier.fillMaxWidth(),
  )
  ErrorText(state.error)
  PrimaryButton(
    stringResource(R.string.vbk_s4_turn_on),
    onClick = {
      tried = true
      if (!tooShort && !mismatch) onSubmit(first)
    },
    modifier = Modifier.padding(top = 8.dp),
  )
}

@Composable
private fun ConfirmStep(state: SetupState, onConfirm: (Map<Int, String>) -> Unit, onShowAgain: () -> Unit) {
  val colors = SamvadTheme.colors
  val typed = remember(state.challenge) { mutableStateOf(state.challenge.associateWith { "" }) }
  StepTitle(stringResource(R.string.vbk_s4_title))
  Text(stringResource(R.string.vbk_s4_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  state.challenge.forEachIndexed { i, position ->
    OutlinedTextField(
      value = typed.value.getValue(position),
      onValueChange = { v -> typed.value = typed.value + (position to v.take(8)) },
      label = { Text(stringResource(R.string.vbk_s4_group, position + 1)) },
      singleLine = true,
      isError = state.error == R.string.vbk_s4_wrong,
      textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
      keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Characters,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Password,
        imeAction = if (i == state.challenge.lastIndex) ImeAction.Done else ImeAction.Next,
      ),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  ErrorText(state.error)
  PrimaryButton(
    stringResource(R.string.vbk_s4_turn_on),
    onClick = { onConfirm(typed.value) },
    enabled = typed.value.values.all { it.isNotBlank() },
    modifier = Modifier.padding(top = 8.dp),
  )
  TextButton(onClick = onShowAgain, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
    Text(stringResource(R.string.vbk_s4_show_again))
  }
}

@Composable
private fun WorkingStep(passphrase: Boolean) {
  Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    CircularProgressIndicator(color = SamvadTheme.colors.primary)
    Text(
      stringResource(if (passphrase) R.string.vbk_pass_working else R.string.vbk_enabling),
      style = MaterialTheme.typography.bodyLarge,
      color = SamvadTheme.colors.onSurface,
    )
  }
}
