package com.samvad.messenger.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ChatList
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.theme.SamvadTheme

private val BANNER_HEIGHT = 112.dp
private val DP_SIZE = 92.dp
private val DP_OVERLAP = 46.dp

/**
 * Settings top: a branded banner (Samvad pinwheel and a soft pinwheel pattern, drawn in Compose),
 * the profile photo overlapping its bottom edge, then username and bio.
 * Tapping the photo, name or bio opens the Profile page.
 */
@Composable
fun SettingsHeader(
  me: String,
  name: String,
  username: String?,
  about: String,
  photoPath: String?,
  onBack: () -> Unit,
  onOpenProfile: () -> Unit,
) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    BrandBanner(Modifier.fillMaxWidth()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp).defaultMinSize(minHeight = BANNER_HEIGHT), verticalAlignment = Alignment.Top) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onPrimaryContainer) }
        Text(
          stringResource(R.string.settings_title),
          style = MaterialTheme.typography.titleLarge,
          color = colors.onPrimaryContainer,
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
    val openLabel = stringResource(R.string.settings_open_profile)
    Column(
      Modifier
        .fillMaxWidth()
        .layout { m, c ->
          val p = m.measure(c)
          val up = DP_OVERLAP.roundToPx()
          layout(p.width, p.height - up) { p.place(0, -up) }
        }
        .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenProfile)
        .padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(Modifier.size(DP_SIZE + 8.dp).border(4.dp, colors.background, CircleShape).padding(4.dp), contentAlignment = Alignment.Center) {
        Avatar(ChatList.initials(name), me, size = DP_SIZE, photoPath = photoPath)
      }
      Text(
        name,
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
      if (!username.isNullOrEmpty()) {
        Text("@$username", style = MaterialTheme.typography.bodyMedium, color = colors.brand)
      }
      Text(
        about.ifBlank { stringResource(R.string.profile_about_empty) },
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
      )
    }
  }
}

/** Flat brand banner: tonal container with a soft pinwheel pattern and a larger mark. No image assets. */
@Composable
private fun BrandBanner(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val colors = SamvadTheme.colors
  val base = colors.primaryContainer
  val soft = colors.primary.copy(alpha = 0.07f)
  val mark = colors.primary.copy(alpha = 0.16f)
  Box(
    modifier.drawBehind {
      drawRect(base)
      val cell = 56.dp.toPx()
      var row = 0
      var y = cell / 2
      while (y < size.height + cell) {
        var x = if (row % 2 == 0) cell / 2 else cell
        while (x < size.width + cell) {
          pinwheel(Offset(x, y), 9.dp.toPx(), soft)
          x += cell
        }
        y += cell * 0.8f
        row++
      }
      pinwheel(Offset(size.width - 64.dp.toPx(), size.height * 0.45f), 46.dp.toPx(), mark)
    },
  ) { content() }
}

/** Four-blade pinwheel: the Samvad mark, drawn from rotated right triangles. */
private fun DrawScope.pinwheel(center: Offset, r: Float, color: Color) {
  for (i in 0 until 4) {
    rotate(90f * i, center) {
      val blade = Path().apply {
        moveTo(center.x, center.y)
        lineTo(center.x + r, center.y)
        lineTo(center.x + r, center.y - r)
        close()
      }
      drawPath(blade, color)
    }
  }
}
