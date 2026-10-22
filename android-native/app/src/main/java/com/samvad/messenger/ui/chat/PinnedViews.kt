package com.samvad.messenger.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.PinnedBanner
import com.samvad.core.chat.PinnedPreview
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/** The words for a pinned message's preview, in the user's language. */
@Composable
internal fun pinnedPreviewText(preview: PinnedPreview): String = when (preview) {
  is PinnedPreview.Text -> preview.text.ifBlank { stringResource(R.string.pinmsg_unavailable) }
  is PinnedPreview.Photo ->
    if (preview.caption.isBlank()) stringResource(R.string.pinmsg_photo) else stringResource(R.string.pinmsg_photo_caption, preview.caption)
  is PinnedPreview.File ->
    if (preview.name.isBlank()) stringResource(R.string.pinmsg_file) else stringResource(R.string.pinmsg_file_name, preview.name)
  PinnedPreview.Voice -> stringResource(R.string.pinmsg_voice)
  is PinnedPreview.Poll ->
    if (preview.question.isBlank()) stringResource(R.string.pinmsg_unavailable) else stringResource(R.string.pinmsg_poll, preview.question)
  is PinnedPreview.Video ->
    if (preview.caption.isBlank()) stringResource(R.string.pinmsg_video) else stringResource(R.string.pinmsg_video_caption, preview.caption)
  is PinnedPreview.Contact ->
    if (preview.name.isBlank()) stringResource(R.string.pinmsg_unavailable) else stringResource(R.string.pinmsg_contact, preview.name)
  is PinnedPreview.Gif ->
    if (preview.caption.isBlank()) stringResource(R.string.gifs_kind_gif) else stringResource(R.string.gifs_pinned_gif_caption, preview.caption)
  PinnedPreview.Sticker -> stringResource(R.string.gifs_kind_sticker)
  PinnedPreview.Unavailable -> stringResource(R.string.pinmsg_unavailable)
}

/**
 * Page pattern: a persistent banner above the message list. Shows the newest pinned message (or the one last
 * jumped to). The whole row is one button that jumps to the message; with more than one pin it says which one.
 */
@Composable
internal fun PinnedBannerBar(banner: PinnedBanner, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val title = if (banner.hasMany) stringResource(R.string.pinmsg_banner_title_count, banner.position, banner.total)
  else stringResource(R.string.pinmsg_banner_title)
  Row(
    modifier
      .fillMaxWidth()
      .background(colors.surfaceContainer)
      .clickable(onClickLabel = stringResource(R.string.pinmsg_go_to), role = Role.Button, onClick = onClick)
      .heightIn(min = 48.dp)
      .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(Icons.Outlined.PushPin, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        pinnedPreviewText(banner.preview),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
