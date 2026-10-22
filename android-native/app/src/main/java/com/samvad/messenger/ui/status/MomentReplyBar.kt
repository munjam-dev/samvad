package com.samvad.messenger.ui.status

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import com.samvad.messenger.R
import com.samvad.messenger.media.VoiceRecorder

/**
 * The private reply row under a moment: a text box, and a microphone for a voice reply of up to 30 seconds. When the
 * author turned replies off, it says so instead. [onTyping] holds the moment still while the person types or records.
 */
@Composable
internal fun MomentReplyBar(vm: StatusViewModel, item: StatusItem, onTyping: (Boolean) -> Unit, onMessage: (String) -> Unit) {
  val shape = RoundedCornerShape(24.dp)
  if (item.repliesOff) {
    Text(
      stringResource(R.string.moment_replies_off),
      style = MaterialTheme.typography.bodyMedium,
      color = Color.White,
      modifier = Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.45f)).defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
    )
    return
  }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val recorder = remember { VoiceRecorder(context, scope) }
  val rec = recorder.state.collectAsStateWithLifecycle().value
  val sending = vm.replySending.collectAsStateWithLifecycle().value
  DisposableEffect(Unit) { onDispose { recorder.cancel(); onTyping(false) } }

  val sentText = stringResource(R.string.moment_voice_reply_sent)
  val failedText = stringResource(R.string.moment_voice_reply_failed)
  fun finish() {
    onTyping(false)
    val voice = recorder.stop() ?: return
    vm.replyVoice(item, voice) { ok -> onMessage(if (ok) sentText else failedText) }
  }
  // Stop by itself at the limit, and send what was recorded.
  LaunchedEffect(rec.elapsedMs) { if (rec.active && rec.elapsedMs >= StatusRules.MAX_VOICE_REPLY_MS) finish() }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted && recorder.start()) onTyping(true)
  }
  fun startRecording() {
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      if (recorder.start()) onTyping(true)
    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
  }

  Row(Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.45f)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    if (rec.active) {
      val cancel = stringResource(R.string.moment_voice_reply_cancel)
      IconButton(onClick = { recorder.cancel(); onTyping(false) }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Close, cancel, tint = Color.White)
      }
      Text(
        stringResource(R.string.moment_voice_reply_recording, clock(rec.elapsedMs), clock(StatusRules.MAX_VOICE_REPLY_MS)),
        style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f),
      )
      IconButton(onClick = ::finish, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Stop, stringResource(R.string.moment_voice_reply_send), tint = Color.White)
      }
    } else {
      var reply by remember(item.id) { mutableStateOf("") }
      BasicTextField(
        value = reply,
        onValueChange = { reply = it },
        enabled = !sending,
        textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
        cursorBrush = SolidColor(Color.White),
        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp).padding(vertical = 12.dp).pointerInput(Unit) { detectTapGestures(onPress = { onTyping(true) }) },
        decorationBox = { inner -> Box { if (reply.isEmpty()) Text(stringResource(R.string.status_reply_hint), color = Color.White.copy(alpha = 0.7f)); inner() } },
      )
      if (reply.isBlank()) {
        IconButton(onClick = ::startRecording, enabled = !sending, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Outlined.Mic, stringResource(R.string.moment_voice_reply), tint = Color.White)
        }
      } else {
        TextButton(onClick = { vm.reply(item, reply) { reply = ""; onTyping(false) } }) { Text(stringResource(R.string.status_send), color = Color.White) }
      }
    }
  }
}
