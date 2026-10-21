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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.call.CallDirection
import com.samvad.core.call.CallHistory
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallRecord
import com.samvad.core.call.CallRules
import com.samvad.core.call.CallStatus
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow

/** The Calls tab: real call history, grouped, with a call-back button on each row. */
@Composable
fun CallsTab(container: AppContainer, me: String, topBar: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val calls: List<CallRecord> = remember(container.calls) {
    (container.calls?.history(me) ?: emptyFlow()).catch { }
  }.collectAsStateWithLifecycle(emptyList()).value
  val now = System.currentTimeMillis()
  val rows = remember(calls, me) { CallHistory.group(calls.map { it.copy(status = CallRules.effectiveStatus(it, now)) }, me) }
  val profiles = container.chats?.profiles?.collectAsStateWithLifecycle()?.value.orEmpty()
  LaunchedEffect(rows.map { it.other }) { container.chats?.loadProfiles(rows.map { it.other }.distinct()) }

  Column(Modifier.fillMaxSize()) {
    topBar()
    if (rows.isEmpty()) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        EmptyState(Icons.Outlined.Call, stringResource(R.string.calls_empty_title), stringResource(R.string.calls_empty_body))
      }
    } else {
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 120.dp)) {
        items(rows, key = { it.latest.id }) { row ->
          val name = profiles[row.other]?.displayName ?: ""
          val call = row.latest
          val missed = row.direction == CallDirection.MISSED
          val dirText = stringResource(
            when (row.direction) {
              CallDirection.OUTGOING -> R.string.call_dir_outgoing
              CallDirection.INCOMING -> R.string.call_dir_incoming
              CallDirection.MISSED -> R.string.call_dir_missed
            },
          )
          val seconds = CallRules.durationSeconds(call)
          val detail = buildString {
            append(dirText)
            if (seconds > 0) append(" · ").append(CallRules.formatDuration(seconds))
            append(" · ").append(TimeLabels.chatListTime(call.createdAtMs, now))
          }
          val backLabel = stringResource(if (call.kind == CallKind.VIDEO) R.string.call_back_video else R.string.call_back_voice, name)
          Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 8.dp),
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
                Icon(
                  when (row.direction) {
                    CallDirection.OUTGOING -> Icons.AutoMirrored.Outlined.CallMade
                    CallDirection.INCOMING -> Icons.AutoMirrored.Outlined.CallReceived
                    CallDirection.MISSED -> Icons.AutoMirrored.Outlined.CallMissed
                  },
                  null, tint = if (missed) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
            IconButton(
              onClick = {
                AppCalls.start(context, ConversationIds.direct(me, row.other), row.other, name, call.kind)
              },
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
}

/** One doorway for the rest of the app. */
object AppCalls {
  fun start(context: android.content.Context, conversationId: String, otherUid: String, name: String, kind: CallKind) =
    CallActivity.startCall(context, conversationId, otherUid, name, kind)
}
