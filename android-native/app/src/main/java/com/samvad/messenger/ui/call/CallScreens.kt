package com.samvad.messenger.ui.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallRules
import com.samvad.core.call.LinkQuality
import com.samvad.core.chat.ChatList
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.theme.SamvadTheme
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon

/** The calm dark backdrop shared by every call screen (the one place a gradient is allowed). */
@Composable
internal fun CallBackdrop(content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  Box(
    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.deep, colors.deep.copy(alpha = 0.92f), colors.primary.copy(alpha = 0.55f)))),
  ) { content() }
}

// ---- ringing (incoming) ------------------------------------------------------------------------

@Composable
fun IncomingCallScreen(name: String, seed: String, kind: CallKind, onAccept: () -> Unit, onDecline: () -> Unit) {
  val colors = SamvadTheme.colors
  CallBackdrop {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(48.dp))
      Avatar(ChatList.initials(name), seed, size = 132.dp)
      Spacer(Modifier.height(24.dp))
      Text(name, style = MaterialTheme.typography.headlineMedium, color = colors.onDeep, textAlign = TextAlign.Center)
      Spacer(Modifier.height(8.dp))
      Text(
        stringResource(if (kind == CallKind.VIDEO) R.string.call_incoming_video else R.string.call_incoming_voice),
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onDeepMuted,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.weight(1f, fill = true).height(64.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        LabelledRound(Icons.Outlined.CallEnd, stringResource(R.string.call_decline), colors.error, colors.onError, onDecline)
        LabelledRound(if (kind == CallKind.VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Call, stringResource(R.string.call_accept), colors.success, colors.onError, onAccept)
      }
    }
  }
}

@Composable
private fun LabelledRound(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
    RoundButton(icon, label, container, content, onClick, size = 72.dp)
    Spacer(Modifier.height(8.dp))
    Text(label, style = MaterialTheme.typography.labelLarge, color = SamvadTheme.colors.onDeep, textAlign = TextAlign.Center)
  }
}

@Composable
internal fun RoundButton(icon: ImageVector, description: String, container: Color, content: Color, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 56.dp) {
  IconButton(
    onClick = onClick,
    modifier = Modifier.size(size).clip(CircleShape).background(container, CircleShape),
  ) { Icon(icon, description, tint = content, modifier = Modifier.size(size / 2.2f)) }
}

// ---- permission rationale ----------------------------------------------------------------------

/** Shown before the system dialog, only when a call starts or is answered. */
@Composable
fun PermissionRationale(video: Boolean, blocked: Boolean, onContinue: () -> Unit, onOpenSettings: () -> Unit, onCancel: () -> Unit) {
  val colors = SamvadTheme.colors
  CallBackdrop {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Spacer(Modifier.height(48.dp))
      Icon(if (video) Icons.Outlined.Videocam else Icons.Outlined.Mic, null, tint = colors.lime, modifier = Modifier.size(56.dp))
      Spacer(Modifier.height(20.dp))
      Text(stringResource(if (video) R.string.call_perm_title_video else R.string.call_perm_title_audio), style = MaterialTheme.typography.headlineSmall, color = colors.onDeep, textAlign = TextAlign.Center)
      Spacer(Modifier.height(12.dp))
      Text(stringResource(if (video) R.string.call_perm_body_video else R.string.call_perm_body_audio), style = MaterialTheme.typography.bodyLarge, color = colors.onDeepMuted, textAlign = TextAlign.Center)
      Spacer(Modifier.height(12.dp))
      Text(stringResource(R.string.call_privacy_note), style = MaterialTheme.typography.bodyMedium, color = colors.onDeepMuted, textAlign = TextAlign.Center)
      if (blocked) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.call_perm_denied), style = MaterialTheme.typography.bodyMedium, color = colors.onDeep, textAlign = TextAlign.Center)
      }
      Spacer(Modifier.height(32.dp))
      PrimaryButton(stringResource(if (blocked) R.string.call_perm_settings else R.string.call_perm_continue), onClick = if (blocked) onOpenSettings else onContinue, modifier = Modifier.fillMaxWidth())
      TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.call_not_now), color = colors.onDeep) }
    }
  }
}

// ---- in call -----------------------------------------------------------------------------------

