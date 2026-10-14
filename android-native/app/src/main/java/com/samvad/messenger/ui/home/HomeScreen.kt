package com.samvad.messenger.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.enterOnce
import com.samvad.messenger.ui.theme.motionSpec
import com.samvad.messenger.ui.theme.pressScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ChatRow
import com.samvad.core.design.HomeLayout
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.components.StatusBanner
import com.samvad.messenger.ui.components.StoryAvatar
import com.samvad.messenger.ui.components.UnreadBadge
import com.samvad.messenger.ui.components.Wordmark
import com.samvad.messenger.ui.theme.SamvadTheme

private enum class Tab { CHATS, CALLS, COMMUNITIES }

@Composable
fun HomeScreen(
  container: AppContainer,
  me: String,
  /** otherUid is null for a group. */
  onOpenChat: (conversationId: String, otherUid: String?) -> Unit,
  onNewChat: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  var tab by rememberSaveable { mutableIntStateOf(0) }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    AnimatedContent(
      targetState = Tab.entries[tab],
      transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f)) togetherWith fadeOut(tween(120)) },
      label = "tab",
    ) { t ->
      when (t) {
        Tab.CHATS -> ChatsTab(container, state, vm, onOpenChat, onNewChat, onOpenSettings)
        Tab.CALLS -> ComingSoon(Icons.Outlined.Call, R.string.calls_title, R.string.calls_body)
        Tab.COMMUNITIES -> ComingSoon(Icons.Outlined.Groups, R.string.communities_title, R.string.communities_body)
      }
    }
    if (Tab.entries[tab] == Tab.CHATS) {
      val fabIn = remember { Animatable(if (reduced) 1f else 0f) }
      LaunchedEffect(Unit) { fabIn.animateTo(1f, Motion.lively()) }
      val fabPress = remember { MutableInteractionSource() }
      FloatingActionButton(
        onClick = onNewChat,
        interactionSource = fabPress,
        containerColor = colors.primary,
        contentColor = colors.onPrimary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .navigationBarsPadding()
          .padding(end = 20.dp, bottom = 104.dp)
          .graphicsLayer { scaleX = fabIn.value; scaleY = fabIn.value; rotationZ = (1f - fabIn.value) * -90f }
          .pressScale(fabPress, 0.9f),
      ) { Icon(Icons.Outlined.Add, stringResource(R.string.cd_new_chat)) }
    }
    FloatingNavBar(
      selected = tab,
      onSelect = { i -> if (i == 3) onOpenSettings() else tab = i },
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

@Composable
private fun ComingSoon(icon: ImageVector, title: Int, body: Int) {
  Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
    EmptyState(icon, stringResource(title), stringResource(body))
  }
}

@Composable
private fun ChatsTab(
  container: AppContainer,
  state: HomeUiState,
  vm: HomeViewModel,
  onOpenChat: (String, String?) -> Unit,
  onNewChat: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val open: (ChatRow) -> Unit = { row -> onOpenChat(row.conversationId, if (row.isGroup) null else vm.otherOf(row)) }
  val seen = remember { mutableSetOf<Any>() }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 128.dp)) {
    item(key = "header") {
      when (state.layout) {
        HomeLayout.CLEAN -> CleanHeader(state, vm, onOpenSettings)
        HomeLayout.BOLD -> BoldHeader(state, vm, onOpenSettings)
        HomeLayout.BENTO -> BentoHeader(state, vm, onOpenSettings)
      }
    }
    item(key = "notif-prompt") { NotificationPrompt(container) }
    item(key = "banners") {
      StatusBanner(state.offline, stringResource(R.string.home_offline))
      StatusBanner(state.error, stringResource(R.string.home_load_error), isError = true, actionLabel = stringResource(R.string.retry), onAction = vm::retry)
    }
    if (state.recentPeople.isNotEmpty() && !state.searching) {
      item(key = "people") {
        val tinted = state.layout == HomeLayout.BOLD
        Box(
          Modifier
            .padding(horizontal = if (tinted) 14.dp else 0.dp, vertical = 6.dp)
            .background(if (tinted) colors.primaryContainer else colors.background, RoundedCornerShape(22.dp)),
        ) { RecentPeople(state.recentPeople, open) }
      }
    }
    if (state.hasAnyChat) {
      item(key = "filters") {
        if (state.layout == HomeLayout.BENTO) SegmentedFilters(state, vm::setFilter) else ChipFilters(state, vm::setFilter)
      }
    }
    when {
      state.loading -> item(key = "loading") {
        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = colors.brand)
        }
      }
      !state.hasAnyChat && !state.error -> item(key = "empty") {
        EmptyState(
          Icons.Outlined.PersonSearch,
          stringResource(R.string.home_empty_title),
          stringResource(R.string.home_empty_body),
          action = stringResource(R.string.home_empty_cta),
          onAction = onNewChat,
        )
      }
      state.rows.isEmpty() -> item(key = "filter-empty") {
        Text(
          if (state.query.isNotBlank()) stringResource(R.string.search_no_results, state.query) else stringResource(R.string.home_filter_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
      }
      else -> {
        val carded = state.layout != HomeLayout.CLEAN
        if (carded) item(key = "card-top") { CardEdge(top = true, heading = stringResource(R.string.home_recent)) }
        itemsIndexed(state.rows, key = { _, r -> r.conversationId }) { index, row ->
          val highlight = state.layout == HomeLayout.BENTO && row.pinned
          Box(
            Modifier
              .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.offsetSpring)
              .enterOnce(index, seen, row.conversationId)
              .padding(horizontal = if (carded) 14.dp else 0.dp)
              .background(if (carded) colors.surface else colors.background),
          ) {
            ChatRowItem(row, highlight, onClick = { open(row) })
          }
        }
        if (carded) item(key = "card-bottom") { CardEdge(top = false) }
        item(key = "footer") { HomeFooter() }
      }
    }
  }
}

