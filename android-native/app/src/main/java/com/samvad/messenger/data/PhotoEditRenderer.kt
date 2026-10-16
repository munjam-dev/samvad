package com.samvad.messenger.data

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.samvad.core.status.PhotoEditPalette
import com.samvad.core.status.PhotoEditRules
import com.samvad.core.status.PhotoEdits

/**
 * Draws the pen strokes and the text of a photo moment onto a canvas of any size. The composer preview and the
 * uploaded JPEG both use it, so what you see is what is posted. All positions are shares of the width and height,
 * and the layout rules live in [PhotoEditRules] (pure Kotlin, unit tested).
 */
internal object PhotoEditRenderer {
  private const val SHADOW = 0xCC000000.toInt()

  fun draw(canvas: Canvas, width: Int, height: Int, edits: PhotoEdits) {
    if (width <= 0 || height <= 0 || edits.isEmpty) return
    drawStrokes(canvas, width, height, edits)
    drawText(canvas, width, height, edits)
  }

  private fun drawStrokes(canvas: Canvas, width: Int, height: Int, edits: PhotoEdits) {
    if (edits.strokes.isEmpty()) return
    val stroke = PhotoEditRules.penWidthPx(width)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
      strokeWidth = stroke
    }
    val w = width.toFloat()
    val h = height.toFloat()
    edits.strokes.forEach { s ->
      paint.color = PhotoEditPalette.penColor(s.color).toInt()
      val pts = s.points
      if (pts.size == 1) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(pts[0].x * w, pts[0].y * h, stroke / 2f, paint)
      } else if (pts.size > 1) {
        paint.style = Paint.Style.STROKE
        val path = Path()
        path.moveTo(pts[0].x * w, pts[0].y * h)
        for (i in 1 until pts.size) {
          val prev = pts[i - 1]
          val cur = pts[i]
          // Curve through the midpoints so a quick drag does not look jagged.
          path.quadTo(prev.x * w, prev.y * h, (prev.x + cur.x) / 2f * w, (prev.y + cur.y) / 2f * h)
        }
        val last = pts.last()
        path.lineTo(last.x * w, last.y * h)
        canvas.drawPath(path, paint)
      }
    }
  }

  private fun drawText(canvas: Canvas, width: Int, height: Int, edits: PhotoEdits) {
    val overlay = edits.text?.takeIf { it.text.isNotBlank() } ?: return
    val size = PhotoEditRules.textSizePx(width, overlay.size)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = size
      color = PhotoEditPalette.textColor(overlay.color).toInt()
      typeface = Typeface.DEFAULT_BOLD
      setShadowLayer(size * 0.12f, 0f, size * 0.04f, SHADOW)
    }
    val maxWidth = PhotoEditRules.maxTextWidthPx(width).toInt().coerceAtLeast(1)
    val wanted = Math.ceil(StaticLayout.getDesiredWidth(overlay.text, paint).toDouble()).toInt().coerceIn(1, maxWidth)
    val layout = StaticLayout.Builder.obtain(overlay.text, 0, overlay.text.length, paint, wanted)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setIncludePad(false)
      .setMaxLines(PhotoEditRules.MAX_TEXT_LINES)
      .setEllipsize(TextUtils.TruncateAt.END)
      .build()
    val at = PhotoEditRules.placeBox(overlay.cx, overlay.cy, layout.width.toFloat(), layout.height.toFloat(), width.toFloat(), height.toFloat())
    canvas.save()
    canvas.translate(at.left, at.top)
    layout.draw(canvas)
    canvas.restore()
  }
}