@Composable
fun InCallScreen(ui: CallUi, room: Room?, seed: String, actions: CallActions) {
  val colors = SamvadTheme.colors
  var confirmLeave by remember { mutableStateOf(false) }
  CallBackdrop {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      QualityBanner(ui)
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (ui.isMeeting) MeetingGrid(ui, room) else OneToOne(ui, room, seed)
      }
      Controls(ui, actions, onHangUp = { if (ui.isMeeting && ui.isHost) confirmLeave = true else actions.hangUp() })
    }
  }
  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text(stringResource(R.string.call_leave_title)) },
      text = { Text(stringResource(R.string.call_leave_body)) },
      confirmButton = { TextButton(onClick = { confirmLeave = false; actions.endForAll() }) { Text(stringResource(R.string.call_end_meeting), color = colors.error) } },
      dismissButton = {
        Row {
          TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.chat_cancel)) }
          TextButton(onClick = { confirmLeave = false; actions.hangUp() }) { Text(stringResource(R.string.call_leave_meeting)) }
        }
      },
    )
  }
}

class CallActions(
  val toggleMic: () -> Unit,
  val toggleCamera: () -> Unit,
  val flipCamera: () -> Unit,
  val toggleSpeaker: () -> Unit,
  val hangUp: () -> Unit,
  val endForAll: () -> Unit,
)

@Composable
private fun QualityBanner(ui: CallUi) {
  val lost = ui.phase == Phase.RECONNECTING || ui.quality == LinkQuality.LOST
  val poor = ui.quality == LinkQuality.POOR
  if (!lost && !poor) return
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Outlined.WifiOff, null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
    Text(stringResource(if (lost) R.string.call_quality_lost else R.string.call_quality_poor), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.OneToOne(ui: CallUi, room: Room?, seed: String) {
  val colors = SamvadTheme.colors
  val remote = ui.tiles.firstOrNull { !it.local }
  val local = ui.tiles.firstOrNull { it.local }
  if (remote?.track != null && room != null) {
    VideoView(room, remote.track, mirror = false, modifier = Modifier.fillMaxSize())
  }
  if (remote?.track == null) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Avatar(ChatList.initials(ui.title), seed, size = 132.dp)
      Spacer(Modifier.height(24.dp))
      Text(ui.title, style = MaterialTheme.typography.headlineMedium, color = colors.onDeep, textAlign = TextAlign.Center)
      Spacer(Modifier.height(8.dp))
      StatusLine(ui)
    }
  } else {
    Column(Modifier.statusBarsPadding().padding(16.dp)) {
      Text(ui.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
      StatusLine(ui)
    }
  }
  if (local?.track != null && room != null) {
    VideoView(
      room, local.track, mirror = true,
      modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).width(104.dp).aspectRatio(0.62f).clip(RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, colors.outlineVariant), RoundedCornerShape(16.dp)),
    )
  }
}

@Composable
private fun StatusLine(ui: CallUi) {
  Text(statusText(ui), style = MaterialTheme.typography.bodyLarge, color = SamvadTheme.colors.onDeepMuted)
}

@Composable
private fun statusText(ui: CallUi): String {
  val elapsed by produceState(0L, ui.answeredAtMs, ui.phase) {
    while (true) {
      val start = ui.answeredAtMs
      value = if (start != null) (System.currentTimeMillis() - start).coerceAtLeast(0) / 1000 else 0
      delay(1000)
    }
  }
  return when {
    ui.isMeeting && ui.phase == Phase.CONNECTED -> stringResource(R.string.call_in_meeting, ui.tiles.size)
    ui.phase == Phase.RINGING -> stringResource(R.string.call_status_ringing)
    ui.phase == Phase.RECONNECTING -> stringResource(R.string.call_status_reconnecting)
    ui.phase == Phase.CONNECTED && ui.answeredAtMs != null -> CallRules.formatDuration(elapsed)
    ui.phase == Phase.CONNECTED -> stringResource(R.string.call_status_connecting)
    ui.phase == Phase.STARTING -> stringResource(R.string.call_status_calling)
    else -> stringResource(R.string.call_status_connecting)
  }
}

@Composable
private fun MeetingGrid(ui: CallUi, room: Room?) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize()) {
    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text(ui.title, style = MaterialTheme.typography.titleMedium, color = colors.onDeep, maxLines = 2)
      Text(statusText(ui), style = MaterialTheme.typography.bodyMedium, color = colors.onDeepMuted)
    }
    LazyVerticalGrid(
      GridCells.Adaptive(150.dp),
      Modifier.fillMaxSize().padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(ui.tiles, key = { it.id.ifEmpty { "local" } + it.local }) { tile ->
        val you = stringResource(R.string.call_you)
        val label = if (tile.local) you else tile.name.ifBlank { "·" }
        Box(
          Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh.copy(alpha = 0.18f))
            .border(BorderStroke(if (tile.speaking) 3.dp else 1.dp, if (tile.speaking) colors.lime else colors.outlineVariant), RoundedCornerShape(16.dp)),
        ) {
          if (tile.track != null && room != null) {
            VideoView(room, tile.track, mirror = tile.local, modifier = Modifier.fillMaxSize())
          } else {
            Avatar(ChatList.initials(label), tile.id.ifBlank { "local" }, size = 64.dp, modifier = Modifier.align(Alignment.Center), photoPath = null)
          }
          Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(colors.deep.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (!tile.micOn) {
              Icon(Icons.Outlined.MicOff, stringResource(R.string.call_tile_muted), tint = colors.onDeep, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onDeep, maxLines = 1)
          }
        }
      }
    }
  }
}

