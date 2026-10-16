package com.samvad.core.status

import com.samvad.core.design.SamvadPalettes
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** How big the text on a photo is, as a share of the photo's width, so it looks the same at any resolution. */
enum class OverlaySize(val fraction: Float) {
  SMALL(0.05f), MEDIUM(0.075f), LARGE(0.11f);

  companion object {
    fun fromOrdinal(value: Int): OverlaySize = entries.getOrElse(value) { MEDIUM }
  }
}

/**
 * A caption drawn on a photo. [cx] and [cy] are the centre of the text as 0..1 shares of the photo's width and
 * height, so the same value works for the small preview and the picture that is uploaded. [color] is an index into
 * [PhotoEditPalette.TEXT_COLORS].
 */
data class OverlayText(val text: String, val cx: Float, val cy: Float, val size: OverlaySize, val color: Int)

/** One pen stroke: a colour (an index into [PhotoEditPalette.PEN_COLORS]) and its points as 0..1 shares of the photo. */
data class PenStroke(val color: Int, val points: List<PenPoint>)

data class PenPoint(val x: Float, val y: Float)

/** Everything drawn on a photo moment before it is posted. Plain data, so it is easy to keep across rotation. */
data class PhotoEdits(val text: OverlayText? = null, val strokes: List<PenStroke> = emptyList()) {
  val hasText: Boolean get() = text != null && text.text.isNotBlank()
  val isEmpty: Boolean get() = !hasText && strokes.isEmpty()

  companion object {
    val NONE = PhotoEdits()
  }
}

/** Colours for the editor. They come from the light design-system palette, so they do not change with the theme. */
object PhotoEditPalette {
  private val light = SamvadPalettes.Light

  /** White, champagne, lime and brand green. */
  val TEXT_COLORS: List<Long> = listOf(light.onDeep, light.tertiaryContainer, light.lime, light.brand)

  /** White, lime and red. */
  val PEN_COLORS: List<Long> = listOf(light.onDeep, light.lime, light.notificationDot)

  fun textColor(index: Int): Long = TEXT_COLORS[index.coerceIn(0, TEXT_COLORS.lastIndex)]

  fun penColor(index: Int): Long = PEN_COLORS[index.coerceIn(0, PEN_COLORS.lastIndex)]
}

/** Where a text box goes inside the photo, in pixels from the top left. */
data class Placement(val left: Float, val top: Float)

/** Layout, limits and editing steps for text and pen on a photo moment. Pure, so it is unit tested. */
object PhotoEditRules {
  const val MAX_TEXT_CHARS = 120
  const val MAX_TEXT_LINES = 6
  const val MAX_STROKES = 40
  const val MAX_POINTS = 3_000

  /** Points closer than this (as a share of the photo) to the last one are dropped, which keeps strokes small. */
  const val MIN_POINT_GAP = 0.004f

  private const val TEXT_WIDTH_FRACTION = 0.86f
  private const val PEN_WIDTH_FRACTION = 0.012f
  private const val EDGE_MARGIN_FRACTION = 0.02f

  /** The longest edge of the photo that is edited and uploaded, so the bitmap stays small. */
  const val MAX_EDGE_PX = 1600

  fun textSizePx(imageWidthPx: Int, size: OverlaySize): Float = imageWidthPx * size.fraction

  fun maxTextWidthPx(imageWidthPx: Int): Float = imageWidthPx * TEXT_WIDTH_FRACTION

  fun penWidthPx(imageWidthPx: Int): Float = (imageWidthPx * PEN_WIDTH_FRACTION).coerceAtLeast(2f)

  /**
   * Top left of a text box of [boxW] by [boxH] pixels centred on ([cx], [cy]) in an image of [imageW] by [imageH],
   * moved just enough to keep the whole box inside the image with a small margin. A box too big to fit on an axis is
   * centred on it.
   */
  fun placeBox(cx: Float, cy: Float, boxW: Float, boxH: Float, imageW: Float, imageH: Float): Placement {
    val margin = imageW * EDGE_MARGIN_FRACTION
    return Placement(
      left = fit(cx * imageW - boxW / 2f, boxW, imageW, margin),
      top = fit(cy * imageH - boxH / 2f, boxH, imageH, margin),
    )
  }

  private fun fit(start: Float, length: Float, total: Float, margin: Float): Float =
    if (length + 2 * margin >= total) (total - length) / 2f else start.coerceIn(margin, total - length - margin)

  fun clamp01(value: Float): Float = if (value.isNaN()) 0.5f else value.coerceIn(0f, 1f)

  // ---- Text ------------------------------------------------------------------------------------

