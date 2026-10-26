package com.samvad.messenger.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.JoinRequest
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/**
 * Groups where I asked to join and an admin has not decided yet. Read-only rows, each with a "Cancel request"
 * action behind a confirmation. Shows nothing when there is none.
 */
@Composable
fun WaitingForApproval(container: AppContainer, me: String) {
  val chats = container.chats ?: return
  val groups = container.groups ?: return
  val requests = remember(me) { chats.myJoinRequests(me) }.collectAsStateWithLifecycle(emptyList()).value
  if (requests.isEmpty()) return
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  var cancelling by remember { mutableStateOf<JoinRequest?>(null) }
  Column(
    Modifier
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .fillMaxWidth()
      .background(colors.surfaceContainer, RoundedCornerShape(22.dp))
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(stringResource(R.string.waiting_approval_title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
    requests.forEach { r ->
      Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(r.groupName.ifBlank { stringResource(R.string.waiting_approval_unnamed) }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
          Text(stringResource(R.string.waiting_approval_status), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        TextButton(onClick = { cancelling = r }) { Text(stringResource(R.string.waiting_approval_cancel)) }
      }
    }
  }
  cancelling?.let { r ->
    AlertDialog(
      onDismissRequest = { cancelling = null },
      title = { Text(stringResource(R.string.waiting_approval_cancel_title)) },
      text = { Text(stringResource(R.string.waiting_approval_cancel_body, r.groupName)) },
      confirmButton = {
        TextButton({
          scope.launch { runCatching { groups.cancelJoinRequest(r.cid) } }
          cancelling = null
        }) { Text(stringResource(R.string.waiting_approval_cancel), color = colors.error) }
      },
      dismissButton = { TextButton({ cancelling = null }) { Text(stringResource(R.string.waiting_approval_keep)) } },
    )
  }
}
