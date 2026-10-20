package com.samvad.messenger.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.JoinRequest
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.theme.SamvadTheme

/** One admin-only group setting with its explanation; the switch state always mirrors the server. */
@Composable
internal fun GroupSettingRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  body: String,
  checked: Boolean,
  busy: Boolean,
  onChange: (Boolean) -> Unit,
) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(icon, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = colors.brand, strokeWidth = 2.dp)
    else Switch(
      checked = checked,
      onCheckedChange = onChange,
      colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
    )
  }
}

/** The explanation shown to non-admin members instead of a composer, while announcement mode is on. */
@Composable
fun AnnouncementNotice(modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  Row(
    modifier
      .fillMaxWidth()
      .background(colors.surfaceContainer)
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Outlined.Campaign, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    Text(stringResource(R.string.group_announce_notice), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
  }
}

/** Group info: "Pending requests" row with a count badge. */
@Composable
internal fun PendingRequestsRow(count: Int, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    androidx.compose.foundation.layout.Box(Modifier.size(36.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.PersonAddAlt, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
    Text(stringResource(R.string.group_pending_requests), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
    Text(
      "$count",
      style = MaterialTheme.typography.labelLarge,
      color = colors.onError,
      modifier = Modifier.background(colors.error, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
    )
  }
}

/** Approve / decline sheet for a group's pending join requests. */
@Composable
fun PendingRequestsSheet(requests: List<JoinRequest>, busyUid: String?, names: (String) -> String, onDecide: (String, Boolean) -> Unit, onClose: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Text(
      stringResource(R.string.group_pending_requests),
      style = MaterialTheme.typography.titleLarge,
      color = colors.onSurface,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
    if (requests.isEmpty()) {
      Text(
        stringResource(R.string.group_pending_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
      )
    }
    requests.forEach { req ->
      val name = names(req.uid)
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Avatar(ChatList.initials(name), req.uid, size = 40.dp)
        Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (busyUid == req.uid) {
          CircularProgressIndicator(Modifier.size(20.dp), color = colors.brand, strokeWidth = 2.dp)
        } else {
          IconButton(onClick = { onDecide(req.uid, false) }) { Icon(Icons.Outlined.Close, stringResource(R.string.group_decline), tint = colors.error) }
          IconButton(onClick = { onDecide(req.uid, true) }) { Icon(Icons.Outlined.Check, stringResource(R.string.group_approve), tint = colors.primary) }
        }
      }
    }
  }
}

/** The requester's own view while a request is open: a calm, explained wait. */
@Composable
fun WaitingForApprovalNotice(modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  Row(
    modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Outlined.HourglassTop, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    Text(stringResource(R.string.group_waiting_approval), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
  }
}
