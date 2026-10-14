package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.ConversationIds
import com.samvad.core.chat.Message
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.StarredRef
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.LocalPhotoSource
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One starred message as listed: where it lives, and how that chat is named on this device. */
data class StarredRow(val ref: StarredRef, val title: String, val isGroup: Boolean, val otherUid: String?, val photoPath: String?)

class StarredViewModel(private val container: AppContainer, val me: String, private val onlyChat: String?) : ViewModel() {
  private val chats = container.chats!!

  /** Null while loading. */
  val rows: StateFlow<List<StarredRow>?> = combine(chats.starred(me), chats.conversations(me), chats.profiles) { refs, convs, profiles ->
    val byId: Map<String, Conversation> = convs.value.associateBy { it.id }
    val others = byId.values.filter { !it.isGroup }.mapNotNull { ConversationIds.otherMember(it, me) }
    viewModelScope.launch { chats.loadProfiles(others) }
    refs.filter { onlyChat == null || it.conversationId == onlyChat }.map { ref ->
      val c = byId[ref.conversationId]
      val other = c?.let { if (it.isGroup) null else ConversationIds.otherMember(it, me) }
      val title = when {
        c == null -> ChatList.DELETED_ACCOUNT
        c.isGroup -> c.name?.takeIf { it.isNotBlank() } ?: ChatList.GROUP
        else -> other?.let { profiles[it]?.displayName } ?: ChatList.DELETED_ACCOUNT
      }
      StarredRow(ref, title, c?.isGroup ?: false, other, if (c?.isGroup == true) c.photoPath else null)
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  suspend fun message(ref: StarredRef): Message? = chats.message(ref.conversationId, ref.messageId)

  fun senderName(uid: String): String = chats.profiles.value[uid]?.displayName ?: ChatList.DELETED_ACCOUNT

  fun unstar(ref: StarredRef) = viewModelScope.launch { runCatching { chats.unstar(me, ref.conversationId, ref.messageId) } }

  companion object {
    fun factory(container: AppContainer, me: String, onlyChat: String?) =
      viewModelFactory { initializer { StarredViewModel(container, me, onlyChat) } }
  }
}

/**
 * Pattern: list detail (Starred messages). Every message I starred, newest first; tapping opens its
 * chat. [onlyChat] limits the list to one conversation (opened from that chat's menu).
 */
@Composable
fun StarredScreen(
  container: AppContainer,
  me: String,
  onlyChat: String?,
  onBack: () -> Unit,
  onOpenChat: (conversationId: String, otherUid: String?) -> Unit,
) {
  val colors = SamvadTheme.colors
  val vm: StarredViewModel = viewModel(key = "starred-${onlyChat.orEmpty()}", factory = StarredViewModel.factory(container, me, onlyChat))
  val rows = vm.rows.collectAsStateWithLifecycle().value

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(
      Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.starred_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = colors.outlineVariant)
    when {
      rows == null -> Unit
      rows.isEmpty() -> Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(Icons.Outlined.Star, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(
          stringResource(R.string.starred_empty),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 12.dp),
        )
      }
      else -> LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { "${it.ref.conversationId}_${it.ref.messageId}" }) { row ->
          StarredRowItem(row, vm, onOpen = { onOpenChat(row.ref.conversationId, row.otherUid) })
          HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(start = 76.dp))
        }
      }
    }
  }
}

@Composable
private fun StarredRowItem(row: StarredRow, vm: StarredViewModel, onOpen: () -> Unit) {
  val colors = SamvadTheme.colors
  // Read on demand: a deleted message, or one in a group I left, comes back null. First = loaded.
  val loaded by produceState<Pair<Boolean, Message?>>(false to null, row.ref) { value = true to vm.message(row.ref) }
  val m = loaded.second
  val available = m != null && !m.deleted
  val now = System.currentTimeMillis()
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(enabled = available, onClick = onOpen)
      .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Avatar(
      ChatList.initials(row.title),
      row.otherUid ?: row.ref.conversationId,
      size = 44.dp,
      photoPath = if (row.isGroup) row.photoPath else LocalPhotoSource.current?.pathFor(row.otherUid.orEmpty()),
    )
    Column(Modifier.weight(1f).padding(start = 16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(TimeLabels.chatListTime(m?.createdAt ?: row.ref.savedAt, now), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
      }
      val line = when {
        !loaded.first -> ""
        m == null || !available -> stringResource(R.string.starred_unavailable)
        m.senderId == vm.me -> stringResource(R.string.starred_you, previewOf(m))
        row.isGroup -> "${vm.senderName(m.senderId)}: ${previewOf(m)}"
        else -> previewOf(m)
      }
      Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    IconButton(onClick = { vm.unstar(row.ref) }) {
      Icon(Icons.Outlined.StarOutline, stringResource(R.string.starred_remove), tint = colors.onSurfaceVariant)
    }
  }
}

@Composable
private fun previewOf(m: Message): String = when (m.kind) {
  "image" -> stringResource(R.string.starred_kind_photo)
  "voice" -> stringResource(R.string.starred_kind_voice)
  "file" -> m.media?.name ?: stringResource(R.string.starred_kind_file)
  "poll" -> m.poll?.question ?: stringResource(R.string.starred_kind_poll)
  else -> m.text
}
