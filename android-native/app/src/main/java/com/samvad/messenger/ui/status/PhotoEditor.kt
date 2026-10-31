package com.samvad.messenger.ui.status

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.samvad.core.status.OverlaySize
import com.samvad.core.status.PhotoEditPalette
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits
import com.samvad.messenger.R
import com.samvad.messenger.data.PhotoEditRenderer
import com.samvad.messenger.ui.theme.SamvadTheme

private const val TOOL_TEXT = 0
private const val TOOL_PEN = 1
private const val TOOL_STICKER = 2
private val PACK_LABELS = mapOf(
  "faces" to R.string.photo_edit_pack_faces,
  "hearts" to R.string.photo_edit_pack_hearts,
  "celebrate" to R.string.photo_edit_pack_celebrate,
  "nature" to R.string.photo_edit_pack_nature,
)

private val COLOR_NAMES_TEXT = listOf(R.string.photo_edit_color_white, R.string.photo_edit_color_champagne, R.string.photo_edit_color_lime, R.string.photo_edit_color_green)
private val COLOR_NAMES_PEN = listOf(R.string.photo_edit_color_white, R.string.photo_edit_color_lime, R.string.photo_edit_color_red)

/** Saves the edits as a short string, so rotation and process death only carry simple state. */
internal val PhotoEditsSaver = androidx.compose.runtime.saveable.Saver<PhotoEdits, String>(
  save = { PhotoEditRules.encode(it) },
  restore = { PhotoEditRules.decode(it) },
)

/**
 * A chosen photo with a caption field and the two edit tools: text on the photo and a freehand pen. The edits are
 * shares of the photo, drawn by [PhotoEditRenderer] both here and into the uploaded JPEG. Pattern: a focused
 * single-task editor inside the composer.
 */
