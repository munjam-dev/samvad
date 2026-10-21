package com.samvad.messenger.ui.call

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.call.CallKind
import com.samvad.core.call.MeetingPhase
import com.samvad.core.call.MeetingWindow
import com.samvad.core.chat.MeetingCard
import com.samvad.core.chat.ScheduleRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.CallException
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

private fun whenLabel(context: android.content.Context, ms: Long): String =
  DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL)

/** The meeting card inside a group chat: title, kind, start time and a Join button once the window is open. */
@Composable
fun MeetingCardView(card: MeetingCard, me: String, container: AppContainer) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val kind = CallKind.parse(card.kind)
  val info = remember(card.meetingId, container.calls) {
    (container.calls?.meeting(card.meetingId) ?: emptyFlow()).catch { }
  }.collectAsStateWithLifecycle(null).value
  val ticker = androidx.compose.runtime.produceState(System.currentTimeMillis()) {
    while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() }
  }.value
  val phase = MeetingWindow.phase(card.startsAtMs, ticker, info?.ended == true)
  Column(Modifier.widthIn(min = 220.dp, max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(if (kind == CallKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Call, null, tint = colors.brand, modifier = Modifier.size(20.dp))
      Text(
        stringResource(if (kind == CallKind.VIDEO) R.string.meeting_video else R.string.meeting_voice),
        style = MaterialTheme.typography.labelMedium, color = colors.bubbleMeta,
      )
    }
    Text(card.title, style = MaterialTheme.typography.titleSmall, color = colors.onBubble)
    Text(
      when (phase) {
        MeetingPhase.ENDED -> stringResource(R.string.meeting_ended)
        MeetingPhase.OPEN -> stringResource(R.string.meeting_open, whenLabel(context, card.startsAtMs))
        MeetingPhase.UPCOMING -> stringResource(R.string.meeting_starts, whenLabel(context, card.startsAtMs))
      },
      style = MaterialTheme.typography.bodySmall, color = colors.bubbleMeta,
    )
    if (phase == MeetingPhase.OPEN) {
      PrimaryButton(
        stringResource(R.string.meeting_join),
        onClick = { CallActivity.joinMeeting(context, card.meetingId, card.title, kind, host = info?.hostUid == me) },
      )
    }
  }
}

/** Admin-only sheet: title, voice or video, and when. Scheduling posts the card in the group. */
@Composable
fun ScheduleMeetingSheet(container: AppContainer, groupId: String, onClose: () -> Unit, onResult: (success: Boolean) -> Unit) {
  val colors = SamvadTheme.colors
  var title by rememberSaveable { mutableStateOf("") }
  var video by rememberSaveable { mutableStateOf(true) }
  var option by rememberSaveable { mutableStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val now = remember { System.currentTimeMillis() }
  val options = listOf(
    R.string.meeting_when_now to now + 60_000,
    R.string.meeting_when_hour to now + 60 * 60_000,
    R.string.meeting_when_tonight to ScheduleRules.tonight(now),
    R.string.meeting_when_tomorrow to ScheduleRules.tomorrowMorning(now),
  )
  Column(
    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(stringResource(R.string.meeting_schedule_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    SamvadTextField(title, { title = it }, stringResource(R.string.meeting_title_label), maxLength = 120)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(!video, { video = false }, label = { Text(stringResource(R.string.meeting_voice)) }, modifier = Modifier.heightIn(min = 48.dp))
      FilterChip(video, { video = true }, label = { Text(stringResource(R.string.meeting_video)) }, modifier = Modifier.heightIn(min = 48.dp))
    }
    options.forEachIndexed { i, (label, _) ->
      Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option == i, role = Role.RadioButton) { option = i },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(option == i, null)
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(start = 12.dp))
      }
    }
    PrimaryButton(
      stringResource(R.string.meeting_create),
      loading = busy,
      enabled = title.isNotBlank(),
      onClick = {
        busy = true
        scope.launch {
          val ok = try {
            container.calls?.scheduleMeeting(groupId, title.trim(), options[option].second, if (video) CallKind.VIDEO else CallKind.AUDIO)
            container.calls != null
          } catch (_: CallException) {
            false
          }
          busy = false
          onResult(ok)
          if (ok) onClose()
        }
      },
    )
  }
}
