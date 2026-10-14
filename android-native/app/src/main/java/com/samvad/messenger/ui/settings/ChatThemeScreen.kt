package com.samvad.messenger.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.design.ChatBackground
import com.samvad.core.design.ChatThemes
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.ChatThemeSettings
import com.samvad.messenger.ui.chat.ChatBackdrop
import com.samvad.messenger.ui.chat.chatThemeColors
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.theme.ProvideChatTheme
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/** "Use default" for one chat; otherwise a theme ID or [ChatThemes.PHOTO]. */
private const val USE_DEFAULT = "__default__"

/**
 * Pattern: picker with live preview (settings detail). Chooses the theme for every chat, or, with
 * [conversationId], for one chat (US-005 to US-007). Themes apply on Apply; a picked photo is saved
 * at once (it has to be processed), and Cancel restores the previous choice.
 */
@Composable
fun ChatThemeScreen(container: AppContainer, conversationId: String?, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val repo = container.chatThemes
  val scope = rememberCoroutineScope()
  val settings = repo.settings.collectAsStateWithLifecycle(null).value ?: return
  val slot = conversationId ?: ChatThemes.DEFAULT_SLOT
  val saved = if (conversationId == null) settings.defaultId else settings.overrides[conversationId] ?: USE_DEFAULT
  var original by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { if (original == null) original = saved }
  var choice by remember(saved) { mutableStateOf(saved) }
  var photoError by remember { mutableStateOf(false) }
  val maxEdge = with(LocalDensity.current) {
    val c = LocalConfiguration.current
    maxOf(c.screenWidthDp, c.screenHeightDp).dp.roundToPx()
  }
  val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
    if (uri != null) scope.launch {
      photoError = !repo.usePhoto(slot, uri, maxEdge)
      if (!photoError) choice = ChatThemes.PHOTO
    }
  }

  fun save(id: String) = scope.launch {
    when {
      conversationId == null -> if (id != ChatThemes.PHOTO) repo.setDefault(id)
      id == USE_DEFAULT -> repo.setOverride(conversationId, null)
      id != ChatThemes.PHOTO -> repo.setOverride(conversationId, id)
    }
  }

  val preview: ChatBackground = when (choice) {
    USE_DEFAULT -> ChatThemes.resolve(settings.defaultId, emptyMap(), slot)
    ChatThemes.PHOTO -> ChatBackground.Photo(slot)
    else -> ChatBackground.Theme(choice)
  }
  val dark = colors.isDark

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(
      Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = {
        // Leaving without Apply keeps what was saved before this screen opened.
        original?.let { if (it != saved && it != ChatThemes.PHOTO) save(it) }
        onBack()
      }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(
        stringResource(if (conversationId == null) R.string.chat_theme_title else R.string.chat_theme_title_one),
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
        modifier = Modifier.padding(start = 8.dp),
      )
    }

    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 100.dp),
      modifier = Modifier.weight(1f).fillMaxWidth(),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        PreviewCard(preview, settings, container, dark, slot, onFocus = { x, y -> scope.launch { repo.setFocus(slot, x, y) } })
      }
      if (choice == ChatThemes.PHOTO) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          DimControl(settings.dims[slot] ?: if (dark) ChatThemes.DEFAULT_DIM_DARK else ChatThemes.DEFAULT_DIM_LIGHT) { pct ->
            scope.launch { repo.setDim(slot, pct) }
          }
        }
      }
      if (photoError) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          Text(stringResource(R.string.chat_theme_photo_error), color = colors.error, style = MaterialTheme.typography.bodyMedium)
        }
      }
      if (conversationId != null) {
        item(key = USE_DEFAULT) {
          Tile(
            label = stringResource(R.string.chat_theme_use_default),
            selected = choice == USE_DEFAULT,
            onClick = { choice = USE_DEFAULT },
          ) {
            ChatBackdrop(ChatThemes.resolve(settings.defaultId, emptyMap(), ChatThemes.DEFAULT_SLOT), settings, repo, dark, Modifier.fillMaxSize())
            Icon(Icons.Outlined.Restore, null, tint = colors.onSurface, modifier = Modifier.align(Alignment.Center).size(28.dp))
          }
        }
      }
      items(ChatThemes.all, key = { it.id }) { theme ->
        Tile(label = stringResource(themeName(theme.id)), selected = choice == theme.id, onClick = { choice = theme.id }) {
          val bg = ChatBackground.Theme(theme.id)
          ChatBackdrop(bg, settings, repo, dark, Modifier.fillMaxSize())
          MiniBubbles(bg, dark)
        }
      }
      item(key = ChatThemes.PHOTO) {
        val hasPhoto = choice == ChatThemes.PHOTO || saved == ChatThemes.PHOTO
        Tile(
          label = stringResource(R.string.chat_theme_photo),
          selected = choice == ChatThemes.PHOTO,
          onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ) {
          if (hasPhoto) {
            ChatBackdrop(ChatBackground.Photo(slot), settings, repo, dark, Modifier.fillMaxSize())
          } else {
            Box(Modifier.fillMaxSize().background(colors.surfaceContainer))
          }
          Icon(Icons.Outlined.AddPhotoAlternate, null, tint = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(28.dp))
        }
      }
    }

    Row(
      Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      QuietButton(stringResource(R.string.cancel), {
        original?.let { if (it != saved && it != ChatThemes.PHOTO) save(it) }
        onBack()
      })
      PrimaryButton(stringResource(R.string.chat_theme_apply), {
        save(choice)
        onBack()
      }, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun PreviewCard(
  background: ChatBackground,
  settings: ChatThemeSettings,
  container: AppContainer,
  dark: Boolean,
  slot: String,
  onFocus: (Float, Float) -> Unit,
) {
  val colors = SamvadTheme.colors
  val isPhoto = background is ChatBackground.Photo
  val start = settings.focus[slot] ?: (0.5f to 0.5f)
  var fx by remember(slot, isPhoto) { mutableFloatStateOf(start.first) }
  var fy by remember(slot, isPhoto) { mutableFloatStateOf(start.second) }
  val shown = settings.copy(focus = settings.focus + (slot to (fx to fy)))
  val dragHint = stringResource(R.string.chat_theme_drag)
  Column {
    Box(
      Modifier
        .fillMaxWidth()
        .height(280.dp)
        .clip(RoundedCornerShape(24.dp))
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp))
        .then(
          if (isPhoto) Modifier
            .semantics { contentDescription = dragHint }
            .pointerInput(slot) {
              detectDragGestures(onDragEnd = { onFocus(fx, fy) }) { change, drag ->
                change.consume()
                // Dragging the picture right reveals more of its left side, as in any photo app.
                fx = (fx - drag.x / size.width).coerceIn(0f, 1f)
                fy = (fy - drag.y / size.height).coerceIn(0f, 1f)
              }
            }
          else Modifier,
        ),
    ) {
      ChatBackdrop(background, shown, container.chatThemes, dark, Modifier.fillMaxSize())
      ProvideChatTheme(chatThemeColors(background, dark)) {
        val c = SamvadTheme.colors
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.chat_theme_preview),
            style = MaterialTheme.typography.labelMedium,
            color = c.onChip,
            modifier = Modifier.align(Alignment.CenterHorizontally).background(c.chip, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
          )
          Spacer(Modifier.weight(1f))
          PreviewBubble(stringResource(R.string.chat_theme_preview_in), mine = false)
          PreviewBubble(stringResource(R.string.chat_theme_preview_out), mine = true, modifier = Modifier.align(Alignment.End))
        }
      }
    }
    if (isPhoto) {
      Text(
        dragHint,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}

@Composable
private fun PreviewBubble(text: String, mine: Boolean, modifier: Modifier = Modifier) {
  val c = SamvadTheme.colors
  val shape = if (mine) RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp) else RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)
  Column(
    modifier
      .widthIn(max = 260.dp)
      .clip(shape)
      .background(if (mine) c.bubbleOutgoing else c.bubbleIncoming)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = c.onBubble)
    Text("9:41", style = MaterialTheme.typography.labelSmall, color = c.bubbleMeta, modifier = Modifier.align(Alignment.End))
  }
}

