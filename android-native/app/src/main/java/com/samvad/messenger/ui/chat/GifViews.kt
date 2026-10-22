package com.samvad.messenger.ui.chat

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import com.samvad.core.gifs.GifGrid
import com.samvad.core.gifs.GifKind
import com.samvad.core.gifs.GifRef
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.SamvadTheme
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Image loaders for GIFs and stickers. [animated] plays them; [still] has no GIF decoder, so Android decodes only the
 * first frame (used when system animations are off, on Wi-Fi-only/Data saver previews, and before a tap to play).
 * Both share one small disk cache that the system can clear.
 */
object GifLoaders {
  private var animatedLoader: ImageLoader? = null
  private var stillLoader: ImageLoader? = null
  private var disk: DiskCache? = null

  private fun diskCache(context: Context): DiskCache = disk ?: DiskCache.Builder()
    .directory(File(context.cacheDir, "gif_cache").toOkioPath())
    .maxSizeBytes(48L * 1024 * 1024)
    .build().also { disk = it }

  @Synchronized
  fun animated(context: Context): ImageLoader = animatedLoader ?: ImageLoader.Builder(context.applicationContext)
    .components { if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory()) }
    .diskCache(diskCache(context.applicationContext))
    .build().also { animatedLoader = it }

  @Synchronized
  fun still(context: Context): ImageLoader = stillLoader ?: ImageLoader.Builder(context.applicationContext)
    .diskCache(diskCache(context.applicationContext))
    .build().also { stillLoader = it }
}

/** One GIF or sticker image: [animate] picks the playing or the first-frame loader. */
@Composable
internal fun GifImage(
  url: String,
  animate: Boolean,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
) {
  val context = LocalContext.current
  val loader = remember(animate) { if (animate) GifLoaders.animated(context) else GifLoaders.still(context) }
  AsyncImage(
    model = ImageRequest.Builder(context).data(url).build(),
    imageLoader = loader,
    contentDescription = contentDescription,
    contentScale = contentScale,
    modifier = modifier,
  )
}

@Composable
private fun gifDescription(ref: GifRef, kind: GifKind): String {
  val label = stringResource(if (kind == GifKind.STICKER) R.string.gifs_kind_sticker else R.string.gifs_kind_gif)
  return if (ref.title.isBlank()) label
  else stringResource(if (kind == GifKind.STICKER) R.string.gifs_cd_sticker else R.string.gifs_cd_gif, ref.title)
}

/**
 * A GIF or sticker inside a chat bubble. It plays when the auto-download policy allows and system animations are on;
 * otherwise it shows the small preview's first frame with "Tap to play" (loading the full image only after the tap).
 * Another tap pauses it. [onTap] is used instead while messages are being selected.
 */
