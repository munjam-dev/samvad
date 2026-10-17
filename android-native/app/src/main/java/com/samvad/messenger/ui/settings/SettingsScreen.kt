package com.samvad.messenger.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.fragment.app.FragmentActivity
import com.samvad.core.security.LockTimeout
import com.samvad.messenger.security.BiometricGate
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
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
import com.samvad.messenger.data.AccountRepository
import com.samvad.messenger.data.UsernameResult
import com.samvad.core.profile.Usernames
import com.samvad.messenger.ui.language.AppLanguage
import com.samvad.messenger.ui.language.LanguageList
import com.samvad.messenger.ui.setup.UsernameField
import com.samvad.messenger.ui.setup.usernameStatus
import com.samvad.messenger.ui.security.PinInput
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
fun SettingsScreen(
  container: AppContainer,
  me: String,
  onBack: () -> Unit,
  onSignOut: () -> Unit,
  onOpenBlocked: () -> Unit = {},
  onOpenTwoStep: () -> Unit = {},
  onOpenChatTheme: () -> Unit = {},
  onOpenStarred: () -> Unit = {},
  onOpenVault: () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val prefs = container.preferences.preferences.collectAsStateWithLifecycle(AppPreferences()).value
  val profile = remember { container.accounts!!.profile(me) }.collectAsStateWithLifecycle(null).value
  val phone = container.auth?.currentUser?.phoneNumber.orEmpty()
  val context = androidx.compose.ui.platform.LocalContext.current
  var editing by remember { mutableStateOf(false) }
  var choosingLanguage by remember { mutableStateOf(false) }
  var confirmSignOutAll by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var reauthing by remember { mutableStateOf(false) }
  var deletingWithPin by remember { mutableStateOf(false) }
  var photoMenu by remember { mutableStateOf(false) }
  var photoBusy by remember { mutableStateOf(false) }
  val msgPhotoFailed = stringResource(R.string.settings_photo_failed)
  val updatePhoto: (suspend () -> ByteArray?) -> Unit = { produce ->
    photoBusy = true
    scope.launch {
      runCatching { container.accounts!!.setProfilePhoto(produce()) }.onFailure { snackbar.showSnackbar(msgPhotoFailed) }
      photoBusy = false
    }
  }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) updatePhoto { container.media!!.prepareAvatar(uri) }
  }
  // With two-step on, deletion is confirmed with the PIN instead of a fresh SMS code.
  val twoStepOn = produceState(false) { value = runCatching { container.twoStep?.session()?.enabled == true }.getOrDefault(false) }.value
  var busy by remember { mutableStateOf(false) }
  val msgSignedOutAll = stringResource(R.string.settings_signed_out_all)
  val msgCooldown = stringResource(R.string.settings_cooldown)
  val msgError = stringResource(R.string.error_unknown)
  val msgUsernameFailed = stringResource(R.string.username_save_failed)
  val msgNoScreenLock = stringResource(R.string.settings_app_lock_unavailable)
  val lockTitle = stringResource(R.string.lock_prompt_title)
  val lockSubtitle = stringResource(R.string.lock_prompt_subtitle)
  val activity = LocalActivity.current

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
        val photoLabel = stringResource(R.string.settings_photo)
        Box(
          Modifier
            .clip(CircleShape)
            .clickable(enabled = !photoBusy, onClickLabel = photoLabel) { photoMenu = true }
            .semantics { contentDescription = photoLabel },
        ) {
          Avatar(ChatList.initials(profile?.displayName ?: ""), me, size = 72.dp, photoPath = profile?.photoPath)
          if (photoBusy) {
            CircularProgressIndicator(Modifier.size(72.dp), color = colors.brand, strokeWidth = 3.dp)
          } else {
            Icon(
              Icons.Outlined.PhotoCamera,
              null,
              tint = colors.onPrimary,
              modifier = Modifier.align(Alignment.BottomEnd).size(24.dp).background(colors.primary, CircleShape).padding(4.dp),
            )
          }
        }
        Column(Modifier.weight(1f)) {
          Text(profile?.displayName ?: "", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
          if (!profile?.username.isNullOrEmpty()) Text("@${profile!!.username}", style = MaterialTheme.typography.bodyMedium, color = colors.brand)
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
        ActionRow(Icons.Outlined.Wallpaper, stringResource(R.string.chat_theme_title), onClick = onOpenChatTheme)
        ActionRow(Icons.Outlined.Language, stringResource(R.string.settings_language), onClick = { choosingLanguage = true })
        ActionRow(Icons.Outlined.StarOutline, stringResource(R.string.starred_title), onClick = onOpenStarred)
      }

      Group(stringResource(R.string.settings_notifications)) {
        ActionRow(Icons.Outlined.Notifications, stringResource(R.string.settings_notifications), onClick = {
          container.appLock.expectExternal()
          context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
              .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
          )
        })
        SwitchRow(
          title = stringResource(R.string.settings_previews),
          body = stringResource(R.string.settings_previews_body),
          checked = prefs.notificationPreviews,
          onChange = { v -> scope.launch { container.preferences.setNotificationPreviews(v) } },
        )
      }

      Group(stringResource(R.string.settings_data)) {
        SwitchRow(
          title = stringResource(R.string.settings_data_saver),
          body = stringResource(R.string.settings_data_saver_body),
          checked = prefs.dataSaver,
          onChange = { v -> scope.launch { container.preferences.setDataSaver(v) } },
        )
        Text(stringResource(R.string.settings_auto_download), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        listOf(
          com.samvad.core.media.AutoDownload.ALWAYS to R.string.settings_auto_always,
          com.samvad.core.media.AutoDownload.WIFI_ONLY to R.string.settings_auto_wifi,
          com.samvad.core.media.AutoDownload.NEVER to R.string.settings_auto_never,
        ).forEach { (value, label) ->
          RadioRow(stringResource(label), prefs.autoDownload == value) { scope.launch { container.preferences.setAutoDownload(value) } }
        }
      }

      Group(stringResource(R.string.settings_default_timer)) {
        Text(stringResource(R.string.settings_default_timer_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        com.samvad.core.chat.Disappearing.CHOICES.forEach { seconds ->
          RadioRow(com.samvad.messenger.ui.chat.timerOptionLabel(seconds), prefs.defaultTimer == seconds) { scope.launch { container.preferences.setDefaultTimer(seconds) } }
        }
      }

      Group(stringResource(R.string.settings_privacy)) {
        val presenceRepo = container.presence
        if (presenceRepo != null) {
          val hidden = remember { presenceRepo.hideLastSeen(me) }.collectAsStateWithLifecycle(false).value
          SwitchRow(
            title = stringResource(R.string.settings_hide_last_seen),
            body = stringResource(R.string.settings_hide_last_seen_body),
            checked = hidden,
            onChange = { v -> scope.launch { runCatching { presenceRepo.setHideLastSeen(me, v) } } },
          )
        }
        SwitchRow(
          title = stringResource(R.string.settings_read_receipts),
          body = stringResource(R.string.settings_read_receipts_body),
          checked = prefs.readReceipts,
          onChange = { v -> scope.launch { container.preferences.setReadReceipts(v) } },
        )
        SwitchRow(
          title = stringResource(R.string.settings_hide_unread),
          body = stringResource(R.string.settings_hide_unread_body),
          checked = prefs.hideUnreadCounts,
          onChange = { v -> scope.launch { container.preferences.setHideUnreadCounts(v) } },
        )
        SwitchRow(
          title = stringResource(R.string.settings_calm_mode),
          body = stringResource(R.string.settings_calm_mode_body),
          checked = prefs.calmMode,
          onChange = { v -> scope.launch { container.preferences.setCalmMode(v) } },
        )
        SwitchRow(
          title = stringResource(R.string.settings_app_lock),
          body = stringResource(R.string.settings_app_lock_body),
          checked = prefs.appLockEnabled,
          onChange = { v ->
            val host = activity as? FragmentActivity
            when {
              host == null -> Unit
              v && !BiometricGate.available(context) -> scope.launch { snackbar.showSnackbar(msgNoScreenLock) }
              // Turning it on or off both need the owner's fingerprint, face or screen lock.
              else -> BiometricGate.prompt(host, container.appLock, lockTitle, lockSubtitle) { ok ->
                if (ok) scope.launch { container.preferences.setAppLock(v) }
              }
            }
          },
        )
        androidx.compose.animation.AnimatedVisibility(visible = prefs.appLockEnabled) {
          Column {
            Text(stringResource(R.string.settings_lock_after), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            listOf(
              LockTimeout.IMMEDIATELY to R.string.settings_lock_immediately,
              LockTimeout.ONE_MINUTE to R.string.settings_lock_one_minute,
              LockTimeout.THIRTY_MINUTES to R.string.settings_lock_thirty_minutes,
            ).forEach { (value, label) ->
              RadioRow(stringResource(label), prefs.lockTimeout == value) { scope.launch { container.preferences.setLockTimeout(value) } }
            }
          }
        }
        SwitchRow(
          title = stringResource(R.string.settings_block_screenshots),
          body = stringResource(R.string.settings_block_screenshots_body),
          checked = prefs.secureScreen,
          onChange = { v -> scope.launch { container.preferences.setSecureScreen(v) } },
        )
        ActionRow(Icons.Outlined.Lock, stringResource(R.string.vault_title), onClick = onOpenVault)
        if (container.safety != null) {
          ActionRow(Icons.Outlined.Block, stringResource(R.string.settings_blocked), onClick = onOpenBlocked)
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          Icon(Icons.Outlined.Lock, null, tint = colors.onPrimaryContainer)
          Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
      }

      Group(stringResource(R.string.settings_account)) {
        ActionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.settings_sign_out), onClick = onSignOut)
        if (container.twoStep != null) {
          ActionRow(Icons.Outlined.VerifiedUser, stringResource(R.string.two_step_title), onClick = onOpenTwoStep)
        }
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

  if (choosingLanguage) {
    val activity = androidx.activity.compose.LocalActivity.current!!
    ModalBottomSheet(onDismissRequest = { choosingLanguage = false }) {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge, color = SamvadTheme.colors.onSurface)
        LanguageList(
          selected = AppLanguage.current(activity),
          onSelect = { tag -> choosingLanguage = false; AppLanguage.apply(activity, tag) },
        )
        Spacer(Modifier.height(16.dp))
      }
    }
  }

  if (editing) {
    EditProfileSheet(
      initialName = profile?.displayName ?: "",
      initialAbout = profile?.about ?: "",
      initialUsername = profile?.username ?: "",
      accounts = container.accounts,
      onDismiss = { editing = false },
      onSave = { name, about, username ->
        scope.launch {
          runCatching { container.accounts!!.updateProfile(me, name, about) }
            .onFailure { snackbar.showSnackbar(msgError) }
          if (username != (profile?.username ?: "")) {
            val result = container.accounts!!.setUsername(username.ifEmpty { null })
            if (result != UsernameResult.Saved) snackbar.showSnackbar(msgUsernameFailed)
          }
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
      text = {
        ObscuredTouchGuard()
        Text(stringResource(R.string.settings_delete_body))
      },
      confirmButton = {
        TextButton({
          confirmDelete = false
          if (twoStepOn) deletingWithPin = true else reauthing = true
        }) {
          Text(stringResource(R.string.settings_delete_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  if (photoMenu) {
    ModalBottomSheet(onDismissRequest = { photoMenu = false }) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        ActionRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.settings_photo_choose), onClick = {
          photoMenu = false
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (profile?.photoPath != null) {
          ActionRow(Icons.Outlined.Delete, stringResource(R.string.settings_photo_remove), danger = true, onClick = {
            photoMenu = false
            updatePhoto { null }
          })
        }
        Text(
          stringResource(R.string.settings_photo_note),
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }

  if (deletingWithPin) {
    ModalBottomSheet(onDismissRequest = { deletingWithPin = false }) {
      DeleteWithPin(
        container = container,
        onDeleted = {
          deletingWithPin = false
          scope.launch { container.preferences.clearDeviceId() }
          container.auth?.signOut()
        },
      )
    }
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
  val activity = requireNotNull(LocalActivity.current)
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
private fun EditProfileSheet(
  initialName: String,
  initialAbout: String,
  initialUsername: String,
  accounts: AccountRepository?,
  onDismiss: () -> Unit,
  onSave: (String, String, String) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var about by remember { mutableStateOf(initialAbout) }
  var username by remember { mutableStateOf(initialUsername) }
  val status by usernameStatus(accounts, username, initialUsername.ifEmpty { null })
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.settings_edit_profile), style = MaterialTheme.typography.titleLarge, color = SamvadTheme.colors.onSurface)
      SamvadTextField(name, { name = it }, stringResource(R.string.profile_name), maxLength = 64, supportingText = stringResource(R.string.setup_name_help))
      UsernameField(username, { username = it }, status)
      SamvadTextField(about, { about = it }, stringResource(R.string.profile_about), maxLength = 140, singleLine = false, supportingText = stringResource(R.string.setup_about_help))
      PrimaryButton(stringResource(R.string.save), { onSave(name.trim(), about.trim(), Usernames.normalize(username)) }, enabled = name.isNotBlank() && status.ok)
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

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .toggleable(checked, role = Role.Switch, onValueChange = onChange)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    Switch(
      checked = checked,
      onCheckedChange = null,
      colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
    )
  }
}

/**
 * Tapjacking guard for irreversible confirmations: while shown, its window ignores touches that pass
 * through another app's overlay. Not applied app-wide, because screen-dimmer apps would block all input.
 */
@Composable
private fun ObscuredTouchGuard() {
  val view = LocalView.current
  DisposableEffect(view) {
    val root = view.rootView
    val previous = root.filterTouchesWhenObscured
    root.filterTouchesWhenObscured = true
    onDispose { root.filterTouchesWhenObscured = previous }
  }
}

/** Account deletion with two-step on: the PIN replaces the SMS re-authentication (see deleteAccount). */
@Composable
private fun DeleteWithPin(container: AppContainer, onDeleted: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  var pin by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Int?>(null) }
  var errorKey by remember { mutableIntStateOf(0) }
  ObscuredTouchGuard()
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).imePadding(),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(stringResource(R.string.settings_delete_pin), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
    PinInput(
      value = pin,
      onValueChange = { value ->
        if (busy) return@PinInput
        pin = value
        if (value.length == 6) {
          busy = true
          scope.launch {
            try {
              container.accounts!!.deleteAccount(value)
              onDeleted()
            } catch (e: AccountException.WrongPin) {
              error = R.string.two_step_wrong
              errorKey++
            } catch (e: Exception) {
              error = R.string.error_unknown
              errorKey++
            }
            pin = ""
            busy = false
          }
        }
      },
      label = stringResource(R.string.two_step_pin_label),
      error = error != null,
      errorKey = errorKey,
      enabled = !busy,
    )
    error?.let { Text(stringResource(it), color = colors.error, style = MaterialTheme.typography.bodyMedium) }
    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = colors.brand, strokeWidth = 2.5.dp)
  }
}
