package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** The card for an "event" message in the chat timeline. Tapping opens the group's Events list. */
@Composable
fun EventMessageCard(title: String, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .widthIn(min = 220.dp)
      .fillMaxWidth()
      .background(colors.surface, RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(36.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.CalendarMonth, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
    }
    androidx.compose.foundation.layout.Column {
      Text(stringResource(R.string.group_event_card_label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
      Text(title.ifBlank { stringResource(R.string.group_event_new) }, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}