@Composable
private fun MiniBubbles(background: ChatBackground, dark: Boolean) {
  val t = chatThemeColors(background, dark)
  Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
    Box(Modifier.size(width = 52.dp, height = 14.dp).background(androidx.compose.ui.graphics.Color(t.bubbleIncoming), RoundedCornerShape(7.dp)))
    Box(
      Modifier
        .align(Alignment.End)
        .size(width = 44.dp, height = 14.dp)
        .background(androidx.compose.ui.graphics.Color(t.bubbleOutgoing), RoundedCornerShape(7.dp)),
    )
  }
}

@Composable
private fun Tile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
  val colors = SamvadTheme.colors
  val selectedLabel = stringResource(R.string.cd_chat_theme_selected, label)
  Column(
    Modifier
      .clip(RoundedCornerShape(16.dp))
      .clickable(role = Role.RadioButton, onClick = onClick)
      .semantics(mergeDescendants = true) {
        this.selected = selected
        contentDescription = if (selected) selectedLabel else label
      },
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier
        .fillMaxWidth()
        .height(150.dp)
        .clip(RoundedCornerShape(16.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
      content()
      if (selected) {
        Icon(
          Icons.Outlined.CheckCircle,
          null,
          tint = colors.primary,
          modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(colors.surface, CircleShape).size(22.dp),
        )
      }
    }
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = colors.onSurface,
      textAlign = TextAlign.Center,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

@Composable
private fun DimControl(percent: Int, onChange: (Int) -> Unit) {
  val colors = SamvadTheme.colors
  var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
  val label = stringResource(R.string.chat_theme_dim)
  Column {
    Text(label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
    Slider(
      value = value,
      onValueChange = { value = it },
      onValueChangeFinished = { onChange(value.toInt()) },
      valueRange = 0f..ChatThemes.MAX_DIM.toFloat(),
      colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
      modifier = Modifier.semantics { contentDescription = label },
    )
  }
}

private fun themeName(id: String): Int = when (id) {
  "sage" -> R.string.chat_theme_sage
  "monsoon" -> R.string.chat_theme_monsoon
  "marigold" -> R.string.chat_theme_marigold
  "night_garden" -> R.string.chat_theme_night_garden
  else -> R.string.chat_theme_ivory
}
