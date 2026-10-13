package com.samvad.messenger.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.MessageRules
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

/** "Forwarded" above a forwarded copy's content. */
@Composable
internal fun ForwardedLabel(tint: Color) {
  Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Icon(Icons.AutoMirrored.Outlined.Forward, null, tint = tint, modifier = Modifier.size(14.dp))
    Text(stringResource(R.string.forwarded), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = tint)
  }
}

/** Pick up to [MessageRules.MAX_FORWARD_TARGETS] chats to forward to. */
@Composable
internal fun ForwardSheet(targets: List<ForwardTarget>?, onSend: (List<ForwardTarget>) -> Unit) {
  val colors = SamvadTheme.colors
  val chosen = remember { mutableStateListOf<String>() }
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
    Text(stringResource(R.string.forward_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    Text(
      stringResource(R.string.forward_limit, MessageRules.MAX_FORWARD_TARGETS),
      style = MaterialTheme.typography.bodySmall,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    when {
      targets == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp), color = colors.brand)
      targets.isEmpty() -> Text(stringResource(R.string.forward_empty), color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
      else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(targets, key = { it.conversationId }) { t ->
          val checked = t.conversationId in chosen
          val full = chosen.size >= MessageRules.MAX_FORWARD_TARGETS
          Row(
            Modifier
              .fillMaxWidth()
              .toggleable(checked, enabled = checked || !full, role = Role.Checkbox) { on ->
                if (on) chosen += t.conversationId else chosen -= t.conversationId
              }
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Avatar(ChatList.initials(t.name), t.otherUid, size = 40.dp)
            Text(t.name, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Checkbox(checked = checked, onCheckedChange = null, enabled = checked || !full, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
          }
        }
      }
    }
    PrimaryButton(
      stringResource(R.string.forward_send, chosen.size),
      onClick = { onSend(targets.orEmpty().filter { it.conversationId in chosen }) },
      enabled = chosen.isNotEmpty(),
      modifier = Modifier.padding(top = 12.dp),
    )
  }
}
