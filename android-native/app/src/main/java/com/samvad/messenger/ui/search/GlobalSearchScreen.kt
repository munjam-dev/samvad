package com.samvad.messenger.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.search.SearchFilter
import com.samvad.core.search.SearchHit
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.theme.SamvadTheme

/**
 * Page pattern: list detail (global message search). Honest about its scope: the subtitle under the
 * search field says it searches only messages this device has already loaded, never the server.
 */
@Composable
fun GlobalSearchScreen(
  container: AppContainer,
  me: String,
  onBack: () -> Unit,
  /** cid, otherUid (null for a group), messageId. */
  onOpenMessage: (String, String?, String) -> Unit,
  /** cid, otherUid (null for a group). */
  onOpenChat: (String, String?) -> Unit = { _, _ -> },
) {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val colors = SamvadTheme.colors
  val vm: GlobalSearchViewModel = viewModel(factory = GlobalSearchViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(
      Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Row(
        Modifier
          .weight(1f)
          .heightIn(min = 48.dp)
          .background(colors.surfaceContainer, RoundedCornerShape(14.dp))
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(Icons.Outlined.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
          if (state.query.isEmpty()) {
            Text(stringResource(R.string.gsearch_placeholder), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
          }
          BasicTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
          )
        }
        if (state.query.isNotEmpty()) {
          IconButton(onClick = { vm.setQuery("") }) {
            Icon(Icons.Outlined.Close, stringResource(R.string.a11y_clear_search), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
          }
        }
      }
    }
    HorizontalDivider(color = colors.outlineVariant)
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(Icons.Outlined.Info, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
      Text(stringResource(R.string.gsearch_on_device_notice), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
    LazyRow(
      Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(SearchFilter.entries) { f ->
        FilterChip(f, selected = state.filter == f, onClick = { vm.setFilter(f) })
      }
    }
    when {
      state.tooShort -> EmptyLine(stringResource(R.string.gsearch_too_short))
      state.query.isBlank() -> Unit
      !state.searched -> Unit
      state.groups.isEmpty() && state.people.isEmpty() && state.groupChats.isEmpty() -> Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(Icons.Outlined.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.gsearch_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(top = 12.dp))
        Text(
          stringResource(R.string.gsearch_empty_body),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      else -> LazyColumn(Modifier.fillMaxSize()) {
        if (state.people.isNotEmpty()) {
          item(key = "h-people") { SectionLabel(stringResource(R.string.gsearch_people)) }
          items(state.people, key = { "p-" + it.uid }) { p ->
            PersonRow(p, onClick = { scope.launch { vm.openPerson(p.uid)?.let { cid -> onOpenChat(cid, p.uid) } } })
          }
        }
        if (state.groupChats.isNotEmpty()) {
          item(key = "h-groups") { SectionLabel(stringResource(R.string.gsearch_groups)) }
          items(state.groupChats, key = { "g-" + it.conversationId }) { g ->
            Row(
              Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenChat(g.conversationId, null) }.padding(horizontal = 16.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Avatar(ChatList.initials(g.name), g.conversationId, size = 40.dp)
              Text(g.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
            }
          }
        }
        if (state.groups.isNotEmpty()) item(key = "h-messages") { SectionLabel(stringResource(R.string.gsearch_messages)) }
        state.groups.forEach { group ->
          item(key = "header-${group.conversationId}") { ChatHeader(group) }
          val shown = if (group.conversationId in state.expanded) group.hits.hits else group.hits.hits.take(com.samvad.core.search.SearchPolicy.PER_CHAT_SHOWN)
          items(shown, key = { "${group.conversationId}-${it.entry.messageId}" }) { hit ->
            ResultRow(hit, vm, onOpen = { onOpenMessage(group.conversationId, group.otherUid, hit.entry.messageId) })
          }
          if (shown.size < group.hits.hits.size) {
            item(key = "more-${group.conversationId}") {
              Text(
                pluralStringResource(R.plurals.gsearch_show_more, group.hits.hits.size - shown.size, group.hits.hits.size - shown.size),
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
                modifier = Modifier.fillMaxWidth().clickable { vm.expand(group.conversationId) }.padding(start = 76.dp, top = 4.dp, bottom = 10.dp),
              )
            }
          }
          item(key = "divider-${group.conversationId}") { HorizontalDivider(color = colors.outlineVariant) }
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = SamvadTheme.colors.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
  )
}

@Composable
private fun PersonRow(p: PersonResult, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Avatar(ChatList.initials(p.title), p.uid, size = 40.dp, photoPath = p.photoPath)
    Column(Modifier.padding(start = 12.dp)) {
      Text(p.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (p.savedName != null) {
        Text(stringResource(R.string.gsearch_saved_as, p.savedName), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun EmptyLine(text: String) {
  val colors = SamvadTheme.colors
  Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(32.dp))
}

@Composable
private fun FilterChip(filter: SearchFilter, selected: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  val label = stringResource(
    when (filter) {
      SearchFilter.ALL -> R.string.gsearch_filter_all
      SearchFilter.PHOTOS -> R.string.gsearch_filter_photos
      SearchFilter.DOCUMENTS -> R.string.gsearch_filter_documents
      SearchFilter.LINKS -> R.string.gsearch_filter_links
    },
  )
  Box(
    Modifier
      .heightIn(min = 36.dp)
      .background(if (selected) colors.primary else colors.surfaceContainer, RoundedCornerShape(18.dp))
      .clickable(onClickLabel = label, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurfaceVariant)
  }
}

@Composable
private fun ChatHeader(group: SearchResultGroup) {
  val colors = SamvadTheme.colors
  Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Avatar(ChatList.initials(group.title), group.otherUid ?: group.conversationId, size = 28.dp, photoPath = if (group.isGroup) group.photoPath else null)
    Text(
      group.title,
      style = MaterialTheme.typography.titleSmall,
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 10.dp).weight(1f),
    )
  }
}

@Composable
private fun ResultRow(hit: SearchHit, vm: GlobalSearchViewModel, onOpen: () -> Unit) {
  val colors = SamvadTheme.colors
  val now = remember { System.currentTimeMillis() }
  val sender = vm.senderName(hit.entry.senderId)
  val you = stringResource(R.string.gsearch_you)
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(start = 76.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      if (sender.isNotEmpty()) {
        Text(sender, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
      } else {
        Text(you, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
      }
      Text(
        highlighted(hit.snippet, colors.primary),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      com.samvad.core.time.TimeLabels.chatListTime(hit.entry.createdAt, now),
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant,
    )
  }
}

private fun highlighted(snippet: com.samvad.core.search.Snippet, color: Color): AnnotatedString =
  buildAnnotatedString {
    append(snippet.text)
    snippet.highlights.forEach { range ->
      if (range.first in snippet.text.indices && range.last < snippet.text.length) {
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
      }
    }
  }
