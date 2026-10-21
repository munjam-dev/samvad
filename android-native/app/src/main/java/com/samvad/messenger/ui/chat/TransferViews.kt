package com.samvad.messenger.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.samvad.core.media.ImageQuality
import com.samvad.core.media.MediaKind
import com.samvad.core.media.NetworkInfo
import com.samvad.core.media.NetworkQuality
import com.samvad.core.media.TransferProgress
import com.samvad.messenger.R
import com.samvad.messenger.data.DownloadState
import com.samvad.messenger.data.UploadItem
import com.samvad.messenger.data.UploadPhase
import com.samvad.messenger.data.WaitReason
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.StatusBanner
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * A determinate progress ring (indeterminate while the total is unknown) with a Cancel button inside it.
 * The button keeps a 48 dp touch target; the ring is drawn around the 20 dp glyph.
 */
@Composable
fun TransferRing(progress: Float?, cancelLabel: String, onCancel: (() -> Unit)?, tint: Color = SamvadTheme.colors.primary) {
  val colors = SamvadTheme.colors
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    if (progress != null) {
      CircularProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.size(38.dp),
        strokeWidth = 3.dp,
        color = tint,
        trackColor = colors.outlineVariant,
      )
    } else {
      CircularProgressIndicator(Modifier.size(38.dp), strokeWidth = 3.dp, color = tint, trackColor = colors.outlineVariant)
    }
    if (onCancel != null) {
      IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Close, cancelLabel, tint = colors.onSurface, modifier = Modifier.size(18.dp))
      }
    }
  }
}

/** Outgoing attachment that is still being sent: progress ring and Cancel, a waiting note, or Retry after a failure. */
@Composable
fun PendingUploadRow(upload: UploadItem, onRetry: () -> Unit, onCancel: () -> Unit) {
  val colors = SamvadTheme.colors
  val failed = upload.phase == UploadPhase.FAILED
  val percent = upload.progress?.let { (it * 100).toInt() }
  val status = when {
    failed -> stringResource(R.string.media2_send_failed)
    upload.phase == UploadPhase.WAITING ->
      stringResource(if (upload.waitReason == WaitReason.NETWORK) R.string.media2_waiting_network else R.string.media2_waiting_retry)
    percent != null -> stringResource(R.string.media2_sending_percent, percent)
    else -> stringResource(R.string.media2_sending)
  }
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 3.dp)
      .background(colors.bubbleOutgoing, RoundedCornerShape(16.dp))
      .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      when (upload.kind) {
        MediaKind.IMAGE -> Icons.Outlined.Image
        MediaKind.VOICE -> Icons.Outlined.Mic
        MediaKind.FILE -> Icons.Outlined.Description
        MediaKind.VIDEO -> Icons.Outlined.Videocam
      },
      null,
      tint = colors.bubbleMeta,
    )
    Column(Modifier.weight(1f)) {
      Text(upload.label, style = MaterialTheme.typography.bodyMedium, color = colors.onBubble, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) colors.error else colors.bubbleMeta,
      )
    }
    if (failed) {
      IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Refresh, stringResource(R.string.media2_cd_retry_sending, upload.label), tint = colors.error)
      }
      IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.Close, stringResource(R.string.media2_cd_cancel_sending, upload.label), tint = colors.bubbleMeta)
      }
    } else if (upload.phase == UploadPhase.WAITING) {
      CancelOnly(upload, onCancel)
    } else {
      TransferRing(
        progress = upload.progress,
        cancelLabel = stringResource(R.string.media2_cd_cancel_sending, upload.label),
        onCancel = if (upload.canCancel) onCancel else null,
        tint = colors.primary,
      )
    }
  }
}

@Composable
private fun CancelOnly(upload: UploadItem, onCancel: () -> Unit) {
  if (!upload.canCancel) return
  IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
    Icon(Icons.Outlined.Close, stringResource(R.string.media2_cd_cancel_sending, upload.label), tint = SamvadTheme.colors.bubbleMeta)
  }
}

/** Placeholder inside a photo bubble while it downloads, waits for a tap, or failed. */
@Composable
fun PhotoDownloadState(
  state: DownloadState?,
  sizeText: String,
  waiting: Boolean,
  onStart: () -> Unit,
  onCancel: () -> Unit,
) {
  val colors = SamvadTheme.colors
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(8.dp)) {
    when (state) {
      is DownloadState.Active -> {
        TransferRing(state.fraction, stringResource(R.string.media2_cd_cancel_download), onCancel)
        Text(downloadText(state), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
      }
      DownloadState.Failed -> {
        IconButton(onClick = onStart, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Outlined.Refresh, stringResource(R.string.media2_cd_retry_download), tint = colors.error)
        }
        Text(stringResource(R.string.media2_download_failed), style = MaterialTheme.typography.labelMedium, color = colors.error)
        Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelLarge, color = colors.primary)
      }
      else -> {
        Icon(Icons.Outlined.Image, null, tint = colors.onSurfaceVariant)
        if (waiting) {
          Text(stringResource(R.string.media_download_photo, sizeText), style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
      }
    }
  }
}

