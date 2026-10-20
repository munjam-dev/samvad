package com.samvad.messenger.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.samvad.core.media.ChatVideo
import com.samvad.core.media.Media
import com.samvad.messenger.R

/**
 * Video bubble: the on-device poster frame with a play badge and the duration, like a photo but never
 * auto-playing. A tap downloads (or reuses the cached copy of) the clip and opens it full screen.
 */
@Composable
fun VideoThumbnail(media: Media, load: suspend (String, Int) -> Bitmap?, onOpen: () -> Unit) {
  val colors = com.samvad.messenger.ui.theme.SamvadTheme.colors
  var bitmap by remember(media.path) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(media.thumbPath) { media.thumbPath?.let { bitmap = load(it, 640) } }
  val ratio = if ((media.width ?: 0) > 0 && (media.height ?: 0) > 0) media.width!!.toFloat() / media.height!! else 16f / 9f
  val duration = ChatVideo.label(media.durationMs ?: 0L)
  val label = stringResource(R.string.video_play)
  Box(
    Modifier
      .widthIn(max = 260.dp)
      .fillMaxWidth()
      .aspectRatio(ratio.coerceIn(0.5f, 2f))
      .clip(RoundedCornerShape(14.dp))
      .background(colors.surfaceContainer)
      .clickable(onClickLabel = label, onClick = onOpen),
    contentAlignment = Alignment.Center,
  ) {
    bitmap?.let {
      Image(
        it.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_video_thumbnail, duration),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
    Text(
      duration,
      style = MaterialTheme.typography.labelSmall,
      color = Color.White,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(8.dp)
        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

/** Full-screen in-app player for a chat video. Uses the platform VideoView, the same player Moments video uses. */
@Composable
fun VideoPlayerDialog(file: java.io.File, onClose: () -> Unit) {
  var ready by remember(file.path) { mutableStateOf(false) }
  Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          android.widget.VideoView(ctx).apply {
            setVideoPath(file.path)
            setOnPreparedListener { mp -> ready = true; mp.isLooping = false; start() }
          }
        },
        onRelease = { it.stopPlayback() },
      )
      if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
      IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
        Icon(Icons.Outlined.Close, stringResource(R.string.cd_close), tint = Color.White)
      }
    }
  }
}
