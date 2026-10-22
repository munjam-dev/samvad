package com.samvad.core.gifs

import java.net.URI

/** GIF or sticker. [wire] is the value used by the callables and the message `kind`. */
enum class GifKind(val wire: String) {
  GIF("gif"), STICKER("sticker");

  companion object {
    fun fromWire(value: String?): GifKind? = entries.firstOrNull { it.wire == value }
  }
}

/**
 * One GIF or sticker, as the server returns it and as a message stores it. The image is loaded from the provider's
 * CDN by link; nothing is uploaded to Samvad's storage.
 */
data class GifRef(
  val id: String,
  val title: String,
  val previewUrl: String,
  val url: String,
  val width: Int,
  val height: Int,
  val provider: String = GifRules.PROVIDER,
) {
  /** Width divided by height, kept within a range so a very wide or tall image cannot break the layout. */
  val aspect: Float get() = GifGrid.clampAspect(width, height)
}

/** One page of results; [next] is the opaque cursor for the next page, or null at the end. */
data class GifPage(val items: List<GifRef>, val next: String?)

/**
 * Rules shared with the server (functions/src/lib/gifs.ts) and the Firestore rules. The server is the authority;
 * the app uses the same checks so it never tries to send, or load, a link the rules would refuse.
 */
object GifRules {
  const val PROVIDER = "giphy"
  const val MAX_QUERY_LENGTH = 50
  const val MAX_CAPTION_LENGTH = 1000
  const val MAX_URL_LENGTH = 500
  const val MAX_SIZE = 1000

  /** Debounce before a typed query is sent, so each keystroke is not a request. */
  const val DEBOUNCE_MS = 400L

  private val HOST = Regex("^(media[0-9]{0,2}|i)\\.giphy\\.com$")
  private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
  private val SPACES = Regex("\\s+")
  private val ID = Regex("^[A-Za-z0-9]{3,40}$")

  /** Trims, drops control characters and collapses runs of spaces. Null or blank gives "". */
  fun normalizeQuery(raw: String?): String {
    val q = (raw ?: "").replace(CONTROL, " ").replace(SPACES, " ").trim()
    return if (q.codePointCount(0, q.length) > MAX_QUERY_LENGTH) {
      q.substring(0, q.offsetByCodePoints(0, MAX_QUERY_LENGTH)).trim()
    } else {
      q
    }
  }

  /** True for https links on an allow-listed provider host, with no credentials or port. */
  fun isAllowedUrl(url: String?): Boolean {
    if (url == null || url.isEmpty() || url.length > MAX_URL_LENGTH) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
      uri.host != null && HOST.matches(uri.host) && !uri.path.isNullOrEmpty() && uri.path.length > 1
  }

  /** Whether [ref] could be sent: allow-listed links, a plain id and sane sizes. */
  fun isValid(ref: GifRef): Boolean =
    ref.provider == PROVIDER && ID.matches(ref.id) && isAllowedUrl(ref.url) && isAllowedUrl(ref.previewUrl) &&
      ref.width in 1..MAX_SIZE && ref.height in 1..MAX_SIZE

  /** A GIF may carry a caption; a sticker never does. */
  fun captionFor(kind: GifKind, caption: String): String =
    if (kind == GifKind.STICKER) "" else caption.trim().take(MAX_CAPTION_LENGTH)
}

/** Layout of the picker grid: staggered columns that keep each image's shape. Pure, so it is tested on the JVM. */
object GifGrid {
  const val MIN_ASPECT = 0.5f
  const val MAX_ASPECT = 2.5f
  /** The picker never lays a tile out narrower than this, so touch targets stay at 48dp or more. */
  const val MIN_TILE_DP = 96

  fun clampAspect(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 1f
    return (width.toFloat() / height).coerceIn(MIN_ASPECT, MAX_ASPECT)
  }

  /** Columns for a sheet of [widthDp]: 2 on a compact phone, more as it widens (3 at 600dp, 4 at 840dp). */
  fun columnsFor(widthDp: Int): Int = when {
    widthDp >= 840 -> 4
    widthDp >= 560 -> 3
    else -> 2
  }

  /** Height of a tile [tileWidthDp] wide for an image with this [aspect]. */
  fun tileHeightDp(tileWidthDp: Float, aspect: Float): Float = tileWidthDp / aspect.coerceIn(MIN_ASPECT, MAX_ASPECT)

  /** The widest the image in a chat bubble is shown, and its height for [aspect]. Stickers are a little smaller. */
  fun bubbleSizeDp(ref: GifRef, kind: GifKind): Pair<Int, Int> {
    val maxW = if (kind == GifKind.STICKER) 160 else 240
    val maxH = if (kind == GifKind.STICKER) 160 else 300
    val w = minOf(maxW.toFloat(), maxH * ref.aspect)
    return w.toInt() to (w / ref.aspect).toInt()
  }
}

/** Recent GIFs and favourites. Both stay on this phone; the lists are small and ordered newest first. */
object GifLibrary {
  const val MAX_RECENTS = 30
  const val MAX_FAVOURITES = 100

  /** [item] goes to the front; an earlier copy (same id and kind) is dropped; the list is capped. */
  fun addRecent(recents: List<GifRef>, item: GifRef, max: Int = MAX_RECENTS): List<GifRef> =
    (listOf(item) + recents.filterNot { it.id == item.id }).take(max)

  /** Adds [item] at the front when absent, removes it when present. A full list refuses a new favourite. */
  fun toggleFavourite(favourites: List<GifRef>, item: GifRef, max: Int = MAX_FAVOURITES): List<GifRef> =
    if (favourites.any { it.id == item.id }) favourites.filterNot { it.id == item.id }
    else if (favourites.size >= max) favourites
    else listOf(item) + favourites

  fun isFavourite(favourites: List<GifRef>, id: String): Boolean = favourites.any { it.id == id }

  /** Favourites first, then recents that are not favourites; used as the picker's "your GIFs" view. */
  fun yours(favourites: List<GifRef>, recents: List<GifRef>): List<GifRef> =
    favourites + recents.filter { r -> favourites.none { it.id == r.id } }

  /** A compact one-line encoding for DataStore: fields joined by TAB, items by newline; titles lose tabs and newlines. */
  fun encode(items: List<GifRef>): String = items.joinToString("\n") { r ->
    listOf(r.id, r.title.replace(Regex("[\\t\\n\\r]"), " "), r.previewUrl, r.url, r.width.toString(), r.height.toString()).joinToString("\t")
  }

  /** Reads [encode]'s output, skipping any line that is malformed or no longer passes [GifRules.isValid]. */
  fun decode(text: String?): List<GifRef> {
    if (text.isNullOrBlank()) return emptyList()
    return text.lineSequence().mapNotNull { line ->
      val p = line.split('\t')
      if (p.size != 6) return@mapNotNull null
      val ref = GifRef(p[0], p[1], p[2], p[3], p[4].toIntOrNull() ?: return@mapNotNull null, p[5].toIntOrNull() ?: return@mapNotNull null)
      ref.takeIf(GifRules::isValid)
    }.toList()
  }
}