/** Rounded top/bottom caps that make consecutive lazy rows read as one white card. */
@Composable
private fun CardEdge(top: Boolean, heading: String? = null) {
  val colors = SamvadTheme.colors
  val shape = if (top) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
  Box(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp).background(colors.surface, shape).height(if (top) 48.dp else 14.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    if (heading != null) {
      Text(heading, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
    }
  }
}

@Composable
private fun HeaderActions(state: HomeUiState, vm: HomeViewModel, onProfile: () -> Unit, onDark: Boolean, circled: Boolean) {
  val colors = SamvadTheme.colors
  val tint = if (onDark) colors.onDeep else colors.onSurface
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(
      onClick = vm::toggleSearch,
      modifier = if (circled) Modifier.padding(4.dp).size(40.dp).border(1.dp, colors.outlineVariant, CircleShape).background(colors.surface, CircleShape) else Modifier,
    ) {
      Icon(if (state.searching) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(R.string.cd_search), tint = tint)
    }
    val initials = ChatList.initials(state.me?.displayName ?: "")
    Box(
      Modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable(onClickLabel = stringResource(R.string.cd_profile), onClick = onProfile)
        .semantics { role = Role.Button },
      contentAlignment = Alignment.Center,
    ) {
      Avatar(initials, seed = state.me?.uid ?: "me", size = 34.dp, photoPath = state.me?.photoPath)
    }
  }
}

@Composable
private fun SearchField(state: HomeUiState, vm: HomeViewModel, onDark: Boolean = false) {
  val colors = SamvadTheme.colors
  AnimatedVisibility(state.searching || onDark) {
    val label = stringResource(R.string.home_search)
    Row(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 8.dp)
        .height(48.dp)
        .background(if (onDark) colors.onDeep.copy(alpha = 0.09f) else colors.surface, RoundedCornerShape(16.dp))
        .border(1.dp, if (onDark) colors.onDeep.copy(alpha = 0.16f) else colors.outlineVariant, RoundedCornerShape(16.dp))
        .clickable(enabled = onDark && !state.searching) { vm.toggleSearch() }
        .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Outlined.Search, null, tint = if (onDark) colors.onDeepMuted else colors.onSurfaceVariant, modifier = Modifier.size(19.dp))
      Box(Modifier.weight(1f)) {
        if (state.query.isEmpty()) {
          Text(label, style = MaterialTheme.typography.bodyMedium, color = if (onDark) colors.onDeepMuted else colors.onSurfaceVariant)
        }
        if (state.searching) {
          BasicTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (onDark) colors.onDeep else colors.onSurface),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
          )
        }
      }
    }
  }
}

@Composable
private fun Greeting(state: HomeUiState, onDark: Boolean, compact: Boolean = false) {
  val colors = SamvadTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(state.greeting, style = MaterialTheme.typography.labelLarge, color = if (onDark) colors.onDeepMuted else colors.onSurfaceVariant)
    Text(
      stringResource(R.string.home_headline),
      style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
      color = if (onDark) colors.onDeep else colors.onSurface,
    )
  }
}

