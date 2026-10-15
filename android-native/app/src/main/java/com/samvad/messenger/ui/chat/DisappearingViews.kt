package com.samvad.messenger.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.Disappearing
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** Chooses the chat's disappearing-message timer. In groups only admins can change it; others just read it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DisappearingSheet(
  current: Long,
  canChange: Boolean,
  saving: Boolean,
  onPick: (Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = SamvadTheme.colors
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
      Text(stringResource(R.string.disappearing_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
      Text(
        stringResource(R.string.disappearing_body),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
      )
      Disappearing.CHOICES.forEach { seconds ->
        Row(
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = canChange && !saving, role = Role.RadioButton) { if (seconds != current) onPick(seconds) },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = seconds == current, onClick = null, enabled = canChange && !saving)
          Text(optionLabel(seconds), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(start = 12.dp))
        }
      }
      if (!canChange) {
        Text(stringResource(R.string.disappearing_admin_only), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
      }
      Text(stringResource(R.string.disappearing_limits), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
  }
}

@Composable
internal fun optionLabel(seconds: Long): String = timerOptionLabel(seconds)

@Composable
fun timerOptionLabel(seconds: Long): String = when (seconds) {
  Disappearing.OFF -> stringResource(R.string.disappearing_off)
  Disappearing.DAY -> stringResource(R.string.disappearing_24h)
  Disappearing.WEEK -> stringResource(R.string.disappearing_7d)
  Disappearing.QUARTER -> stringResource(R.string.disappearing_90d)
  else -> Disappearing.label(seconds)
}
