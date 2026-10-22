package com.samvad.messenger.ui.media

import androidx.compose.ui.semantics.Role
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.SharedDocument
import com.samvad.core.chat.SharedLink
import com.samvad.core.chat.SharedMediaItem
import com.samvad.core.media.MediaRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.chat.MediaViewer
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/** "Media, links and documents" for a chat (group or 1:1), built only from messages already loaded here. */
@Composable
fun SharedMediaScreen(container: AppContainer, conversationId: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: SharedMediaViewModel = viewModel(key = "media-$conversationId", factory = SharedMediaViewModel.factory(container, conversationId))
  val shared = vm.shared.collectAsStateWithLifecycle().value
  var tab by remember { mutableIntStateOf(0) }
  var viewingPhoto by remember { mutableStateOf<String?>(null) }
  val titles = listOf(
    stringResource(R.string.media_tab_photos),
    stringResource(R.string.media_tab_docs),
    stringResource(R.string.media_tab_links),
  )

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.media_tab_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
    }
    Text(
      stringResource(R.string.media_loaded_note),
      style = MaterialTheme.typography.bodySmall,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
    TabRow(selectedTabIndex = tab, containerColor = colors.background, contentColor = colors.primary) {
      titles.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
    }
    Box(Modifier.weight(1f)) {
      when {
        shared == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.brand)
        tab == 0 -> PhotosGrid(vm, shared.photos, onOpen = { viewingPhoto = it })
        tab == 1 -> DocumentsList(vm, shared.documents)
        else -> LinksList(shared.links)
      }
    }
  }
  viewingPhoto?.let { path -> MediaViewer(path, vm::bitmap) { viewingPhoto = null } }
}

@Composable
private fun PhotosGrid(vm: SharedMediaViewModel, items: List<SharedMediaItem>, onOpen: (String) -> Unit) {
  val colors = SamvadTheme.colors
  if (items.isEmpty()) {
    EmptyState(Icons.Outlined.Image, stringResource(R.string.media_photos_empty_title), stringResource(R.string.media_photos_empty_body), modifier = Modifier.fillMaxSize())
    return
  }
  LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp)) {
    items(items, key = { it.messageId }) { item ->
      var bitmap by remember(item.media.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
      LaunchedEffect(item.media.path) { bitmap = runCatching { vm.bitmap(item.media.path, 300) }.getOrNull() }
      Box(
        Modifier.aspectRatio(1f).padding(2.dp).background(colors.surfaceContainer).clickable(role = Role.Button, onClickLabel = stringResource(R.string.a11y_open_media)) { onOpen(item.media.path) },
      ) {
        bitmap?.let {
          androidx.compose.foundation.Image(
            it.asImageBitmap(), null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = colors.onSurfaceVariant) }
        if (item.video) Icon(Icons.Filled.PlayArrow, null, tint = colors.onSurface, modifier = Modifier.align(Alignment.Center).size(28.dp))
      }
    }
  }
}

@Composable
private fun DocumentsList(vm: SharedMediaViewModel, items: List<SharedDocument>) {
  val colors = SamvadTheme.colors
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  if (items.isEmpty()) {
    EmptyState(Icons.Outlined.Description, stringResource(R.string.media_docs_empty_title), stringResource(R.string.media_docs_empty_body), modifier = Modifier.fillMaxSize())
    return
  }
  LazyColumn(Modifier.fillMaxSize()) {
    items(items, key = { it.messageId }) { doc ->
      Row(
        Modifier
          .fillMaxWidth()
          .clickable {
            scope.launch {
              val file = vm.localFile(doc.media.path) ?: return@launch
              val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
              runCatching {
                context.startActivity(
                  Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.media.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
              }
            }
          }
          .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(Modifier.size(40.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.Description, null, tint = colors.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
          Text(doc.media.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(MediaRules.formatBytes(doc.media.size), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun LinksList(items: List<SharedLink>) {
  val colors = SamvadTheme.colors
  if (items.isEmpty()) {
    EmptyState(Icons.Outlined.Link, stringResource(R.string.media_links_empty_title), stringResource(R.string.media_links_empty_body), modifier = Modifier.fillMaxSize())
    return
  }
  val context = LocalContext.current
  LazyColumn(Modifier.fillMaxSize()) {
    items(items, key = { "${it.messageId}-${it.url}" }) { link ->
      Row(
        Modifier
          .fillMaxWidth()
          .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } }
          .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.Link, null, tint = colors.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
          Text(link.host, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(link.url, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}
