package com.samvad.core.chat

import java.text.Normalizer

/**
 * Searching inside one chat, on the device. Nothing is sent to a server: it looks only at the messages
 * already loaded. Matching ignores case and accents, so "cafe" finds "Café".
 */
object ChatSearch {
  const val MIN_QUERY = 2

  private val accents = Regex("""\p{InCombiningDiacriticalMarks}+""")

  fun fold(text: String): String =
    accents.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

  /** Keys of the bubbles whose text contains [query], in the list's order (newest first). */
  fun matches(items: List<TimelineItem>, query: String): List<String> {
    val q = fold(query.trim())
    if (q.length < MIN_QUERY) return emptyList()
    return items.mapNotNull { item ->
      val m = (item as? TimelineItem.Bubble)?.message ?: return@mapNotNull null
      if (m.deleted) return@mapNotNull null
      val haystack = fold(listOfNotNull(m.text, m.media?.name, m.contact?.name, m.contact?.organisation).joinToString(" "))
      if (haystack.contains(q)) item.key else null
    }
  }

  /** The next match index when stepping through [count] results, wrapping at both ends. */
  fun step(current: Int, count: Int, forward: Boolean): Int =
    if (count <= 0) 0 else (current + if (forward) 1 else -1).mod(count)
}