@Composable
private fun SummaryLine(state: HomeUiState, accent: androidx.compose.ui.graphics.Color, dot: androidx.compose.ui.graphics.Color) {
  val text = if (state.summary.allCaughtUp) stringResource(R.string.home_all_caught_up)
  else pluralStringResource(R.plurals.home_unread_summary, state.summary.chats, state.summary.messages, state.summary.chats)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
    Box(Modifier.size(7.dp).background(dot, CircleShape))
    Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
  }
}

@Composable
private fun CleanHeader(state: HomeUiState, vm: HomeViewModel, onProfile: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Wordmark(Modifier.weight(1f))
      HeaderActions(state, vm, onProfile, onDark = false, circled = false)
    }
    SearchField(state, vm)
    if (!state.searching) {
      Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Greeting(state, onDark = false)
        SummaryLine(state, colors.primary, colors.brand)
      }
    }
  }
}

@Composable
private fun BoldHeader(state: HomeUiState, vm: HomeViewModel, onProfile: () -> Unit) {
  val colors = SamvadTheme.colors
  Box(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
      .background(colors.deep),
  ) {
    SamvadMark(210.dp, Modifier.align(Alignment.TopEnd).padding(top = 40.dp).offsetX(54.dp).alphaMark(0.10f))
    Column(Modifier.statusBarsPadding().padding(bottom = 18.dp)) {
      Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark(Modifier.weight(1f), color = colors.onDeep)
        HeaderActions(state, vm, onProfile, onDark = true, circled = false)
      }
      Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Greeting(state, onDark = true)
        SummaryLine(state, colors.lime, colors.lime)
      }
      SearchField(state, vm, onDark = true)
    }
  }
}

@Composable
private fun BentoHeader(state: HomeUiState, vm: HomeViewModel, onProfile: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.statusBarsPadding()) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Wordmark(Modifier.weight(1f), markSize = 26.dp)
      HeaderActions(state, vm, onProfile, onDark = false, circled = true)
    }
    SearchField(state, vm)
    if (!state.searching) {
      Box(
        Modifier
          .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)
          .fillMaxWidth()
          .height(136.dp)
          .clip(RoundedCornerShape(26.dp))
          .background(colors.deep)
          .padding(18.dp),
      ) {
        BubbleIllustration(Modifier.align(Alignment.CenterEnd).size(width = 132.dp, height = 104.dp))
        Column(Modifier.fillMaxWidth(0.66f).align(Alignment.TopStart)) { Greeting(state, onDark = true, compact = true) }
        val text = if (state.summary.allCaughtUp) stringResource(R.string.home_all_caught_up)
        else pluralStringResource(R.plurals.home_unread_summary, state.summary.chats, state.summary.messages, state.summary.chats)
        Text(
          text,
          style = MaterialTheme.typography.labelMedium,
          color = colors.onLime,
          modifier = Modifier.align(Alignment.BottomStart).background(colors.lime, RoundedCornerShape(13.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
      }
    }
  }
}

/** Two overlapping message bubbles drawn in brand colours (decorative). */
@Composable
private fun BubbleIllustration(modifier: Modifier) {
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  val t = remember { Animatable(if (reduced) 1f else 0f) }
  LaunchedEffect(Unit) { t.animateTo(1f, Motion.lively()) }
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val back = (1f - t.value) * w * 0.5f
    val front = (1f - t.value) * w * 0.8f
    drawRoundRect(colors.brand.copy(alpha = 0.55f), Offset(w * 0.30f + back, h * 0.02f), Size(w * 0.68f, h * 0.48f), CornerRadius(h * 0.14f))
    drawRoundRect(colors.lime, Offset(w * 0.02f + front, h * 0.40f), Size(w * 0.62f, h * 0.44f), CornerRadius(h * 0.13f))
    val dot = colors.deep
    listOf(0.17f, 0.31f, 0.45f).forEach { x -> drawCircle(dot, radius = h * 0.035f * t.value, center = Offset(w * x + front, h * 0.62f)) }
  }
}

