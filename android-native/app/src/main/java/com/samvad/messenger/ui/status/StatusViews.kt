package com.samvad.messenger.ui.status

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.design.ChatThemes
import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusGroup
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import com.samvad.core.status.TapTarget
import com.samvad.core.status.ViewerNav
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.chat.ReportDialog
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.LocalPhotoSource
import com.samvad.messenger.ui.components.StoryAvatar
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

private const val STATUS_MS = 5_000

/** Text-status colours come from the chat themes, so no new colours are introduced. */
private fun styleColors(style: String?): Pair<Color, Color> {
  val t = ChatThemes.byId(StatusRules.style(style)).light
  return Color(t.background) to Color(t.text)
}

// ---- Status tab ------------------------------------------------------------------------------

/** One person (or me) in the Status list: ring, name and a line underneath. */
@Composable
private fun StatusRow(name: String, seed: String, line: String, unread: Boolean, onClick: () -> Unit, initials: String = ChatList.initials(name), trailing: @Composable () -> Unit = {}) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).clickable(role = Role.Button, onClickLabel = name, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StoryAvatar(initials, seed, unread = unread, size = 56.dp)
    Column(Modifier.weight(1f).padding(start = 14.dp)) {
      Text(name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1)
      Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
    trailing()
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = SamvadTheme.colors.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
  )
}

/**
 * The Status tab: my status, then recent updates (something unseen), viewed updates, and muted people.
 * Two buttons start a new post: a pencil for text and a camera-style button for a photo.
 */
@Composable
fun StatusTab(
  container: AppContainer,
  me: String,
  topBar: @Composable () -> Unit,
  onOpen: (String) -> Unit,
  onNewText: () -> Unit,
  onNewPhoto: () -> Unit,
) {
  val vm: StatusViewModel = viewModel(factory = StatusViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val colors = SamvadTheme.colors
  val recent = state.groups.filter { !it.allSeen }
  val viewed = state.groups.filter { it.allSeen }
  fun nameOf(uid: String) = state.names[uid] ?: ChatList.DELETED_ACCOUNT
  fun lineOf(g: StatusGroup) = StatusRules.ago(g.latestAt, state.now)

  Box(Modifier.fillMaxSize()) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 190.dp)) {
      item(key = "top") { topBar() }
      item(key = "mine") {
        val mine = state.mine
        StatusRow(
          name = stringResource(R.string.status_my),
          seed = me,
          line = if (mine.isEmpty()) stringResource(R.string.status_tap_to_add) else stringResource(R.string.status_updates_count, mine.size, StatusRules.ago(mine.maxOf { it.createdAt }, state.now)),
          unread = mine.isNotEmpty(),
          onClick = if (mine.isEmpty()) onNewText else ({ onOpen(me) }),
          initials = ChatList.initials(state.names[me] ?: "Me"),
        )
      }
      if (recent.isNotEmpty()) {
        item(key = "recent-label") { SectionLabel(stringResource(R.string.status_recent)) }
        items(recent, key = { "r-" + it.authorId }) { g -> StatusRow(nameOf(g.authorId), g.authorId, lineOf(g), unread = true, onClick = { onOpen(g.authorId) }) }
      }
      if (viewed.isNotEmpty()) {
        item(key = "viewed-label") { SectionLabel(stringResource(R.string.status_viewed)) }
        items(viewed, key = { "v-" + it.authorId }) { g -> StatusRow(nameOf(g.authorId), g.authorId, lineOf(g), unread = false, onClick = { onOpen(g.authorId) }) }
      }
      if (state.mutedGroups.isNotEmpty()) {
        item(key = "muted-label") { SectionLabel(stringResource(R.string.status_muted)) }
        items(state.mutedGroups, key = { "m-" + it.authorId }) { g ->
          StatusRow(nameOf(g.authorId), g.authorId, lineOf(g), unread = false, onClick = { onOpen(g.authorId) }) {
            TextButton(onClick = { vm.mute(g.authorId, false) }) { Text(stringResource(R.string.status_unmute)) }
          }
        }
      }
      if (recent.isEmpty() && viewed.isEmpty() && state.mutedGroups.isEmpty() && !state.loading) {
        item(key = "empty") {
          Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.status_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
            Text(stringResource(R.string.status_empty_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
          }
        }
      }
    }
    Column(
      Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 104.dp),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      androidx.compose.material3.SmallFloatingActionButton(onClick = onNewText, containerColor = colors.primaryContainer, contentColor = colors.primary) {
        Icon(Icons.Outlined.TextFields, stringResource(R.string.status_new_text))
      }
      androidx.compose.material3.FloatingActionButton(onClick = onNewPhoto, containerColor = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(20.dp)) {
        Icon(Icons.Outlined.Image, stringResource(R.string.status_new_photo))
      }
    }
  }
}

