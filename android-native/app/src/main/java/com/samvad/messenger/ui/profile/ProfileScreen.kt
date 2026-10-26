package com.samvad.messenger.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.ChatList
import com.samvad.core.privacy.PrivacySettings
import com.samvad.core.profile.ProfileRules
import com.samvad.core.profile.Usernames
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.UsernameResult
import com.samvad.messenger.ui.chat.MediaViewer
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.LocalPhotoSource
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.setup.UsernameField
import com.samvad.messenger.ui.setup.usernameStatus
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.SamvadTheme
import com.samvad.messenger.ui.theme.motionSpec
import kotlinx.coroutines.launch
import java.io.File

private val DP_SIZE = 136.dp

/**
 * Pattern: form page (single column of editable fields) under a soft brand header.
 * Photo changes go through the system photo picker or the camera, then the same crop, compress and
 * setProfilePhoto path as before. Name and About save only when the person confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(container: AppContainer, me: String, onBack: () -> Unit, onOpenPrivacy: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val accounts = container.accounts!!
  val snackbar = remember { SnackbarHostState() }
  val profile = remember { accounts.profile(me) }.collectAsStateWithLifecycle(null).value
  val privacy = container.privacy
  val settings = privacy?.let { remember(me) { it.settings(me) } }?.collectAsStateWithLifecycle(PrivacySettings())?.value ?: PrivacySettings()
  val phone = container.auth?.currentUser?.phoneNumber.orEmpty()
  val photoSource = LocalPhotoSource.current

  var photoSheet by remember { mutableStateOf(false) }
  var confirmRemove by remember { mutableStateOf(false) }
  var viewing by remember { mutableStateOf(false) }
  var photoBusy by remember { mutableStateOf(false) }
  var editingName by remember { mutableStateOf(false) }
  var editingAbout by remember { mutableStateOf(false) }
  var editingUsername by remember { mutableStateOf(false) }

  val msgPhotoFailed = stringResource(R.string.settings_photo_failed)
  val msgError = stringResource(R.string.error_unknown)
  val msgSaved = stringResource(R.string.profile_saved)
  val msgCameraDenied = stringResource(R.string.profile_camera_denied)
  val msgUsernameFailed = stringResource(R.string.username_save_failed)

  val cameraFile = remember { File(File(context.cacheDir, "camera").apply { mkdirs() }, "profile.jpg") }
  val updatePhoto: (suspend () -> ByteArray?) -> Unit = { produce ->
    photoBusy = true
    scope.launch {
      runCatching { accounts.setProfilePhoto(produce()) }.onFailure { snackbar.showSnackbar(msgPhotoFailed) }
      cameraFile.delete()
      photoBusy = false
    }
  }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) updatePhoto { container.media!!.prepareAvatar(uri) }
  }
  val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    if (ok) {
      val uri = FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile)
      updatePhoto { container.media!!.prepareAvatar(uri) }
    } else {
      cameraFile.delete()
    }
  }
  val launchCamera: () -> Unit = {
    container.appLock.expectExternal()
    takePicture.launch(FileProvider.getUriForFile(context, context.packageName + ".files", cameraFile))
  }
  val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) launchCamera() else scope.launch { snackbar.showSnackbar(msgCameraDenied) }
  }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
      // Soft brand header: the one gradient this screen is allowed.
      Column(
        Modifier
          .fillMaxWidth()
          .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.background)))
          .statusBarsPadding()
          .padding(start = 8.dp, end = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
          Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        val entered by animateFloatAsState(1f, motionSpec(tween(360)), label = "dpIn")
        val dpScale = if (LocalReducedMotion.current) 1f else 0.9f + 0.1f * entered
        val photoLabel = stringResource(R.string.settings_photo)
        Box(
          Modifier
            .scale(dpScale)
            .clip(CircleShape)
            .clickable(enabled = !photoBusy, onClickLabel = photoLabel, role = Role.Button) { photoSheet = true }
            .semantics { contentDescription = photoLabel },
        ) {
          Avatar(ChatList.initials(profile?.displayName ?: ""), me, size = DP_SIZE, photoPath = profile?.photoPath)
          if (photoBusy) {
            Box(Modifier.size(DP_SIZE).background(colors.background.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(Modifier.size(DP_SIZE - 8.dp), color = colors.brand, strokeWidth = 4.dp)
            }
          } else {
            Icon(
              Icons.Outlined.PhotoCamera,
              null,
              tint = colors.onPrimary,
              modifier = Modifier.align(Alignment.BottomEnd).size(44.dp).border(3.dp, colors.background, CircleShape).background(colors.primary, CircleShape).padding(10.dp),
            )
          }
        }
        if (photoBusy) {
          Text(
            stringResource(R.string.profile_photo_uploading),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
          )
        }
      }

      Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Display name
        FieldCard(stringResource(R.string.profile_name)) {
          var draft by remember(editingName) { mutableStateOf(profile?.displayName ?: "") }
          var saving by remember(editingName) { mutableStateOf(false) }
          if (!editingName) {
            ValueRow(profile?.displayName.orEmpty(), stringResource(R.string.profile_name_edit)) { editingName = true }
          } else {
            val blank = ProfileRules.nameProblem(draft) != null
            SamvadTextField(
              draft, { draft = it }, stringResource(R.string.profile_name),
              maxLength = ProfileRules.NAME_MAX,
              isError = blank,
              supportingText = stringResource(if (blank) R.string.profile_name_blank else R.string.setup_name_help),
            )
            EditActions(
              saveEnabled = !blank && !saving && draft.trim() != profile?.displayName,
              onCancel = { editingName = false },
              onSave = {
                saving = true
                scope.launch {
                  runCatching { accounts.updateProfile(me, draft.trim(), profile?.about.orEmpty()) }
                    .onSuccess { editingName = false; snackbar.showSnackbar(msgSaved) }
                    .onFailure { snackbar.showSnackbar(msgError) }
                  saving = false
                }
              },
            )
          }
        }

        // About
        FieldCard(stringResource(R.string.profile_about)) {
          var draft by remember(editingAbout) { mutableStateOf(profile?.about.orEmpty()) }
          var saving by remember(editingAbout) { mutableStateOf(false) }
          if (!editingAbout) {
            ValueRow(
              profile?.about.orEmpty().ifBlank { stringResource(R.string.profile_about_empty) },
              stringResource(R.string.profile_about_edit), muted = profile?.about.isNullOrBlank(),
            ) { editingAbout = true }
          } else {
            SamvadTextField(
              draft, { draft = it }, stringResource(R.string.profile_about),
              maxLength = ProfileRules.ABOUT_MAX, singleLine = false,
              supportingText = stringResource(R.string.profile_about_counter, ProfileRules.aboutRemaining(draft)),
            )
            EditActions(
              saveEnabled = ProfileRules.aboutValid(draft) && !saving && draft.trim() != profile?.about.orEmpty(),
              onCancel = { editingAbout = false },
              onSave = {
                saving = true
                scope.launch {
                  runCatching { accounts.updateProfile(me, profile?.displayName.orEmpty(), draft.trim()) }
                    .onSuccess { editingAbout = false; snackbar.showSnackbar(msgSaved) }
                    .onFailure { snackbar.showSnackbar(msgError) }
                  saving = false
                }
              },
            )
          }
        }

        // Username (existing flow)
        FieldCard(stringResource(R.string.profile_username)) {
          val name = profile?.username
          ValueRow(
            if (name.isNullOrEmpty()) stringResource(R.string.profile_username_add) else "@$name",
            stringResource(R.string.profile_username_edit), muted = name.isNullOrEmpty(),
          ) { editingUsername = true }
        }

        // Phone: read only, with the visibility control living on the privacy screen.
        FieldCard(stringResource(R.string.profile_phone_label)) {
          Text(phone, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(vertical = 8.dp))
          val chipText = stringResource(if (settings.phoneDiscovery) R.string.profile_phone_find_on else R.string.profile_phone_find_off)
          val chipCd = stringResource(R.string.profile_phone_chip_cd, chipText)
          Row(
            Modifier
              .defaultMinSize(minHeight = 48.dp)
              .clip(RoundedCornerShape(14.dp))
              .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
              .clickable(role = Role.Button, onClick = onOpenPrivacy)
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .semantics { contentDescription = chipCd },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Outlined.Visibility, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
            Text(chipText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
          }
        }
        Spacer(Modifier.height(24.dp))
      }
    }
    SnackbarHost(snackbar, Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp).wrapContentSize(Alignment.BottomCenter))
  }

  if (photoSheet) {
    ModalBottomSheet(onDismissRequest = { photoSheet = false }) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
          stringResource(R.string.profile_photo_sheet_title),
          style = MaterialTheme.typography.titleLarge,
          color = colors.onSurface,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (profile?.photoPath != null) {
          SheetRow(Icons.Outlined.Fullscreen, stringResource(R.string.profile_photo_view)) { photoSheet = false; viewing = true }
        }
        SheetRow(Icons.Outlined.PhotoCamera, stringResource(R.string.profile_photo_camera)) {
          photoSheet = false
          if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
          else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        // The system photo picker already offers recent photos and albums in one place.
        SheetRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.profile_photo_gallery)) {
          photoSheet = false
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (profile?.photoPath != null) {
          SheetRow(Icons.Outlined.Delete, stringResource(R.string.settings_photo_remove), danger = true) { photoSheet = false; confirmRemove = true }
        }
        Text(
          stringResource(R.string.settings_photo_note),
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
    }
  }

  if (confirmRemove) {
    AlertDialog(
      onDismissRequest = { confirmRemove = false },
      title = { Text(stringResource(R.string.profile_photo_remove_title)) },
      text = { Text(stringResource(R.string.profile_photo_remove_body)) },
      confirmButton = {
        TextButton({ confirmRemove = false; updatePhoto { null } }) {
          Text(stringResource(R.string.profile_photo_remove_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  val path = profile?.photoPath
  if (viewing && path != null && photoSource != null) {
    MediaViewer(path, load = { p, px -> photoSource.load(p, px)?.asAndroidBitmap() }, onClose = { viewing = false })
  }

  if (editingUsername) {
    val initial = profile?.username.orEmpty()
    var username by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    val status by usernameStatus(accounts, username, initial.ifEmpty { null })
    ModalBottomSheet(onDismissRequest = { editingUsername = false }) {
      Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.profile_username_edit), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        UsernameField(username, { username = it }, status)
        PrimaryButton(
          stringResource(R.string.save),
          {
            saving = true
            scope.launch {
              val result = accounts.setUsername(Usernames.normalize(username).ifEmpty { null })
              saving = false
              if (result == UsernameResult.Saved) { editingUsername = false; snackbar.showSnackbar(msgSaved) } else snackbar.showSnackbar(msgUsernameFailed)
            }
          },
          enabled = status.ok && !saving && Usernames.normalize(username) != initial,
          loading = saving,
        )
        Spacer(Modifier.height(16.dp))
      }
    }
  }
}

@Composable
private fun FieldCard(title: String, content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier
      .fillMaxWidth()
      .background(colors.surface, RoundedCornerShape(20.dp))
      .border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp))
      .animateContentSize(motionSpec(tween(220)))
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    content()
  }
}

@Composable
private fun ValueRow(value: String, editLabel: String, muted: Boolean = false, onEdit: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      value,
      style = MaterialTheme.typography.bodyLarge,
      color = if (muted) colors.onSurfaceVariant else colors.onSurface,
      modifier = Modifier.weight(1f).padding(vertical = 8.dp),
    )
    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, editLabel, tint = colors.primary) }
  }
}

@Composable
private fun EditActions(saveEnabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
    QuietButton(stringResource(R.string.cancel), onCancel)
    QuietButton(stringResource(R.string.save), onSave, enabled = saveEnabled)
  }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, null, tint = if (danger) colors.error else colors.onPrimaryContainer)
    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface)
  }
}
