package com.samvad.messenger.ui.tarang

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.DeliveryState
import com.samvad.core.tarang.TarangDelivery
import com.samvad.core.tarang.TarangMessage
import com.samvad.messenger.R
import com.samvad.messenger.ui.chat.DateChip
import com.samvad.messenger.ui.chat.DeliveryIcon
import com.samvad.messenger.ui.chat.SendButton
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.SamvadTheme
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * A nearby chat, shared by the public room and a private 1:1 chat. Pattern: Detail (message thread). It uses
 * the same top bar, bubble shapes, colours, send button and composer shape as Samvad's own conversation screen,
 * plus a small "Tarang" tag in the top bar so it is clear this chat travels over Bluetooth, not the internet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TarangChatScreen(
  title: String,
  subtitle: String?,
  avatarSeed: String,
  isRoom: Boolean,
  messages: List<TarangMessage>,
  canSend: Boolean,
  banner: String?,
  emptyTitle: String,
  emptyBody: String,
  onSend: (String) -> Unit,
  onRetry: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val listState = rememberLazyListState()
  LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
  Column(modifier.fillMaxSize().background(colors.background).imePadding()) {
    Row(
      Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      if (isRoom) {
        Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.Groups, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
      } else {
        Avatar(ChatList.initials(title), avatarSeed, size = 40.dp, photoPath = null)
      }
      Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
          Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      TarangChip(Modifier.padding(end = 12.dp))
    }
    HorizontalDivider(color = colors.outlineVariant)
    if (banner != null) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
          .background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
      ) {
        Text(banner, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
      }
    }
    Box(Modifier.weight(1f)) {
      if (messages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
          Text(emptyTitle, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
          Text(emptyBody, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
      } else {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
          itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
            val previous = messages.getOrNull(index - 1)
            if (previous == null || !sameDay(previous.timestampMs, m.timestampMs)) DateChip(dayLabel(m.timestampMs))
            val grouped = previous != null && previous.mine == m.mine && previous.peerId == m.peerId &&
              previous.senderName == m.senderName && sameDay(previous.timestampMs, m.timestampMs)
            TarangBubble(m, grouped = grouped, showSender = isRoom && !m.mine && !grouped, onRetry = { onRetry(m.id) })
          }
        }
      }
    }
    TarangComposer(canSend = canSend, onSend = onSend)
  }
}

/** The small tag that marks a chat as nearby (Bluetooth) rather than over the internet. Outlined, not filled. */
@Composable
fun TarangChip(modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val label = stringResource(R.string.tarang_title)
  val description = stringResource(R.string.tarang_chip_cd)
  Row(
    modifier
      .border(1.dp, colors.outline, RoundedCornerShape(10.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp)
      .semantics(mergeDescendants = true) { contentDescription = description },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(Icons.Outlined.Bluetooth, null, tint = colors.primary, modifier = Modifier.size(14.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 1)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TarangBubble(m: TarangMessage, grouped: Boolean, showSender: Boolean, onRetry: () -> Unit) {
  val colors = SamvadTheme.colors
  val mine = m.mine
  val shape = when {
    grouped -> RoundedCornerShape(20.dp)
    mine -> RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
    else -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
  }
  val reduced = LocalReducedMotion.current
  val appear = remember(m.id) { Animatable(if (reduced) 1f else 0f) }
  LaunchedEffect(m.id) { if (appear.value < 1f) appear.animateTo(1f, Motion.lively()) }
  val failed = m.delivery == TarangDelivery.FAILED
  val retryLabel = stringResource(R.string.tarang_retry)
  Column(
    Modifier
      .fillMaxWidth()
      .padding(top = if (grouped) 3.dp else 10.dp)
      .graphicsLayer {
        alpha = appear.value.coerceIn(0f, 1f)
        val s = 0.85f + 0.15f * appear.value
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
      },
    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
  ) {
    Column(
      Modifier
        .widthIn(max = 300.dp)
        .clip(shape)
        .background(if (mine) colors.bubbleOutgoing else colors.bubbleIncoming)
        .then(if (!mine) Modifier.border(1.dp, colors.outlineVariant, shape) else Modifier)
        .then(if (failed) Modifier.clickable(onClickLabel = retryLabel, onClick = onRetry) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      if (showSender) {
        Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Text(m.text, style = MaterialTheme.typography.bodyLarge, color = colors.onBubble)
      Row(
        Modifier.align(Alignment.End).padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(timeLabel(m.timestampMs), style = MaterialTheme.typography.labelSmall, color = colors.bubbleMeta)
        m.delivery?.let { DeliveryIcon(it.toDeliveryState()) }
      }
    }
    if (failed) Text(retryLabel, style = MaterialTheme.typography.labelSmall, color = colors.error, modifier = Modifier.padding(top = 2.dp))
  }
}

@Composable
private fun TarangComposer(canSend: Boolean, onSend: (String) -> Unit) {
  val colors = SamvadTheme.colors
  val hint = stringResource(R.string.tarang_chat_hint)
  var text by rememberSaveable { mutableStateOf("") }
  Column(Modifier.fillMaxWidth().background(colors.background).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
    if (!canSend) {
      Text(stringResource(R.string.tarang_chat_off), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp, start = 6.dp))
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(
        Modifier
          .weight(1f)
          .heightIn(min = 48.dp)
          .background(colors.surface, RoundedCornerShape(24.dp))
          .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
          .padding(horizontal = 16.dp, vertical = 12.dp),
      ) {
        if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        BasicTextField(
          value = text,
          onValueChange = { if (it.length <= MAX_CHARS) text = it },
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
          cursorBrush = SolidColor(colors.brand),
          maxLines = 6,
          modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        )
      }
      SendButton(
        canSend = canSend && text.isNotBlank(),
        editing = false,
        onSend = {
          val body = text
          text = ""
          onSend(body)
        },
      )
    }
  }
}

private const val MAX_CHARS = 500

private fun TarangDelivery.toDeliveryState(): DeliveryState = when (this) {
  TarangDelivery.SENDING -> DeliveryState.SENDING
  TarangDelivery.SENT -> DeliveryState.SENT
  TarangDelivery.DELIVERED -> DeliveryState.DELIVERED
  TarangDelivery.READ -> DeliveryState.READ
  TarangDelivery.FAILED -> DeliveryState.FAILED
}

private fun timeLabel(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

private fun sameDay(a: Long, b: Long): Boolean {
  val x = Calendar.getInstance().apply { timeInMillis = a }
  val y = Calendar.getInstance().apply { timeInMillis = b }
  return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
