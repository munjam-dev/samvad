package com.samvad.core.search

/** One message that matched, with its score and the snippet to show. */
data class SearchHit(val entry: SearchEntry, val score: Double, val snippet: Snippet)

/** The hits in one chat. [hits] are best first; [total] counts all hits in the chat, shown or not. */
data class ChatHits(val conversationId: String, val hits: List<SearchHit>, val total: Int, val bestScore: Double)

/**
 * Ranking of what the index returned. The index finds messages that contain every word (as a word start); this
 * re-checks that, so the result never depends on index quirks, scores what is left and builds the snippets.
 */
object SearchRanking {
  private const val DAY_MS = 24L * 60 * 60 * 1000

  /**
   * Hits for [query] among [candidates], best first. Order: whole words and exact phrases beat prefixes, newer beats older.
   * Entries that expired, or that fail the filter, are dropped.
   */
  fun rank(
    candidates: List<SearchEntry>,
    query: SearchQuery,
    filter: SearchFilter,
    nowMs: Long,
    maxHits: Int = SearchPolicy.MAX_CANDIDATES,
  ): List<SearchHit> {
    if (query.isEmpty) return emptyList()
    val phrase = query.terms.joinToString(" ")
    return candidates.asSequence()
      .filter { filter.accepts(it.flags) && !SearchEntries.isExpired(it, nowMs) }
      .mapNotNull { entry -> score(entry, query, phrase, nowMs)?.let { SearchHit(entry, it, Snippets.build(entry.text, query)) } }
      .sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.entry.createdAt }.thenBy { it.entry.messageId })
      .take(maxHits)
      .toList()
  }

  private fun score(entry: SearchEntry, query: SearchQuery, phrase: String, nowMs: Long): Double? {
    val tokens = Tokenizer.tokens(entry.text)
    if (tokens.isEmpty()) return null
    var whole = 0
    for (term in query.terms) {
      val exact = tokens.any { it.norm == term }
      if (!exact && tokens.none { it.norm.startsWith(term) }) return null // every word must be there
      if (exact) whole++
    }
    val words = tokens.joinToString(" ") { it.norm }
    var score = 100.0
    score += 30.0 * whole / query.terms.size
    if (query.terms.size > 1 && " $words ".contains(" $phrase ")) score += 25.0
    else if (query.terms.size > 1 && words.contains(phrase)) score += 12.0
    val ageDays = ((nowMs - entry.createdAt).coerceAtLeast(0L)).toDouble() / DAY_MS
    score += 20.0 / (1.0 + ageDays / 30.0)
    return score
  }

  /** Groups [hits] by chat. Chats are ordered by their best hit; hits inside a chat keep their order. */
  fun groupByChat(hits: List<SearchHit>): List<ChatHits> =
    hits.groupBy { it.entry.conversationId }
      .map { (cid, list) -> ChatHits(cid, list, list.size, list.maxOf { it.score }) }
      .sortedWith(compareByDescending<ChatHits> { it.bestScore }.thenBy { it.conversationId })
}
