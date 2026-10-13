package com.samvad.messenger.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.design.HomeLayout
import com.samvad.core.design.ThemeMode
import com.samvad.messenger.AppContainer
import com.samvad.messenger.BuildConfig
import com.samvad.messenger.R
import com.samvad.messenger.data.AccountException
import com.samvad.messenger.data.AppPreferences
import com.samvad.messenger.ui.auth.AuthStep
import com.samvad.messenger.ui.auth.AuthViewModel
import com.samvad.messenger.ui.auth.OtpStep
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, me: String, onBack: () -> Unit, onSignOut: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val prefs = container.preferences.preferences.collectAsStateWithLifecycle(AppPreferences()).value
  val profile = remember { container.accounts!!.profile(me) }.collectAsStateWithLifecycle(null).value
  val phone = container.auth?.currentUser?.phoneNumber.orEmpty()
  var editing by remember { mutableStateOf(false) }
  var confirmSignOutAll by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var reauthing by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  val msgSignedOutAll = stringResource(R.string.settings_signed_out_all)
  val msgCooldown = stringResource(R.string.settings_cooldown)
  val msgError = stringResource(R.string.error_unknown)

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Column(
      Modifier
        .fillMaxWidth()
        .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.background)))
        .statusBarsPadding()
        .padding(start = 8.dp, end = 20.dp, bottom = 20.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
      }
      Row(Modifier.padding(start = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Avatar(ChatList.initials(profile?.displayName ?: ""), me, size = 72.dp)
        Column(Modifier.weight(1f)) {
          Text(profile?.displayName ?: "", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
          if (!profile?.about.isNullOrBlank()) Text(profile!!.about, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
          Text(phone, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        IconButton(onClick = { editing = true }, modifier = Modifier.background(colors.surface, RoundedCornerShape(14.dp))) {
          Icon(Icons.Outlined.Edit, stringResource(R.string.settings_edit_profile), tint = colors.primary)
        }
      }
    }

    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Group(stringResource(R.string.settings_appearance)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        listOf(
          ThemeMode.SYSTEM to R.string.settings_theme_system,
          ThemeMode.LIGHT to R.string.settings_theme_light,
          ThemeMode.DARK to R.string.settings_theme_dark,
          ThemeMode.AMOLED to R.string.settings_theme_amoled,
        ).forEach { (mode, label) ->
          RadioRow(stringResource(label), prefs.themeMode == mode) { scope.launch { container.preferences.setThemeMode(mode) } }
        }
        Text(stringResource(R.string.settings_home_layout), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        listOf(
          HomeLayout.CLEAN to R.string.settings_layout_clean,
          HomeLayout.BOLD to R.string.settings_layout_bold,
          HomeLayout.BENTO to R.string.settings_layout_bento,
        ).forEach { (layout, label) ->
          RadioRow(stringResource(label), prefs.homeLayout == layout) { scope.launch { container.preferences.setHomeLayout(layout) } }
        }
      }

      Group(stringResource(R.string.settings_privacy)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          Icon(Icons.Outlined.Lock, null, tint = colors.onPrimaryContainer)
          Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
      }

      Group(stringResource(R.string.settings_account)) {
        ActionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.settings_sign_out), onClick = onSignOut)
        ActionRow(Icons.Outlined.Devices, stringResource(R.string.settings_sign_out_all), onClick = { confirmSignOutAll = true })
        ActionRow(Icons.Outlined.DeleteForever, stringResource(R.string.settings_delete), danger = true, onClick = { confirmDelete = true })
      }
      Text(
        stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
      )
    }
  }
  SnackbarHost(snackbar, Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp).wrapContentSize(Alignment.BottomCenter))

  if (editing) {
    EditProfileSheet(
      initialName = profile?.displayName ?: "",
      initialAbout = profile?.about ?: "",
      onDismiss = { editing = false },
      onSave = { name, about ->
        scope.launch {
          runCatching { container.accounts!!.updateProfile(me, name, about) }
            .onFailure { snackbar.showSnackbar(msgError) }
          editing = false
        }
      },
    )
  }

  if (confirmSignOutAll) {
    AlertDialog(
      onDismissRequest = { confirmSignOutAll = false },
      title = { Text(stringResource(R.string.settings_sign_out_all)) },
      text = { Text(stringResource(R.string.settings_sign_out_all_body)) },
      confirmButton = {
        TextButton(enabled = !busy, onClick = {
          busy = true
          scope.launch {
            try {
              container.accounts!!.signOutAllDevices()
              snackbar.showSnackbar(msgSignedOutAll)
              onSignOut()
            } catch (_: AccountException.Cooldown) {
              snackbar.showSnackbar(msgCooldown)
            } catch (_: Exception) {
              snackbar.showSnackbar(msgError)
            }
            busy = false
            confirmSignOutAll = false
          }
        }) { Text(stringResource(R.string.settings_sign_out_all)) }
      },
      dismissButton = { TextButton({ confirmSignOutAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.settings_delete)) },
      text = { Text(stringResource(R.string.settings_delete_body)) },
      confirmButton = {
        TextButton({ confirmDelete = false; reauthing = true }) {
          Text(stringResource(R.string.settings_delete_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  if (reauthing) {
    ModalBottomSheet(onDismissRequest = { reauthing = false }) {
      ReauthThenDelete(
        container = container,
        phone = phone,
        onDeleted = {
          reauthing = false
          scope.launch { container.preferences.clearDeviceId() }
          container.auth?.signOut()
        },
        onError = { scope.launch { snackbar.showSnackbar(msgError) } },
      )
    }
  }
}

/** deleteAccount needs a sign-in under five minutes old, so the user confirms with a fresh code. */
@Composable
private fun ReauthThenDelete(container: AppContainer, phone: String, onDeleted: () -> Unit, onError: () -> Unit) {
  val vm: AuthViewModel = viewModel(key = "reauth", factory = AuthViewModel.factory(container.auth!!, reauth = true, presetE164 = phone))
  val state = vm.state.collectAsStateWithLifecycle().value
  val activity = LocalContext.current as Activity
  LaunchedEffect(Unit) { if (state.step == AuthStep.PHONE) vm.sendCode(activity) }
  LaunchedEffect(state.step) {
    if (state.step == AuthStep.DONE) {
      runCatching { container.accounts!!.deleteAccount() }
        .onSuccess { onDeleted() }
        .onFailure { onError() }
    }
  }
  Column(Modifier.fillMaxWidth().height(560.dp)) {
    OtpStep(
      state = state,
      onCode = vm::onCodeChange,
      onVerify = vm::verify,
      onResend = { vm.sendCode(activity, isResend = true) },
      onBack = {},
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(initialName: String, initialAbout: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
  var name by remember { mutableStateOf(initialName) }
  var about by remember { mutableStateOf(initialAbout) }
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.settings_edit_profile), style = MaterialTheme.typography.titleLarge, color = SamvadTheme.colors.onSurface)
      SamvadTextField(name, { name = it }, stringResource(R.string.profile_name), maxLength = 64, supportingText = stringResource(R.string.setup_name_help))
      SamvadTextField(about, { about = it }, stringResource(R.string.profile_about), maxLength = 140, singleLine = false, supportingText = stringResource(R.string.setup_about_help))
      PrimaryButton(stringResource(R.string.save), { onSave(name.trim(), about.trim()) }, enabled = name.isNotBlank())
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  Column {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    Column(
      Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(20.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(vertical = 4.dp),
    ) { content() }
  }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline), modifier = Modifier.size(48.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
  }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(icon, null, tint = if (danger) colors.error else colors.onPrimaryContainer)
    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface)
  }
}
