package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.samvad.core.chat.ScheduleCheck
import com.samvad.core.chat.ScheduleRules
import com.samvad.messenger.R
import com.samvad.messenger.data.ScheduledItem
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.SamvadTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
private fun formatWhen(ms: Long): String {
  val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: java.util.Locale.getDefault()
  return DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", locale).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}

@Composable
private fun checkMessage(check: ScheduleCheck): String? = when (check) {
  ScheduleCheck.OK -> null
  ScheduleCheck.TOO_SOON -> stringResource(R.string.schedule_too_soon)
  ScheduleCheck.TOO_FAR -> stringResource(R.string.schedule_too_far)
}

/**
 * Pattern: action sheet. Picks when to send the composer's message: tonight, tomorrow morning, or a
 * date and time. [onPick] returns the check result so the sheet can explain a rejected time.
 */
@Composable
internal fun ScheduleSheet(title: String, onPick: (Long) -> ScheduleCheck, onDone: () -> Unit) {
  val colors = SamvadTheme.colors
  val now = remember { System.currentTimeMillis() }
  val tonight = remember { ScheduleRules.tonight(now) }
  val morning = remember { ScheduleRules.tomorrowMorning(now) }
  var picking by remember { mutableStateOf(false) }
  var problem by remember { mutableStateOf<ScheduleCheck?>(null) }

  fun choose(ms: Long) {
    val result = onPick(ms)
    if (result == ScheduleCheck.OK) onDone() else problem = result
  }

  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Text(stringResource(R.string.schedule_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
    Option(Icons.Outlined.Nightlight, stringResource(R.string.schedule_tonight), formatWhen(tonight)) { choose(tonight) }
    Option(Icons.Outlined.WbSunny, stringResource(R.string.schedule_tomorrow_morning), formatWhen(morning)) { choose(morning) }
    Option(Icons.Outlined.CalendarMonth, stringResource(R.string.schedule_pick), null) { picking = true }
    problem?.let { p ->
      checkMessage(p)?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
    }
  }
  if (picking) DateTimePicker(onDismiss = { picking = false }, onPicked = { ms -> picking = false; choose(ms) })
}

@Composable
private fun Option(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, null, tint = colors.primary)
    Column {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
      subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
  }
}

/** Material date picker, then time picker; returns the chosen moment in the phone's time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
  val today = remember { LocalDate.now() }
  val dateState = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
  var date by remember { mutableStateOf<LocalDate?>(null) }
  if (date == null) {
    DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = {
          date = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        }) { Text(stringResource(R.string.schedule_next)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = dateState) }
  } else {
    val nowTime = remember { LocalTime.now().plusMinutes(5) }
    val timeState = rememberTimePickerState(initialHour = nowTime.hour, initialMinute = nowTime.minute)
    AlertDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(onClick = {
          val ms = date!!.atTime(timeState.hour, timeState.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
          onPicked(ms)
        }) { Text(stringResource(R.string.schedule_set)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
      text = { TimePicker(state = timeState) },
    )
  }
}

/** Above the composer: how many messages are waiting to send in this chat. */
@Composable
internal fun ScheduledBanner(items: List<ScheduledItem>, onOpen: () -> Unit) {
  if (items.isEmpty()) return
  val colors = SamvadTheme.colors
  val failed = items.count { it.status == "failed" }
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = 4.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
      .clickable(onClick = onOpen)
      .padding(horizontal = 14.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(if (failed > 0) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule, null, tint = if (failed > 0) colors.error else colors.primary, modifier = Modifier.size(18.dp))
    Text(
      if (failed > 0) pluralStringResource(R.plurals.scheduled_failed_banner, failed, failed)
      else pluralStringResource(R.plurals.scheduled_banner, items.size, items.size),
      style = MaterialTheme.typography.labelLarge,
      color = colors.onSurface,
    )
  }
}

/** Pattern: list sheet. This chat's scheduled messages with send now, edit, change time and cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledListSheet(
  items: List<ScheduledItem>,
  onSendNow: (ScheduledItem) -> Unit,
  onEdit: (ScheduledItem, String) -> Unit,
  onReschedule: (ScheduledItem, Long) -> ScheduleCheck,
  onCancel: (ScheduledItem) -> Unit,
  onRemove: (ScheduledItem) -> Unit,
) {
  val colors = SamvadTheme.colors
  var editing by remember { mutableStateOf<ScheduledItem?>(null) }
  var rescheduling by remember { mutableStateOf<ScheduledItem?>(null) }
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Text(stringResource(R.string.scheduled_list_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    LazyColumn {
      items(items, key = { it.id }) { item ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
          Text(item.text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
          if (item.status == "failed") {
            Text(stringResource(R.string.scheduled_failed, failureText(item.failure)), style = MaterialTheme.typography.bodySmall, color = colors.error)
            Row { QuietButton(stringResource(R.string.scheduled_remove), { onRemove(item) }) }
          } else {
            Text(stringResource(R.string.scheduled_at, formatWhen(item.sendAt)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              QuietButton(stringResource(R.string.scheduled_send_now), { onSendNow(item) })
              QuietButton(stringResource(R.string.scheduled_edit), { editing = item })
              QuietButton(stringResource(R.string.scheduled_change_time), { rescheduling = item })
              QuietButton(stringResource(R.string.scheduled_cancel), { onCancel(item) })
            }
          }
        }
        HorizontalDivider(color = colors.outlineVariant)
      }
    }
  }
  editing?.let { item ->
    var text by remember(item.id) { mutableStateOf(item.text) }
    AlertDialog(
      onDismissRequest = { editing = null },
      title = { Text(stringResource(R.string.scheduled_edit)) },
      text = { OutlinedTextField(text, { text = it }, maxLines = 6) },
      confirmButton = { TextButton(onClick = { onEdit(item, text); editing = null }, enabled = text.isNotBlank()) { Text(stringResource(R.string.schedule_save)) } },
      dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  rescheduling?.let { item ->
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = { rescheduling = null }) {
      ScheduleSheet(stringResource(R.string.scheduled_change_time), onPick = { ms -> onReschedule(item, ms) }, onDone = { rescheduling = null })
    }
  }
}

@Composable
private fun failureText(failure: String?): String = stringResource(
  when (failure) {
    "not_member" -> R.string.scheduled_failed_not_member
    "blocked" -> R.string.scheduled_failed_blocked
    "account_deleted" -> R.string.scheduled_failed_account
    "encrypted" -> R.string.e2ee_scheduled_failed
    else -> R.string.scheduled_failed_invalid
  },
)