@Composable
private fun Controls(ui: CallUi, actions: CallActions, onHangUp: () -> Unit) {
  val colors = SamvadTheme.colors
  val idle = colors.deep.copy(alpha = 0.55f)
  val onIdle = colors.onDeep
  val video = ui.kind == CallKind.VIDEO
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RoundButton(
      if (ui.micOn) Icons.Outlined.Mic else Icons.Outlined.MicOff,
      stringResource(if (ui.micOn) R.string.call_mute else R.string.call_unmute),
      if (ui.micOn) idle else colors.onDeep, if (ui.micOn) onIdle else colors.deep, actions.toggleMic,
    )
    RoundButton(
      if (ui.speakerOn) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
      stringResource(if (ui.speakerOn) R.string.call_speaker_off else R.string.call_speaker_on),
      if (ui.speakerOn) colors.onDeep else idle, if (ui.speakerOn) colors.deep else onIdle, actions.toggleSpeaker,
    )
    if (video) {
      RoundButton(
        if (ui.camOn) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
        stringResource(if (ui.camOn) R.string.call_camera_off else R.string.call_camera_on),
        if (ui.camOn) idle else colors.onDeep, if (ui.camOn) onIdle else colors.deep, actions.toggleCamera,
      )
      if (ui.camOn) RoundButton(Icons.Outlined.FlipCameraAndroid, stringResource(R.string.call_flip), idle, onIdle, actions.flipCamera)
    }
    RoundButton(Icons.Outlined.CallEnd, stringResource(if (ui.isMeeting) R.string.call_leave_meeting else R.string.call_hang_up), colors.error, colors.onError, onHangUp)
  }
}

@Composable
private fun VideoView(room: Room, track: io.livekit.android.room.track.VideoTrack, mirror: Boolean, modifier: Modifier) {
  AndroidView(
    modifier = modifier.clearAndSetSemantics { },
    factory = { ctx ->
      TextureViewRenderer(ctx).also {
        room.initVideoRenderer(it)
        it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        it.setMirror(mirror)
      }
    },
    update = { view -> track.addRenderer(view) },
    onRelease = { view -> runCatching { track.removeRenderer(view) }; runCatching { view.release() } },
  )
}

// ---- finished / problems -----------------------------------------------------------------------

@Composable
fun CallEndedScreen(reason: EndReason, name: String, onClose: () -> Unit) {
  val colors = SamvadTheme.colors
  val notConfigured = reason == EndReason.NOT_CONFIGURED
  val text = stringResource(
    when (reason) {
      EndReason.HANGUP, EndReason.REMOTE_ENDED -> R.string.call_end_hangup
      EndReason.DECLINED -> R.string.call_end_declined
      EndReason.NO_ANSWER -> R.string.call_end_no_answer
      EndReason.BUSY -> R.string.call_end_busy
      EndReason.NOT_CONFIGURED -> R.string.call_end_not_configured_body
      EndReason.NOT_ALLOWED -> R.string.call_end_not_allowed
      EndReason.LIMIT -> R.string.call_end_limit
      EndReason.CONNECTION_LOST -> R.string.call_end_connection
      EndReason.MEETING_FULL -> R.string.call_end_meeting_full
      EndReason.MEETING_NOT_OPEN -> R.string.call_end_meeting_not_open
      EndReason.MEETING_ENDED -> R.string.call_end_meeting_ended
      EndReason.FAILED -> R.string.call_end_failed
    },
  )
  CallBackdrop {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Icon(if (notConfigured) Icons.Outlined.WifiOff else Icons.Outlined.CallEnd, null, tint = colors.lime, modifier = Modifier.size(48.dp))
      Spacer(Modifier.height(20.dp))
      if (notConfigured) {
        Text(stringResource(R.string.call_end_not_configured_title), style = MaterialTheme.typography.headlineSmall, color = colors.onDeep, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
      } else if (name.isNotBlank()) {
        Text(name, style = MaterialTheme.typography.headlineSmall, color = colors.onDeep, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
      }
      Text(text, style = MaterialTheme.typography.bodyLarge, color = colors.onDeepMuted, textAlign = TextAlign.Center)
      Spacer(Modifier.height(28.dp))
      PrimaryButton(stringResource(R.string.call_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
  }
}
