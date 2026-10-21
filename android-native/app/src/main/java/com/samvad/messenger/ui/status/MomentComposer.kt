package com.samvad.messenger.ui.status

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import android.Manifest
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.design.ChatThemes
import com.samvad.core.status.PhotoEdits
import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.media.RecordedVoice
import com.samvad.messenger.media.VoiceRecorder
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MomentMode { TEXT, PHOTO, VIDEO, VOICE }

private fun styleColorsOf(style: String?): Pair<Color, Color> {
  val t = ChatThemes.byId(StatusRules.style(style)).light
  return Color(t.background) to Color(t.text)
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/** Make a moment: text, a photo, a short video, or a voice note, with a choice of who sees it. */
@Composable
fun StatusComposerScreen(container: AppContainer, me: String, startWithPhoto: Boolean = false, onBack: () -> Unit) {
  val vm: StatusViewModel = viewModel(factory = StatusViewModel.factory(container, me))
  val colors = SamvadTheme.colors
  val post = vm.post.collectAsStateWithLifecycle().value
  val progress = vm.progress.collectAsStateWithLifecycle().value
  val error = vm.error.collectAsStateWithLifecycle().value
  val people = vm.people.collectAsStateWithLifecycle().value
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // Simple values survive rotation and process death; bitmaps do not, so the preview is decoded again from the Uri.
  var mode by rememberSaveable { mutableStateOf(if (startWithPhoto) MomentMode.PHOTO else MomentMode.TEXT) }
  var text by rememberSaveable { mutableStateOf("") }
  var caption by rememberSaveable { mutableStateOf("") }
  var style by rememberSaveable { mutableStateOf(StatusRules.DEFAULT_STYLE) }
  var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
  var photoEdits by rememberSaveable(stateSaver = PhotoEditsSaver) { mutableStateOf(PhotoEdits.NONE) }
  var photoPreview by remember { mutableStateOf<Bitmap?>(null) }
  var video by remember { mutableStateOf<Uri?>(null) }
  var videoFrame by remember { mutableStateOf<Bitmap?>(null) }
  var recorded by remember { mutableStateOf<RecordedVoice?>(null) }
  var audience by remember { mutableStateOf(StatusAudience.ALL) }
  var chosen by remember { mutableStateOf(setOf<String>()) }
  var audienceOpen by remember { mutableStateOf(false) }

  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) {
      photo = uri
      photoEdits = PhotoEdits.NONE
    }
  }
  // A small preview (1024 px) is all the screen needs; the full-size picture is only made when posting.
  LaunchedEffect(photo) {
    val uri = photo
    if (uri == null) { photoPreview = null; return@LaunchedEffect }
    val decoded = withContext(Dispatchers.IO) {
      runCatching { com.samvad.messenger.data.ImageCodec.decodeUpright(context.contentResolver, uri, 1024) }.getOrNull()
    }
    photoPreview = decoded
    if (decoded == null) { photo = null; photoEdits = PhotoEdits.NONE }
  }
  val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) video = uri
  }
  LaunchedEffect(video) {
    val uri = video ?: return@LaunchedEffect
    videoFrame = withContext(Dispatchers.IO) {
      runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, uri); r.getFrameAtTime(0) } finally { runCatching { r.release() } }
      }.getOrNull()
    }
  }

  val recorder = remember { VoiceRecorder(context, scope) }
  val rec = recorder.state.collectAsStateWithLifecycle().value
  DisposableEffect(Unit) { onDispose { recorder.cancel() } }
  LaunchedEffect(rec.elapsedMs) {
    if (rec.active && rec.elapsedMs >= StatusRules.MAX_VOICE_MS) recorded = recorder.stop()
  }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) { recorded = null; recorder.start() }
  }
  fun toggleRecording() {
    if (rec.active) { recorded = recorder.stop(); return }
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      recorded = null
      recorder.start()
    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
  }

  LaunchedEffect(post) { if (post == PostState.Done) { vm.resetPost(); onBack() } }

  val (bg, fg) = styleColorsOf(style)
  val plain = mode != MomentMode.TEXT
  val onColor = if (plain) colors.onSurface else fg
  val audienceOk = audience == StatusAudience.ALL || chosen.isNotEmpty()
  val ready = when (mode) {
    MomentMode.TEXT -> StatusRules.validText(text)
    MomentMode.PHOTO -> photo != null
    MomentMode.VIDEO -> video != null
    MomentMode.VOICE -> recorded != null && !rec.active
  }
  val posting = post == PostState.Posting
  val canPost = !posting && audienceOk && ready

  Column(Modifier.fillMaxSize().background(if (plain) colors.background else bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_back), tint = onColor) }
      Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
          Triple(MomentMode.TEXT, R.string.status_text, Icons.Outlined.TextFields),
          Triple(MomentMode.PHOTO, R.string.status_photo, Icons.Outlined.Image),
          Triple(MomentMode.VIDEO, R.string.moment_video, Icons.Outlined.Videocam),
          Triple(MomentMode.VOICE, R.string.moment_voice, Icons.Outlined.Mic),
        ).forEach { (m, label, icon) ->
          FilterChip(selected = mode == m, onClick = { if (!posting) { if (rec.active) recorder.cancel(); mode = m } }, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) })
        }
      }
    }

    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
      when (mode) {
        MomentMode.TEXT -> BasicTextField(
          value = text,
          onValueChange = { text = it.take(StatusRules.MAX_TEXT) },
          textStyle = MaterialTheme.typography.headlineMedium.copy(color = fg, textAlign = TextAlign.Center),
          cursorBrush = SolidColor(fg),
          modifier = Modifier.fillMaxWidth(),
          decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (text.isEmpty()) Text(stringResource(R.string.status_hint), style = MaterialTheme.typography.headlineMedium, color = fg.copy(alpha = 0.55f), textAlign = TextAlign.Center); inner() } },
        )
        MomentMode.PHOTO -> {
          val shown = photoPreview
          if (shown == null) {
            MediaPick(
              preview = null, caption = caption, onCaption = { caption = it.take(StatusRules.MAX_TEXT) },
              pickLabel = R.string.status_pick_photo,
              onPick = { photoPicker.launch("image/*") }, description = R.string.status_photo,
            )
          } else {
            PhotoMomentPane(
              preview = shown, edits = photoEdits, onEdits = { photoEdits = it },
              caption = caption, onCaption = { caption = it.take(StatusRules.MAX_TEXT) },
              onPick = { photoPicker.launch("image/*") }, enabled = !posting,
            )
          }
        }
        MomentMode.VIDEO -> MediaPick(
          preview = videoFrame, caption = caption, onCaption = { caption = it.take(StatusRules.MAX_TEXT) },
          pickLabel = if (video == null) R.string.moment_pick_video else R.string.moment_change_video,
          onPick = { videoPicker.launch("video/*") }, description = R.string.moment_video, hasMedia = video != null,
          note = stringResource(R.string.moment_video_limits),
        )
        MomentMode.VOICE -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Box(
            Modifier.size(96.dp).clip(CircleShape).background(if (rec.active) colors.error else colors.primary)
              .clickable(role = Role.Button, onClickLabel = stringResource(if (rec.active) R.string.moment_stop else R.string.moment_record)) { toggleRecording() },
            contentAlignment = Alignment.Center,
          ) { Icon(if (rec.active) Icons.Outlined.Stop else Icons.Outlined.Mic, null, tint = if (rec.active) colors.onError else colors.onPrimary, modifier = Modifier.size(40.dp)) }
          val shown = if (rec.active) rec.elapsedMs else recorded?.durationMs ?: 0L
          Text(clock(shown), style = MaterialTheme.typography.headlineMedium, color = colors.onSurface)
          Waveform(if (rec.active) rec.levels else recorded?.waveform.orEmpty(), colors.primary)
          Text(
            stringResource(when { rec.active -> R.string.moment_recording; recorded != null -> R.string.moment_recorded; else -> R.string.moment_tap_to_record }),
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
          )
        }
      }
    }

    if (mode == MomentMode.TEXT) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusRules.STYLES.forEach { s ->
          val (sb, _) = styleColorsOf(s)
          Box(
            Modifier.size(48.dp).selectable(s == style, role = Role.RadioButton) { style = s }.semantics { contentDescription = s },
            contentAlignment = Alignment.Center,
          ) {
            Box(
              Modifier.size(36.dp).clip(CircleShape).background(sb)
                .border(if (s == style) 3.dp else 1.dp, if (s == style) fg else colors.outlineVariant, CircleShape),
            )
          }
        }
      }
    }

    if (posting && mode != MomentMode.TEXT) {
      LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
    }

    // One calm note, once, right above the Post button: nothing here can stop a viewer from keeping a copy.
    Text(
      stringResource(R.string.moment_capture_note),
      style = MaterialTheme.typography.bodySmall, color = onColor.copy(alpha = 0.8f),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (audience != StatusAudience.ALL && chosen.isEmpty()) {
      Text(
        stringResource(R.string.moment_audience_pick_hint),
        style = MaterialTheme.typography.bodySmall, color = onColor.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
      )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      val audienceName = when (audience) {
        StatusAudience.ALL -> stringResource(R.string.status_audience_all)
        StatusAudience.ONLY -> stringResource(R.string.status_audience_only, chosen.size)
        StatusAudience.EXCEPT -> stringResource(R.string.status_audience_except, chosen.size)
      }
      TextButton(onClick = { audienceOpen = true }, enabled = !posting, modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.moment_audience_line, audienceName), color = onColor, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
      }
      Spacer(Modifier.width(8.dp))
      Button(
        enabled = canPost,
        colors = ButtonDefaults.buttonColors(
          containerColor = colors.primary, contentColor = colors.onPrimary,
          disabledContainerColor = onColor.copy(alpha = 0.18f), disabledContentColor = onColor.copy(alpha = 0.6f),
        ),
        onClick = {
          val list = if (audience == StatusAudience.ALL) emptyList() else chosen.toList()
          when (mode) {
            MomentMode.TEXT -> vm.postText(text, style, audience, list)
            MomentMode.PHOTO -> vm.postPhoto(photo!!, caption, audience, list, photoEdits)
            MomentMode.VIDEO -> vm.postVideo(video!!, caption, audience, list)
            MomentMode.VOICE -> vm.postVoice(recorded!!, audience, list)
          }
        },
      ) { Text(stringResource(if (posting) R.string.status_posting else R.string.status_post)) }
    }
    if (post == PostState.Failed) {
      Text(
        stringResource(
          when (error) {
            PostError.VIDEO_TOO_LONG -> R.string.moment_video_too_long
            PostError.VIDEO_TOO_BIG -> R.string.moment_video_too_big
            PostError.VIDEO_UNREADABLE -> R.string.moment_video_unreadable
            PostError.GENERIC -> R.string.status_failed
          },
        ),
        color = colors.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
      )
    }
  }

  if (audienceOpen) AudienceDialog(audience, { audience = it }, chosen, { chosen = it }, people, { audienceOpen = false })
}

