package com.samvad.messenger.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.ProvideChatTheme
import com.samvad.core.design.ChatThemes
import com.samvad.core.design.ChatBackground
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.DeliveryState
import com.samvad.core.chat.Message
import com.samvad.core.chat.TimelineItem
import com.samvad.core.presence.PresenceStatus
import androidx.compose.animation.togetherWith
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.core.chat.ExportLabels
import com.samvad.messenger.ui.components.LocalPhotoSource
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
  /** Null for a group. */
  otherUid: String?,
  onBack: () -> Unit,
  /** Groups: opens group info; null hides the entry points. */
  onOpenGroupInfo: (() -> Unit)? = null,
  onOpenChatTheme: () -> Unit = {},
) {
  val appCtx = LocalContext.current.applicationContext as android.app.Application
  val vm: ConversationViewModel = viewModel(factory = ConversationViewModel.factory(appCtx, container, me, conversationId, otherUid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val composer = vm.composer.collectAsStateWithLifecycle().value
  val presence = vm.status.collectAsStateWithLifecycle().value
  val groupStatus = vm.groupStatus.collectAsStateWithLifecycle().value
  val mentionCandidates = vm.mentionCandidates.collectAsStateWithLifecycle().value
  var composingPoll by remember { mutableStateOf(false) }
  val themeSettings = container.chatThemes.settings.collectAsStateWithLifecycle(null).value
  val backdrop = themeSettings?.backgroundFor(conversationId) ?: ChatBackground.Theme(ChatThemes.DEFAULT.id)
  val dark = SamvadTheme.colors.isDark
  ProvideChatTheme(chatThemeColors(backdrop, dark)) {
  val colors = SamvadTheme.colors
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val clipboard = LocalClipboardManager.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var selected by remember { mutableStateOf<Message?>(null) }
  var person by remember { mutableStateOf<String?>(null) }
  var privacyInfo by remember { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  val copied = stringResource(R.string.chat_copied)
  val uploads = vm.uploads.collectAsStateWithLifecycle().value
  val recording = vm.recorder.state.collectAsStateWithLifecycle().value
  val playback = vm.player.state.collectAsStateWithLifecycle().value
  val mediaError = vm.mediaError.collectAsStateWithLifecycle().value
  val blocked = vm.blocked.collectAsStateWithLifecycle().value
  val notice = vm.notice.collectAsStateWithLifecycle().value
  val reportSending = vm.reporting.collectAsStateWithLifecycle().value
  var confirmBlock by remember { mutableStateOf(false) }
  var reportOpen by remember { mutableStateOf(false) }
  val noticeText = notice?.let {
    stringResource(
      when (it) {
        SafetyNotice.REPORTED -> R.string.report_sent
        SafetyNotice.REPORTED_AND_BLOCKED -> R.string.report_sent_blocked
        SafetyNotice.REPORT_LIMIT -> R.string.report_limit
        SafetyNotice.FAILED -> R.string.safety_failed
      },
    )
  }
  LaunchedEffect(noticeText) {
    if (noticeText != null) {
      // Any outcome closes the report dialog; a retry reuses the same request ID.
      reportOpen = false
      snackbar.showSnackbar(noticeText)
      vm.clearNotice()
    }
  }
  var attaching by remember { mutableStateOf(false) }
  var viewing by remember { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  var confirmExport by remember { mutableStateOf(false) }
  var forwarding by remember { mutableStateOf<Message?>(null) }
  val forwardResult = vm.forwardResult.collectAsStateWithLifecycle().value
  val forwardText = forwardResult?.let {
    if (it.failed == 0) androidx.compose.ui.res.pluralStringResource(R.plurals.forward_sent, it.sent, it.sent)
    else stringResource(R.string.forward_partial, it.sent, it.sent + it.failed)
  }
  LaunchedEffect(forwardText) {
    if (forwardText != null) {
      vm.clearForwardResult()
      snackbar.showSnackbar(forwardText)
    }
  }
  var exporting by remember { mutableStateOf(false) }
  val exportLabels = ExportLabels(
    header = stringResource(R.string.export_header, state.title),
    deleted = stringResource(R.string.chat_deleted),
    photo = stringResource(R.string.export_photo),
    voice = stringResource(R.string.export_voice),
    file = stringResource(R.string.export_file),
    edited = stringResource(R.string.export_edited),
    unknownSender = stringResource(R.string.export_unknown),
  )
  val you = stringResource(R.string.chat_you)
  val exportFailed = stringResource(R.string.export_failed)
  val shareTitle = stringResource(R.string.export_share_title)
  val runExport: () -> Unit = {
    exporting = true
    scope.launch {
      val file = vm.exportChat(exportLabels, you)
      exporting = false
      if (file == null) {
        snackbar.showSnackbar(exportFailed)
      } else {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(android.content.Intent.EXTRA_STREAM, uri)
          .putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
          .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        container.appLock.expectExternal()
        runCatching { context.startActivity(android.content.Intent.createChooser(send, shareTitle)) }
          .onFailure { snackbar.showSnackbar(exportFailed) }
      }
    }
  }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::sendImage) }
  val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::sendDocument) }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) vm.startRecording() else vm.reportMediaError(MediaError.OPEN)
  }
  val errorText = mediaError?.let {
    stringResource(
      when (it) {
        MediaError.TOO_LARGE -> R.string.media_too_large
        MediaError.TYPE_BLOCKED -> R.string.media_type_blocked
        MediaError.UNREADABLE -> R.string.error_unknown
        MediaError.DOWNLOAD -> R.string.media_download_failed
        MediaError.OPEN -> R.string.voice_permission
      },
    )
  }
  LaunchedEffect(errorText) {
    if (errorText != null) {
      snackbar.showSnackbar(errorText)
      vm.clearMediaError()
    }
  }
  val openFile: (com.samvad.core.media.Media) -> Unit = { media ->
    scope.launch {
      val file = vm.localFile(media.path) ?: return@launch vm.reportMediaError(MediaError.DOWNLOAD)
      val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
      val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(uri, media.mime)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
      container.appLock.expectExternal()
      runCatching { context.startActivity(intent) }.onFailure {
        snackbar.showSnackbar(context.getString(R.string.media_open_failed))
      }
    }
  }

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
        Avatar(
          state.initials,
          otherUid ?: conversationId,
          size = 40.dp,
          photoPath = if (state.isGroup) state.groupPhotoPath else LocalPhotoSource.current?.pathFor(otherUid.orEmpty()),
        )
        val groupInfoLabel = stringResource(R.string.group_info)
        Column(
          Modifier
            .weight(1f)
            .then(if (state.isGroup && onOpenGroupInfo != null) Modifier.clickable(onClickLabel = groupInfoLabel, onClick = onOpenGroupInfo) else Modifier)
            .padding(start = 12.dp),
        ) {
          Text(
            state.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val you = stringResource(R.string.chat_you)
          val subtitle = when {
            state.isGroup && groupStatus.typing.size == 1 -> stringResource(R.string.group_typing_one, groupStatus.typing[0])
            state.isGroup && groupStatus.typing.size > 1 -> stringResource(R.string.group_typing_many, groupStatus.typing.joinToString(", "))
            state.isGroup -> (groupStatus.members + you).joinToString(", ")
            else -> when (presence) {
              PresenceStatus.Typing -> stringResource(R.string.chat_typing)
              PresenceStatus.Online -> stringResource(R.string.chat_online)
              is PresenceStatus.LastSeen -> presence.label
              PresenceStatus.Unknown -> null
            }
          }
          val highlight = presence == PresenceStatus.Typing || presence == PresenceStatus.Online || groupStatus.typing.isNotEmpty()
          androidx.compose.animation.AnimatedContent(
            targetState = subtitle,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "presence",
          ) { line ->
            if (line != null) {
              Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlight) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
        Box {
          IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = colors.onSurface) }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (state.isGroup && onOpenGroupInfo != null) {
              DropdownMenuItem(text = { Text(stringResource(R.string.group_info)) }, onClick = { menu = false; onOpenGroupInfo() })
            }
            DropdownMenuItem(
              text = { Text(stringResource(if (state.pinned) R.string.chat_unpin else R.string.chat_pin)) },
              onClick = { vm.toggle("pinned", !state.pinned); menu = false },
            )
            DropdownMenuItem(
              text = { Text(stringResource(if (state.muted) R.string.chat_unmute else R.string.chat_mute)) },
              onClick = { vm.toggle("muted", !state.muted); menu = false },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_archive)) }, onClick = { vm.toggle("archived", true); menu = false; onBack() })
            DropdownMenuItem(
              text = { Text(stringResource(R.string.export_chat)) },
              enabled = !exporting,
              onClick = { menu = false; confirmExport = true },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_theme_title)) }, onClick = { menu = false; onOpenChatTheme() })
            if (!state.isGroup) {
            HorizontalDivider(color = colors.outlineVariant)
            DropdownMenuItem(
              text = { Text(stringResource(if (blocked) R.string.chat_unblock else R.string.chat_block)) },
              onClick = {
                menu = false
                if (blocked) vm.unblock() else confirmBlock = true
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.chat_report), color = colors.error) },
              onClick = { menu = false; reportOpen = true },
            )
            }
          }
        }
      }
      HorizontalDivider(color = colors.outlineVariant)
      StatusBanner(state.offline, stringResource(R.string.home_offline))
      StatusBanner(state.error, stringResource(R.string.home_load_error), isError = true, actionLabel = stringResource(R.string.retry), onAction = vm::retryLoad)

      Box(Modifier.weight(1f)) {
        if (themeSettings != null) ChatBackdrop(backdrop, themeSettings, container.chatThemes, dark, Modifier.fillMaxSize())
        when {
          state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.brand)
          state.items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            PrivacyNotice(onLearnMore = { privacyInfo = true })
            Text(
              stringResource(R.string.chat_empty, state.title),
              style = MaterialTheme.typography.bodyLarge,
              color = colors.onChip,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
          }
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
                is TimelineItem.SystemLine -> SystemLineChip(item.text)
                is TimelineItem.Bubble -> MessageBubble(
                  item = item,
                  animateIn = initialIds?.contains(item.key) == false,
                  replied = item.message.replyTo?.let { id -> state.items.firstOrNull { it.key == id } as? TimelineItem.Bubble },
                  otherName = item.message.replyTo?.let { id -> (state.items.firstOrNull { it.key == id } as? TimelineItem.Bubble)?.message?.senderId }?.let(vm::displayName) ?: state.title,
                  onLongPress = { selected = item.message },
                  onOpenSender = { person = item.message.senderId },
                  onRetry = { vm.retry(item.message) },
                  onReact = { emoji -> vm.react(item.message, emoji) },
                  mentionNames = groupStatus.allNames,
                  mediaContent = {
                    val media = item.message.media
                    if (media != null) {
                      when (item.message.kind) {
                        "image" -> MediaImage(media, vm::bitmap) { viewing = media.path }
                        "voice" -> VoiceNote(item.message.id, media, playback, item.mine) { vm.togglePlayback(item.message) }
                        else -> FileCard(media) { openFile(media) }
                      }
                    }
                    val poll = item.message.poll
                    if (item.message.kind == "poll" && poll != null && !item.message.deleted) {
                      val votes = remember(item.message.id) { vm.votes(item.message.id) }.collectAsStateWithLifecycle(emptyMap()).value
                      val result = remember(votes, poll) { com.samvad.core.chat.Polls.tally(poll, votes, me) }
                      PollCard(item.message, result, item.mine) { id -> vm.vote(item.message, id, result.mine) }
                    }
                  },
                )
              }
              }
            }
            if (!state.hasMore) item(key = "privacy-notice") { PrivacyNotice(onLearnMore = { privacyInfo = true }) }
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

      uploads.forEach { up ->
        PendingUploadRow(up, onRetry = { vm.retryUpload(up.id) }, onCancel = { vm.cancelUpload(up.id) })
      }
      if (state.removed) {
        RemovedBar()
      } else if (blocked) {
        BlockedBar(state.title, onUnblock = vm::unblock)
      } else if (recording.active) {
        RecorderBar(recording, onCancel = vm::cancelRecording, onSend = vm::finishRecording)
      } else {
        if (mentionCandidates.isNotEmpty()) MentionSuggestions(mentionCandidates, vm::pickMention)
        Composer(
          state = composer,
          me = me,
          otherName = composer.replyTo?.let { vm.displayName(it.senderId) } ?: state.title,
          onText = vm::onText,
          onSend = vm::submit,
          onCancel = vm::cancelContext,
          onAttach = { attaching = true },
          onMic = {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
              context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) vm.startRecording() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
          },
        )
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
  }

  if (attaching) {
    ModalBottomSheet(onDismissRequest = { attaching = false }) {
      AttachOptions(
        onPhoto = {
          attaching = false
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onDocument = {
          attaching = false
          container.appLock.expectExternal()
          pickDocument.launch(com.samvad.core.media.MediaRules.pickableDocuments)
        },
        onPoll = { attaching = false; composingPoll = true },
      )
    }
  }
  viewing?.let { path -> MediaViewer(path, vm::bitmap) { viewing = null } }

  if (privacyInfo) {
    ModalBottomSheet(onDismissRequest = { privacyInfo = false }) { PrivacyInfoSheet(onClose = { privacyInfo = false }) }
  }

  person?.let { uid ->
    ModalBottomSheet(onDismissRequest = { person = null }) {
      PersonSheet(uid, vm.displayName(uid), vm.profile(uid)?.about.orEmpty())
    }
  }

  if (composingPoll) {
    ModalBottomSheet(onDismissRequest = { composingPoll = false }) {
      PollComposer(onCreate = vm::sendPoll, onClose = { composingPoll = false })
    }
  }

  forwarding?.let { msg ->
    ModalBottomSheet(onDismissRequest = { forwarding = null }) {
      val targets = remember { vm.forwardTargets() }.collectAsStateWithLifecycle(null).value
      ForwardSheet(targets) { chosen ->
        forwarding = null
        vm.forward(msg, chosen)
      }
    }
  }

  if (confirmExport) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { confirmExport = false },
      title = { Text(stringResource(R.string.export_chat)) },
      text = { Text(stringResource(R.string.export_body)) },
      confirmButton = {
        androidx.compose.material3.TextButton({ confirmExport = false; runExport() }) { Text(stringResource(R.string.export_confirm)) }
      },
      dismissButton = { androidx.compose.material3.TextButton({ confirmExport = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }

  if (confirmBlock) {
    BlockDialog(
      name = state.title,
      onConfirm = { confirmBlock = false; vm.block() },
      onDismiss = { confirmBlock = false },
    )
  }
  if (reportOpen) {
    ReportDialog(
      name = state.title,
      sending = reportSending,
      alreadyBlocked = blocked,
      onSend = vm::report,
      onDismiss = { reportOpen = false },
    )
  }

  selected?.let { msg ->
    ModalBottomSheet(onDismissRequest = { selected = null }) {
      MessageActions(
        message = msg,
        mine = msg.senderId == me,
        canEdit = vm.canEdit(msg),
        onReact = { vm.react(msg, it); selected = null },
        onReply = { vm.reply(msg); selected = null },
        canCopy = msg.kind == "text",
        onCopy = {
          clipboard.setText(AnnotatedString(msg.text))
          selected = null
          scope.launch { snackbar.showSnackbar(copied) }
        },
        onEdit = { vm.startEdit(msg); selected = null },
        onDelete = { vm.delete(msg); selected = null },
        canForward = com.samvad.core.chat.MessageRules.canForward(msg),
        onForward = { forwarding = msg; selected = null },
      )
    }
  }
  } // ProvideChatTheme
}

@Composable
private fun DateChip(label: String) {
  val colors = SamvadTheme.colors
  Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = colors.onChip,
      modifier = Modifier.background(colors.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
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
  mediaContent: @Composable () -> Unit = {},
  mentionNames: List<String> = emptyList(),
  /** Opens the sender's info (incoming messages: avatar and name). */
  onOpenSender: () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  val m = item.message
  val mine = item.mine
  // The first bubble of a run carries the tail at its top corner, beside the avatar (US-003).
  val shape = when {
    item.groupedWithPrevious -> RoundedCornerShape(20.dp)
    mine -> RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
    else -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
  }
  val indent = if (mine) 0.dp else AVATAR_SIZE + AVATAR_GAP
  val viewSender = item.senderName?.let { stringResource(R.string.chat_view_person, it) }
  val bg = if (mine) colors.bubbleOutgoing else colors.bubbleIncoming
  val meta = colors.bubbleMeta
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
    Row(verticalAlignment = Alignment.Top) {
      if (!mine) {
        if (item.showAvatar) {
          Avatar(
            ChatList.initials(item.senderName ?: ""),
            m.senderId,
            size = AVATAR_SIZE,
            modifier = Modifier
              .clip(CircleShape)
              .clickable(onClickLabel = viewSender, onClick = onOpenSender),
          )
        } else {
          Spacer(Modifier.width(AVATAR_SIZE))
        }
        Spacer(Modifier.width(AVATAR_GAP))
      }
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
        item.senderName?.let { SenderLabel(it, m.senderId) }
        if (m.forwarded && !m.deleted) ForwardedLabel(tint = meta)
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
        if (!m.deleted && (m.media != null || m.poll != null)) {
          mediaContent()
          if (m.text.isNotBlank()) Spacer(Modifier.size(6.dp))
        }
        if (m.deleted || m.text.isNotBlank()) {
          Text(
            if (m.deleted) AnnotatedString(stringResource(R.string.chat_deleted)) else mentionText(m.text, mentionNames),
            style = MaterialTheme.typography.bodyLarge,
            color = if (m.deleted) colors.onSurfaceVariant else colors.onBubble,
          )
        }
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
    }
    if (m.failed) {
      Text(retryLabel, style = MaterialTheme.typography.labelSmall, color = colors.error, modifier = Modifier.padding(top = 2.dp, start = indent))
    }
    androidx.compose.animation.AnimatedVisibility(
      visible = m.reactions.isNotEmpty(),
      enter = scaleIn(Motion.lively(), initialScale = 0.4f) + fadeIn(),
      exit = scaleOut() + fadeOut(),
    ) {
      Row(
        Modifier
          .padding(top = 2.dp, start = 8.dp + indent, end = 8.dp)
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

private val AVATAR_SIZE = 32.dp
private val AVATAR_GAP = 8.dp

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
  onMic: () -> Unit,
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
          // Keeps the cursor. When the view model replaces the text (a picked @mention, starting an
          // edit, clearing after send) the cursor moves to the end instead of staying mid-word.
          var field by remember { mutableStateOf(TextFieldValue(state.text, TextRange(state.text.length))) }
          val value = if (field.text == state.text) field else TextFieldValue(state.text, TextRange(state.text.length))
          BasicTextField(
            value = value,
            onValueChange = {
              field = it
              if (it.text != value.text) onText(it.text)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.brand),
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
          )
        }
        IconButton(onClick = onAttach) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.cd_attach), tint = colors.onSurfaceVariant) }
      }
      if (state.text.isEmpty() && state.editing == null) {
        IconButton(
          onClick = onMic,
          modifier = Modifier.size(48.dp).background(colors.primary, CircleShape),
        ) { Icon(Icons.Outlined.Mic, stringResource(R.string.voice_record), tint = colors.onPrimary) }
      } else {
        SendButton(canSend = state.text.isNotBlank() && !state.tooLong, editing = state.editing != null, onSend = onSend)
      }
    }
  }
}

