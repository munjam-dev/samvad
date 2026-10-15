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
import androidx.compose.ui.semantics.Role
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
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.LocalPhotoSource
import com.samvad.messenger.ui.components.StoryAvatar
import com.samvad.messenger.ui.theme.SamvadTheme

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

  var paused by remember { mutableStateOf(false) }
  var typing by remember { mutableStateOf(false) }
  val progress = remember(item.id) { Animatable(0f) }
  LaunchedEffect(item.id) { vm.seen(item) }
  LaunchedEffect(item.id, paused, typing) {
    if (paused || typing) return@LaunchedEffect
    progress.animateTo(1f, tween(((1f - progress.value) * STATUS_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
    if (i < items.lastIndex) index = i + 1 else onBack()
  }

  val (bg, fg) = styleColors(item.style)
  var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(item.id) { if (item.kind == "image") item.mediaPath?.let { bitmap = vm.photo(it) } }

  var viewersOpen by remember { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  val name = if (mineView) stringResource(R.string.status_my) else state.names[authorId] ?: ChatList.DELETED_ACCOUNT

  Box(
    Modifier
      .fillMaxSize()
      .background(if (item.kind == "image") Color.Black else bg)
      .pointerInput(item.id, items.size) {
        detectTapGestures(
          onTap = { offset ->
            if (offset.x < size.width / 3f) { if (i > 0) index = i - 1 else onBack() }
            else if (i < items.lastIndex) index = i + 1 else onBack()
          },
          onPress = { paused = true; tryAwaitRelease(); paused = false },
        )
      },
  ) {
    when {
      item.kind == "image" -> bitmap?.let {
        Image(it.asImageBitmap(), stringResource(R.string.status_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
      }
      else -> Text(
        item.text,
        style = MaterialTheme.typography.headlineMedium.copy(color = fg, textAlign = TextAlign.Center),
        modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp).verticalScroll(rememberScrollState()),
      )
    }
    if (item.kind == "image" && item.text.isNotBlank()) {
      Text(
        item.text,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 20.dp, vertical = 12.dp).padding(bottom = 96.dp),
      )
    }

    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Moment ${i + 1} of ${items.size}" }) {
        items.forEachIndexed { n, _ ->
          Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxSize(if (n < i) 1f else if (n == i) progress.value else 0f).background(Color.White))
          }
        }
      }
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(ChatList.initials(if (mineView) (state.names[me] ?: "Me") else name), authorId, size = 36.dp, photoPath = LocalPhotoSource.current?.pathFor(authorId))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
          Text(name, style = MaterialTheme.typography.titleSmall, color = if (item.kind == "image") Color.White else fg, maxLines = 1)
          Text(StatusRules.ago(item.createdAt, state.now), style = MaterialTheme.typography.labelSmall, color = (if (item.kind == "image") Color.White else fg).copy(alpha = 0.8f))
        }
        Box {
          IconButton(onClick = { menu = true; paused = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.cd_more), tint = if (item.kind == "image") Color.White else fg) }
          DropdownMenu(menu, onDismissRequest = { menu = false; paused = false }) {
            if (mineView) {
              DropdownMenuItem(text = { Text(stringResource(R.string.status_delete)) }, onClick = { menu = false; paused = false; vm.delete(item) })
            } else {
              DropdownMenuItem(text = { Text(stringResource(R.string.status_mute)) }, onClick = { menu = false; paused = false; vm.mute(authorId, true); onBack() })
            }
          }
        }
        IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_back), tint = if (item.kind == "image") Color.White else fg) }
      }
    }

    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
      if (mineView) {
        Row(
          Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)).clickable { viewersOpen = true; paused = true }.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Outlined.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
          val viewers = vm.viewers(item.id).collectAsStateWithLifecycle(emptyList()).value
          Text(stringResource(R.string.status_viewers, viewers.size), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
      } else {
        var reply by remember(item.id) { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
          listOf("❤️", "😂", "😮", "😢", "👍", "🙏").forEach { e ->
            Text(e, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.status_react)) { vm.react(item, e) }.padding(8.dp))
          }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          BasicTextField(
            value = reply,
            onValueChange = { reply = it },
            textStyle = TextStyle(color = Color.White, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp).padding(vertical = 12.dp).pointerInput(Unit) { detectTapGestures(onPress = { typing = true }) },
            decorationBox = { inner -> Box { if (reply.isEmpty()) Text(stringResource(R.string.status_reply_hint), color = Color.White.copy(alpha = 0.7f)); inner() } },
          )
          TextButton(onClick = { vm.reply(item, reply) { reply = ""; typing = false } }, enabled = reply.isNotBlank()) { Text(stringResource(R.string.status_send), color = Color.White) }
        }
      }
    }
  }

  if (viewersOpen) {
    val viewers = vm.viewers(item.id).collectAsStateWithLifecycle(emptyList()).value
    AlertDialog(
      onDismissRequest = { viewersOpen = false; paused = false },
      confirmButton = { TextButton(onClick = { viewersOpen = false; paused = false }) { Text(stringResource(R.string.status_done)) } },
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

// ---- Composer --------------------------------------------------------------------------------

@Composable
fun StatusComposerScreen(container: AppContainer, me: String, startWithPhoto: Boolean = false, onBack: () -> Unit) {
  val vm: StatusViewModel = viewModel(factory = StatusViewModel.factory(container, me))
  val colors = SamvadTheme.colors
  val post = vm.post.collectAsStateWithLifecycle().value
  val people = vm.people.collectAsStateWithLifecycle().value
  val context = LocalContext.current

  var photoMode by remember { mutableStateOf(startWithPhoto) }
  var text by remember { mutableStateOf("") }
  var caption by remember { mutableStateOf("") }
  var style by remember { mutableStateOf(StatusRules.DEFAULT_STYLE) }
  var photo by remember { mutableStateOf<android.net.Uri?>(null) }
  var preview by remember { mutableStateOf<Bitmap?>(null) }
  var audience by remember { mutableStateOf(StatusAudience.ALL) }
  var chosen by remember { mutableStateOf(setOf<String>()) }
  var audienceOpen by remember { mutableStateOf(false) }

  val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
    if (uri != null) {
      photo = uri
      preview = com.samvad.messenger.data.ImageCodec.decodeUpright(context.contentResolver, uri, 1024)
    }
  }

  LaunchedEffect(post) { if (post == PostState.Done) { vm.resetPost(); onBack() } }

  val (bg, fg) = styleColors(style)
  val audienceOk = audience == StatusAudience.ALL || chosen.isNotEmpty()
  val canPost = post != PostState.Posting && audienceOk && (if (photoMode) photo != null else StatusRules.validText(text))

  Column(Modifier.fillMaxSize().background(if (photoMode) colors.background else bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_back), tint = if (photoMode) colors.onSurface else fg) }
      Spacer(Modifier.weight(1f))
      FilterChip(selected = !photoMode, onClick = { photoMode = false }, label = { Text(stringResource(R.string.status_text)) }, leadingIcon = { Icon(Icons.Outlined.TextFields, null, Modifier.size(18.dp)) })
      Spacer(Modifier.width(8.dp))
      FilterChip(selected = photoMode, onClick = { photoMode = true }, label = { Text(stringResource(R.string.status_photo)) }, leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)) })
      Spacer(Modifier.weight(1f))
    }

    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
      if (!photoMode) {
        BasicTextField(
          value = text,
          onValueChange = { text = it.take(StatusRules.MAX_TEXT) },
          textStyle = MaterialTheme.typography.headlineMedium.copy(color = fg, textAlign = TextAlign.Center),
          cursorBrush = SolidColor(fg),
          modifier = Modifier.fillMaxWidth(),
          decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { if (text.isEmpty()) Text(stringResource(R.string.status_hint), style = MaterialTheme.typography.headlineMedium, color = fg.copy(alpha = 0.55f), textAlign = TextAlign.Center); inner() } },
        )
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
          val shown = preview
          if (shown != null) {
            Image(shown.asImageBitmap(), stringResource(R.string.status_photo), contentScale = ContentScale.Fit, modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(16.dp)))
            BasicTextField(
              value = caption,
              onValueChange = { caption = it.take(StatusRules.MAX_TEXT) },
              textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
              cursorBrush = SolidColor(colors.onSurface),
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(14.dp),
              decorationBox = { inner -> Box { if (caption.isEmpty()) Text(stringResource(R.string.status_caption_hint), color = colors.onSurfaceVariant); inner() } },
            )
          }
          TextButton(onClick = { picker.launch("image/*") }) { Text(stringResource(if (shown == null) R.string.status_pick_photo else R.string.status_change_photo)) }
        }
      }
    }

    if (!photoMode) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusRules.STYLES.forEach { s ->
          val (sb, _) = styleColors(s)
          Box(
            Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(sb)
              .border(if (s == style) 3.dp else 1.dp, if (s == style) fg else colors.outlineVariant, CircleShape)
              .clickable(role = Role.RadioButton, onClickLabel = s) { style = s },
          )
        }
      }
    }

    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      val onColor = if (photoMode) colors.onSurface else fg
      TextButton(onClick = { audienceOpen = true }) {
        Text(
          when (audience) {
            StatusAudience.ALL -> stringResource(R.string.status_audience_all)
            StatusAudience.ONLY -> stringResource(R.string.status_audience_only, chosen.size)
            StatusAudience.EXCEPT -> stringResource(R.string.status_audience_except, chosen.size)
          },
          color = onColor,
        )
      }
      Spacer(Modifier.weight(1f))
      androidx.compose.material3.Button(
        enabled = canPost,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
          containerColor = colors.primary,
          contentColor = colors.onPrimary,
          disabledContainerColor = onColor.copy(alpha = 0.18f),
          disabledContentColor = onColor.copy(alpha = 0.6f),
        ),
        onClick = {
          val list = if (audience == StatusAudience.ALL) emptyList() else chosen.toList()
          if (photoMode) vm.postPhoto(photo!!, caption, audience, list) else vm.postText(text, style, audience, list)
        },
      ) { Text(stringResource(if (post == PostState.Posting) R.string.status_posting else R.string.status_post)) }
    }
    if (post == PostState.Failed) {
      Text(stringResource(R.string.status_failed), color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
    }
  }

  if (audienceOpen) {
    AlertDialog(
      onDismissRequest = { audienceOpen = false },
      confirmButton = { TextButton(onClick = { audienceOpen = false }) { Text(stringResource(R.string.status_done)) } },
      title = { Text(stringResource(R.string.status_audience_title)) },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          listOf(
            StatusAudience.ALL to R.string.status_audience_all,
            StatusAudience.EXCEPT to R.string.status_audience_except_label,
            StatusAudience.ONLY to R.string.status_audience_only_label,
          ).forEach { (a, label) ->
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(role = Role.RadioButton) { audience = a }, verticalAlignment = Alignment.CenterVertically) {
              RadioButton(selected = audience == a, onClick = null)
              Text(stringResource(label), modifier = Modifier.padding(start = 12.dp))
            }
          }
          if (audience != StatusAudience.ALL) {
            Text(stringResource(R.string.status_pick_people), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            if (people.isEmpty()) Text(stringResource(R.string.status_no_people), style = MaterialTheme.typography.bodySmall)
            people.forEach { p ->
              Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { chosen = if (p.uid in chosen) chosen - p.uid else chosen + p.uid }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = p.uid in chosen, onCheckedChange = null)
                Text(p.name, modifier = Modifier.padding(start = 12.dp))
              }
            }
          }
          Text(stringResource(R.string.status_audience_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
      },
    )
  }
}
