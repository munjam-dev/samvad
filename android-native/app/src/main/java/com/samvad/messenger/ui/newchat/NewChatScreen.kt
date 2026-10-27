package com.samvad.messenger.ui.newchat

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme

@Composable
fun NewChatScreen(
  container: AppContainer,
  me: String,
  onBack: () -> Unit,
  onOpenChat: (conversationId: String, otherUid: String) -> Unit,
  onNewGroup: () -> Unit = {},
  onOpenGroup: (String) -> Unit = {},
) {
  var joining by remember { mutableStateOf(false) }
  val vm: NewChatViewModel = viewModel(factory = NewChatViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::onPermissionResult)
  val inviteText = stringResource(R.string.newchat_invite_text)
  val invite = {
    context.startActivity(
      Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText), null),
    )
  }

  Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.newchat_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
      if (container.groups != null) {
        item(key = "new-group") { ActionEntry(Icons.Outlined.GroupAdd, stringResource(R.string.group_new), onNewGroup) }
        item(key = "join-group") { ActionEntry(Icons.Outlined.Link, stringResource(R.string.group_join)) { joining = true } }
      }
      item(key = "by-number") {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.newchat_by_number), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
          SamvadTextField(
            state.number, vm::onNumber, stringResource(R.string.newchat_number_hint),
            keyboardType = KeyboardType.Phone,
            isError = state.numberNotFound || state.invalidInput,
            supportingText = when {
              state.invalidInput -> stringResource(R.string.newchat_invalid_input)
              state.numberNotFound -> stringResource(R.string.newchat_not_found)
              else -> null
            },
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
              stringResource(R.string.newchat_find),
              { vm.find() },
              enabled = state.number.isNotBlank(),
              loading = state.finding,
              modifier = Modifier.weight(1f),
            )
          }
          state.found?.let { person ->
            ContactRow(person.displayName, person.uid, action = stringResource(R.string.newchat_start), onClick = { vm.open(person.uid, onOpenChat) }, photoPath = person.photoPath)
          }
          if (state.numberNotFound) QuietButton(stringResource(R.string.newchat_invite), invite)
          if (state.error && !state.hasPermission) Text(stringResource(R.string.error_unknown), color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
      }

      if (!state.hasPermission) {
        item(key = "rationale") {
          EmptyState(
            Icons.Outlined.Contacts,
            stringResource(R.string.newchat_permission_title),
            if (state.permissionDenied) stringResource(R.string.newchat_permission_denied) else stringResource(R.string.newchat_permission_body),
            action = stringResource(R.string.newchat_permission_allow),
            onAction = { permission.launch(Manifest.permission.READ_CONTACTS) },
          )
        }
      } else if (state.loading) {
        item(key = "loading") {
          Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
        }
      } else {
        val q = state.query.trim()
        val matches = state.onSamvad.filter { q.isEmpty() || it.name.contains(q, true) }
        item(key = "search") {
          SamvadTextField(state.query, vm::onQuery, stringResource(R.string.newchat_search), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (state.limitReached || state.error) {
          item(key = "sync-note") {
            Text(
              stringResource(if (state.limitReached) R.string.newchat_limit else R.string.newchat_sync_error),
              color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            QuietButton(stringResource(R.string.newchat_retry), { vm.load() }, modifier = Modifier.padding(horizontal = 12.dp))
          }
        }
        item(key = "h1") { Header(stringResource(R.string.newchat_on_samvad)) }
        if (matches.isEmpty()) {
          item(key = "none") {
            Text(stringResource(R.string.newchat_none), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(20.dp))
          }
        }
        items(matches, key = { "s-" + it.uid }) { c ->
          ContactRow(c.name, c.uid, action = null, onClick = { vm.open(c.uid, onOpenChat) })
        }
        val invites = state.invite.filter { q.isEmpty() || it.name.contains(q, true) }.take(200)
        if (invites.isNotEmpty()) {
          item(key = "h2") { Header(stringResource(R.string.newchat_invite)) }
          items(invites, key = { "i-" + it.e164 }) { c ->
            ContactRow(c.name, c.e164, action = stringResource(R.string.newchat_invite), onClick = invite)
          }
        }
      }
    }
  }
  if (joining) {
    com.samvad.messenger.ui.groups.JoinGroupDialog(
      groups = container.groups!!,
      onJoined = { cid -> joining = false; onOpenGroup(cid) },
      onDismiss = { joining = false },
    )
  }
}

@Composable
private fun ActionEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(44.dp).background(colors.primary, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, null, tint = colors.onPrimary)
    }
    Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
  }
}

@Composable
private fun Header(text: String) {
  Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = SamvadTheme.colors.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
  )
}

@Composable
private fun ContactRow(name: String, seed: String, action: String?, onClick: () -> Unit, photoPath: String? = null) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Avatar(ChatList.initials(name), seed, size = 44.dp, photoPath = photoPath)
    Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f), maxLines = 1)
    if (action != null) Text(action, style = MaterialTheme.typography.labelLarge, color = colors.primary)
  }
}
