package com.samvad.messenger.ui.chat

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.samvad.core.design.ChatBackground
import com.samvad.core.design.ChatThemeColors
import com.samvad.core.design.ChatThemes
import com.samvad.messenger.data.ChatThemeRepository
import com.samvad.messenger.data.ChatThemeSettings
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bubble and chip colours for [background]: its theme, or the default theme behind a photo. */
fun chatThemeColors(background: ChatBackground, dark: Boolean): ChatThemeColors = when (background) {
  is ChatBackground.Theme -> ChatThemes.byId(background.id).colors(dark)
  is ChatBackground.Photo -> ChatThemes.DEFAULT.colors(dark)
}

/** Draws what sits behind a chat's messages: a theme with the India doodle, or the user's photo. */
@Composable
fun ChatBackdrop(
  background: ChatBackground,
  settings: ChatThemeSettings,
  repo: ChatThemeRepository,
  dark: Boolean,
  modifier: Modifier = Modifier,
) {
  when (background) {
    is ChatBackground.Theme -> ThemeBackdrop(ChatThemes.byId(background.id).colors(dark), modifier)
    is ChatBackground.Photo -> {
      val maxEdge = with(LocalDensity.current) {
        val c = LocalConfiguration.current
        maxOf(c.screenWidthDp, c.screenHeightDp).dp.roundToPx()
      }
      val file = repo.photoFile(background.slot)
      val photo by produceState<ImageBitmap?>(null, file.path, file.lastModified(), maxEdge) {
        value = withContext(Dispatchers.IO) { decodeFile(file.path, maxEdge) }
      }
      val image = photo
      if (image == null) {
        // Missing file (e.g. app data cleared): fall back quietly to the default theme.
        ThemeBackdrop(ChatThemes.DEFAULT.colors(dark), modifier)
      } else {
        val (fx, fy) = settings.focus[background.slot] ?: (0.5f to 0.5f)
        val dim = settings.dims[background.slot] ?: if (dark) ChatThemes.DEFAULT_DIM_DARK else ChatThemes.DEFAULT_DIM_LIGHT
        Box(modifier) {
          Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(fx * 2 - 1, fy * 2 - 1),
            modifier = Modifier.fillMaxSize(),
          )
          Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim / 100f)))
        }
      }
    }
  }
}

@Composable
private fun ThemeBackdrop(colors: ChatThemeColors, modifier: Modifier) {
  val density = LocalDensity.current
  val doodle = colors.pattern
  // Drawn off the main thread; the plain background shows for the first frame or two.
  val tile by produceState<ImageBitmap?>(null, doodle, density.density) {
    value = doodle?.let { withContext(Dispatchers.Default) { IndiaDoodle.tile(Color(it), density) } }
  }
  Canvas(modifier.background(Color(colors.background))) {
    tile?.let { drawRect(ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated))) }
  }
}

