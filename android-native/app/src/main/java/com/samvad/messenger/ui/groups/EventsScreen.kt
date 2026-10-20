package com.samvad.messenger.ui.groups

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.EventRules
import com.samvad.core.chat.GroupEvent
import com.samvad.core.chat.Rsvp
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(container: AppContainer, me: String, cid: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: EventsViewModel = viewModel(key = "events-$cid", factory = EventsViewModel.factory(container, me, cid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val busy = vm.busy.collectAsStateWithLifecycle().value
  var creating by remember { mutableStateOf(false) }
  var cancelling by remember { mutableStateOf<GroupEvent?>(null) }

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.group_events), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
    Box(Modifier.weight(1f)) {
      when {
        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.brand)
        state.upcoming.isEmpty() && state.earlier.isEmpty() -> EmptyState(
          Icons.Outlined.CalendarMonth,
          stringResource(R.string.group_events_empty_title),
          stringResource(R.string.group_events_empty_body),
          modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
          if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming-h") { SectionHeader(stringResource(R.string.group_events_upcoming)) }
            state.upcoming.forEach { e ->
              item(key = e.id) {
                EventRow(e, me, state.group, vm.rsvpFlow(e.id), onRsvp = { vm.rsvp(e.id, it) }, onCancel = { cancelling = e })
              }
            }
          }
          if (state.earlier.isNotEmpty()) {
            item(key = "earlier-h") { SectionHeader(stringResource(R.string.group_events_earlier)) }
            state.earlier.forEach { e ->
              item(key = e.id) {
                EventRow(e, me, state.group, vm.rsvpFlow(e.id), onRsvp = { vm.rsvp(e.id, it) }, onCancel = { cancelling = e })
              }
            }
          }
        }
      }
      if (state.canCreate) {
        FloatingActionButton(
          onClick = { creating = true },
          containerColor = colors.primary,
          contentColor = colors.onPrimary,
          modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Filled.Add, stringResource(R.string.group_event_new)) }
      }
    }
  }

  if (creating) {
    ModalBottomSheet(onDismissRequest = { creating = false }) {
      CreateEventSheet(
        busy = busy,
        error = vm.error.collectAsStateWithLifecycle().value,
        onClearError = vm::clearError,
        onCreate = { title, desc, place, startsAt -> vm.create(title, desc, place, startsAt) { creating = false } },
        onClose = { creating = false },
      )
    }
  }

  cancelling?.let { e ->
    AlertDialog(
      onDismissRequest = { cancelling = null },
      title = { Text(stringResource(R.string.group_event_cancel_title)) },
      text = { Text(stringResource(R.string.group_event_cancel_body, e.title)) },
      confirmButton = { TextButton({ vm.cancel(e.id); cancelling = null }) { Text(stringResource(R.string.group_event_cancel), color = colors.error) } },
      dismissButton = { TextButton({ cancelling = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun SectionHeader(text: String) {
  val colors = SamvadTheme.colors
  Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = colors.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
  )
}

@Composable
internal fun EventRow(
  event: GroupEvent,
  me: String,
  group: com.samvad.core.chat.Conversation?,
  rsvpFlow: kotlinx.coroutines.flow.Flow<Rsvp?>,
  onRsvp: (Rsvp?) -> Unit,
  onCancel: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val mine = rsvpFlow.collectAsStateWithLifecycle(null).value
  val now = System.currentTimeMillis()
  val canAnswer = EventRules.canAnswer(event, now)
  val canCancel = group != null && EventRules.canCancel(event, group, me)
  Column(
    Modifier
      .fillMaxWidth()
      .background(colors.surfaceContainer, RoundedCornerShape(16.dp))
      .padding(horizontal = 16.dp, vertical = 14.dp)
      .padding(horizontal = 4.dp, vertical = 0.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(event.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
      if (canCancel) {
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.group_event_cancel), tint = colors.error) }
      }
    }
    if (event.cancelled) {
      Text(stringResource(R.string.group_event_cancelled), style = MaterialTheme.typography.labelMedium, color = colors.error)
    }
    Text(TimeLabels.chatListTime(event.startsAt, now).let { "${TimeLabels.clock(event.startsAt)} · $it" }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    if (event.place.isNotBlank()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.Place, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(event.place, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
    if (event.description.isNotBlank()) {
      Text(event.description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
    Text(
      stringResource(R.string.group_event_counts, event.counts.going, event.counts.maybe),
      style = MaterialTheme.typography.labelMedium,
      color = colors.onSurfaceVariant,
    )
    if (canAnswer) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        RsvpChip(stringResource(R.string.group_event_going), mine == Rsvp.GOING) { onRsvp(if (mine == Rsvp.GOING) null else Rsvp.GOING) }
        RsvpChip(stringResource(R.string.group_event_maybe), mine == Rsvp.MAYBE) { onRsvp(if (mine == Rsvp.MAYBE) null else Rsvp.MAYBE) }
        RsvpChip(stringResource(R.string.group_event_no), mine == Rsvp.NO) { onRsvp(if (mine == Rsvp.NO) null else Rsvp.NO) }
      }
    }
  }
}

@Composable
private fun RsvpChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Text(
    label,
    style = MaterialTheme.typography.labelLarge,
    color = if (selected) colors.onPrimary else colors.onSurface,
    modifier = Modifier
      .background(if (selected) colors.primary else colors.surface, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

@Composable
private fun CreateEventSheet(
  busy: Boolean,
  error: EventError?,
  onClearError: () -> Unit,
  onCreate: (String, String, String, Long) -> Unit,
  onClose: () -> Unit,
) {
  val colors = SamvadTheme.colors
  var title by rememberSaveable { mutableStateOf("") }
  var description by rememberSaveable { mutableStateOf("") }
  var place by rememberSaveable { mutableStateOf("") }
  var picking by remember { mutableStateOf(false) }
  var startsAt by rememberSaveable { mutableStateOf<Long?>(null) }
  val now = System.currentTimeMillis()
  val check = EventRules.check(title, description, place, startsAt, now)
  Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(stringResource(R.string.group_event_new), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    OutlinedTextField(
      value = title,
      onValueChange = { title = it.take(com.samvad.core.chat.EventRules.MAX_TITLE); onClearError() },
      label = { Text(stringResource(R.string.group_event_title_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = place,
      onValueChange = { place = it.take(com.samvad.core.chat.EventRules.MAX_PLACE) },
      label = { Text(stringResource(R.string.group_event_place_hint)) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = description,
      onValueChange = { description = it.take(com.samvad.core.chat.EventRules.MAX_DESCRIPTION) },
      label = { Text(stringResource(R.string.group_event_description_hint)) },
      minLines = 2,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(
      Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Outlined.CalendarMonth, null, tint = colors.onSurfaceVariant)
      Text(
        startsAt?.let { "${TimeLabels.chatListTime(it, now)} · ${TimeLabels.clock(it)}" } ?: stringResource(R.string.group_event_pick_time),
        style = MaterialTheme.typography.bodyLarge,
        color = if (startsAt != null) colors.onSurface else colors.onSurfaceVariant,
      )
    }
    val errorText = error?.let { eventErrorText(it) }
    if (errorText != null) Text(errorText, color = colors.error, style = MaterialTheme.typography.bodySmall)
    PrimaryButton(stringResource(R.string.group_event_create), onClick = {
      val ok = check as? com.samvad.core.chat.EventCheck.Ok ?: return@PrimaryButton
      onCreate(ok.title, ok.description, ok.place, ok.startsAt)
    }, enabled = check is com.samvad.core.chat.EventCheck.Ok, loading = busy)
  }
  if (picking) {
    EventDateTimePicker(onDismiss = { picking = false }, onPicked = { ms -> startsAt = ms; picking = false })
  }
}

/** Material date picker, then time picker; returns the chosen moment in the phone's time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventDateTimePicker(onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
  val today = remember { java.time.LocalDate.now() }
  val dateState = androidx.compose.material3.rememberDatePickerState(
    initialSelectedDateMillis = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
  )
  var date by remember { mutableStateOf<java.time.LocalDate?>(null) }
  if (date == null) {
    androidx.compose.material3.DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = {
          date = dateState.selectedDateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
        }) { Text(stringResource(R.string.schedule_next)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { androidx.compose.material3.DatePicker(state = dateState) }
  } else {
    val nowTime = remember { java.time.LocalTime.now().plusMinutes(5) }
    val timeState = androidx.compose.material3.rememberTimePickerState(initialHour = nowTime.hour, initialMinute = nowTime.minute)
    AlertDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = {
          val ms = date!!.atTime(timeState.hour, timeState.minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
          onPicked(ms)
        }) { Text(stringResource(R.string.schedule_set)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
      text = { androidx.compose.material3.TimePicker(state = timeState) },
    )
  }
}

@Composable
private fun eventErrorText(e: EventError): String = stringResource(
  when (e) {
    EventError.PAST -> R.string.group_event_error_past
    EventError.TOO_FAR -> R.string.group_event_error_too_far
    EventError.GONE -> R.string.group_event_error_gone
    EventError.LIMIT -> R.string.group_event_error_limit
    EventError.DAILY_LIMIT -> R.string.group_error_limit
    EventError.NOT_ALLOWED -> R.string.group_error_not_allowed
    EventError.FAILED -> R.string.group_error_failed
  },
)