@Composable
internal fun PhotoMomentPane(
  preview: Bitmap,
  edits: PhotoEdits,
  onEdits: (PhotoEdits) -> Unit,
  caption: String,
  onCaption: (String) -> Unit,
  onPick: () -> Unit,
  enabled: Boolean,
) {
  val colors = SamvadTheme.colors
  BoxWithConstraints(Modifier.fillMaxSize()) {
    // The photo takes most of the height, but never so little that it cannot be drawn on; the rest scrolls.
    val imageMax = (maxHeight * 0.62f).coerceAtLeast(220.dp)
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PhotoEditor(preview, edits, onEdits, enabled, imageMax)
      BasicTextField(
        value = caption, onValueChange = onCaption, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(14.dp),
        decorationBox = { inner -> Box { if (caption.isEmpty()) Text(stringResource(R.string.status_caption_hint), color = colors.onSurfaceVariant); inner() } },
      )
      TextButton(onClick = onPick, enabled = enabled) { Text(stringResource(R.string.status_change_photo)) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoEditor(
  preview: Bitmap,
  edits: PhotoEdits,
  onEdits: (PhotoEdits) -> Unit,
  enabled: Boolean,
  imageMax: androidx.compose.ui.unit.Dp,
) {
  val colors = SamvadTheme.colors
  var tool by rememberSaveable { mutableIntStateOf(TOOL_TEXT) }
  var textSize by rememberSaveable { mutableIntStateOf(edits.text?.size?.ordinal ?: OverlaySize.MEDIUM.ordinal) }
  var textColor by rememberSaveable { mutableIntStateOf(edits.text?.color ?: 0) }
  var penColor by rememberSaveable { mutableIntStateOf(0) }
  var dialogOpen by rememberSaveable { mutableStateOf(false) }
  var draft by rememberSaveable { mutableStateOf("") }
  var spotX by rememberSaveable { mutableFloatStateOf(0.5f) }
  var spotY by rememberSaveable { mutableFloatStateOf(0.5f) }
  var confirmClear by rememberSaveable { mutableStateOf(false) }
  var selected by rememberSaveable { mutableIntStateOf(-1) }
  var packIndex by rememberSaveable { mutableIntStateOf(0) }
  // A removed sticker must not leave a stale selection behind.
  val selectedIndex = if (selected in edits.stickers.indices) selected else -1

  val latest = rememberUpdatedState(edits)
  val sizeNow = OverlaySize.fromOrdinal(textSize)

  fun openTextDialog(x: Float, y: Float) {
    draft = latest.value.text?.text.orEmpty()
    spotX = x
    spotY = y
    dialogOpen = true
  }

  val ratio = (preview.width.toFloat() / preview.height.toFloat()).coerceIn(0.2f, 5f)
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val surface = stringResource(R.string.photo_edit_surface)
    Box(
      Modifier
        .heightIn(max = imageMax)
        .aspectRatio(ratio)
        .clip(RoundedCornerShape(16.dp))
        .semantics { contentDescription = surface },
    ) {
      Image(preview.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
      Canvas(
        Modifier
          .fillMaxSize()
          .pointerInput(tool, enabled, penColor) {
            if (!enabled) return@pointerInput
            if (tool == TOOL_TEXT) {
              detectTapGestures { at ->
                val x = at.x / size.width
                val y = at.y / size.height
                if (latest.value.hasText) onEdits(PhotoEditRules.moveText(latest.value, x, y)) else openTextDialog(x, y)
              }
            } else if (tool == TOOL_PEN) {
              detectTapGestures { at -> onEdits(PhotoEditRules.beginStroke(latest.value, penColor, at.x / size.width, at.y / size.height)) }
            }
          }
          .pointerInput(tool, enabled, penColor) {
            if (!enabled) return@pointerInput
            // Work on a local copy while the finger is down, so quick moves never lose a point.
            var working = latest.value
            if (tool == TOOL_TEXT) {
              detectDragGestures(
                onDragStart = { at -> working = PhotoEditRules.moveText(latest.value, at.x / size.width, at.y / size.height); onEdits(working) },
                onDrag = { change, _ ->
                  change.consume()
                  working = PhotoEditRules.moveText(working, change.position.x / size.width, change.position.y / size.height)
                  onEdits(working)
                },
              )
            } else if (tool == TOOL_PEN) {
              detectDragGestures(
                onDragStart = { at -> working = PhotoEditRules.beginStroke(latest.value, penColor, at.x / size.width, at.y / size.height); onEdits(working) },
                onDrag = { change, _ ->
                  change.consume()
                  working = PhotoEditRules.extendStroke(working, change.position.x / size.width, change.position.y / size.height)
                  onEdits(working)
                },
              )
            }
          }
          .pointerInput(tool, enabled) {
            if (!enabled || tool != TOOL_STICKER) return@pointerInput
            // Touch a sticker to select it, then drag, pinch and twist it. Touching empty space deselects.
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              val hit = PhotoEditRules.hitSticker(latest.value, down.position.x / size.width, down.position.y / size.height, size.width.toFloat(), size.height.toFloat())
              selected = hit
              if (hit < 0) return@awaitEachGesture
              var working = latest.value
              do {
                val event = awaitPointerEvent()
                val zoom = event.calculateZoom()
                val turn = event.calculateRotation()
                val pan = event.calculatePan()
                if (zoom != 1f || turn != 0f || pan != Offset.Zero) {
                  working = PhotoEditRules.transformSticker(working, hit, pan.x / size.width, pan.y / size.height, zoom, turn)
                  onEdits(working)
                  event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
              } while (event.changes.any { it.pressed })
            }
          },
      ) {
        drawIntoCanvas { c -> PhotoEditRenderer.draw(c.nativeCanvas, size.width.toInt(), size.height.toInt(), edits) }
        // The ring that shows which sticker is selected is only drawn here, never into the posted picture.
        val chosen = edits.stickers.getOrNull(selectedIndex)
        if (tool == TOOL_STICKER && chosen != null) {
          val r = PhotoEditRules.stickerRadiusPx(size.width, chosen.scale) * 1.1f
          val c = Offset(chosen.cx * size.width, chosen.cy * size.height)
          drawCircle(Color.Black.copy(alpha = 0.55f), r, c, style = Stroke(width = 5.dp.toPx()))
          drawCircle(Color.White, r, c, style = Stroke(width = 2.dp.toPx()))
        }
      }
    }

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
      FilterChip(
        selected = tool == TOOL_TEXT, enabled = enabled, onClick = { tool = TOOL_TEXT },
        label = { Text(stringResource(R.string.photo_edit_tool_text)) },
        leadingIcon = { Icon(Icons.Outlined.TextFields, null, Modifier.size(18.dp)) },
      )
      FilterChip(
        selected = tool == TOOL_PEN, enabled = enabled, onClick = { tool = TOOL_PEN },
        label = { Text(stringResource(R.string.photo_edit_tool_pen)) },
        leadingIcon = { Icon(Icons.Outlined.Brush, null, Modifier.size(18.dp)) },
      )
      FilterChip(
        selected = tool == TOOL_STICKER, enabled = enabled, onClick = { tool = TOOL_STICKER },
        label = { Text(stringResource(R.string.photo_edit_tool_stickers)) },
        leadingIcon = { Icon(Icons.Outlined.EmojiEmotions, null, Modifier.size(18.dp)) },
      )
    }

    if (tool == TOOL_STICKER) StickerPanel(
      edits = edits, onEdits = onEdits, enabled = enabled, selectedIndex = selectedIndex,
      onSelect = { selected = it }, packIndex = packIndex, onPack = { packIndex = it },
    ) else

    if (tool == TOOL_TEXT) {
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = edits.text
        if (edits.hasText && current != null) {
          TextButton(onClick = { openTextDialog(current.cx, current.cy) }, enabled = enabled) { Text(stringResource(R.string.photo_edit_text_edit)) }
          TextButton(onClick = { onEdits(PhotoEditRules.removeText(edits)) }, enabled = enabled) { Text(stringResource(R.string.photo_edit_text_remove)) }
        } else {
          TextButton(onClick = { openTextDialog(0.5f, 0.5f) }, enabled = enabled) { Text(stringResource(R.string.photo_edit_text_title)) }
        }
        listOf(OverlaySize.SMALL to R.string.photo_edit_size_small, OverlaySize.MEDIUM to R.string.photo_edit_size_medium, OverlaySize.LARGE to R.string.photo_edit_size_large).forEach { (s, label) ->
          FilterChip(
            selected = sizeNow == s, enabled = enabled,
            onClick = { textSize = s.ordinal; onEdits(PhotoEditRules.resizeText(edits, s)) },
            label = { Text(stringResource(label)) },
          )
        }
      }
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        PhotoEditPalette.TEXT_COLORS.forEachIndexed { i, argb ->
          Swatch(Color(argb), stringResource(COLOR_NAMES_TEXT[i]), selected = textColor == i, enabled = enabled) {
            textColor = i
            onEdits(PhotoEditRules.recolorText(edits, i))
          }
        }
      }
    } else {
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        PhotoEditPalette.PEN_COLORS.forEachIndexed { i, argb ->
          Swatch(Color(argb), stringResource(COLOR_NAMES_PEN[i]), selected = penColor == i, enabled = enabled) { penColor = i }
        }
        IconButton(onClick = { onEdits(PhotoEditRules.undoStroke(edits)) }, enabled = enabled && edits.strokes.isNotEmpty()) {
          Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.photo_edit_pen_undo))
        }
        IconButton(onClick = { confirmClear = true }, enabled = enabled && edits.strokes.isNotEmpty()) {
          Icon(Icons.Outlined.Delete, stringResource(R.string.photo_edit_pen_clear))
        }
      }
    }
    if (edits.isEmpty) {
      Text(
        stringResource(when (tool) { TOOL_TEXT -> R.string.photo_edit_hint_text; TOOL_PEN -> R.string.photo_edit_hint_pen; else -> R.string.photo_edit_hint_stickers }),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  if (dialogOpen) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun confirm() {
      onEdits(PhotoEditRules.withText(latest.value, draft, spotX, spotY, sizeNow, textColor))
      dialogOpen = false
    }
    AlertDialog(
      onDismissRequest = { dialogOpen = false },
      title = { Text(stringResource(R.string.photo_edit_text_title)) },
      text = {
        BasicTextField(
          value = draft, onValueChange = { draft = it.take(PhotoEditRules.MAX_TEXT_CHARS) },
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
          cursorBrush = SolidColor(colors.onSurface),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { confirm() }),
          modifier = Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainer).padding(14.dp),
          decorationBox = { inner -> Box { if (draft.isEmpty()) Text(stringResource(R.string.photo_edit_text_hint), color = colors.onSurfaceVariant); inner() } },
        )
      },
      confirmButton = { TextButton(onClick = { confirm() }) { Text(stringResource(R.string.photo_edit_text_done)) } },
      dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text(stringResource(R.string.photo_edit_clear_title)) },
      text = { Text(stringResource(R.string.photo_edit_clear_body)) },
      confirmButton = {
        TextButton(onClick = { confirmClear = false; onEdits(PhotoEditRules.clearStrokes(latest.value)) }) {
          Text(stringResource(R.string.photo_edit_clear_confirm), color = colors.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/**
 * Emoji packs and the controls for the chosen sticker. Dragging, pinching and twisting happen on the photo; the buttons
 * here do the same in steps, for people who cannot pinch (switch access, TalkBack).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerPanel(
  edits: PhotoEdits,
  onEdits: (PhotoEdits) -> Unit,
  enabled: Boolean,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  packIndex: Int,
  onPack: (Int) -> Unit,
) {
  val colors = SamvadTheme.colors
  val packs = com.samvad.core.status.EmojiPacks.ALL
  val pack = packs[packIndex.coerceIn(0, packs.lastIndex)]
  val full = edits.stickers.size >= PhotoEditRules.MAX_STICKERS
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    packs.forEachIndexed { i, p ->
      FilterChip(
        selected = pack.id == p.id, enabled = enabled, onClick = { onPack(i) },
        label = { Text(stringResource(PACK_LABELS.getValue(p.id))) },
      )
    }
  }
  // Fixed in dp, so the emoji stay inside their 48 dp targets at 200% font scale.
  val emojiSize = with(androidx.compose.ui.platform.LocalDensity.current) { 26.dp.toSp() }
  FlowRow(Modifier.fillMaxWidth()) {
    pack.emoji.forEach { e ->
      val label = stringResource(R.string.photo_edit_sticker_add, e)
      Box(
        Modifier.size(48.dp).clip(CircleShape)
          .clickable(enabled = enabled && !full, role = Role.Button, onClickLabel = label) {
            val next = PhotoEditRules.addSticker(edits, e)
            onEdits(next)
            onSelect(next.stickers.lastIndex)
          }
          .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
      ) { Text(e, fontSize = emojiSize, color = if (enabled && !full) Color.Unspecified else colors.onSurfaceVariant.copy(alpha = 0.5f)) }
    }
  }
  if (full) {
    Text(
      stringResource(R.string.photo_edit_sticker_limit, PhotoEditRules.MAX_STICKERS),
      style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
    )
  }
  if (selectedIndex >= 0) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
      IconButton(onClick = { onEdits(PhotoEditRules.transformSticker(edits, selectedIndex, 0f, 0f, 1.25f, 0f)) }, enabled = enabled) {
        Icon(Icons.Outlined.ZoomIn, stringResource(R.string.photo_edit_sticker_bigger))
      }
      IconButton(onClick = { onEdits(PhotoEditRules.transformSticker(edits, selectedIndex, 0f, 0f, 0.8f, 0f)) }, enabled = enabled) {
        Icon(Icons.Outlined.ZoomOut, stringResource(R.string.photo_edit_sticker_smaller))
      }
      IconButton(onClick = { onEdits(PhotoEditRules.transformSticker(edits, selectedIndex, 0f, 0f, 1f, 15f)) }, enabled = enabled) {
        Icon(Icons.Outlined.RotateRight, stringResource(R.string.photo_edit_sticker_rotate))
      }
      IconButton(onClick = { onEdits(PhotoEditRules.removeSticker(edits, selectedIndex)); onSelect(-1) }, enabled = enabled) {
        Icon(Icons.Outlined.Delete, stringResource(R.string.photo_edit_sticker_delete))
      }
    }
  }
}

/** A colour choice: a 48 dp target around a 32 dp circle; the chosen one has a thick ring, so colour is not the only cue. */
@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  val label = stringResource(R.string.photo_edit_color_label, name)
  Box(
    Modifier
      .size(48.dp)
      .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.size(32.dp).clip(CircleShape).background(color)
        .border(if (selected) 3.dp else 1.dp, if (selected) colors.onSurface else colors.outline, CircleShape),
    )
  }
}
