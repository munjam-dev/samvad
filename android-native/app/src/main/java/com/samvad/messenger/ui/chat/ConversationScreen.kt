package com.samvad.messenger.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.zIndex
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import com.samvad.core.chat.Selection
import com.samvad.core.chat.SelectionActions
import com.samvad.core.chat.StarAction
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
  onOpenStarred: () -> Unit = {},
  /** Groups: opens the Events list; tapping an event card goes here too. */
  onOpenEvents: (() -> Unit)? = null,
  onOpenMedia: () -> Unit = {},
) {
  val appCtx = LocalContext.current.applicationContext as android.app.Application
  val vm: ConversationViewModel = viewModel(factory = ConversationViewModel.factory(appCtx, container, me, conversationId, otherUid))
  val state = vm.state.collectAsStateWithLifecycle().value
  val composer = vm.composer.collectAsStateWithLifecycle().value
  val presence = vm.status.collectAsStateWithLifecycle().value
  val groupStatus = vm.groupStatus.collectAsStateWithLifecycle().value
  val mentionCandidates = vm.mentionCandidates.collectAsStateWithLifecycle().value
  val starredIds = vm.starredIds.collectAsStateWithLifecycle().value
  val pinnedBanner = vm.pinnedBanner.collectAsStateWithLifecycle().value
  val pinnedIds = vm.pinnedIds.collectAsStateWithLifecycle().value
  val pinNotice = vm.pinNotice.collectAsStateWithLifecycle().value
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
  var scheduling by remember { mutableStateOf(false) }
  var scheduledList by remember { mutableStateOf(false) }
  val scheduledHere = vm.scheduledHere.collectAsStateWithLifecycle().value
  var menu by remember { mutableStateOf(false) }
  val appPrefs = container.preferences.preferences.collectAsStateWithLifecycle(com.samvad.messenger.data.AppPreferences()).value
  val autoLoadPhotos = com.samvad.core.media.DownloadPolicy.shouldAutoDownload(appPrefs.autoDownload, appPrefs.dataSaver, vm.isMetered(), cached = false)
  var searching by rememberSaveable { mutableStateOf(false) }
  var timerSheet by rememberSaveable { mutableStateOf(false) }
  val timer = vm.timer.collectAsStateWithLifecycle().value
  val timerChange = vm.timerChange.collectAsStateWithLifecycle().value
  var query by rememberSaveable { mutableStateOf("") }
  var matchIdx by rememberSaveable { mutableStateOf(0) }
  val matches = remember(state.items, query) { com.samvad.core.chat.ChatSearch.matches(state.items, query) }
  val currentMatch = if (searching) matches.getOrNull(matchIdx.coerceIn(0, (matches.size - 1).coerceAtLeast(0))) else null
  LaunchedEffect(currentMatch) {
    val at = currentMatch?.let { key -> state.items.indexOfFirst { it.key == key } } ?: -1
    if (at >= 0) list.animateScrollToItem(at)
  }
  // Jumping to a pinned message: scroll to it, loading older messages first when it is not on the screen yet.
  var jumpTo by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(jumpTo, state.items, state.hasMore) {
    val target = jumpTo ?: return@LaunchedEffect
    val at = state.items.indexOfFirst { it.key == target }
    when {
      at >= 0 -> { list.animateScrollToItem(at); jumpTo = null }
      state.hasMore -> vm.loadMore()
      else -> jumpTo = null
    }
  }
  val pinNoticeText = pinNotice?.let {
    stringResource(
      when (it) {
        PinNotice.PINNED -> R.string.pinmsg_pinned
        PinNotice.UNPINNED -> R.string.pinmsg_unpinned
        PinNotice.LIMIT -> R.string.pinmsg_limit
        PinNotice.NOT_ALLOWED -> R.string.pinmsg_not_allowed
        PinNotice.DAILY_LIMIT -> R.string.pinmsg_daily
        PinNotice.FAILED -> R.string.pinmsg_failed
      },
    )
  }
  LaunchedEffect(pinNoticeText) {
    if (pinNoticeText != null) {
      snackbar.showSnackbar(pinNoticeText)
      vm.clearPinNotice()
    }
  }
  val copied = stringResource(R.string.chat_copied)
  val uploads = vm.uploads.collectAsStateWithLifecycle().value
  // Phase 5: photo preview with "Original quality", downloads with progress, network hints.
  var previewUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
  val downloadMap = vm.downloads.collectAsStateWithLifecycle()
  val deviceOffline = rememberDeviceOffline(vm.network)
  val photoReduced = vm.photoReduced.collectAsStateWithLifecycle().value
  val reducedText = photoReduced?.let {
    stringResource(if (it == com.samvad.core.media.ImageQuality.Outcome.LOWER_QUALITY) R.string.media2_photo_reduced_quality else R.string.media2_photo_reduced_size)
  }
  LaunchedEffect(reducedText) {
    if (reducedText != null) {
      vm.clearPhotoReduced()
      snackbar.showSnackbar(reducedText)
    }
  }
  val recording = vm.recorder.state.collectAsStateWithLifecycle().value
  val playback = vm.player.state.collectAsStateWithLifecycle().value
  val mediaError = vm.mediaError.collectAsStateWithLifecycle().value
  val blocked = vm.blocked.collectAsStateWithLifecycle().value
  val notice = vm.notice.collectAsStateWithLifecycle().value
  val reportSending = vm.reporting.collectAsStateWithLifecycle().value
  var confirmBlock by remember { mutableStateOf(false) }
  var reportOpen by remember { mutableStateOf(false) }
  // Scam Shield (ScamViews.kt): link taps, the link confirmation and the new-contact banner.
  val scam = rememberScamLinks(container)
  val firstContact = rememberFirstContact(container, conversationId, otherUid, state, blocked)
  ScamLinkDialog(scam)
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
  var forwarding by remember { mutableStateOf<List<Message>?>(null) }
  val selection = vm.selection.collectAsStateWithLifecycle().value
  val selecting = selection.isNotEmpty()
  var confirmDeleteSelected by remember { mutableStateOf(false) }
  val bubbleMessages = remember(state.items) { state.items.filterIsInstance<TimelineItem.Bubble>().map { it.message } }
  val selectedMessages = remember(bubbleMessages, selection) { Selection.ordered(bubbleMessages, selection) }
  val selectionActions = remember(selectedMessages, starredIds) { Selection.actions(selectedMessages, me, starredIds) }
  // Messages that disappear or are deleted drop out; when none are left, selection mode ends.
  LaunchedEffect(selection, selectedMessages, state.loading) {
    if (selecting && selectedMessages.isEmpty() && !state.loading) vm.clearSelection()
  }
  LaunchedEffect(selecting) {
    if (selecting && searching) { searching = false; query = ""; matchIdx = 0 }
  }
  androidx.activity.compose.BackHandler(enabled = selecting) { vm.clearSelection() }
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
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) previewUri = uri }
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
        Lifecycle.Event.ON_PAUSE -> {
          ActiveChat.set(null)
          vm.saveDraft()
        }
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
    if (timerSheet) {
      DisappearingSheet(
        current = timer,
        canChange = !state.isGroup || state.isAdmin,
        saving = timerChange == TimerChange.Saving,
        onPick = vm::setTimer,
        onDismiss = { timerSheet = false; vm.dismissTimerError() },
      )
    }
    if (timerChange is TimerChange.Failed) {
      androidx.compose.material3.AlertDialog(
        onDismissRequest = vm::dismissTimerError,
        confirmButton = { androidx.compose.material3.TextButton(onClick = vm::dismissTimerError) { Text(stringResource(R.string.disappearing_ok)) } },
        title = { Text(stringResource(R.string.disappearing_error_title)) },
        text = {
          Text(
            stringResource(
              when (timerChange) {
                TimerChange.Failed.NotAllowed -> R.string.disappearing_error_not_allowed
                TimerChange.Failed.Limit -> R.string.disappearing_error_limit
                else -> R.string.disappearing_error_other
              },
            ),
          )
        },
      )
    }
    if (searching) {
      ChatSearchBar(
        query = query,
        onQuery = { query = it; matchIdx = 0 },
        position = matchIdx.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
        total = matches.size,
        onStep = { fwd -> matchIdx = com.samvad.core.chat.ChatSearch.step(matchIdx, matches.size, fwd) },
        onClose = { searching = false; query = ""; matchIdx = 0 },
        modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
      )
    }
    Column(Modifier.fillMaxSize().imePadding()) {
      // Header
      if (selecting) SelectionBar(
        count = selectedMessages.size,
        actions = selectionActions,
        onClose = vm::clearSelection,
        onCopy = {
          clipboard.setText(AnnotatedString(Selection.copyText(selectedMessages)))
          vm.clearSelection()
          scope.launch { snackbar.showSnackbar(copied) }
        },
        onForward = { forwarding = selectedMessages; vm.clearSelection() },
        onStar = { vm.starMany(selectedMessages, selectionActions.star); vm.clearSelection() },
        onDelete = { confirmDeleteSelected = true },
        onMessageOptions = { selected = selectedMessages.singleOrNull(); vm.clearSelection() },
      ) else Row(
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
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              state.title,
              style = MaterialTheme.typography.titleMedium,
              color = colors.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (com.samvad.core.chat.Disappearing.isOn(timer)) {
              val timerDescription = stringResource(R.string.cd_disappearing_on, optionLabel(timer))
              Icon(Icons.Outlined.Timer, timerDescription, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
          }
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
            if (state.isGroup && onOpenEvents != null) {
              DropdownMenuItem(text = { Text(stringResource(R.string.group_events)) }, onClick = { menu = false; onOpenEvents() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.media_tab_title)) }, onClick = { menu = false; onOpenMedia() })
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
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_search)) }, onClick = { menu = false; searching = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.disappearing_title)) }, onClick = { menu = false; timerSheet = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.starred_title)) }, onClick = { menu = false; onOpenStarred() })
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
      StatusBanner(state.offline && !deviceOffline, stringResource(R.string.home_offline))
      ChatNetworkBanners(
        deviceOffline = deviceOffline,
        network = vm.network,
        dataSaverOn = vm.dataSaverOn.collectAsStateWithLifecycle().value,
        hintAnswered = vm.dataSaverHintAnswered.collectAsStateWithLifecycle().value,
        onAnswerHint = vm::answerDataSaverHint,
      )
      StatusBanner(state.error, stringResource(R.string.home_load_error), isError = true, actionLabel = stringResource(R.string.retry), onAction = vm::retryLoad)

      if (pinnedBanner != null) PinnedBannerBar(pinnedBanner, onClick = { vm.bannerTapped()?.let { jumpTo = it } })

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
              val bubble = item as? TimelineItem.Bubble
              val canPick = bubble != null && Selection.canSelect(bubble.message)
              val picked = canPick && bubble != null && bubble.message.id in selection
              val toggle: () -> Unit = { if (bubble != null && canPick) vm.toggleSelected(bubble.message.id) }
              Row(
                Modifier
                  .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.offsetSpring)
                  .then(if (item.key == currentMatch) Modifier.background(colors.primary.copy(alpha = 0.14f), RoundedCornerShape(14.dp)) else Modifier)
                  .then(if (picked) Modifier.background(colors.primary.copy(alpha = 0.14f)) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
              ) {
              if (selecting && bubble != null) SelectionMark(picked = picked, selectable = canPick, onClick = toggle)
              Box(Modifier.weight(1f)) {
              when (item) {
                is TimelineItem.DateSeparator -> DateChip(item.label)
                is TimelineItem.SystemLine -> SystemLineChip(item.text)
                is TimelineItem.Bubble -> MessageBubble(
                  item = item,
                  animateIn = initialIds?.contains(item.key) == false,
                  replied = item.message.replyTo?.let { id -> state.items.firstOrNull { it.key == id } as? TimelineItem.Bubble },
                  otherName = item.message.replyTo?.let { id -> (state.items.firstOrNull { it.key == id } as? TimelineItem.Bubble)?.message?.senderId }?.let(vm::displayName) ?: state.title,
                  onLongPress = { if (selecting) toggle() else vm.startSelection(item.message.id) },
                  selecting = selecting,
                  picked = picked,
                  onToggle = toggle,
                  onOpenSender = { person = item.message.senderId },
                  starred = item.message.id in starredIds,
                  onRetry = { vm.retry(item.message) },
                  onReact = { emoji -> if (selecting) toggle() else vm.react(item.message, emoji) },
                  mentionNames = groupStatus.allNames,
                  scam = scam,
                  mediaContent = {
                    val media = item.message.media
                    if (media != null) {
                      when (item.message.kind) {
                        "image" -> MediaImage(
                          media, vm::bitmap,
                          autoLoad = autoLoadPhotos,
                          isCached = vm::isCached,
                          downloadState = { downloadMap.value[it] },
                          onDownload = vm::startDownload,
                          onCancelDownload = vm::cancelDownload,
                        ) { if (selecting) toggle() else viewing = media.path }
                        "voice" -> VoiceNote(item.message.id, media, playback, item.mine) { if (selecting) toggle() else vm.togglePlayback(item.message) }
                        else -> {
                          val fileState = downloadMap.value[media.path]
                          FileCard(
                            media,
                            cached = remember(media.path, fileState) { vm.isCached(media.path) },
                            download = fileState,
                            onDownload = { vm.startDownload(media.path, media.size) },
                            onCancelDownload = { vm.cancelDownload(media.path) },
                          ) { if (selecting) toggle() else openFile(media) }
                        }
                      }
                    }
                    val poll = item.message.poll
                    if (item.message.kind == "poll" && poll != null && !item.message.deleted) {
                      val votes = remember(item.message.id) { vm.votes(item.message.id) }.collectAsStateWithLifecycle(emptyMap()).value
                      val result = remember(votes, poll) { com.samvad.core.chat.Polls.tally(poll, votes, me) }
                      PollCard(item.message, result, item.mine) { id -> if (selecting) toggle() else vm.vote(item.message, id, result.mine) }
                    }
                    if (item.message.kind == "event" && !item.message.deleted) {
                      EventMessageCard(item.message.eventTitle.orEmpty()) { if (selecting) toggle() else onOpenEvents?.invoke() }
                    }
                  },
                )
              }
              }
              }
            }
            if (!state.hasMore) item(key = "privacy-notice") { PrivacyNotice(onLearnMore = { privacyInfo = true }) }
            if (firstContact.show) item(key = "first-contact") {
              FirstContactBanner(state.title, onBlock = { confirmBlock = true }, onReport = { reportOpen = true }, onDismiss = firstContact.dismiss)
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

      uploads.forEach { up ->
        PendingUploadRow(up, onRetry = { vm.retryUpload(up.id) }, onCancel = { vm.cancelUpload(up.id) })
      }
      if (state.removed) {
        RemovedBar()
      } else if (blocked) {
        BlockedBar(state.title, onUnblock = vm::unblock)
      } else if (recording.active) {
        RecorderBar(recording, onCancel = vm::cancelRecording, onSend = vm::finishRecording)
      } else if (!state.canSend) {
        com.samvad.messenger.ui.groups.AnnouncementNotice()
      } else {
        if (mentionCandidates.isNotEmpty()) MentionSuggestions(mentionCandidates, vm::pickMention)
        ScheduledBanner(scheduledHere, onOpen = { scheduledList = true })
        Composer(
          state = composer,
          me = me,
          otherName = composer.replyTo?.let { vm.displayName(it.senderId) } ?: state.title,
          onText = vm::onText,
          onSend = vm::submit,
          onCancel = vm::cancelContext,
          onAttach = { attaching = true },
          onSchedule = { scheduling = true },
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
  previewUri?.let { uri ->
    PhotoSendPreview(
      load = { vm.photoPreview(uri) },
      sourceSize = { vm.photoSourceSize(uri) },
      onSend = { original -> previewUri = null; vm.sendImage(uri, original) },
      onCancel = { previewUri = null },
    )
  }

  if (scheduling) {
    ModalBottomSheet(onDismissRequest = { scheduling = false }) {
      ScheduleSheet(stringResource(R.string.schedule_title), onPick = vm::schedule, onDone = {
        scheduling = false
        scheduledList = false
      })
    }
  }
  if (scheduledList && scheduledHere.isNotEmpty()) {
    ModalBottomSheet(onDismissRequest = { scheduledList = false }) {
      ScheduledListSheet(
        scheduledHere,
        onSendNow = { vm.sendScheduledNow(it) },
        onEdit = { item, text -> vm.editScheduled(item, text) },
        onReschedule = vm::reschedule,
        onCancel = { vm.cancelScheduled(it) },
        onRemove = { vm.removeScheduled(it) },
      )
    }
  }

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

  forwarding?.let { msgs ->
    ModalBottomSheet(onDismissRequest = { forwarding = null }) {
      val targets = remember { vm.forwardTargets() }.collectAsStateWithLifecycle(null).value
      ForwardSheet(targets) { chosen ->
        forwarding = null
        vm.forwardMany(msgs, chosen)
      }
    }
  }

  if (confirmDeleteSelected && selectedMessages.isNotEmpty()) {
    val count = selectedMessages.size
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { confirmDeleteSelected = false },
      title = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.selection_delete_title, count, count)) },
      text = { Text(stringResource(R.string.selection_delete_body)) },
      confirmButton = {
        androidx.compose.material3.TextButton({
          confirmDeleteSelected = false
          vm.deleteMany(selectedMessages)
          vm.clearSelection()
        }) { Text(stringResource(R.string.chat_delete), color = colors.error) }
      },
      dismissButton = { androidx.compose.material3.TextButton({ confirmDeleteSelected = false }) { Text(stringResource(R.string.cancel)) } },
    )
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
        onForward = { forwarding = listOf(msg); selected = null },
        starred = msg.id in starredIds,
        canStar = !msg.deleted && msg.kind != "system",
        onStar = { vm.toggleStar(msg); selected = null },
        canPin = com.samvad.core.chat.PinnedMessages.canChange(state.isGroup, state.isAdmin) && !state.removed &&
          (msg.id in pinnedIds || !blocked) && com.samvad.core.chat.PinnedMessages.isPinnable(msg),
        pinned = msg.id in pinnedIds,
        onPin = { vm.togglePin(msg); selected = null },
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
  starred: Boolean = false,
  /** Selection mode: a tap toggles this message instead of its usual action. */
  selecting: Boolean = false,
  picked: Boolean = false,
  onToggle: () -> Unit = {},
  scam: ScamLinks? = null,
) {
  val colors = SamvadTheme.colors
  val m = item.message
  val selectLabel = stringResource(R.string.selection_select)
  val mine = item.mine
  val assessment = rememberScamAssessment(m)
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
            onClick = { if (selecting) onToggle() else if (m.failed) onRetry() },
            onClickLabel = if (!selecting && m.failed) retryLabel else null,
            role = if (selecting) androidx.compose.ui.semantics.Role.Checkbox else null,
            onLongClickLabel = selectLabel,
            onLongClick = { if (!m.deleted && !m.pending) onLongPress() },
          )
          .then(if (selecting) Modifier.semantics { this.selected = picked } else Modifier)
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
            if (m.deleted) AnnotatedString(stringResource(R.string.chat_deleted)) else linkedText(mentionText(m.text, mentionNames), assessment, scam),
            style = MaterialTheme.typography.bodyLarge,
            color = if (m.deleted) colors.onSurfaceVariant else colors.onBubble,
          )
        }
        Row(
          Modifier.align(Alignment.End).padding(top = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (starred && !m.deleted) Icon(Icons.Filled.Star, stringResource(R.string.cd_starred), tint = meta, modifier = Modifier.size(13.dp))
          if (m.editedAt != null && !m.deleted) Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = meta)
          Text(item.timeLabel, style = MaterialTheme.typography.labelSmall, color = meta)
          item.delivery?.let { DeliveryIcon(it) }
        }
      }
    }
    if (!mine) ScamNotice(assessment, Modifier.padding(start = indent, top = 4.dp))
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