@Composable
fun GifBubble(ref: GifRef, kind: GifKind, autoLoad: Boolean, selecting: Boolean, onSelectTap: () -> Unit, modifier: Modifier = Modifier) {
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  var userPlay by remember(ref.id) { mutableStateOf<Boolean?>(null) }
  val animate = userPlay ?: (autoLoad && !reduced)
  val everPlayed = autoLoad || userPlay == true
  val (w, h) = remember(ref, kind) { GifGrid.bubbleSizeDp(ref, kind) }
  val description = gifDescription(ref, kind)
  val play = stringResource(R.string.gifs_tap_to_play)
  Box(
    modifier
      .size(w.dp, h.dp)
      .clip(RoundedCornerShape(if (kind == GifKind.STICKER) 4.dp else 12.dp))
      .background(if (kind == GifKind.STICKER) androidx.compose.ui.graphics.Color.Transparent else colors.surfaceContainer)
      .combinedClickable(
        role = Role.Button,
        onClickLabel = if (selecting) null else if (animate) null else play,
        onClick = { if (selecting) onSelectTap() else userPlay = !animate },
      ),
  ) {
    GifImage(
      url = if (everPlayed) ref.url else ref.previewUrl,
      animate = animate,
      contentDescription = description,
      modifier = Modifier.fillMaxSize(),
    )
    if (!animate && !selecting) {
      Text(
        play,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurface,
        modifier = Modifier
          .align(Alignment.Center)
          .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }
}

/**
 * Page pattern: list (searchable staggered grid) in a modal bottom sheet. Tabs for GIFs and stickers, a debounced
 * search box, Trending and Yours (favourites and recents, kept on this phone), and a footer that names the provider
 * and says plainly what it can see.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GifPickerContent(
  container: AppContainer,
  autoLoad: Boolean,
  onPick: (GifKind, GifRef) -> Unit,
  onClose: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val vm: GifPickerViewModel = viewModel(key = "gif-picker", factory = GifPickerViewModel.factory(container))
  val ui by vm.state.collectAsStateWithLifecycle()
  val reduced = LocalReducedMotion.current
  val animateTiles = autoLoad && !reduced
  val columns = GifGrid.columnsFor(LocalConfiguration.current.screenWidthDp)
  val gridState = rememberLazyStaggeredGridState()
  val shown = if (ui.showingYours) ui.library.yours else ui.items

  // Back at the top whenever the list is replaced.
  LaunchedEffect(ui.kind, ui.query, ui.section) { runCatching { gridState.scrollToItem(0) } }
  val nearEnd by remember(gridState, shown.size) {
    derivedStateOf {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
      shown.isNotEmpty() && last >= shown.size - 6
    }
  }
  LaunchedEffect(nearEnd, ui.next) { if (nearEnd) vm.loadMore() }

  Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.gifs_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
      IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.gifs_close), tint = colors.onSurfaceVariant) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = ui.kind == GifKind.GIF,
        onClick = { vm.setKind(GifKind.GIF) },
        label = { Text(stringResource(R.string.gifs_tab_gifs)) },
        modifier = Modifier.heightIn(min = 48.dp),
      )
      FilterChip(
        selected = ui.kind == GifKind.STICKER,
        onClick = { vm.setKind(GifKind.STICKER) },
        label = { Text(stringResource(R.string.gifs_tab_stickers)) },
        modifier = Modifier.heightIn(min = 48.dp),
      )
    }
    val hint = stringResource(if (ui.kind == GifKind.STICKER) R.string.gifs_search_stickers else R.string.gifs_search_gifs)
    OutlinedTextField(
      value = ui.rawQuery,
      onValueChange = vm::setQuery,
      singleLine = true,
      placeholder = { Text(hint) },
      trailingIcon = if (ui.rawQuery.isNotEmpty()) {
        { IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.gifs_search_clear)) } }
      } else null,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { /* the debounce already searches */ }),
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = hint },
    )
    if (ui.query.isEmpty()) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = ui.section == GifSection.TRENDING,
          onClick = { vm.setSection(GifSection.TRENDING) },
          label = { Text(stringResource(R.string.gifs_section_trending)) },
          modifier = Modifier.heightIn(min = 48.dp),
        )
        FilterChip(
          selected = ui.section == GifSection.YOURS,
          onClick = { vm.setSection(GifSection.YOURS) },
          label = { Text(stringResource(R.string.gifs_section_yours)) },
          modifier = Modifier.heightIn(min = 48.dp),
        )
      }
    }

    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
      when {
        ui.problem != null -> ProblemView(ui.problem!!, onRetry = vm::retry)
        ui.loading -> SkeletonGrid(columns, reduced)
        shown.isEmpty() -> EmptyView(
          if (ui.showingYours) stringResource(R.string.gifs_yours_empty) else stringResource(R.string.gifs_no_results, ui.query),
        )
        else -> LazyVerticalStaggeredGrid(
          state = gridState,
          columns = StaggeredGridCells.Fixed(columns),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalItemSpacing = 8.dp,
        ) {
          items(shown.size, key = { shown[it].id }) { index ->
            val item = shown[index]
            GifTile(
              item = item,
              kind = ui.kind,
              animate = animateTiles,
              favourite = item.id in ui.favouriteIds,
              onPick = { onPick(ui.kind, item) },
              onToggleFavourite = { vm.toggleFavourite(item) },
            )
          }
          if (ui.loadingMore) {
            item(span = StaggeredGridItemSpan.FullLine) { Text(stringResource(R.string.gifs_loading), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp)) }
          }
          item(span = StaggeredGridItemSpan.FullLine) { PrivacyNote() }
        }
      }
    }
    Text(
      stringResource(R.string.gifs_powered_by),
      style = MaterialTheme.typography.labelLarge,
      color = colors.onSurface,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GifTile(
  item: GifRef,
  kind: GifKind,
  animate: Boolean,
  favourite: Boolean,
  onPick: () -> Unit,
  onToggleFavourite: () -> Unit,
) {
  val colors = SamvadTheme.colors
  val description = gifDescription(item, kind)
  val favLabel = stringResource(if (favourite) R.string.gifs_favourite_remove else R.string.gifs_favourite_add)
  Box(
    Modifier
      .fillMaxWidth()
      .aspectRatio(item.aspect)
      .clip(RoundedCornerShape(12.dp))
      .background(colors.surfaceContainer)
      .combinedClickable(
        role = Role.Button,
        onClick = onPick,
        onLongClickLabel = favLabel,
        onLongClick = onToggleFavourite,
      )
      .semantics { customActions = listOf(CustomAccessibilityAction(favLabel) { onToggleFavourite(); true }) },
  ) {
    GifImage(url = item.previewUrl, animate = animate, contentDescription = description, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
  }
}

@Composable
private fun SkeletonGrid(columns: Int, reduced: Boolean) {
  val colors = SamvadTheme.colors
  val pulse = if (reduced) 1f else rememberInfiniteTransition(label = "gifSkeleton").animateFloat(
    initialValue = 0.55f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "gifSkeletonAlpha",
  ).value
  val loading = stringResource(R.string.gifs_loading)
  val aspects = listOf(1.3f, 0.9f, 1.6f, 1f, 0.8f, 1.4f, 1.1f, 1.5f)
  LazyVerticalStaggeredGrid(
    columns = StaggeredGridCells.Fixed(columns),
    modifier = Modifier.fillMaxSize().semantics { contentDescription = loading },
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalItemSpacing = 8.dp,
    userScrollEnabled = false,
  ) {
    items(columns * 4) { i ->
      Box(Modifier.fillMaxWidth().aspectRatio(aspects[i % aspects.size]).alpha(pulse).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer))
    }
  }
}

@Composable
private fun EmptyView(message: String) {
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(message, style = MaterialTheme.typography.bodyLarge, color = SamvadTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
    PrivacyNote()
  }
}

@Composable
private fun ProblemView(problem: GifProblem, onRetry: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    when (problem) {
      GifProblem.NOT_CONFIGURED -> {
        Text(stringResource(R.string.gifs_not_set_up_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.gifs_not_set_up_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
      }
      GifProblem.LIMIT -> Text(stringResource(R.string.gifs_limit), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, textAlign = TextAlign.Center)
      GifProblem.OFFLINE -> {
        Text(stringResource(R.string.gifs_offline), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, textAlign = TextAlign.Center)
        RetryButton(onRetry)
      }
      GifProblem.FAILED -> {
        Text(stringResource(R.string.gifs_error_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.gifs_error_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        RetryButton(onRetry)
      }
    }
  }
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
  Spacer(Modifier.height(16.dp))
  Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gifs_retry)) }
}

/** Words for a GIF or sticker where only text fits (a quoted reply). Empty for other kinds. */
@Composable
internal fun gifReplyLabel(message: com.samvad.core.chat.Message): String = when (message.kind) {
  "gif" -> stringResource(R.string.gifs_kind_gif)
  "sticker" -> stringResource(R.string.gifs_kind_sticker)
  else -> ""
}

/** What the provider can see, said plainly (also required reading for anyone choosing to send a GIF). */
@Composable
private fun PrivacyNote() {
  Text(
    stringResource(R.string.gifs_privacy_note),
    style = MaterialTheme.typography.bodySmall,
    color = SamvadTheme.colors.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
  )
}
