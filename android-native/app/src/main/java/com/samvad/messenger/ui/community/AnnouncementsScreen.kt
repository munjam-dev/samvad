package com.samvad.messenger.ui.community

import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Message
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme

/** The community's Announcements channel: everyone reads; only admins get a composer (the server enforces it too). */
@Composable
fun AnnouncementsScreen(container: AppContainer, me: String, communityId: String, cid: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: AnnouncementsViewModel = viewModel(key = "ann-$cid", factory = AnnouncementsViewModel.factory(container, me, communityId, cid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val sending = vm.sending.collectAsStateWithLifecycle().value
  var draft by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()

  LaunchedEffect(state.messages.size) {
    vm.markRead()
    if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
  }

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.community_announcements), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(state.communityName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    Box(Modifier.weight(1f)) {
      when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
        state.messages.isEmpty() -> EmptyState(
          Icons.Outlined.Campaign,
          stringResource(R.string.community_announcements_empty_title),
          stringResource(if (state.canPost) R.string.community_announcements_empty_admin_body else R.string.community_announcements_empty_body),
          modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(state.messages, key = { it.id }) { m ->
            AnnouncementCard(m, vm.nameOf(m.senderId), mine = m.senderId == me, onReact = { vm.react(m.id, it) })
          }
        }
      }
    }
    if (state.canPost) {
      Row(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        OutlinedTextField(
          value = draft,
          onValueChange = { draft = it },
          placeholder = { Text(stringResource(R.string.community_announcement_hint)) },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(20.dp),
          maxLines = 6,
        )
        IconButton(
          enabled = draft.isNotBlank() && !sending,
          onClick = { vm.send(draft); draft = "" },
          modifier = Modifier.background(colors.primary, androidx.compose.foundation.shape.CircleShape).size(48.dp),
        ) {
          Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.cd_send), tint = colors.onPrimary)
        }
      }
    }
  }
}

@Composable
private fun AnnouncementCard(message: Message, senderName: String, mine: Boolean, onReact: (String?) -> Unit) {
  val colors = SamvadTheme.colors
  Column(
    Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Avatar(ChatList.initials(senderName), message.senderId, size = 32.dp)
      Text(senderName, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
      Text(TimeLabels.clock(message.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
    Text(message.text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    if (message.reactions.isNotEmpty()) {
      Text(
        message.reactions.values.toSet().joinToString(" "),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .clickable(role = Role.Button, onClickLabel = stringResource(R.string.a11y_react_thumbs_up)) { onReact("👍") },
      )
    }
  }
}

