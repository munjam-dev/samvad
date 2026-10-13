package com.samvad.messenger.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.motionSpec
import com.samvad.messenger.ui.theme.pressScale
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.DeliveryState
import com.samvad.core.chat.Message
import com.samvad.core.chat.TimelineItem
import com.samvad.core.presence.PresenceStatus
import androidx.compose.animation.togetherWith
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.push.ActiveChat
import com.samvad.messenger.push.Notifications
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.StatusBanner
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "🙏", "🎉")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
  container: AppContainer,
  me: String,
  conversationId: String,
  otherUid: String,
  onBack: () -> Unit,
) {
  val vm: ConversationViewModel = viewModel(factory = ConversationViewModel.factory(container, me, conversationId, otherUid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val composer = vm.composer.collectAsStateWithLifecycle().value
  val presence = vm.status.collectAsStateWithLifecycle().value
  val colors = SamvadTheme.colors
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val clipboard = LocalClipboardManager.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var selected by remember { mutableStateOf<Message?>(null) }
  var menu by remember { mutableStateOf(false) }
  val copied = stringResource(R.string.chat_copied)
  val mediaSoon = stringResource(R.string.chat_attachments_soon)

  // While this chat is on screen, its notifications are suppressed and cleared.
  val appContext = LocalContext.current.applicationContext
  DisposableEffect(conversationId, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          ActiveChat.set(conversationId)
          Notifications.cancel(appContext, conversationId)
        }
        Lifecycle.Event.ON_PAUSE -> ActiveChat.set(null)
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      ActiveChat.set(null)
    }
  }

  // Read receipts: only while the screen is actually visible.
  LaunchedEffect(state.latestIncomingId) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.markRead() }
  }
  // Page older history when the user nears the top (the end of a reversed list).
  val nearTop by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 5 } }
  LaunchedEffect(nearTop, state.hasMore) { if (nearTop && state.hasMore) vm.loadMore() }
  val awayFromLatest by remember { derivedStateOf { list.firstVisibleItemIndex > 3 } }
  // Messages present at first load appear instantly; only later arrivals animate in.
  var initialIds by remember { mutableStateOf<Set<String>?>(null) }
  LaunchedEffect(state.loading, state.items.isNotEmpty()) {
    if (!state.loading && initialIds == null) initialIds = state.items.map { it.key }.toSet()
  }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize().imePadding()) {
      // Header
      Row(
        Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Avatar(state.initials, otherUid, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
          Text(
            state.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val subtitle = when (presence) {
            PresenceStatus.Typing -> stringResource(R.string.chat_typing)
            PresenceStatus.Online -> stringResource(R.string.chat_online)
            is PresenceStatus.LastSeen -> presence.label
            PresenceStatus.Unknown -> null
          }
          androidx.compose.animation.AnimatedContent(
            targetState = subtitle,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "presence",
          ) { line ->
            if (line != null) {
              Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = if (presence == PresenceStatus.Typing || presence == PresenceStatus.Online) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
              )
            }
          }
        }
        Box {
          IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = colors.onSurface) }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(if (state.pinned) R.string.chat_unpin else R.string.chat_pin)) },
              onClick = { vm.toggle("pinned", !state.pinned); menu = false },
            )
            DropdownMenuItem(
              text = { Text(stringResource(if (state.muted) R.string.chat_unmute else R.string.chat_mute)) },
              onClick = { vm.toggle("muted", !state.muted); menu = false },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_archive)) }, onClick = { vm.toggle("archived", true); menu = false; onBack() })
          }
        }
      }
      HorizontalDivider(color = colors.outlineVariant)
      StatusBanner(state.offline, stringResource(R.string.home_offline))
      StatusBanner(state.error, stringResource(R.string.home_load_error), isError = true, actionLabel = stringResource(R.string.retry), onAction = vm::retryLoad)

      Box(Modifier.weight(1f)) {
        when {
          state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.brand)
          state.items.isEmpty() -> Text(
            stringResource(R.string.chat_empty, state.title),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
          )
          else -> LazyColumn(
            state = list,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
          ) {
            items(state.items, key = { it.key }, contentType = { it::class }) { item ->
              Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.offsetSpring)) {
              when (item) {
                is TimelineItem.DateSeparator -> DateChip(item.label)
                is TimelineItem.Bubble -> MessageBubble(
                  item = item,
                  animateIn = initialIds?.contains(item.key) == false,
                  replied = item.message.replyTo?.let { id -> state.items.firstOrNull { it.key == id } as? TimelineItem.Bubble },
                  otherName = state.title,
                  onLongPress = { selected = item.message },
                  onRetry = { vm.retry(item.message) },
                  onReact = { emoji -> vm.react(item.message, emoji) },
                )
              }
              }
            }
          }
        }
        androidx.compose.animation.AnimatedVisibility(
          visible = awayFromLatest,
          modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
          enter = fadeIn() + scaleIn(Motion.lively(), initialScale = 0.6f),
          exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
          SmallFloatingActionButton(
            onClick = { scope.launch { list.animateScrollToItem(0) } },
            containerColor = colors.surface,
            contentColor = colors.onSurface,
          ) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.chat_scroll_latest)) }
        }
      }

      Composer(
        state = composer,
        me = me,
        otherName = state.title,
        onText = vm::onText,
        onSend = vm::submit,
        onCancel = vm::cancelContext,
        onAttach = { scope.launch { snackbar.showSnackbar(mediaSoon) } },
      )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
  }

  selected?.let { msg ->
    ModalBottomSheet(onDismissRequest = { selected = null }) {
      MessageActions(
        message = msg,
        mine = msg.senderId == me,
        canEdit = vm.canEdit(msg),
        onReact = { vm.react(msg, it); selected = null },
        onReply = { vm.reply(msg); selected = null },
        onCopy = {
          clipboard.setText(AnnotatedString(msg.text))
          selected = null
          scope.launch { snackbar.showSnackbar(copied) }
        },
        onEdit = { vm.startEdit(msg); selected = null },
        onDelete = { vm.delete(msg); selected = null },
      )
    }
  }
}

