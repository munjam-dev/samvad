package com.samvad.messenger.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samvad.core.notify.QuietHours
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

private fun clock(minute: Int): String {
  val h = minute / 60
  val m = minute % 60
  val period = if (h < 12) "AM" else "PM"
  val h12 = when (h % 12) { 0 -> 12; else -> h % 12 }
  return "%d:%02d %s".format(h12, m, period)
}

/**
 * Settings → Notifications: a daily window in which message notifications are delivered silently.
 * Pure schedule logic lives in core/notify/QuietHours.kt; this row only edits and shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietHoursRow(hours: QuietHours, onChange: (enabled: Boolean, startMinute: Int, endMinute: Int) -> Unit) {
  val colors = SamvadTheme.colors
  var picking by remember { mutableStateOf<Boolean?>(null) } // true = picking start, false = picking end

  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.settings_quiet_hours), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        Text(stringResource(R.string.settings_quiet_hours_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
      Switch(
        checked = hours.enabled,
        onCheckedChange = { onChange(it, hours.startMinute, hours.endMinute) },
        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
      )
    }
    if (hours.enabled) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeField(stringResource(R.string.settings_quiet_hours_start), clock(hours.startMinute)) { picking = true }
        TimeField(stringResource(R.string.settings_quiet_hours_end), clock(hours.endMinute)) { picking = false }
      }
      Text(
        stringResource(R.string.settings_quiet_hours_note),
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
  }

  picking?.let { isStart ->
    val initial = if (isStart) hours.startMinute else hours.endMinute
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
    AlertDialog(
      onDismissRequest = { picking = null },
      confirmButton = {
        TextButton(onClick = {
          val minute = QuietHours.minuteOf(state.hour, state.minute)
          if (isStart) onChange(true, minute, hours.endMinute) else onChange(true, hours.startMinute, minute)
          picking = null
        }) { Text(stringResource(R.string.save)) }
      },
      dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
      text = { TimePicker(state = state) },
    )
  }
}

@Composable
private fun TimeField(label: String, value: String, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.clickable(onClick = onClick)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleMedium, color = colors.primary)
  }
}