private fun decodeFile(path: String, maxEdge: Int): ImageBitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(path, bounds)
  if (bounds.outWidth <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
  BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/**
 * Samvad's default chat doodle, in the spirit of a hand-drawn India sketchbook: landmarks (Charminar,
 * a Mughal dome, India Gate, a temple gopuram), culture (lotus, diya, kite, kulhad chai, auto-rickshaw,
 * peacock feather, kolam, paisley, tabla, dhol, umbrella, train, palm, mountains, cricket), the first
 * letter of ten Indian scripts, and many small leaves, flowers and sparkles filling the gaps. Drawn
 * once, off the main thread, into a tile that repeats seamlessly. Motifs are 24-unit SVG paths.
 */
internal object IndiaDoodle {
  /** Bigger motifs: places and culture. */
  private val large = listOf(
    // Charminar (Hyderabad)
    "M3 21h18M6 21V11h12v10M10 21v-4a2 2 0 0 1 4 0v4M4 21V8M20 21V8M4 8c-1-1.2-1-2.6 0-4c1 1.4 1 2.8 0 4M20 8c-1-1.2-1-2.6 0-4c1 1.4 1 2.8 0 4M10 11c0-1.8 4-1.8 4 0M7 14h2M15 14h2",
    // Mughal dome
    "M4 21h16M6 21v-7h12v7M9 14c-1-2 0-4 3-6c3 2 4 4 3 6M12 8V5M10 21v-3a2 2 0 0 1 4 0v3M3.5 21V11M20.5 21V11M3 11h1M20 11h1",
    // India Gate
    "M4 21V5h16v16M4 8h16M9 21v-7a3 3 0 0 1 6 0v7M7 5V3.5h10V5M5.5 11h1.5M17 11h1.5",
    // Temple gopuram
    "M6 21v-3h12v3M7 18l1-4h8l1 4M8.6 14l0.8-3.5h5.2l0.8 3.5M10 10.5l0.6-3h2.8l0.6 3M11.2 7.5l0.4-2h0.8l0.4 2M12 5.5V4M11 21v-2h2v2",
    // Palm tree
    "M12 22c0-5 1-9 0-13M12 9c-2-2-6-2-8 0M12 9c2-2 6-2 8 0M12 9c-1-3-4-5-7-5M12 9c1-3 4-5 7-5M12 9c0-3 1-5 2-6M7 22h10",
    // Mountains
    "M2 19l6-9 4 5 3-4 7 8zM7 11.5l1 1.5 1-1M14 12l1 1 1-1",
    // Lotus
    "M12 18c-3-2-3-7 0-11c3 4 3 9 0 11zM12 18c-4 0-7-3-8-6c3 0 6 2 8 6zM12 18c4 0 7-3 8-6c-3 0-6 2-8 6zM5 20.5h14",
    // Diya
    "M3 14c2 4 16 4 18 0zM12 11c-1.5-1.5-1.5-3.5 0-6c1.5 2.5 1.5 4.5 0 6zM8 15.5h8",
    // Kite
    "M12 2l6 8-6 9-6-9zM6 10h12M12 2v17M12 19c1.5 1 -1.5 2 0 3.5",
    // Kulhad chai
    "M6 10h12l-1.5 10h-9zM9 7c0-1 1-1 1-2M12 7c0-1 1-1 1-2M15 7c0-1 1-1 1-2M7 13h10",
    // Auto-rickshaw
    "M3.5 17V11c0-3 2-5 6-5h5l4.5 5v6zM3.5 11h15.5M10 6v5M5 18.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0M15 18.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    // Peacock feather
    "M12 22V10M12 10c-3.5-1-5-4-3-7.5c1.5-0.6 4.5-0.6 6 0c2 3.5 0.5 6.5-3 7.5zM10.5 6a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0M12 14l-3-2M12 17l3-2M12 20l-3-2",
    // Kolam / rangoli
    "M12 4c2 3 2 5 0 8c-2-3-2-5 0-8zM12 20c-2-3-2-5 0-8c2 3 2 5 0 8zM4 12c3-2 5-2 8 0c-3 2-5 2-8 0zM20 12c-3 2-5 2-8 0c3-2 5-2 8 0zM12 1.6h0.01M12 22.4h0.01M1.6 12h0.01M22.4 12h0.01",
    // Paisley (mango)
    "M14 3c4 2 6 7 3 12c-3 5-10 6-11 2c-1-3 2-5 5-5c3 0 5-3 3-6c-1-2 0-3 0-3zM10 16c1 0 2-1 2-2M15 8c1 1 1 3 0 4",
    // Tabla
    "M4 9a4 2 0 1 0 8 0a4 2 0 1 0 -8 0M4 9l1 9c0 1.2 6 1.2 6 0l1-9M13.5 11a3.5 1.5 0 1 0 7 0a3.5 1.5 0 1 0 -7 0M13.5 11l0.5 8c0 1 6 1 6 0l0.5-8",
    // Dhol
    "M5 8a7 2.5 0 1 0 14 0a7 2.5 0 1 0 -14 0M5 8v8c0 3.3 14 3.3 14 0V8M5 9l14 7M19 9L5 16",
    // Umbrella
    "M3 12a9 7 0 0 1 18 0zM12 12v7c0 1.5-2 1.5-2 0M12 5V3M7.5 12c0-2.5 1-5 2.5-6.5M16.5 12c0-2.5-1-5-2.5-6.5",
    // Train
    "M5 17V7c0-2 2-3 4-3h6c2 0 4 1 4 3v10zM5 12h14M8 7h3v3H8zM13 7h3v3h-3zM7 20l2-3M17 20l-2-3M8 15h0.01M16 15h0.01",
    // Cricket bat and ball
    "M15 3l3 3-9 9-3-3zM6 12l-3 3M17 18.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
  )

  /** Small fillers between the bigger motifs, as in a hand-drawn pattern. */
  private val small = listOf(
    // Leaf
    "M5 19C5 10 11 5 19 5C19 13 14 19 5 19zM5 19L14 10",
    // Sprig
    "M12 22V5M12 9c-2-2-5-2-6 0c2 2 4 2 6 0zM12 9c2-2 5-2 6 0c-2 2-4 2-6 0zM12 15c-2-2-5-2-6 0c2 2 4 2 6 0zM12 15c2-2 5-2 6 0c-2 2-4 2-6 0z",
    // Four-petal flower
    "M12 10a2.5 2.5 0 1 1 0-5a2.5 2.5 0 1 1 0 5zM14 12a2.5 2.5 0 1 1 5 0a2.5 2.5 0 1 1 -5 0zM12 14a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5zM10 12a2.5 2.5 0 1 1 -5 0a2.5 2.5 0 1 1 5 0zM11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
    // Sparkle
    "M12 4c0.8 4.4 3.6 7.2 8 8c-4.4 0.8-7.2 3.6-8 8c-0.8-4.4-3.6-7.2-8-8c4.4-0.8 7.2-3.6 8-8z",
    // Swirl with dots
    "M3 13c2-3 5-3 6 0c1 3 4 3 6 0M18 12h0.01M21 12h0.01",
    // Heart-shaped leaf
    "M12 20c-5-3-8-6-8-10a4 4 0 0 1 8-1a4 4 0 0 1 8 1c0 4-3 7-8 10zM12 9v11",
  )

  /** अ Hindi · అ Telugu · அ Tamil · অ Bengali · ಅ Kannada · അ Malayalam · ਅ Punjabi · અ Gujarati · ଅ Odia · ا Urdu */
  private val letters = listOf("अ", "అ", "அ", "অ", "ಅ", "അ", "ਅ", "અ", "ଅ", "ا")

  private val largePaths: List<Path> by lazy { large.map { PathParser().parsePathString(it).toPath() } }
  private val smallPaths: List<Path> by lazy { small.map { PathParser().parsePathString(it).toPath() } }

  private const val TILE_DP = 460
  private const val GRID = 8

  private sealed interface Mark
  private class Big(val path: Path) : Mark
  private class Filler(val path: Path) : Mark
  private class Letter(val text: String) : Mark

  /**
   * One repeating tile, deterministic so the pattern never shifts between screens or launches. Odd
   * rows are offset by half a cell and every mark is also drawn wrapped around the tile edges, so
   * the repeat has no visible rows or seams. Safe to call off the main thread.
   */
  fun tile(color: Color, density: Density): ImageBitmap {
    val px = with(density) { TILE_DP.dp.roundToPx() }
    val bitmap = ImageBitmap(px, px)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val cell = px / GRID.toFloat()
    val random = Random(1947)
    // Roughly a third landmarks and culture, half small fillers, the rest script letters.
    val pool: List<Mark> = buildList {
      repeat(2) { largePaths.forEach { add(Big(it)) } }
      repeat(7) { smallPaths.forEach { add(Filler(it)) } }
      repeat(2) { letters.forEach { add(Letter(it)) } }
    }.shuffled(random)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      this.color = color.toArgb()
      typeface = Typeface.DEFAULT
      textAlign = Paint.Align.CENTER
    }
    val stroke = with(density) { 1.2.dp.toPx() }
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat())) {
      for (index in 0 until GRID * GRID) {
        val mark = pool[index % pool.size]
        val col = index % GRID
        val row = index / GRID
        val baseX = col * cell + cell / 2 + if (row % 2 == 1) cell / 2 else 0f
        val cx = baseX + (random.nextFloat() - 0.5f) * cell * 0.45f
        val cy = row * cell + cell / 2 + (random.nextFloat() - 0.5f) * cell * 0.45f
        val angle = (random.nextFloat() - 0.5f) * 44f
        val sizeDp = when (mark) {
          is Big -> 32 + random.nextInt(11)
          is Filler -> 12 + random.nextInt(7)
          is Letter -> 16 + random.nextInt(7)
        }
        val sizePx = with(density) { sizeDp.dp.toPx() }
        // Draw at every wrapped position that can touch the tile, so edges join seamlessly.
        for (dx in listOf(-px, 0, px)) for (dy in listOf(-px, 0, px)) {
          val x = cx + dx
          val y = cy + dy
          if (x < -sizePx || x > px + sizePx || y < -sizePx || y > px + sizePx) continue
          when (mark) {
            is Letter -> {
              textPaint.textSize = sizePx
              rotate(angle, pivot = Offset(x, y)) {
                drawContext.canvas.nativeCanvas.drawText(mark.text, x, y + sizePx / 3, textPaint)
              }
            }
            is Big, is Filler -> {
              val path = if (mark is Big) mark.path else (mark as Filler).path
              val unit = sizePx / 24f
              translate(x - sizePx / 2, y - sizePx / 2) {
                rotate(angle, pivot = Offset(sizePx / 2, sizePx / 2)) {
                  scale(unit, unit, pivot = Offset.Zero) {
                    drawPath(path, color, style = Stroke(width = stroke / unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                  }
                }
              }
            }
          }
        }
      }
    }
    return bitmap
  }
}