// ---- Viewer ----------------------------------------------------------------------------------

@Composable
fun StatusViewerScreen(container: AppContainer, me: String, authorId: String, onBack: () -> Unit) {
  val vm: StatusViewModel = viewModel(factory = StatusViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val mineView = authorId == me
  val group: StatusGroup? = if (mineView) null else state.groups.firstOrNull { it.authorId == authorId }
  val items: List<StatusItem> = if (mineView) state.mine else group?.items.orEmpty()
  // Where to start is decided once, from what I had seen when I opened it.
  var index by remember(authorId) { mutableIntStateOf(-1) }
  if (items.isEmpty() && !state.loading) {
    LaunchedEffect(Unit) { onBack() }
    return
  }
  if (items.isEmpty()) return
  if (index < 0) index = if (mineView) 0 else StatusRules.startIndex(group!!, state.seen)
  val i = index.coerceIn(0, items.lastIndex)
  val item = items[i]
  val colors = SamvadTheme.colors

  // Several things can pause a moment at once (finger held, a menu or dialog, typing, app in the background),
  // so each has its own flag and letting go of one never restarts the clock while another still holds it.
  var menuPaused by remember { mutableStateOf(false) }
  var held by remember { mutableStateOf(false) }
  var inBackground by remember { mutableStateOf(false) }
  var reportOpen by remember { mutableStateOf(false) }
  val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
  val reporting = vm.reporting.collectAsStateWithLifecycle().value
  val reportResult = vm.reportResult.collectAsStateWithLifecycle().value
  val blockedUids = vm.blockedUids.collectAsStateWithLifecycle().value
  var typing by remember { mutableStateOf(false) }
  val paused = menuPaused || held || inBackground || reportOpen
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { inBackground = true }
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_START) { inBackground = false }
  val progress = remember(item.id) { Animatable(0f) }
  val needsFile = item.kind == "video" || item.kind == "voice"
  var mediaFile by remember(item.id) { mutableStateOf<java.io.File?>(null) }
  LaunchedEffect(item.id) { if (needsFile) item.mediaPath?.let { mediaFile = vm.localFile(it) } }
  val ready = !needsFile || mediaFile != null
  val durationMs = StatusRules.playbackMs(item)
  LaunchedEffect(item.id) { vm.seen(item) }
  LaunchedEffect(item.id, paused, typing, ready) {
    if (paused || typing || !ready) return@LaunchedEffect
    progress.animateTo(1f, tween(((1f - progress.value) * durationMs).toInt().coerceAtLeast(1), easing = LinearEasing))
    if (i < items.lastIndex) index = i + 1 else onBack()
  }

  fun go(target: TapTarget) {
    val next = ViewerNav.move(i, items.lastIndex, target)
    if (next != null) index = next else onBack()
  }
  val positionLabel = stringResource(R.string.moment_position, i + 1, items.size)
  val nextLabel = stringResource(R.string.moment_next)
  val previousLabel = stringResource(R.string.moment_previous)

  val (bg, fg) = styleColors(item.style)
  val dark =item.kind == "image" || item.kind == "video"
  var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(item.id) { if (item.kind == "image") item.mediaPath?.let { bitmap = vm.photo(it) } }

  var viewersOpen by remember { mutableStateOf(false) }
  var audienceEdit by remember { mutableStateOf(false) }
  var audienceDraft by remember { mutableStateOf(StatusAudience.ALL to emptySet<String>()) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val people = vm.people.collectAsStateWithLifecycle().value
  val audienceSaved = stringResource(R.string.moment_audience_saved)
  val audienceFailed = stringResource(R.string.moment_audience_failed)
  var menu by remember { mutableStateOf(false) }
  val name = if (mineView) stringResource(R.string.status_my) else state.names[authorId] ?: ChatList.DELETED_ACCOUNT

  Box(
    Modifier
      .fillMaxSize()
      .background(if (dark) Color.Black else bg)
      .semantics {
        customActions = listOf(
          CustomAccessibilityAction(nextLabel) { go(TapTarget.NEXT); true },
          CustomAccessibilityAction(previousLabel) { go(TapTarget.PREVIOUS); true },
        )
      }
      .pointerInput(item.id, items.size) {
        // Holding a finger down (a press or a long press) pauses the moment and the progress bar; letting go resumes.
        // A tap on the left half goes back, a tap on the right half goes on. A long press is never also a tap.
        detectTapGestures(
          onTap = { offset -> go(ViewerNav.tapTarget(offset.x, size.width.toFloat())) },
          onPress = { held = true; try { tryAwaitRelease() } finally { held = false } },
        )
      },
  ) {
    when {
      item.kind == "image" -> bitmap?.let {
        Image(it.asImageBitmap(), stringResource(R.string.status_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
      }
      item.kind == "video" -> mediaFile?.let { VideoMoment(it, playing = !paused && !typing) }
      item.kind == "voice" -> mediaFile?.let { file ->
        VoiceMoment(file, item, playing = !paused && !typing, progress = progress.value, color = fg)
      }
      else -> Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(item.text, style = MaterialTheme.typography.headlineMedium.copy(color = fg, textAlign = TextAlign.Center))
        StatusRules.links(item.text).firstOrNull()?.let { link ->
          val context = LocalContext.current
          TextButton(onClick = {
            container.appLock.expectExternal()
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
          }) { Text(stringResource(R.string.moment_open_link), color = fg) }
        }
      }
    }
    if (dark && item.text.isNotBlank()) {
      Text(
        item.text,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = 96.dp),
      )
    }

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = positionLabel }) {
        items.forEachIndexed { n, _ ->
          Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxSize(if (n < i) 1f else if (n == i) progress.value else 0f).background(Color.White))
          }
        }
      }
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(ChatList.initials(if (mineView) (state.names[me] ?: "Me") else name), authorId, size = 36.dp, photoPath = LocalPhotoSource.current?.pathFor(authorId))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
          Text(name, style = MaterialTheme.typography.titleSmall, color = if (dark) Color.White else fg, maxLines = 1)
          Text(StatusRules.ago(item.createdAt, state.now), style = MaterialTheme.typography.labelSmall, color = (if (dark) Color.White else fg).copy(alpha = 0.8f))
        }
        Box {
          IconButton(onClick = { menu = true; menuPaused = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = if (dark) Color.White else fg) }
          DropdownMenu(menu, onDismissRequest = { menu = false; menuPaused = false }) {
            if (mineView) {
              DropdownMenuItem(text = { Text(stringResource(R.string.moment_change_audience)) }, onClick = { menu = false; audienceDraft = item.audience to item.audienceList.toSet(); audienceEdit = true })
              DropdownMenuItem(text = { Text(stringResource(R.string.status_delete)) }, onClick = { menu = false; menuPaused = false; vm.delete(item) })
            } else {
              DropdownMenuItem(text = { Text(stringResource(R.string.status_mute)) }, onClick = { menu = false; menuPaused = false; vm.mute(authorId, true); onBack() })
              DropdownMenuItem(text = { Text(stringResource(R.string.moment_report)) }, onClick = { menu = false; menuPaused = false; reportOpen = true })
            }
          }
        }
        IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_back), tint = if (dark) Color.White else fg) }
      }
    }

    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
      if (mineView) {
        Row(
          Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable { viewersOpen = true; menuPaused = true }.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
          val viewers = vm.viewers(item.id).collectAsStateWithLifecycle(emptyList()).value
          Text(stringResource(R.string.status_viewers, viewers.size), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
          listOf("❤️", "😂", "😮", "😢", "👍", "🙏").forEach { e ->
            Text(e, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.status_react)) { vm.react(item, e) }.padding(8.dp))
          }
        }
        MomentReplyBar(vm, item, onTyping = { typing = it }, onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } })
      }
    }
    androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
  }

  if (reportOpen && !mineView) {
    ReportDialog(
      name = name,
      sending = reporting,
      alreadyBlocked = authorId in blockedUids,
      onSend = { reason, alsoBlock -> vm.reportMoment(authorId, reason, alsoBlock) },
      onDismiss = { reportOpen = false },
      body = stringResource(R.string.moment_report_body),
    )
  }
  val reportText = reportResult?.let {
    stringResource(
      when (it) {
        MomentReportResult.SENT -> R.string.report_sent
        MomentReportResult.SENT_AND_BLOCKED -> R.string.report_sent_blocked
        MomentReportResult.LIMIT -> R.string.report_limit
        MomentReportResult.NO_CHAT -> R.string.moment_report_no_chat
        MomentReportResult.FAILED -> R.string.safety_failed
      },
    )
  }
  LaunchedEffect(reportText) {
    if (reportText != null) {
      // Any outcome closes the dialog; a retry from the menu reuses the same request ID.
      reportOpen = false
      vm.clearReportResult()
      snackbar.showSnackbar(reportText)
    }
  }

  if (audienceEdit && mineView) {
    val (draftAudience, draftChosen) = audienceDraft
    // Closing the dialog saves, unless "only"/"except" has nobody picked (the server needs at least one person).
    val canSave = draftAudience == StatusAudience.ALL || draftChosen.isNotEmpty()
    AudienceDialog(
      audience = draftAudience, onAudience = { audienceDraft = it to draftChosen },
      chosen = draftChosen, onChosen = { audienceDraft = draftAudience to it },
      people = people,
      onDone = {
        audienceEdit = false
        menuPaused = false
        val unchanged = draftAudience == item.audience && (draftAudience == StatusAudience.ALL || draftChosen == item.audienceList.toSet())
        if (canSave && !unchanged) {
          vm.setAudience(item, draftAudience, if (draftAudience == StatusAudience.ALL) emptyList() else draftChosen.toList()) { ok ->
            scope.launch { snackbar.showSnackbar(if (ok) audienceSaved else audienceFailed) }
          }
        }
      },
    )
  }

  if (viewersOpen) {
    val viewers = vm.viewers(item.id).collectAsStateWithLifecycle(emptyList()).value
    AlertDialog(
      onDismissRequest = { viewersOpen = false; menuPaused = false },
      confirmButton = { TextButton(onClick = { viewersOpen = false; menuPaused = false }) { Text(stringResource(R.string.status_done)) } },
      title = { Text(stringResource(R.string.status_viewers, viewers.size)) },
      text = {
        if (viewers.isEmpty()) Text(stringResource(R.string.status_no_viewers))
        else Column(Modifier.verticalScroll(rememberScrollState())) {
          viewers.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(state.names[v.uid] ?: ChatList.DELETED_ACCOUNT, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              v.reaction?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            }
          }
          Text(stringResource(R.string.status_viewers_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
      },
    )
  }
}


// ---- Video and voice players -----------------------------------------------------------------

/** Plays a downloaded video inside the viewer. It stops while the viewer is paused (finger held, typing, menu). */
@Composable
private fun VideoMoment(file: java.io.File, playing: Boolean) {
  androidx.compose.ui.viewinterop.AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      android.widget.VideoView(ctx).apply {
        setVideoPath(file.path)
        setOnPreparedListener { mp -> mp.isLooping = false; if (playing) start() }
      }
    },
    update = { view -> if (playing) { if (!view.isPlaying) view.start() } else if (view.isPlaying) view.pause() },
    onRelease = { view -> view.stopPlayback() },
  )
}

/** Plays a voice moment with its waveform. */
@Composable
private fun VoiceMoment(file: java.io.File, item: StatusItem, playing: Boolean, progress: Float, color: Color) {
  var player by remember(file.path) { mutableStateOf<android.media.MediaPlayer?>(null) }
  androidx.compose.runtime.DisposableEffect(file.path) {
    val p = android.media.MediaPlayer()
    val ok = runCatching { p.setDataSource(file.path); p.prepare() }.isSuccess
    if (ok) player = p else p.release()
    onDispose { player = null; runCatching { p.release() } }
  }
  LaunchedEffect(player, playing) {
    val p = player ?: return@LaunchedEffect
    runCatching { if (playing) p.start() else if (p.isPlaying) p.pause() }
  }
  Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    com.samvad.messenger.ui.status.Waveform(item.waveform, color, progress = progress)
    Text(
      stringResource(R.string.moment_voice),
      style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(top = 16.dp),
    )
  }
}