/** A picked photo or video: preview, caption, and a button to choose (or change) the file. */
@Composable
private fun MediaPick(
  preview: Bitmap?, caption: String, onCaption: (String) -> Unit, pickLabel: Int, onPick: () -> Unit, description: Int,
  hasMedia: Boolean = preview != null, note: String? = null,
) {
  val colors = SamvadTheme.colors
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (preview != null) {
      Image(preview.asImageBitmap(), stringResource(description), contentScale = ContentScale.Fit, modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(16.dp)))
    }
    if (hasMedia) {
      BasicTextField(
        value = caption, onValueChange = onCaption,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(14.dp),
        decorationBox = { inner -> Box { if (caption.isEmpty()) Text(stringResource(R.string.status_caption_hint), color = colors.onSurfaceVariant); inner() } },
      )
    }
    TextButton(onClick = onPick) { Text(stringResource(pickLabel)) }
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}

/** Bars for a voice note: the recorded loudness, left to right. */
@Composable
internal fun Waveform(levels: List<Int>, color: Color, modifier: Modifier = Modifier, progress: Float = 1f) {
  val bars = if (levels.isEmpty()) List(24) { 2 } else levels
  Row(modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    bars.forEachIndexed { i, level ->
      val played = i.toFloat() / bars.size <= progress
      Box(Modifier.width(4.dp).height((6 + level.coerceIn(0, 31)).dp).clip(RoundedCornerShape(2.dp)).background(if (played) color else color.copy(alpha = 0.3f)))
    }
  }
}

