package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samvad.core.safety.ReportReason
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** Replaces the composer while I have blocked this contact. */
@Composable
internal fun BlockedBar(name: String, onUnblock: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .background(colors.background)
      .navigationBarsPadding()
      .padding(horizontal = 12.dp, vertical = 10.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(18.dp))
      .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Outlined.Block, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    Text(
      stringResource(R.string.blocked_banner, name),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = onUnblock) {
      Text(stringResource(R.string.chat_unblock), color = colors.primary, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
internal fun BlockDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val colors = SamvadTheme.colors
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Outlined.Block, null, tint = colors.error) },
    title = { Text(stringResource(R.string.block_title, name)) },
    text = { Text(stringResource(R.string.block_body)) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_block), color = colors.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

private val REASON_LABELS = listOf(
  ReportReason.SPAM to R.string.report_reason_spam,
  ReportReason.ABUSE to R.string.report_reason_abuse,
  ReportReason.HARASSMENT to R.string.report_reason_harassment,
  ReportReason.IMPERSONATION to R.string.report_reason_impersonation,
  ReportReason.OTHER to R.string.report_reason_other,
)

/** Reason, "also block" (on by default) and the evidence disclosure, shown before anything is sent. */
@Composable
internal fun ReportDialog(
  name: String,
  sending: Boolean,
  alreadyBlocked: Boolean,
  onSend: (ReportReason, Boolean) -> Unit,
  onDismiss: () -> Unit,
  /** Replaces the default explanation, for reports that start somewhere other than a chat. */
  body: String? = null,
) {
  val colors = SamvadTheme.colors
  var reason by rememberSaveable { mutableStateOf(ReportReason.SPAM) }
  var alsoBlock by rememberSaveable { mutableStateOf(!alreadyBlocked) }
  AlertDialog(
    onDismissRequest = { if (!sending) onDismiss() },
    title = { Text(stringResource(R.string.report_title, name)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(body ?: stringResource(R.string.report_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Column(Modifier.selectableGroup().padding(top = 8.dp)) {
          REASON_LABELS.forEach { (value, label) ->
            Row(
              Modifier
                .fillMaxWidth()
                .selectable(selected = reason == value, role = Role.RadioButton, onClick = { reason = value })
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(
                selected = reason == value,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = colors.primary),
              )
              Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(start = 12.dp))
            }
          }
        }
        if (!alreadyBlocked) {
          Row(
            Modifier
              .fillMaxWidth()
              .toggleable(alsoBlock, role = Role.Checkbox, onValueChange = { alsoBlock = it })
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = alsoBlock, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
            Text(stringResource(R.string.report_also_block), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(start = 12.dp))
          }
        }
      }
    },
    confirmButton = {
      TextButton(enabled = !sending, onClick = { onSend(reason, alsoBlock && !alreadyBlocked) }) {
        Text(stringResource(R.string.chat_report), color = colors.error)
      }
    },
    dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
