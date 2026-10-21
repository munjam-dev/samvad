package com.samvad.core.search

import java.text.Normalizer

/** One word found in a text: where it sits in the original text and its folded form. */
data class Token(val start: Int, val end: Int, val norm: String)

/**
 * Splits text into searchable words. Works for English and for Hindi, Telugu and Tamil: letters, digits and the
 * combining marks that carry vowel signs stay inside a word, and everything else (spaces, punctuation, emoji)
 * ends it. Words are folded to lower case, and accents on Latin letters are removed ("Café" matches "cafe").
 *
 * The same folding is used for the stored text and for the query, so both sides always agree. Folding keeps one
 * character for one character, so positions in the folded text are positions in the original (snippets rely on this).
 */
object Tokenizer {
  private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)
  private const val ZWNJ = '‌'
  private const val ZWJ = '‍'

  private fun isWordChar(c: Char): Boolean =
    c.isLetterOrDigit() || c.category in MARKS || c == ZWNJ || c == ZWJ

  /** Lower case, and for accented Latin letters the plain letter. Never changes the length. */
  fun fold(c: Char): Char {
    val lower = Character.toLowerCase(c)
    if (lower.code !in 0xC0..0x24F) return lower
    val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
    return if (decomposed.length > 1 && decomposed.drop(1).all { it.code in 0x300..0x36F }) decomposed[0] else lower
  }

  fun fold(text: String): String = buildString(text.length) { text.forEach { append(fold(it)) } }

  /** The words of [text] with their positions, in order. Words without a letter or digit are skipped. */
  fun tokens(text: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    while (i < text.length) {
      if (!isWordChar(text[i])) { i++; continue }
      val start = i
      while (i < text.length && isWordChar(text[i])) i++
      val raw = text.substring(start, i)
      if (raw.none { it.isLetterOrDigit() }) continue
      // Joiners stay in the position range but not in the folded word, so "క్ష" typed with or without them matches.
      val norm = fold(raw).filter { it != ZWNJ && it != ZWJ }
      if (norm.isNotEmpty()) out += Token(start, i, norm)
    }
    return out
  }

  /** The folded words of [text], in order, repeats removed. */
  fun terms(text: String): List<String> = tokens(text).map { it.norm }.distinct()

  /** [text] as folded words separated by single spaces: what the full-text index stores. */
  fun indexForm(text: String): String = tokens(text).joinToString(" ") { it.norm }
}
