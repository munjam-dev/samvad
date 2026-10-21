package com.samvad.messenger.ui.community

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
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonAdd
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
import com.samvad.core.community.CommunityRole
import com.samvad.core.community.CommunityRules
import com.samvad.core.community.MemberAction
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
  container: AppContainer,
  me: String,
  id: String,
  onBack: () -> Unit,
  onOpenAnnouncements: (String) -> Unit,
  onOpenGroup: (String) -> Unit,
  onAddMembers: () -> Unit,
  onLeft: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val vm: CommunityViewModel = viewModel(key = "community-$id", factory = CommunityViewModel.factory(container, me, id))
  val state = vm.state.collectAsStateWithLifecycle().value
  val busy = vm.busy.collectAsStateWithLifecycle().value
  val error = vm.error.collectAsStateWithLifecycle().value
  val invite = vm.invite.collectAsStateWithLifecycle().value
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  var photoMenu by remember { mutableStateOf(false) }
  var editingName by remember { mutableStateOf(false) }
  var editingDescription by remember { mutableStateOf(false) }
  var selectedMember by remember { mutableStateOf<String?>(null) }
  var confirmLeave by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var unlinkGroup by remember { mutableStateOf<LinkedGroupRow?>(null) }
  val errorText = error?.let { communityErrorText(it) }
  LaunchedEffect(errorText) {
    if (errorText != null) {
      vm.clearError()
      snackbar.showSnackbar(errorText)
    }
  }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setPhoto) }
  val shareText = stringResource(R.string.community_invite_share, state.name, invite.orEmpty())
  val shareTitle = stringResource(R.string.community_invite)

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.community_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = colors.brand, strokeWidth = 2.5.dp)
      }
      when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
        state.gone -> Text(
          stringResource(R.string.community_removed),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.onSurfaceVariant,
          modifier = Modifier.padding(24.dp),
        )
        else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
          item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(Modifier.then(if (state.isAdmin) Modifier.clickable(onClickLabel = stringResource(R.string.community_change_photo)) { photoMenu = true } else Modifier)) {
                Avatar(communityInitials(state.name), id, size = 96.dp, photoPath = state.photoPath)
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.isAdmin) {
                  IconButton(onClick = { editingName = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.community_rename), tint = colors.primary) }
                }
              }
              if (state.description.isNotBlank() || state.isAdmin) {
                Text(
                  state.description.ifBlank { stringResource(R.string.community_add_description) },
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (state.description.isBlank()) colors.onSurfaceVariant else colors.onSurfaceVariant,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                  modifier = if (state.isAdmin) Modifier.clickable { editingDescription = true } else Modifier,
                )
              }
              Text(pluralStringResource(R.plurals.community_members_count, state.memberCount, state.memberCount), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
          }
          item(key = "announcements") {
            Row(
              Modifier.fillMaxWidth().clickable { onOpenAnnouncements(state.announcementsCid) }.padding(horizontal = 20.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Box(Modifier.size(36.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Campaign, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
              }
              Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.community_announcements), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(
                  stringResource(if (state.isAdmin) R.string.community_announcements_sub_admin else R.string.community_announcements_sub_member),
                  style = MaterialTheme.typography.bodySmall,
                  color = colors.onSurfaceVariant,
                )
              }
            }
          }
          if (state.isAdmin) {
            item(key = "add") { InfoRow(Icons.Outlined.PersonAdd, stringResource(R.string.community_add_members), onClick = onAddMembers) }
            item(key = "invite") { InfoRow(Icons.Outlined.Link, stringResource(R.string.community_invite), onClick = vm::showInvite) }
          }
          item(key = "groups-h") {
            Text(
              stringResource(R.string.community_groups_heading).uppercase(),
              style = MaterialTheme.typography.labelMedium,
              color = colors.onSurfaceVariant,
              modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
            )
          }
          if (state.groups.isEmpty()) {
            item(key = "groups-empty") {
              Text(
                stringResource(R.string.community_groups_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
              )
            }
          }
          items(state.groups, key = { "g-${it.cid}" }) { g ->
            GroupCard(
              g,
              isAdmin = state.isAdmin,
              busy = busy,
              onJoinOrOpen = { if (g.joined) onOpenGroup(g.cid) else vm.joinGroup(g.cid, onOpenGroup) },
              onUnlink = { unlinkGroup = g },
            )
          }
          item(key = "members-h") {
            Text(
              pluralStringResource(R.plurals.community_members_count, state.memberCount, state.memberCount).uppercase(),
              style = MaterialTheme.typography.labelMedium,
              color = colors.onSurfaceVariant,
              modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
            )
          }
          items(state.members, key = { "m-${it.uid}" }) { m ->
            Row(
              Modifier
                .fillMaxWidth()
                .then(if (!m.me && state.isAdmin) Modifier.clickable { selectedMember = m.uid } else Modifier)
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
              if (m.role == CommunityRole.ADMIN) {
                Text(
                  stringResource(R.string.community_admin),
                  style = MaterialTheme.typography.labelSmall,
                  color = colors.onPrimaryContainer,
                  modifier = Modifier.background(colors.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }
            }
          }
          item(key = "leave") {
            InfoRow(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.community_leave), danger = true, onClick = { confirmLeave = true })
          }
          if (state.isAdmin) {
            item(key = "delete") {
              InfoRow(Icons.Outlined.Delete, stringResource(R.string.community_delete), danger = true, onClick = { confirmDelete = true })
            }
          }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }

  if (editingName) {
    CommunityTextDialog(
      title = stringResource(R.string.community_rename),
      initial = state.name,
      maxLength = CommunityRules.MAX_NAME,
      singleLine = true,
      onDone = { vm.rename(it); editingName = false },
      onDismiss = { editingName = false },
    )
  }
  if (editingDescription) {
    CommunityTextDialog(
      title = stringResource(R.string.community_description_hint),
      initial = state.description,
      maxLength = CommunityRules.MAX_DESCRIPTION,
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
  selectedMember?.let { uid ->
    val name = state.members.firstOrNull { it.uid == uid }?.name.orEmpty()
    val isTargetAdmin = state.members.firstOrNull { it.uid == uid }?.role == CommunityRole.ADMIN
    ModalBottomSheet(onDismissRequest = { selectedMember = null }) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        vm.memberActions(uid).forEach { action ->
          when (action) {
            MemberAction.PROMOTE -> InfoRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.community_make_admin), onClick = { selectedMember = null; vm.setAdmin(uid, true) })
            MemberAction.DEMOTE -> InfoRow(Icons.Outlined.AdminPanelSettings, stringResource(R.string.community_dismiss_admin), onClick = { selectedMember = null; vm.setAdmin(uid, false) })
            MemberAction.REMOVE -> InfoRow(Icons.Outlined.PersonRemove, stringResource(R.string.community_remove_member, name), danger = true, onClick = { selectedMember = null; vm.removeMember(uid) })
          }
        }
        if (vm.memberActions(uid).isEmpty() && !isTargetAdmin) {
          Text(stringResource(R.string.community_no_actions), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
      }
    }
  }
  invite?.let { code ->
    ModalBottomSheet(onDismissRequest = vm::hideInvite) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.community_invite), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(stringResource(R.string.community_invite_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(
          CommunityRules.displayInvite(code),
          style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
          fontWeight = FontWeight.SemiBold,
          color = colors.onSurface,
          modifier = Modifier.align(Alignment.CenterHorizontally).background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        )
        InfoRow(Icons.Outlined.Share, stringResource(R.string.community_invite_send), onClick = {
          container.appLock.expectExternal()
          context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), shareTitle))
        })
        InfoRow(Icons.Outlined.ContentCopy, stringResource(R.string.community_invite_copy), onClick = { clipboard.setText(AnnotatedString(code)) })
        InfoRow(Icons.Outlined.Edit, stringResource(R.string.community_invite_regenerate), onClick = vm::regenerateInvite)
        InfoRow(Icons.Outlined.LinkOff, stringResource(R.string.community_invite_revoke), danger = true, onClick = vm::revokeInvite)
      }
    }
  }
  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text(stringResource(R.string.community_leave_title, state.name)) },
      text = { Text(stringResource(R.string.community_leave_body)) },
      confirmButton = { TextButton({ confirmLeave = false; vm.leave(onLeft) }) { Text(stringResource(R.string.community_leave), color = colors.error) } },
      dismissButton = { TextButton({ confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text(stringResource(R.string.community_delete_title, state.name)) },
      text = { Text(stringResource(R.string.community_delete_body)) },
      confirmButton = { TextButton({ confirmDelete = false; vm.deleteCommunity(onLeft) }) { Text(stringResource(R.string.community_delete), color = colors.error) } },
      dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  unlinkGroup?.let { g ->
    AlertDialog(
      onDismissRequest = { unlinkGroup = null },
      title = { Text(stringResource(R.string.community_unlink_title, g.name)) },
      text = { Text(stringResource(R.string.community_unlink_body)) },
      confirmButton = { TextButton({ val t = g; unlinkGroup = null; vm.unlinkGroup(t.cid) }) { Text(stringResource(R.string.community_unlink), color = colors.error) } },
      dismissButton = { TextButton({ unlinkGroup = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun GroupCard(group: LinkedGroupRow, isAdmin: Boolean, busy: Boolean, onJoinOrOpen: () -> Unit, onUnlink: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).background(colors.surface, RoundedCornerShape(16.dp)).padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Avatar(ChatList.initials(group.name), group.cid, size = 44.dp)
    Text(group.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    if (isAdmin) {
      IconButton(onClick = onUnlink) { Icon(Icons.Outlined.LinkOff, stringResource(R.string.community_unlink), tint = colors.onSurfaceVariant) }
    }
    androidx.compose.material3.TextButton(onClick = onJoinOrOpen, enabled = !busy) {
      Text(stringResource(if (group.joined) R.string.community_group_open else R.string.community_group_join), color = colors.primary, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(36.dp).background(if (danger) colors.error.copy(alpha = 0.12f) else colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, null, tint = if (danger) colors.error else colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface)
  }
}

@Composable
private fun CommunityTextDialog(
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
        supportingText = { Text(stringResource(R.string.a11y_character_count, text.length, maxLength)) },
      )
    },
    confirmButton = { TextButton({ onDone(text.trim()) }, enabled = !singleLine || text.isNotBlank()) { Text(stringResource(R.string.save)) } },
    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
internal fun communityErrorText(error: CommunityError): String = stringResource(
  when (error) {
    CommunityError.FULL -> R.string.community_error_full
    CommunityError.GROUPS_FULL -> R.string.community_error_groups_full
    CommunityError.GROUP_FULL -> R.string.group_error_full
    CommunityError.ADMIN_LIMIT -> R.string.community_error_admin_limit
    CommunityError.LAST_ADMIN -> R.string.community_error_last_admin
    CommunityError.ALREADY_LINKED -> R.string.community_error_already_linked
    CommunityError.NOT_ALLOWED -> R.string.group_error_not_allowed
    CommunityError.LIMIT -> R.string.group_error_limit
    CommunityError.FAILED -> R.string.group_error_failed
  },
)