@Composable
private fun AudienceDialog(
  audience: StatusAudience, onAudience: (StatusAudience) -> Unit,
  chosen: Set<String>, onChosen: (Set<String>) -> Unit,
  people: List<StatusPerson>, onDone: () -> Unit,
) {
  val colors = SamvadTheme.colors
  AlertDialog(
    onDismissRequest = onDone,
    confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.status_done)) } },
    title = { Text(stringResource(R.string.status_audience_title)) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        listOf(
          StatusAudience.ALL to R.string.status_audience_all,
          StatusAudience.EXCEPT to R.string.status_audience_except_label,
          StatusAudience.ONLY to R.string.status_audience_only_label,
        ).forEach { (a, label) ->
          Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).selectable(audience == a, role = Role.RadioButton) { onAudience(a) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = audience == a, onClick = null)
            Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
          }
        }
        if (audience != StatusAudience.ALL) {
          Text(stringResource(R.string.status_pick_people), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
          if (people.isEmpty()) Text(stringResource(R.string.status_no_people), style = MaterialTheme.typography.bodySmall)
          people.forEach { p ->
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).toggleable(p.uid in chosen, role = Role.Checkbox) { onChosen(if (p.uid in chosen) chosen - p.uid else chosen + p.uid) }, verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = p.uid in chosen, onCheckedChange = null)
              Text(p.name, modifier = Modifier.padding(start = 12.dp))
            }
          }
        }
        Text(stringResource(R.string.status_audience_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
      }
    },
  )
}
