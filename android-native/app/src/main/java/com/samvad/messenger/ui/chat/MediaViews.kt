package com.samvad.messenger.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.core.media.MediaRules
import com.samvad.messenger.R
import com.samvad.messenger.data.DownloadState
import com.samvad.messenger.media.PlaybackState
import com.samvad.messenger.media.RecordingState
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.motionSpec

/**
 * Photo bubble: reserves its aspect ratio, then shows the cached, rules-checked download. When auto-download
 * is off for this connection and the photo is not on the phone yet, it waits for a tap and shows its size.
 * A download shows a determinate progress ring with Cancel, and a failed one offers Retry. Whether a download
 * may start by itself is decided by the caller through [autoLoad] (the auto-download policy).
 */
@Composable
fun MediaImage(
  media: Media,
  load: suspend (String, Int) -> Bitmap?,
  autoLoad: Boolean = true,
  isCached: (String) -> Boolean = { true },
  downloadState: (String) -> DownloadState? = { null },
  onDownload: (String, Long) -> Unit = { _, _ -> },
  onCancelDownload: (String) -> Unit = {},
  onOpen: () -> Unit,
) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  var bitmap by remember(media.path) { mutableStateOf<Bitmap?>(null) }
  var requested by remember(media.path) { mutableStateOf(false) }
  val state = downloadState(media.path)
  val cached = remember(media.path, state) { isCached(media.path) }
  val wants = autoLoad || requested
  // Starts only when the policy allows and nothing was tried yet: a failed or cancelled download waits for a tap.
  LaunchedEffect(media.path, wants, cached) {
    if (wants && !cached && downloadState(media.path) == null) onDownload(media.path, media.size)
  }
  LaunchedEffect(media.path, cached) { if (cached) bitmap = load(media.path, 900) }
  val ratio = if ((media.width ?: 0) > 0 && (media.height ?: 0) > 0) media.width!!.toFloat() / media.height!! else 4f / 3f
  val label = stringResource(R.string.cd_open_photo)
  val size = android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, media.size)
  val downloadLabel = stringResource(R.string.media_download_photo, size)
  val retryLabel = stringResource(R.string.media2_cd_retry_download)
  val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, motionSpec(Motion.standard()), label = "img")
  val start = { requested = true; onDownload(media.path, media.size) }
  Box(
    Modifier
      .widthIn(max = 260.dp)
      .fillMaxWidth()
      .aspectRatio(ratio.coerceIn(0.5f, 2f))
      .clip(RoundedCornerShape(14.dp))
      .background(colors.surfaceContainer)
      .clickable(
        onClickLabel = when {
          cached -> label
          state is DownloadState.Failed -> retryLabel
          else -> downloadLabel
        },
        role = if (cached) Role.Image else Role.Button,
        onClick = { if (cached) onOpen() else if (state !is DownloadState.Active) start() },
      ),
    contentAlignment = Alignment.Center,
  ) {
    when {
      bitmap != null -> Image(
        bitmap!!.asImageBitmap(),
        contentDescription = stringResource(R.string.media_photo),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
      )
      !cached -> PhotoDownloadState(
        state = state,
        sizeText = size,
        waiting = state is DownloadState.Cancelled || (state == null && !wants),
        onStart = start,
        onCancel = { onCancelDownload(media.path) },
      )
      else -> Icon(Icons.Outlined.Image, null, tint = colors.onSurfaceVariant)
    }
  }
}

/**
 * Document card. A file that is not on the phone yet shows its size and downloads on tap, with a progress ring
 * and Cancel while it runs and Retry if it fails; once it is on the phone a tap opens it.
 */
@Composable
fun FileCard(
  media: Media,
  cached: Boolean = true,
  download: DownloadState? = null,
  onDownload: () -> Unit = {},
  onCancelDownload: () -> Unit = {},
  onOpen: () -> Unit,
) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  val active = download as? DownloadState.Active
  val failed = download is DownloadState.Failed
  val sizeText = MediaRules.formatBytes(media.size)
  val clickLabel = when {
    cached -> stringResource(R.string.media2_cd_open_file, media.name)
    failed -> stringResource(R.string.media2_cd_retry_download)
    else -> stringResource(R.string.media2_cd_download_file, media.name)
  }
  Row(
    Modifier
      .widthIn(min = 200.dp, max = 260.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(colors.surfaceContainer)
      .clickable(
        enabled = active == null,
        onClickLabel = clickLabel,
        role = Role.Button,
        onClick = { if (cached) onOpen() else onDownload() },
      )
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (active != null) {
      TransferRing(active.fraction, stringResource(R.string.media2_cd_cancel_download), onCancelDownload)
    } else {
      Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(
          when {
            failed -> Icons.Outlined.Refresh
            cached -> Icons.Outlined.Description
            else -> Icons.Outlined.Download
          },
          null,
          tint = colors.onPrimaryContainer,
        )
      }
    }
    Column(Modifier.weight(1f)) {
      Text(media.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(
        when {
          active != null -> downloadProgressText(active)
          failed -> stringResource(R.string.media2_download_failed) + " · " + stringResource(R.string.retry)
          cached -> stringResource(R.string.media2_file_open, sizeText)
          else -> stringResource(R.string.media2_file_download, sizeText)
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) colors.error else colors.onSurfaceVariant,
      )
    }
  }
}

