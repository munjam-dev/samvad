package com.samvad.core.chat

/** What to do with a chat's stored draft when the user leaves it. */
sealed interface DraftChange {
  /** Leave whatever is stored as it is. */
  data object Keep : DraftChange

  /** Remove the stored draft. */
  data object Clear : DraftChange

  data class Save(val text: String) : DraftChange
}

/**
 * Unsent text kept per chat on this device only. Pure rules: what is worth saving and what the chat
 * list shows. Drafts are never uploaded.
 */
object Drafts {
  /** Same ceiling as a sent message, so a restored draft can always be sent. */
  const val MAX_LENGTH = MessageRules.MAX_LENGTH

  private val WHITESPACE = Regex("\\s+")

  /**
   * The text to store for [raw], or null when there is nothing worth keeping (blank). Surrounding
   * whitespace is trimmed, inner line breaks stay, and the result is cut to [MAX_LENGTH].
   */
  fun normalize(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH).trimEnd() else text
  }

  /**
   * What to do when the user leaves the chat with [composerText] in the composer. While a sent
   * message is being edited the text belongs to that message, so the stored draft is left alone.
   */
  fun onLeave(composerText: String, editing: Boolean): DraftChange {
    if (editing) return DraftChange.Keep
    val text = normalize(composerText) ?: return DraftChange.Clear
    return DraftChange.Save(text)
  }

  /** The one-line text the chat list shows after "Draft:", or null when the chat has no draft. */
  fun rowText(draft: String?): String? = normalize(draft)?.replace(WHITESPACE, " ")
}
