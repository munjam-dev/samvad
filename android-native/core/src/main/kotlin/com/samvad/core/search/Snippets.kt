package com.samvad.core.search

/** A short piece of a message around the first match. [highlights] are character ranges inside [text]. */
data class Snippet(val text: String, val highlights: List<IntRange>)

object Snippets {
  const val DEFAULT_MAX = 110
  private const val LEAD = 28
  private const val ELLIPSIS = "…"

  /** Tokens of [text] that start with one of the query words. */
  internal fun matching(text: String, query: SearchQuery): List<Token> =
    Tokenizer.tokens(text).filter { t -> query.terms.any { t.norm.startsWith(it) } }

  fun build(text: String, query: SearchQuery, maxChars: Int = DEFAULT_MAX): Snippet {
    val flat = text.map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("").trim()
    // Trimming at the start shifts positions, so work on the trimmed string throughout.
    val hits = matching(flat, query)
    if (flat.length <= maxChars) return Snippet(flat, hits.map { it.start until it.end })

    val first = hits.firstOrNull()?.start ?: 0
    var from = (first - LEAD).coerceAtLeast(0)
    // Begin at a word start, so the snippet does not open in the middle of a word.
    if (from > 0) {
      val space = flat.lastIndexOf(' ', from)
      from = if (space >= 0 && from - space <= LEAD) space + 1 else from
    }
    var to = (from + maxChars).coerceAtMost(flat.length)
    if (to < flat.length) {
      val space = flat.lastIndexOf(' ', to)
      if (space > from + maxChars / 2) to = space
    }
    val prefix = if (from > 0) ELLIPSIS else ""
    val suffix = if (to < flat.length) ELLIPSIS else ""
    val body = flat.substring(from, to).trimEnd()
    val shift = prefix.length - from
    val ranges = hits.filter { it.start >= from && it.end <= from + body.length }.map { (it.start + shift) until (it.end + shift) }
    return Snippet(prefix + body + suffix, ranges)
  }
}