/**
 * Selection mode: top bar with the count and the actions that are allowed for the selection.
 * Copy and forward sit on the bar; star, delete and the single-message menu sit in the overflow
 * menu so the bar still fits at 360 dp and 200% font scale.
 */
@Composable
private fun SelectionBar(
  count: Int,
  actions: SelectionActions,
  onClose: () -> Unit,
  onCopy: () -> Unit,
  onForward: () -> Unit,
  onStar: () -> Unit,
  onDelete: () -> Unit,
  onMessageOptions: () -> Unit,
) {
  val colors = SamvadTheme.colors
  var menu by remember { mutableStateOf(false) }
  val hasMenu = actions.star != StarAction.NONE || actions.deleteForEveryone || actions.moreOptions
  Row(
    Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_selection_close), tint = colors.onSurface) }
    Text(
      androidx.compose.ui.res.pluralStringResource(R.plurals.selection_count, count, count),
      style = MaterialTheme.typography.titleMedium,
      color = colors.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp)
        .semantics {
          heading()
          liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        },
    )
    if (actions.copy) {
      IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.chat_copy), tint = colors.onSurface) }
    }
    if (actions.forward) {
      IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Outlined.Forward, stringResource(R.string.chat_forward), tint = colors.onSurface) }
    }
    if (hasMenu) {
      Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = colors.onSurface) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
          if (actions.star != StarAction.NONE) {
            val unstar = actions.star == StarAction.UNSTAR
            DropdownMenuItem(
              text = { Text(stringResource(if (unstar) R.string.chat_unstar else R.string.chat_star)) },
              leadingIcon = { Icon(if (unstar) Icons.Outlined.StarOutline else Icons.Outlined.Star, null) },
              onClick = { menu = false; onStar() },
            )
          }
          if (actions.moreOptions) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.selection_message_options)) },
              leadingIcon = { Icon(Icons.Outlined.MoreVert, null) },
              onClick = { menu = false; onMessageOptions() },
            )
          }
          if (actions.deleteForEveryone) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.chat_delete), color = colors.error) },
              leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = colors.error) },
              onClick = { menu = false; onDelete() },
            )
          }
        }
      }
    }
  }
}