@Composable
private fun RecentPeople(people: List<ChatRow>, onOpen: (ChatRow) -> Unit) {
  val colors = SamvadTheme.colors
  val peopleSeen = remember { mutableSetOf<Any>() }
  LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    itemsIndexed(people, key = { _, it -> "p-" + it.conversationId }) { index, p ->
      Column(
        Modifier.enterOnce(index, peopleSeen, p.conversationId).width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = p.title) { onOpen(p) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StoryAvatar(p.initials, p.otherUid ?: p.conversationId, unread = p.unreadCount > 0)
        Text(
          p.title.substringBefore(' '),
          style = MaterialTheme.typography.labelSmall,
          color = if (p.unreadCount > 0) colors.onSurface else colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private val filterLabels = mapOf(
  ChatFilter.ALL to R.string.filter_all,
  ChatFilter.UNREAD to R.string.filter_unread,
  ChatFilter.GROUPS to R.string.filter_groups,
  ChatFilter.FAVORITES to R.string.filter_favorites,
)

@Composable
private fun ChipFilters(state: HomeUiState, onSelect: (ChatFilter) -> Unit) {
  val colors = SamvadTheme.colors
  LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(ChatFilter.entries.toList(), key = { it.name }) { f ->
      val sel = f == state.filter
      val bg by animateColorAsState(if (sel) colors.primary else colors.surface, motionSpec(Motion.standard()), label = "chipBg")
      val edge by animateColorAsState(if (sel) colors.primary else colors.outlineVariant, motionSpec(Motion.standard()), label = "chipEdge")
      val press = remember { MutableInteractionSource() }
      Row(
        Modifier
          .pressScale(press)
          .height(36.dp)
          .clip(RoundedCornerShape(18.dp))
          .background(bg)
          .border(1.dp, edge, RoundedCornerShape(18.dp))
          .clickable(interactionSource = press, indication = LocalIndication.current) { onSelect(f) }
          .semantics { selected = sel; role = Role.Tab }
          .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(stringResource(filterLabels.getValue(f)), style = MaterialTheme.typography.titleSmall, color = if (sel) colors.onPrimary else colors.onSurface)
        if (f == ChatFilter.UNREAD && state.summary.chats > 0) {
          Box(
            Modifier.size(18.dp).background(if (sel) colors.onPrimary else colors.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
          ) { Text("${state.summary.chats}", style = MaterialTheme.typography.labelSmall, color = colors.onPrimaryContainer) }
        }
      }
    }
  }
}

@Composable
private fun SegmentedFilters(state: HomeUiState, onSelect: (ChatFilter) -> Unit) {
  val colors = SamvadTheme.colors
  BoxWithConstraints(
    Modifier
      .padding(horizontal = 20.dp, vertical = 8.dp)
      .fillMaxWidth()
      .height(42.dp)
      .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
      .padding(4.dp),
  ) {
    val segment = maxWidth / ChatFilter.entries.size
    val indicatorX by animateDpAsState(segment * state.filter.ordinal, motionSpec(Motion.lively()), label = "segment")
    Box(
      Modifier
        .offset(x = indicatorX)
        .width(segment)
        .fillMaxHeight()
        .shadow(1.dp, RoundedCornerShape(10.dp))
        .background(colors.surface, RoundedCornerShape(10.dp)),
    )
    Row(Modifier.fillMaxSize()) {
    ChatFilter.entries.forEach { f ->
      val sel = f == state.filter
      Box(
        Modifier
          .weight(1f)
          .fillMaxSize()
          .clip(RoundedCornerShape(10.dp))
          .clickable { onSelect(f) }
          .semantics { selected = sel; role = Role.Tab },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          stringResource(filterLabels.getValue(f)),
          style = MaterialTheme.typography.titleSmall,
          color = if (sel) colors.onSurface else colors.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
    }
  }
}

@Composable
fun ChatRowItem(row: ChatRow, highlight: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  val press = remember { MutableInteractionSource() }
  Row(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 6.dp, vertical = 1.dp)
      .pressScale(press, 0.98f)
      .clip(RoundedCornerShape(18.dp))
      .background(if (highlight) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
      .clickable(interactionSource = press, indication = LocalIndication.current, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(13.dp),
  ) {
    Avatar(
      row.initials,
      row.otherUid ?: row.conversationId,
      size = 50.dp,
      photoPath = if (row.isGroup) row.photoPath else com.samvad.messenger.ui.components.LocalPhotoSource.current?.pathFor(row.otherUid.orEmpty()),
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
          row.timeLabel,
          style = MaterialTheme.typography.labelSmall,
          color = if (row.unreadCount > 0) colors.primary else colors.onSurfaceVariant,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          row.preview,
          style = MaterialTheme.typography.bodyMedium,
          color = if (row.unreadCount > 0) colors.onSurface else colors.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        if (row.muted) Icon(Icons.Outlined.NotificationsOff, stringResource(R.string.cd_muted), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        if (row.pinned) Icon(Icons.Outlined.PushPin, stringResource(R.string.cd_pinned), tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        if (row.unreadCount > 0) UnreadBadge(row.unreadCount, pluralStringResource(R.plurals.cd_unread_count, row.unreadCount, row.unreadCount))
      }
    }
  }
}

@Composable
private fun FloatingNavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val items = listOf(
    Triple(R.string.nav_chats, Icons.AutoMirrored.Outlined.Chat, Icons.Filled.ChatBubble),
    Triple(R.string.nav_calls, Icons.Outlined.Call, Icons.Outlined.Call),
    Triple(R.string.nav_communities, Icons.Outlined.Groups, Icons.Outlined.Groups),
    Triple(R.string.nav_settings, Icons.Outlined.Settings, Icons.Outlined.Settings),
  )
  Box(
    modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0f), colors.background)))
      .navigationBarsPadding()
      .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 14.dp),
  ) {
    Row(
      Modifier
        .fillMaxWidth()
        .height(68.dp)
        .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = colors.deep, spotColor = colors.deep)
        .background(colors.surface, RoundedCornerShape(24.dp))
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp)),
    ) {
      items.forEachIndexed { i, (label, icon, activeIcon) ->
        val active = i == selected
        val pill by animateColorAsState(if (active) colors.primaryContainer else colors.surface, motionSpec(Motion.standard()), label = "pill")
        val pillWidth by animateDpAsState(if (active) 52.dp else 30.dp, motionSpec(Motion.lively()), label = "pillW")
        val tint by animateColorAsState(if (active) colors.brand else colors.onSurfaceVariant, motionSpec(Motion.standard()), label = "tint")
        Column(
          Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable(onClickLabel = stringResource(label)) { onSelect(i) }
            .semantics { this.selected = active; role = Role.Tab },
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Box(
            Modifier.size(width = pillWidth, height = 30.dp).background(pill, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(if (active) activeIcon else icon, null, tint = tint, modifier = Modifier.size(22.dp))
          }
          Spacer(Modifier.height(3.dp))
          Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = if (active) colors.primary else colors.onSurfaceVariant, maxLines = 1)
        }
      }
    }
  }
}

