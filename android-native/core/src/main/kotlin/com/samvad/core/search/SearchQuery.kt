package com.samvad.core.search

/** Limits of the on-device message search. */
object SearchPolicy {
  const val MIN_QUERY_CHARS = 2
  const val MAX_TERMS = 6
  /** The most candidates read from the index before ranking. */
  const val MAX_CANDIDATES = 300
  /** The most messages the index keeps; above this the oldest are dropped. */
  const val MAX_ENTRIES = 10_000
  /** After dropping, the index is brought down to this many, so it is not trimmed on every message. */
  const val TRIM_TO = 9_000
  /** Results shown per chat before "Show more". */
  const val PER_CHAT_SHOWN = 3
}

/** A search typed by the person: folded words, every one of which must appear (as a word start). */
data class SearchQuery(val terms: List<String>) {
  val isEmpty: Boolean get() = terms.isEmpty()

  /**
   * The expression for the full-text index: each word as a prefix, all required. Words only ever contain letters,
   * digits and combining marks, so nothing typed can act as an index operator or break the expression.
   */
  fun matchExpression(): String = terms.joinToString(" ") { "$it*" }

  companion object {
    /** Null when the text is too short to search: under two characters, or no words at all. */
    fun parse(raw: String): SearchQuery? {
      if (raw.trim().length < SearchPolicy.MIN_QUERY_CHARS) return null
      val terms = Tokenizer.terms(raw).take(SearchPolicy.MAX_TERMS)
      if (terms.isEmpty() || terms.sumOf { it.length } < 1) return null
      return SearchQuery(terms)
    }
  }
}