@Composable
fun downloadText(state: DownloadState.Active): String {
  val percent = TransferProgress.percent(state.transferred, state.total)
  return if (percent != null) stringResource(R.string.media2_downloading_percent, percent) else stringResource(R.string.media2_downloading)
}

/** Size line of a file card while it downloads: "1.2 MB of 4.0 MB". */
@Composable
fun downloadProgressText(state: DownloadState.Active): String {
  val context = LocalContext.current
  return stringResource(
    R.string.media2_download_progress,
    android.text.format.Formatter.formatShortFileSize(context, state.transferred.coerceAtLeast(0)),
    android.text.format.Formatter.formatShortFileSize(context, state.total.coerceAtLeast(0)),
  )
}

/**
 * Full-screen preview shown after a photo is picked and before it is sent. "Original quality" is off by
 * default; when on, the standard downscale is skipped (location details are removed either way, and the
 * 16 MB limit still applies).
 */
@Composable
fun PhotoSendPreview(
  load: suspend () -> Bitmap?,
  sourceSize: suspend () -> Long,
  onSend: (original: Boolean) -> Unit,
  onCancel: () -> Unit,
) {
  val colors = SamvadTheme.colors
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var size by remember { mutableStateOf(-1L) }
  var original by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { bitmap = load() }
  LaunchedEffect(Unit) { size = sourceSize() }
  val photoCd = stringResource(R.string.media2_preview_photo_cd)
  Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        bitmap?.let {
          Image(it.asImageBitmap(), photoCd, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } ?: CircularProgressIndicator(color = Color.White)
      }
      Column(
        Modifier
          .fillMaxWidth()
          .background(colors.surface)
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(bottom = 8.dp),
      ) {
        Row(
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(original, role = Role.Switch, onValueChange = { original = it })
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.media2_original_quality), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(stringResource(R.string.media2_original_quality_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (original && size > 0) {
              val text = android.text.format.Formatter.formatShortFileSize(LocalContext.current, size)
              Text(stringResource(R.string.media2_photo_size, text), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
              if (ImageQuality.sourceMayBeReduced(size)) {
                Text(stringResource(R.string.media2_photo_large_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
              }
            }
          }
          Switch(
            checked = original,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
          )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
          QuietButton(stringResource(R.string.cancel), onCancel)
          QuietButton(stringResource(R.string.media2_preview_send), { onSend(original) }, enabled = bitmap != null)
        }
      }
    }
  }
}

/** True once the device has had no working connection for a moment, so a flicker does not flash a banner. */
@Composable
fun rememberDeviceOffline(network: StateFlow<NetworkInfo>): Boolean {
  val info = network.collectAsStateWithLifecycle().value
  val offline = NetworkQuality.showOfflineBanner(NetworkQuality.classify(info))
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(offline) {
    if (offline) delay(2_000)
    shown = offline
  }
  return shown && offline
}

/**
 * Calm notes under the chat header: "waiting for network" while there is no connection, and a one-time,
 * dismissible suggestion to turn on Data saver on a slow or metered connection. Neither blocks anything.
 */
@Composable
fun ChatNetworkBanners(
  deviceOffline: Boolean,
  network: StateFlow<NetworkInfo>,
  dataSaverOn: Boolean,
  hintAnswered: Boolean,
  onAnswerHint: (turnOn: Boolean) -> Unit,
) {
  val colors = SamvadTheme.colors
  StatusBanner(deviceOffline, stringResource(R.string.media2_waiting_network_banner))
  val level = NetworkQuality.classify(network.collectAsStateWithLifecycle().value)
  val wanted = NetworkQuality.shouldSuggestDataSaver(level, dataSaverOn, hintAnswered)
  // Estimates flap, so the suggestion appears only when the condition has lasted a few seconds.
  var show by remember { mutableStateOf(false) }
  LaunchedEffect(wanted) {
    if (wanted) delay(3_000)
    show = wanted
  }
  if (show && wanted) {
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
        .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.CloudOff, null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(stringResource(R.string.media2_data_saver_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        QuietButton(stringResource(R.string.media2_data_saver_not_now), { onAnswerHint(false) })
        QuietButton(stringResource(R.string.media2_data_saver_turn_on), { onAnswerHint(true) })
      }
    }
  }
}