@Composable
private fun DateChip(label: String) {
  val colors = SamvadTheme.colors
  Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.background(colors.surfaceContainer, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
  item: TimelineItem.Bubble,
  animateIn: Boolean,
  replied: TimelineItem.Bubble?,
  otherName: String,
  onLongPress: () -> Unit,
  onRetry: () -> Unit,
  onReact: (String) -> Unit,
) {
  val colors = SamvadTheme.colors
  val m = item.message
  val mine = item.mine
  val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
  val bg = if (mine) colors.bubbleOutgoing else colors.bubbleIncoming
  val meta = if (mine) colors.bubbleMeta else colors.onSurfaceVariant
  val retryLabel = stringResource(R.string.chat_retry)
  val reduced = LocalReducedMotion.current
  val appear = remember(m.id) { Animatable(if (animateIn && !reduced) 0f else 1f) }
  LaunchedEffect(m.id) { if (appear.value < 1f) appear.animateTo(1f, Motion.lively()) }

  Column(
    Modifier
      .fillMaxWidth()
      .padding(top = if (item.groupedWithPrevious) 2.dp else 8.dp)
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
        .background(bg)
        .then(if (!mine) Modifier.border(1.dp, colors.outlineVariant, shape) else Modifier)
        .combinedClickable(
          onClick = { if (m.failed) onRetry() },
          onClickLabel = if (m.failed) retryLabel else null,
          onLongClick = { if (!m.deleted && !m.pending) onLongPress() },
        )
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      if (replied != null && !m.deleted) {
        Column(
          Modifier
            .padding(bottom = 6.dp)
            .background(if (mine) colors.primaryContainer else colors.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
          Text(
            if (replied.mine) stringResource(R.string.chat_you) else otherName,
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
          )
          Text(replied.message.text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
      Text(
        if (m.deleted) stringResource(R.string.chat_deleted) else m.text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (m.deleted) colors.onSurfaceVariant else colors.onBubble,
      )
      Row(
        Modifier.align(Alignment.End).padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (m.editedAt != null && !m.deleted) Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = meta)
        Text(item.timeLabel, style = MaterialTheme.typography.labelSmall, color = meta)
        item.delivery?.let { DeliveryIcon(it) }
      }
    }
    if (m.failed) {
      Text(retryLabel, style = MaterialTheme.typography.labelSmall, color = colors.error, modifier = Modifier.padding(top = 2.dp))
    }
    androidx.compose.animation.AnimatedVisibility(
      visible = m.reactions.isNotEmpty(),
      enter = scaleIn(Motion.lively(), initialScale = 0.4f) + fadeIn(),
      exit = scaleOut() + fadeOut(),
    ) {
      Row(
        Modifier
          .padding(top = 2.dp, start = 8.dp, end = 8.dp)
          .background(colors.surface, RoundedCornerShape(12.dp))
          .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp),
      ) {
        m.reactions.values.groupingBy { it }.eachCount().forEach { (emoji, count) ->
          Text(
            if (count > 1) "$emoji $count" else emoji,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable { onReact(emoji) }.padding(horizontal = 2.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun DeliveryIcon(state: DeliveryState) {
  val colors = SamvadTheme.colors
  val (icon: ImageVector, label: Int) = when (state) {
    DeliveryState.SENDING -> Icons.Outlined.Schedule to R.string.cd_delivery_sending
    DeliveryState.SENT -> Icons.Outlined.Done to R.string.cd_delivery_sent
    DeliveryState.DELIVERED -> Icons.Outlined.DoneAll to R.string.cd_delivery_delivered
    DeliveryState.READ -> Icons.Outlined.DoneAll to R.string.cd_delivery_read
    DeliveryState.FAILED -> Icons.Outlined.ErrorOutline to R.string.cd_delivery_failed
  }
  val tint = when (state) {
    DeliveryState.READ -> colors.primary
    DeliveryState.FAILED -> colors.error
    else -> colors.bubbleMeta
  }
  Crossfade(targetState = icon, label = "delivery") { shown ->
    Icon(shown, stringResource(label), tint = tint, modifier = Modifier.size(15.dp))
  }
}

@Composable
private fun Composer(
  state: ComposerState,
  me: String,
  otherName: String,
  onText: (String) -> Unit,
  onSend: () -> Unit,
  onCancel: () -> Unit,
  onAttach: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val hint = stringResource(R.string.chat_message_hint)
  Column(Modifier.fillMaxWidth().background(colors.background).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
    val context = state.editing?.let { stringResource(R.string.chat_editing) }
      ?: state.replyTo?.let { stringResource(R.string.chat_replying_to, if (it.senderId == me) stringResource(R.string.chat_you) else otherName) }
    if (context != null) {
      Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).background(colors.surfaceContainer, RoundedCornerShape(14.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(if (state.editing != null) Icons.Outlined.Edit else Icons.AutoMirrored.Outlined.Reply, null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Text(context, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.chat_cancel), tint = colors.onSurfaceVariant) }
      }
    }
    if (state.tooLong) Text(stringResource(R.string.chat_too_long), color = colors.error, style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        Modifier
          .weight(1f)
          .heightIn(min = 48.dp)
          .background(colors.surface, RoundedCornerShape(24.dp))
          .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
          .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
          if (state.text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
          BasicTextField(
            value = state.text,
            onValueChange = onText,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.brand),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
          )
        }
        IconButton(onClick = onAttach) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.cd_attach), tint = colors.onSurfaceVariant) }
      }
      val canSend = state.text.isNotBlank() && !state.tooLong
      val sendBg by animateColorAsState(if (canSend) colors.primary else colors.surfaceContainer, motionSpec(Motion.standard()), label = "sendBg")
      val sendTint by animateColorAsState(if (canSend) colors.onPrimary else colors.onSurfaceVariant, motionSpec(Motion.standard()), label = "sendTint")
      val sendScale by animateFloatAsState(if (canSend) 1f else 0.9f, motionSpec(Motion.lively()), label = "sendScale")
      val sendPress = remember { MutableInteractionSource() }
      IconButton(
        onClick = onSend,
        enabled = canSend,
        interactionSource = sendPress,
        modifier = Modifier
          .size(48.dp)
          .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
          .pressScale(sendPress, 0.88f)
          .background(sendBg, CircleShape),
      ) {
        Crossfade(targetState = state.editing != null, label = "sendIcon") { editing ->
          Icon(
            if (editing) Icons.Outlined.Done else Icons.AutoMirrored.Outlined.Send,
            stringResource(R.string.cd_send),
            tint = sendTint,
          )
        }
      }
    }
  }
}

@Composable
private fun MessageActions(
  message: Message,
  mine: Boolean,
  canEdit: Boolean,
  onReact: (String) -> Unit,
  onReply: () -> Unit,
  onCopy: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
      QUICK_REACTIONS.forEach { emoji ->
        val chosen = message.reactions.values.contains(emoji)
        Box(
          Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (chosen) colors.primaryContainer else colors.surfaceContainer)
            .clickable { onReact(emoji) },
          contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
      }
    }
    Spacer(Modifier.size(8.dp))
    ActionRow(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.chat_reply), onReply)
    ActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.chat_copy), onCopy)
    if (canEdit) ActionRow(Icons.Outlined.Edit, stringResource(R.string.chat_edit), onEdit)
    if (mine) ActionRow(Icons.Outlined.Delete, stringResource(R.string.chat_delete), onDelete, danger = true)
  }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(icon, null, tint = if (danger) colors.error else colors.onSurfaceVariant)
    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) colors.error else colors.onSurface)
  }
}
