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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.EventRules
import com.samvad.core.chat.Rsvp
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.GroupException
import com.samvad.messenger.ui.groups.CreateEventSheet
import com.samvad.messenger.ui.groups.EventError
import kotlinx.coroutines.launch
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * The event card with live details, counts and Going / Maybe / Can't go right in the timeline. The creator
 * or an admin also gets an edit button until the event starts. Falls back to the plain card while loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveEventMessageCard(
  container: AppContainer,
  me: String,
  cid: String,
  eventId: String,
  fallbackTitle: String,
  onOpenEvents: () -> Unit,
) {
  val chats = container.chats ?: return
  val groups = container.groups ?: return
  val colors = SamvadTheme.colors
  val event = remember(cid, eventId) { chats.groupEvent(cid, eventId) }.collectAsStateWithLifecycle(null).value
  val mine = remember(cid, eventId) { chats.myRsvp(cid, eventId, me) }.collectAsStateWithLifecycle(null).value
  val group = remember(cid) { chats.conversation(cid) }.collectAsStateWithLifecycle(null).value
  val scope = rememberCoroutineScope()
  var editing by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<EventError?>(null) }
  if (event == null) {
    EventMessageCard(fallbackTitle, onOpenEvents)
    return
  }
  val now = System.currentTimeMillis()
  val canAnswer = EventRules.canAnswer(event, now)
  val canEdit = group != null && EventRules.canEdit(event, group, me, now)
  Column(
    Modifier
      .widthIn(min = 220.dp)
      .fillMaxWidth()
      .background(colors.surface, RoundedCornerShape(14.dp))
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Outlined.CalendarMonth, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
      Text(
        stringResource(R.string.group_event_card_label),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      if (canEdit) {
        IconButton(onClick = { error = null; editing = true }) {
          Icon(Icons.Outlined.Edit, stringResource(R.string.group_event_edit), tint = colors.onSurfaceVariant)
        }
      }
    }
    Text(event.title.ifBlank { fallbackTitle }, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
    if (event.cancelled) {
      Text(stringResource(R.string.group_event_cancelled), style = MaterialTheme.typography.labelMedium, color = colors.error)
    }
    Text(
      "${TimeLabels.clock(event.startsAt)} · ${TimeLabels.chatListTime(event.startsAt, now)}",
      style = MaterialTheme.typography.bodySmall,
      color = colors.onSurfaceVariant,
    )
    if (event.place.isNotBlank()) Text(event.place, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    if (event.description.isNotBlank()) Text(event.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    Text(
      stringResource(R.string.group_event_counts_full, event.counts.going, event.counts.maybe, event.counts.no),
      style = MaterialTheme.typography.labelMedium,
      color = colors.onSurfaceVariant,
    )
    if (canAnswer) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
          Rsvp.GOING to R.string.group_event_going,
          Rsvp.MAYBE to R.string.group_event_maybe,
          Rsvp.NO to R.string.group_event_no,
        ).forEach { (answer, label) ->
          val selected = mine == answer
          Box(
            Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .background(if (selected) colors.primary else colors.surfaceContainer, RoundedCornerShape(10.dp))
              .clickable(role = Role.Button) {
                scope.launch { runCatching { groups.rsvpEvent(cid, eventId, if (selected) null else answer) } }
              }
              .padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              stringResource(label),
              style = MaterialTheme.typography.labelLarge,
              color = if (selected) colors.onPrimary else colors.onSurface,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    }
  }
  if (editing) {
    ModalBottomSheet(onDismissRequest = { editing = false }) {
      CreateEventSheet(
        busy = busy,
        error = error,
        onClearError = { error = null },
        onCreate = { title, desc, place, startsAt ->
          scope.launch {
            busy = true
            error = try {
              groups.editEvent(cid, eventId, title, desc, place, startsAt)
              editing = false
              null
            } catch (e: GroupException.EventInPast) {
              EventError.PAST
            } catch (e: GroupException.EventTooFar) {
              EventError.TOO_FAR
            } catch (e: GroupException.EventGone) {
              EventError.GONE
            } catch (e: GroupException.LimitReached) {
              EventError.DAILY_LIMIT
            } catch (e: GroupException.NotAllowed) {
              EventError.NOT_ALLOWED
            } catch (e: Exception) {
              EventError.FAILED
            }
            busy = false
          }
        },
        onClose = { editing = false },
        initial = event,
      )
    }
  }
}

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
