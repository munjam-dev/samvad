package com.samvad.messenger.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.call.CallDirection
import com.samvad.core.call.CallHistory
import com.samvad.core.call.CallHistoryRow
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallRecord
import com.samvad.core.call.CallRules
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

private sealed interface CallsLoad {
  data object Loading : CallsLoad
  data object Failed : CallsLoad
  data class Ready(val calls: List<CallRecord>) : CallsLoad
}

/**
 * The Calls tab: real call history, grouped; tap a row for details and call back, or start a new
 * voice or video call with anyone you chat with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTab(container: AppContainer, me: String, topBar: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  var retry by remember { mutableIntStateOf(0) }
  val load: CallsLoad = remember(container.calls, retry) {
    (container.calls?.history(me) ?: emptyFlow<List<CallRecord>>())
      .map<List<CallRecord>, CallsLoad> { CallsLoad.Ready(it) }
      .catch { emit(CallsLoad.Failed) }
  }.collectAsStateWithLifecycle(CallsLoad.Loading).value
  val calls = (load as? CallsLoad.Ready)?.calls.orEmpty()
  val now = System.currentTimeMillis()
  val rows = remember(calls, me) { CallHistory.group(calls.map { it.copy(status = CallRules.effectiveStatus(it, now)) }, me) }
  val profiles = container.chats?.profiles?.collectAsStateWithLifecycle()?.value.orEmpty()
  LaunchedEffect(rows.map { it.other }) { container.chats?.loadProfiles(rows.map { it.other }.distinct()) }

  val people: List<String> = remember(container.chats, me) {
    (container.chats?.conversations(me) ?: emptyFlow())
      .map { snap -> snap.value.filter { !it.isGroup }.mapNotNull { ConversationIds.otherMember(it, me) }.distinct() }
      .catch { emit(emptyList()) }
  }.collectAsStateWithLifecycle(emptyList()).value
  LaunchedEffect(people) { container.chats?.loadProfiles(people) }

  var picking by remember { mutableStateOf(false) }
  var detail by remember { mutableStateOf<CallHistoryRow?>(null) }

  Column(Modifier.fillMaxSize()) {
    topBar()
    when {
      load is CallsLoad.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.brand)
      }
      load is CallsLoad.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
          Icons.Outlined.CloudOff,
          stringResource(R.string.calls_error_title),
          stringResource(R.string.calls_error_body),
          action = stringResource(R.string.retry),
          onAction = { retry++ },
        )
      }
      rows.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(
          Icons.Outlined.Call,
          stringResource(R.string.calls_empty_title),
          stringResource(R.string.calls_empty_body),
          action = stringResource(R.string.calls_new),
          onAction = { picking = true },
        )
      }
      else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item(key = "new") {
          Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            PrimaryButton(stringResource(R.string.calls_new), onClick = { picking = true }, modifier = Modifier.fillMaxWidth())
          }
        }
        items(rows, key = { it.latest.id }) { row ->
          val name = profiles[row.other]?.displayName ?: ""
          val call = row.latest
          val missed = row.direction == CallDirection.MISSED
          val detailText = detailLine(row, now)
          val backLabel = stringResource(if (call.kind == CallKind.VIDEO) R.string.call_back_video else R.string.call_back_voice, name)
          Row(
            Modifier
              .fillMaxWidth()
              .heightIn(min = 72.dp)
              .clickable(onClickLabel = stringResource(R.string.call_detail_title)) { detail = row }
              .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Avatar(ChatList.initials(name), row.other, size = 48.dp)
            Column(Modifier.weight(1f)) {
              Text(
                if (row.count > 1) "$name (${row.count})" else name,
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) colors.error else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(directionIcon(row.direction), null, tint = if (missed) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(detailText, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
            IconButton(
              onClick = { AppCalls.start(context, ConversationIds.direct(me, row.other), row.other, name, call.kind) },
              modifier = Modifier.size(48.dp),
            ) {
              Icon(if (call.kind == CallKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Call, backLabel, tint = colors.primary)
            }
          }
        }
        item {
          Text(
            stringResource(R.string.call_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
          )
        }
      }
    }
  }

  detail?.let { row ->
    val name = profiles[row.other]?.displayName ?: ""
    ModalBottomSheet(onDismissRequest = { detail = null }) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          Avatar(ChatList.initials(name), row.other, size = 56.dp)
          Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(detailLine(row, now), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
          }
        }
        PrimaryButton(
          stringResource(R.string.call_detail_voice),
          onClick = { detail = null; AppCalls.start(context, ConversationIds.direct(me, row.other), row.other, name, CallKind.AUDIO) },
          modifier = Modifier.fillMaxWidth(),
        )
        QuietButton(
          stringResource(R.string.call_detail_video),
          onClick = { detail = null; AppCalls.start(context, ConversationIds.direct(me, row.other), row.other, name, CallKind.VIDEO) },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }

  if (picking) {
    ModalBottomSheet(onDismissRequest = { picking = false }) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Text(
          stringResource(R.string.calls_new_title),
          style = MaterialTheme.typography.titleLarge,
          color = colors.onSurface,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        if (people.isEmpty()) {
          Text(
            stringResource(R.string.calls_pick_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
          )
        } else {
          LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(people, key = { it }) { uid ->
              val name = profiles[uid]?.displayName ?: ""
              Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                Avatar(ChatList.initials(name), uid, size = 44.dp)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(
                  onClick = { picking = false; AppCalls.start(context, ConversationIds.direct(me, uid), uid, name, CallKind.AUDIO) },
                  modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Outlined.Call, stringResource(R.string.call_back_voice, name), tint = colors.primary) }
                IconButton(
                  onClick = { picking = false; AppCalls.start(context, ConversationIds.direct(me, uid), uid, name, CallKind.VIDEO) },
                  modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Outlined.Videocam, stringResource(R.string.call_back_video, name), tint = colors.primary) }
              }
            }
          }
        }
      }
    }
  }
}

private fun directionIcon(d: CallDirection) = when (d) {
  CallDirection.OUTGOING -> Icons.AutoMirrored.Outlined.CallMade
  CallDirection.INCOMING -> Icons.AutoMirrored.Outlined.CallReceived
  CallDirection.MISSED -> Icons.AutoMirrored.Outlined.CallMissed
}

@Composable
private fun detailLine(row: CallHistoryRow, now: Long): String {
  val dirText = stringResource(
    when (row.direction) {
      CallDirection.OUTGOING -> R.string.call_dir_outgoing
      CallDirection.INCOMING -> R.string.call_dir_incoming
      CallDirection.MISSED -> R.string.call_dir_missed
    },
  )
  val seconds = CallRules.durationSeconds(row.latest)
  return buildString {
    append(dirText)
    if (seconds > 0) append(" · ").append(CallRules.formatDuration(seconds))
    append(" · ").append(TimeLabels.chatListTime(row.latest.createdAtMs, now))
  }
}

/** One doorway for the rest of the app. */
object AppCalls {
  fun start(context: android.content.Context, conversationId: String, otherUid: String, name: String, kind: CallKind) =
    CallActivity.startCall(context, conversationId, otherUid, name, kind)
}