/** Voice note: play/pause, waveform that fills as it plays, and the remaining/total time. */
@Composable
fun VoiceNote(messageId: String, media: Media, playback: PlaybackState, mine: Boolean, onToggle: () -> Unit) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  val active = playback.messageId == messageId
  val duration = media.durationMs ?: 0L
  val progress = if (active && duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
  val bars = media.waveform.ifEmpty { List(32) { 8 } }
  val on = colors.primary
  val off = if (mine) colors.bubbleMeta.copy(alpha = 0.45f) else colors.outline
  Row(Modifier.widthIn(min = 220.dp, max = 260.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    IconButton(
      onClick = onToggle,
      modifier = Modifier.size(40.dp).background(colors.primary, CircleShape),
    ) {
      Icon(
        if (active && playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
        stringResource(if (active && playback.playing) R.string.voice_pause else R.string.voice_play),
        tint = colors.onPrimary,
      )
    }
    Column(Modifier.weight(1f)) {
      Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bars.size - 1)) / bars.size
        bars.forEachIndexed { i, v ->
          val h = (v / 31f).coerceIn(0.12f, 1f) * size.height
          val x = i * (w + gap)
          drawRoundRect(
            if (i.toFloat() / bars.size <= progress) on else off,
            Offset(x, (size.height - h) / 2),
            Size(w, h),
            CornerRadius(w / 2),
          )
        }
      }
      Text(
        MediaRules.formatDuration(if (active) playback.positionMs else duration),
        style = MaterialTheme.typography.labelSmall,
        color = colors.bubbleMeta,
      )
    }
  }
}

/** Full-screen photo with pinch-to-zoom and pan. */
@Composable
fun MediaViewer(path: String, load: suspend (String, Int) -> Bitmap?, onClose: () -> Unit) {
  var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(path) { bitmap = load(path, 2400) }
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val transform = rememberTransformableState { zoom, pan, _ ->
    scale = (scale * zoom).coerceIn(1f, 5f)
    offset = if (scale == 1f) Offset.Zero else offset + pan
  }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      bitmap?.let {
        Image(
          it.asImageBitmap(),
          contentDescription = stringResource(R.string.media_photo),
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .transformable(transform)
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = offset.x
              translationY = offset.y
            },
        )
      } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
      IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
        Icon(Icons.Outlined.Close, stringResource(R.string.cd_close), tint = Color.White)
      }
    }
  }
}

/** Replaces the composer while recording: cancel, live level, timer, send. */
@Composable
fun RecorderBar(state: RecordingState, onCancel: () -> Unit, onSend: () -> Unit) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  val label = stringResource(R.string.voice_recording)
  Row(
    Modifier
      .fillMaxWidth()
      .background(colors.background)
      .navigationBarsPadding()
      .padding(horizontal = 10.dp, vertical = 8.dp)
      .semantics { contentDescription = label },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.voice_cancel), tint = colors.error) }
    Row(
      Modifier.weight(1f).height(48.dp).background(colors.deep, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(Modifier.size(10.dp).background(colors.notificationDot, CircleShape))
      Text(MediaRules.formatDuration(state.elapsedMs), style = MaterialTheme.typography.labelLarge, color = colors.onDeep)
      Canvas(Modifier.weight(1f).height(24.dp)) {
        val bars = state.levels.ifEmpty { listOf(2) }
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (32 - 1)) / 32).coerceAtLeast(1f)
        bars.takeLast(32).forEachIndexed { i, v ->
          val h = (v / 31f).coerceIn(0.1f, 1f) * size.height
          drawRoundRect(colors.lime, Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
      }
    }
    IconButton(onClick = onSend, modifier = Modifier.size(48.dp).background(colors.primary, CircleShape)) {
      Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.voice_send), tint = colors.onPrimary)
    }
  }
}

@Composable
fun AttachOptions(
  onPhoto: () -> Unit,
  onDocument: () -> Unit,
  onVideo: () -> Unit = {},
  onContact: () -> Unit = {},
  onPoll: (() -> Unit)? = null,
  onGif: (() -> Unit)? = null,
) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
    Text(
      stringResource(R.string.attach_title),
      style = MaterialTheme.typography.titleMedium,
      color = colors.onSurface,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    androidx.compose.foundation.lazy.LazyRow(
      Modifier.fillMaxWidth(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item { AttachTile(Icons.Outlined.Image, stringResource(R.string.attach_photo), colors.primaryContainer, colors.onPrimaryContainer, onPhoto) }
      item { AttachTile(Icons.Outlined.Videocam, stringResource(R.string.attach_video), colors.tertiaryContainer, colors.onTertiaryContainer, onVideo) }
      item { AttachTile(Icons.Outlined.Description, stringResource(R.string.attach_document), colors.tertiaryContainer, colors.onTertiaryContainer, onDocument) }
      item { AttachTile(Icons.Outlined.PersonOutline, stringResource(R.string.attach_contact), colors.secondaryContainer, colors.onSecondaryContainer, onContact) }
      if (onGif != null) {
        item { AttachTile(Icons.Outlined.Gif, stringResource(R.string.gifs_open), colors.primaryContainer, colors.onPrimaryContainer, onGif) }
      }
      if (onPoll != null) {
        item { AttachTile(Icons.Outlined.Poll, stringResource(R.string.attach_poll), colors.secondaryContainer, colors.onSecondaryContainer, onPoll) }
      }
    }
  }
}

@Composable
private fun AttachTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
  Column(
    Modifier.width(88.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.size(56.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg) }
    Spacer(Modifier.height(6.dp))
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
  }
}