/**
 * Asks for notification permission once, from a dismissible card (Android 13+), never on launch.
 * "Not now" is remembered so the card doesn't come back.
 */
@Composable
private fun NotificationPrompt(container: AppContainer) {
  if (android.os.Build.VERSION.SDK_INT < 33) return
  val colors = SamvadTheme.colors
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val prefs = container.preferences.preferences.collectAsStateWithLifecycle(null).value ?: return
  var granted by androidx.compose.runtime.remember {
    androidx.compose.runtime.mutableStateOf(com.samvad.messenger.push.Notifications.canPost(context))
  }
  val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
  ) { ok ->
    granted = ok
    if (!ok) scope.launch { container.preferences.dismissNotificationPrompt() }
  }
  AnimatedVisibility(!granted && !prefs.notificationPromptDismissed) {
    Column(
      Modifier
        .padding(horizontal = 14.dp, vertical = 6.dp)
        .fillMaxWidth()
        .background(colors.primaryContainer, RoundedCornerShape(22.dp))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(stringResource(R.string.notif_prompt_title), style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
      Text(stringResource(R.string.notif_prompt_body), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.samvad.messenger.ui.components.QuietButton(stringResource(R.string.notif_prompt_allow), {
          launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        })
        com.samvad.messenger.ui.components.QuietButton(stringResource(R.string.notif_prompt_later), {
          scope.launch { container.preferences.dismissNotificationPrompt() }
        })
      }
    }
  }
}

/** A quiet signature at the end of the chat list (US-010); read as one line by screen readers. */
@Composable
private fun HomeFooter() {
  val colors = SamvadTheme.colors
  val tag = stringResource(R.string.home_footer_tag)
  val line1 = stringResource(R.string.home_footer_line1)
  val line2 = stringResource(R.string.home_footer_line2)
  Column(
    Modifier
      .fillMaxWidth()
      // Extra bottom space so the new-chat button never covers it at the end of the list.
      .padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 88.dp)
      .clearAndSetSemantics { contentDescription = "$tag. $line1 $line2" },
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(tag, style = MaterialTheme.typography.labelLarge, color = colors.primary)
    Text(line1, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    Text(line2, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}