  /** Sets (or removes, when [text] is blank) the caption. The centre is kept inside the photo. */
  fun withText(edits: PhotoEdits, text: String, cx: Float, cy: Float, size: OverlaySize, color: Int): PhotoEdits {
    val clean = text.replace('\n', ' ').trim().take(MAX_TEXT_CHARS)
    if (clean.isEmpty()) return edits.copy(text = null)
    return edits.copy(text = OverlayText(clean, clamp01(cx), clamp01(cy), size, color.coerceIn(0, PhotoEditPalette.TEXT_COLORS.lastIndex)))
  }

  fun moveText(edits: PhotoEdits, cx: Float, cy: Float): PhotoEdits =
    edits.text?.let { edits.copy(text = it.copy(cx = clamp01(cx), cy = clamp01(cy))) } ?: edits

  fun resizeText(edits: PhotoEdits, size: OverlaySize): PhotoEdits =
    edits.text?.let { edits.copy(text = it.copy(size = size)) } ?: edits

  fun recolorText(edits: PhotoEdits, color: Int): PhotoEdits =
    edits.text?.let { edits.copy(text = it.copy(color = color.coerceIn(0, PhotoEditPalette.TEXT_COLORS.lastIndex))) } ?: edits

  fun removeText(edits: PhotoEdits): PhotoEdits = edits.copy(text = null)

  // ---- Pen -------------------------------------------------------------------------------------

  fun pointCount(edits: PhotoEdits): Int = edits.strokes.sumOf { it.points.size }

  /** Starts a stroke with one point. Nothing changes once the stroke or point limit is reached. */
  fun beginStroke(edits: PhotoEdits, color: Int, x: Float, y: Float): PhotoEdits {
    if (edits.strokes.size >= MAX_STROKES || pointCount(edits) >= MAX_POINTS) return edits
    val stroke = PenStroke(color.coerceIn(0, PhotoEditPalette.PEN_COLORS.lastIndex), listOf(PenPoint(clamp01(x), clamp01(y))))
    return edits.copy(strokes = edits.strokes + stroke)
  }

  /** Adds a point to the last stroke, skipping points that are too close to the previous one. */
  fun extendStroke(edits: PhotoEdits, x: Float, y: Float): PhotoEdits {
    val last = edits.strokes.lastOrNull() ?: return edits
    if (pointCount(edits) >= MAX_POINTS) return edits
    val p = PenPoint(clamp01(x), clamp01(y))
    val prev = last.points.last()
    val dx = p.x - prev.x
    val dy = p.y - prev.y
    if (dx * dx + dy * dy < MIN_POINT_GAP * MIN_POINT_GAP) return edits
    return edits.copy(strokes = edits.strokes.dropLast(1) + last.copy(points = last.points + p))
  }

  fun undoStroke(edits: PhotoEdits): PhotoEdits = edits.copy(strokes = edits.strokes.dropLast(1))

  fun clearStrokes(edits: PhotoEdits): PhotoEdits = edits.copy(strokes = emptyList())

  // ---- Keeping the edits across rotation and process death -----------------------------------

  /** A short string for saved instance state; [decode] reads it back. */
  fun encode(edits: PhotoEdits): String {
    val parts = mutableListOf<String>()
    edits.text?.let { t ->
      parts += "T:${num(t.cx)},${num(t.cy)},${t.size.ordinal},${t.color},${URLEncoder.encode(t.text, "UTF-8")}"
    }
    edits.strokes.forEach { s ->
      parts += "S:${s.color}:" + s.points.joinToString(",") { "${num(it.x)},${num(it.y)}" }
    }
    return parts.joinToString(";")
  }

  /** Reads what [encode] wrote. Anything unreadable gives [PhotoEdits.NONE] rather than a crash. */
  fun decode(saved: String?): PhotoEdits {
    if (saved.isNullOrEmpty()) return PhotoEdits.NONE
    return try {
      var edits = PhotoEdits.NONE
      saved.split(';').forEach { part ->
        when {
          part.startsWith("T:") -> {
            val f = part.removePrefix("T:").split(',', limit = 5)
            edits = withText(edits, URLDecoder.decode(f[4], "UTF-8"), f[0].toFloat(), f[1].toFloat(), OverlaySize.fromOrdinal(f[2].toInt()), f[3].toInt())
          }
          part.startsWith("S:") -> {
            val f = part.removePrefix("S:").split(':', limit = 2)
            val nums = f[1].split(',').map { it.toFloat() }
            if (nums.size >= 2 && nums.size % 2 == 0) {
              edits = beginStroke(edits, f[0].toInt(), nums[0], nums[1])
              for (i in 2 until nums.size step 2) edits = extendStroke(edits, nums[i], nums[i + 1])
            }
          }
        }
      }
      edits
    } catch (_: Exception) {
      PhotoEdits.NONE
    }
  }

  private fun num(v: Float): String = String.format(Locale.ROOT, "%.3f", v)
}