/** The check beside a message in selection mode. It repeats what the bubble's own state says, so it is hidden from screen readers. */
@Composable
private fun SelectionMark(picked: Boolean, selectable: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Box(
    Modifier
      .size(width = 40.dp, height = 48.dp)
      .clickable(enabled = selectable, onClick = onClick)
      .clearAndSetSemantics {},
    contentAlignment = Alignment.Center,
  ) {
    if (selectable) {
      Icon(
        if (picked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        null,
        tint = if (picked) colors.primary else colors.onSurfaceVariant,
      )
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
  onSchedule: (() -> Unit)? = null,
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
        SendButton(canSend = state.text.isNotBlank() && !state.tooLong, editing = state.editing != null, onSend = onSend, onSchedule = onSchedule)
      }
    }
  }
}

@Composable
private fun SendButton(canSend: Boolean, editing: Boolean, onSend: () -> Unit, onSchedule: (() -> Unit)? = null) {
  val colors = SamvadTheme.colors
  val sendBg by animateColorAsState(if (canSend) colors.primary else colors.surfaceContainer, motionSpec(Motion.standard()), label = "sendBg")
  val sendTint by animateColorAsState(if (canSend) colors.onPrimary else colors.onSurfaceVariant, motionSpec(Motion.standard()), label = "sendTint")
  val sendScale by animateFloatAsState(if (canSend) 1f else 0.9f, motionSpec(Motion.lively()), label = "sendScale")
  val sendPress = remember { MutableInteractionSource() }
  val scheduleLabel = stringResource(R.string.cd_schedule)
  // Tap sends; touch and hold schedules (not while editing a sent message).
  Box(
    Modifier
      .size(48.dp)
      .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
      .pressScale(sendPress, 0.88f)
      .background(sendBg, CircleShape)
      .clip(CircleShape)
      .combinedClickable(
        interactionSource = sendPress,
        indication = androidx.compose.material3.ripple(bounded = true),
        enabled = canSend,
        role = androidx.compose.ui.semantics.Role.Button,
        onClick = onSend,
        onLongClickLabel = if (onSchedule != null && !editing) scheduleLabel else null,
        onLongClick = if (onSchedule != null && !editing) onSchedule else null,
      ),
    contentAlignment = Alignment.Center,
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
  starred: Boolean = false,
  canStar: Boolean = false,
  onStar: () -> Unit = {},
  canPin: Boolean = false,
  pinned: Boolean = false,
  onPin: () -> Unit = {},
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
    if (canStar) ActionRow(if (starred) Icons.Outlined.StarOutline else Icons.Outlined.Star, stringResource(if (starred) R.string.chat_unstar else R.string.chat_star), onStar)
    if (canPin) ActionRow(Icons.Outlined.PushPin, stringResource(if (pinned) R.string.pinmsg_unpin else R.string.pinmsg_pin), onPin)
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

/** Search within this chat: type, then step through matches. Everything happens on the device. */
@Composable
private fun ChatSearchBar(
  query: String,
  onQuery: (String) -> Unit,
  position: Int,
  total: Int,
  onStep: (Boolean) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = SamvadTheme.colors
  val focus = remember { androidx.compose.ui.focus.FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Row(
    modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_search_close), tint = colors.onSurface) }
    androidx.compose.material3.TextField(
      value = query,
      onValueChange = onQuery,
      singleLine = true,
      placeholder = { Text(stringResource(R.string.chat_search_hint)) },
      keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
      keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onStep(true) }),
      colors = androidx.compose.material3.TextFieldDefaults.colors(
        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
      ),
      modifier = Modifier.weight(1f).focusRequester(focus),
    )
    val tooShort = query.trim().length < com.samvad.core.chat.ChatSearch.MIN_QUERY
    if (!tooShort) {
      Text(
        if (total == 0) stringResource(R.string.chat_search_none) else stringResource(R.string.chat_search_count, position + 1, total),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    IconButton(onClick = { onStep(false) }, enabled = total > 1) {
      Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.cd_search_previous), tint = colors.onSurface)
    }
    IconButton(onClick = { onStep(true) }, enabled = total > 1) {
      Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.cd_search_next), tint = colors.onSurface)
    }
  }
}
