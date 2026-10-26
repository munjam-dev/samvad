package com.samvad.messenger.ui.community

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.pressScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Bento hub: a square Communities card, a tall Vault card beside it, a Moments card under the
 * square and a slim Starred card across the bottom. Each card opens an existing destination.
 */
@Composable
fun HubCards(
  onCommunities: () -> Unit,
  onVault: () -> Unit,
  onMoments: () -> Unit,
  onStarred: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val gap = 12.dp
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val total = if (maxWidth > 560.dp) 560.dp else maxWidth
    val cell = (total - gap) / 2
    Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
      Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
          HubCard(
            Icons.Outlined.Groups, R.string.nav_communities, R.string.hub_communities_sub,
            colors.primaryContainer, colors.onPrimaryContainer, onCommunities,
            Modifier.heightIn(min = cell).fillMaxWidth(),
          )
          HubCard(
            Icons.Outlined.PhotoCamera, R.string.nav_status, R.string.hub_moments_sub,
            colors.secondaryContainer, colors.onSecondaryContainer, onMoments,
            Modifier.heightIn(min = cell * 0.6f).fillMaxWidth(),
          )
        }
        HubCard(
          Icons.Outlined.Lock, R.string.vault_title, R.string.hub_vault_sub,
          colors.tertiaryContainer, colors.onTertiaryContainer, onVault,
          Modifier.weight(1f).fillMaxHeight(),
        )
      }
      HubCard(
        Icons.Outlined.StarOutline, R.string.starred_title, R.string.hub_starred_sub,
        colors.surfaceContainerHigh, colors.onSurface, onStarred,
        Modifier.fillMaxWidth(), wide = true,
      )
    }
  }
}

@Composable
private fun HubCard(
  icon: ImageVector,
  title: Int,
  subtitle: Int,
  container: Color,
  content: Color,
  onClick: () -> Unit,
  modifier: Modifier,
  wide: Boolean = false,
) {
  val shape = RoundedCornerShape(24.dp)
  val titleText = stringResource(title)
  val subText = stringResource(subtitle)
  val press = remember { MutableInteractionSource() }
  val reduced = LocalReducedMotion.current
  val appear = remember { Animatable(if (reduced) 1f else 0f) }
  LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, Motion.lively()) }
  Column(
    modifier
      .graphicsLayer {
        val t = appear.value.coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 16.dp.toPx()
      }
      .pressScale(press, 0.97f)
      .shadow(1.dp, shape, clip = false)
      .clip(shape)
      .background(container, shape)
      .border(1.dp, content.copy(alpha = 0.08f), shape)
      .clickable(interactionSource = press, indication = LocalIndication.current, role = Role.Button, onClickLabel = titleText, onClick = onClick)
      .padding(20.dp),
    verticalArrangement = if (wide) Arrangement.spacedBy(4.dp) else Arrangement.SpaceBetween,
  ) {
    if (wide) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = content, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
          Text(titleText, style = MaterialTheme.typography.titleMedium, color = content)
          Text(subText, style = MaterialTheme.typography.bodySmall, color = content, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
      }
    } else {
      Icon(icon, null, tint = content, modifier = Modifier.size(32.dp))
      Spacer(Modifier.height(12.dp))
      Column {
        Text(titleText, style = MaterialTheme.typography.titleLarge, color = content)
        Text(subText, style = MaterialTheme.typography.bodyMedium, color = content, modifier = Modifier.padding(top = 4.dp))
      }
    }
  }
}
