package com.samvad.messenger.ui.groups

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.GroupRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.theme.SamvadTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
  container: AppContainer,
  me: String,
  cid: String,
  onBack: () -> Unit,
  onAddMembers: () -> Unit,
  onLeft: () -> Unit,
  onOpenChat: (String, String) -> Unit,
  onOpenEvents: () -> Unit = {},
  onOpenMedia: () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  val vm: GroupInfoViewModel = viewModel(key = "info-$cid", factory = GroupInfoViewModel.factory(container, me, cid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val busy = vm.busy.collectAsStateWithLifecycle().value
  val error = vm.error.collectAsStateWithLifecycle().value
  val invite = vm.invite.collectAsStateWithLifecycle().value
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  var editingName by remember { mutableStateOf(false) }
  var editingDescription by remember { mutableStateOf(false) }
  var photoMenu by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<GroupMember?>(null) }
  var confirmLeave by remember { mutableStateOf(false) }
  var pendingSheet by remember { mutableStateOf(false) }
  val decideBusy = vm.decideBusy.collectAsStateWithLifecycle().value
  val errorText = error?.let { groupErrorText(it) }
  LaunchedEffect(errorText) {
    if (errorText != null) {
      vm.clearError()
      snackbar.showSnackbar(errorText)
    }
  }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setPhoto) }
  val shareText = stringResource(R.string.group_invite_share, state.name, invite.orEmpty())
  val shareTitle = stringResource(R.string.group_invite)

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.group_info), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = colors.brand, strokeWidth = 2.5.dp)
      }
      when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
        state.gone -> Text(
          stringResource(R.string.group_removed),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(24.dp),
        )
        else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
          item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(Modifier.then(if (state.isAdmin) Modifier.clickable(onClickLabel = stringResource(R.string.group_change_photo)) { photoMenu = true } else Modifier)) {
                Avatar(ChatList.initials(state.name), cid, size = 96.dp, photoPath = state.photoPath)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.isAdmin) {
                  IconButton(onClick = { editingName = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.group_rename), tint = colors.primary) }
                }
              }
              Text(pluralStringResource(R.plurals.group_members_count, state.members.size, state.members.size), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
          }
          item(key = "description") {
            Column(
              Modifier
                .fillMaxWidth()
                .then(if (state.isAdmin) Modifier.clickable { editingDescription = true } else Modifier)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
              Text(stringResource(R.string.group_description), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
              Text(
                state.description.ifBlank { stringResource(if (state.isAdmin) R.string.group_add_description else R.string.group_no_description) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.description.isBlank()) colors.onSurfaceVariant else colors.onSurface,
              )
            }
          }
          item(key = "mute") {
            Row(
              Modifier.fillMaxWidth().clickable { vm.toggleMute() }.padding(horizontal = 20.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Icon(Icons.Outlined.NotificationsOff, null, tint = colors.onPrimaryContainer)
              Text(stringResource(R.string.chat_mute), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
              Switch(checked = state.muted, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary))
            }
          }
          if (state.isAdmin) {
            item(key = "add") { InfoRow(Icons.Outlined.PersonAdd, stringResource(R.string.group_add_members), onClick = onAddMembers) }
            item(key = "invite") { InfoRow(Icons.Outlined.Link, stringResource(R.string.group_invite), onClick = vm::showInvite) }
            if (state.pendingRequests.isNotEmpty()) {
              item(key = "pending") { PendingRequestsRow(state.pendingRequests.size, onClick = { pendingSheet = true }) }
            }
          }
          item(key = "events") {
            InfoRow(
              Icons.Outlined.Event,
              stringResource(R.string.group_events),
              trailing = if (state.upcomingEvents > 0) "${state.upcomingEvents}" else null,
              onClick = onOpenEvents,
            )
          }
          item(key = "media") { InfoRow(Icons.Outlined.PermMedia, stringResource(R.string.media_tab_title), onClick = onOpenMedia) }
          if (state.isAdmin) {
            item(key = "settings-h") {
              Text(
                stringResource(R.string.group_settings_section).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
              )
            }
            item(key = "announce") {
              GroupSettingRow(
                Icons.Outlined.Campaign,
                stringResource(R.string.group_announce_title),
                stringResource(R.string.group_announce_body),
                state.onlyAdminsSend,
                busy,
                vm::setOnlyAdminsSend,
              )
            }
            item(key = "approve") {
              GroupSettingRow(
                Icons.Outlined.PersonAddAlt,
                stringResource(R.string.group_approve_title),
                stringResource(R.string.group_approve_body),
                state.approveMembers,
                busy,
                vm::setApproveMembers,
              )
            }
            item(key = "events-setting") {
              GroupSettingRow(
                Icons.Outlined.EventAvailable,
                stringResource(R.string.group_events_setting_title),
                stringResource(R.string.group_events_setting_body),
                state.eventsByMembers,
                busy,
                vm::setEventsByMembers,
              )
            }
          }
          item(key = "members-h") {
            Text(
              pluralStringResource(R.plurals.group_members_count, state.members.size, state.members.size).uppercase(),
              style = MaterialTheme.typography.labelMedium,
              color = colors.onSurfaceVariant,
              modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
            )
          }
          items(state.members, key = { it.uid }) { m ->
            Row(
              Modifier
                .fillMaxWidth()
                .then(if (!m.me) Modifier.clickable { selected = m } else Modifier)
                .padding(horizontal = 20.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Avatar(ChatList.initials(m.name), m.uid, size = 44.dp)
              Text(
                if (m.me) stringResource(R.string.chat_you) else m.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (m.admin) {
                Text(
                  stringResource(R.string.group_admin),
                  style = MaterialTheme.typography.labelSmall,
                  color = colors.onPrimaryContainer,
                  modifier = Modifier.background(colors.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
          }
          item(key = "leave") {
            InfoRow(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.group_leave), danger = true, onClick = { confirmLeave = true })
          }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }

  if (editingName) {
    TextDialog(
      title = stringResource(R.string.group_rename),
      initial = state.name,
      maxLength = GroupRules.MAX_NAME,
      singleLine = true,
      onDone = { vm.rename(it); editingName = false },
      onDismiss = { editingName = false },
    )
  }
  if (editingDescription) {
    TextDialog(
      title = stringResource(R.string.group_description),
      initial = state.description,
      maxLength = GroupRules.MAX_DESCRIPTION,
      singleLine = false,
      onDone = { vm.describe(it); editingDescription = false },
      onDismiss = { editingDescription = false },
    )
  }
  if (photoMenu) {
    ModalBottomSheet(onDismissRequest = { photoMenu = false }) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        InfoRow(Icons.Outlined.PhotoLibrary, stringResource(R.string.settings_photo_choose), onClick = {
          photoMenu = false
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        if (state.photoPath != null) {
          InfoRow(Icons.Outlined.Delete, stringResource(R.string.settings_photo_remove), danger = true, onClick = { photoMenu = false; vm.removePhoto() })
        }
      }
    }
  }
  selected?.let { m ->
    ModalBottomSheet(onDismissRequest = { selected = null }) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(m.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        InfoRow(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.group_message_member, m.name), onClick = {
          selected = null
          vm.message(m.uid, onOpenChat)
        })
        if (state.isAdmin) {
          InfoRow(
            Icons.Outlined.AdminPanelSettings,
            stringResource(if (m.admin) R.string.group_dismiss_admin else R.string.group_make_admin),
            onClick = { selected = null; vm.setAdmin(m.uid, !m.admin) },
          )
          InfoRow(Icons.Outlined.PersonRemove, stringResource(R.string.group_remove_member, m.name), danger = true, onClick = {
            selected = null
            vm.remove(m.uid)
          })
        }
      }
    }
  }
  invite?.let { code ->
    ModalBottomSheet(onDismissRequest = vm::hideInvite) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.group_invite), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(stringResource(R.string.group_invite_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
          code.chunked(4).joinToString(" "),
          style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
          fontWeight = FontWeight.SemiBold,
          color = colors.onSurface,
          modifier = Modifier.align(Alignment.CenterHorizontally).background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        )
        InfoRow(Icons.Outlined.Share, stringResource(R.string.group_invite_send), onClick = {
          container.appLock.expectExternal()
          context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), shareTitle))
        })
        InfoRow(Icons.Outlined.ContentCopy, stringResource(R.string.group_invite_copy), onClick = { clipboard.setText(AnnotatedString(code)) })
        InfoRow(Icons.Outlined.LinkOff, stringResource(R.string.group_invite_revoke), danger = true, onClick = vm::revokeInvite)
      }
    }
  }
  if (pendingSheet) {
    ModalBottomSheet(onDismissRequest = { pendingSheet = false }) {
      PendingRequestsSheet(
        requests = state.pendingRequests,
        busyUid = decideBusy,
        names = { uid -> vm.nameOf(uid) },
        onDecide = vm::decide,
        onClose = { pendingSheet = false },
      )
    }
  }

  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text(stringResource(R.string.group_leave_title, state.name)) },
      text = { Text(stringResource(R.string.group_leave_body)) },
      confirmButton = { TextButton({ confirmLeave = false; vm.leave(onLeft) }) { Text(stringResource(R.string.group_leave), color = colors.error) } },
      dismissButton = { TextButton({ confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, danger: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(36.dp).background(if (danger) colors.error.copy(alpha = 0.12f) else colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, null, tint = if (danger) colors.error else colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface, modifier = Modifier.weight(1f))
    if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
  }
}

@Composable
private fun TextDialog(
  title: String,
  initial: String,
  maxLength: Int,
  singleLine: Boolean,
  onDone: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by rememberSaveable { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(maxLength) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        supportingText = { Text("${text.length} / $maxLength") },
      )
    },
    confirmButton = { TextButton({ onDone(text.trim()) }, enabled = !singleLine || text.isNotBlank()) { Text(stringResource(R.string.save)) } },
    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