@Composable
private fun SendButton(canSend: Boolean, editing: Boolean, onSend: () -> Unit) {
  val colors = SamvadTheme.colors
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
    Crossfade(targetState = editing, label = "sendIcon") { isEditing ->
      Icon(
        if (isEditing) Icons.Outlined.Done else Icons.AutoMirrored.Outlined.Send,
        stringResource(R.string.cd_send),
        tint = sendTint,
      )
    }
  }
}

@Composable
private fun MessageActions(
  message: Message,
  mine: Boolean,
  canEdit: Boolean,
  canCopy: Boolean,
  onReact: (String) -> Unit,
  onReply: () -> Unit,
  onCopy: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  canForward: Boolean = false,
  onForward: () -> Unit = {},
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
    if (canForward) ActionRow(Icons.AutoMirrored.Outlined.Forward, stringResource(R.string.chat_forward), onForward)
    if (canCopy) ActionRow(Icons.Outlined.ContentCopy, stringResource(R.string.chat_copy), onCopy)
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

/** A person's photo, name and about, opened from their avatar or name in a chat. */
@Composable
private fun PersonSheet(uid: String, name: String, about: String) {
  val colors = SamvadTheme.colors
  Column(
    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Avatar(ChatList.initials(name), uid, size = 96.dp)
    Spacer(Modifier.size(12.dp))
    Text(name, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, textAlign = TextAlign.Center)
    if (about.isNotBlank()) {
      Spacer(Modifier.size(6.dp))
      Text(about, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
  }
}
