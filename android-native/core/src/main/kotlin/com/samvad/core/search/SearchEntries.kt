package com.samvad.core.search

import com.samvad.core.chat.Message
import com.samvad.core.safety.ScamShield

/** Which part of the chat a message belongs to, for the Photos, Documents and Links filters. */
enum class SearchFilter(val mask: Int) {
  ALL(0), PHOTOS(SearchEntry.PHOTO), DOCUMENTS(SearchEntry.DOCUMENT), LINKS(SearchEntry.LINK);

  fun accepts(flags: Int): Boolean = this == ALL || (flags and mask) != 0
}

/**
 * What the on-device index keeps about one message the phone has seen: the words to search and enough to show
 * a result. Voice notes and media without a caption have no words, so they are not kept. Nothing here leaves the phone.
 */
data class SearchEntry(
  val conversationId: String,
  val messageId: String,
  val senderId: String,
  val createdAt: Long,
  /** When the chat's disappearing-message timer removes the message; null when it stays. */
  val expiresAt: Long?,
  val kind: String,
  /** [PHOTO], [DOCUMENT] and [LINK] bits. */
  val flags: Int,
  /** The searchable text as written (shown in the result snippet). */
  val text: String,
) {
  companion object {
    const val PHOTO = 1
    const val DOCUMENT = 2
    const val LINK = 4
  }
}

object SearchEntries {
  /** Longest text kept per message. Longer messages are cut, the rest is simply not searchable. */
  const val MAX_CHARS = 1000

  /** The entry for [message], or null when there is nothing to search (deleted, group event, voice, no words). */
  fun from(conversationId: String, message: Message): SearchEntry? {
    if (message.deleted || message.pending || message.failed) return null
    val parts = when (message.kind) {
      "text" -> listOf(message.text)
      "image", "video" -> listOf(message.text)
      "file" -> listOf(message.media?.name.orEmpty(), message.text)
      "contact" -> message.contact?.let { c -> listOf(c.name, c.organisation.orEmpty(), c.phones.joinToString(" ")) }.orEmpty()
      "poll" -> message.poll?.let { p -> listOf(p.question) + p.options }.orEmpty()
      else -> emptyList()
    }
    val text = parts.filter { it.isNotBlank() }.joinToString("\n").trim().take(MAX_CHARS)
    if (Tokenizer.terms(text).isEmpty()) return null
    var flags = 0
    if (message.kind == "image" || message.kind == "video") flags = flags or SearchEntry.PHOTO
    if (message.kind == "file") flags = flags or SearchEntry.DOCUMENT
    if (message.kind == "text" || message.kind == "image" || message.kind == "video" || message.kind == "file") {
      if (ScamShield.extractLinks(message.text).isNotEmpty()) flags = flags or SearchEntry.LINK
    }
    return SearchEntry(conversationId, message.id, message.senderId, message.createdAt, message.expiresAt, message.kind, flags, text)
  }

  /** True when the message has left the chat because of the disappearing-message timer. */
  fun isExpired(entry: SearchEntry, nowMs: Long): Boolean = entry.expiresAt != null && entry.expiresAt <= nowMs
}
