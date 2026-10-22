package com.samvad.core.chat

/** One pinned message as stored on the conversation document. Times are epoch milliseconds. */
data class Pin(val messageId: String, val pinnedBy: String, val pinnedAt: Long)

/** What the banner says about the pinned message. The screen turns this into words in the user's language. */
sealed interface PinnedPreview {
  /** The message text on one line. */
  data class Text(val text: String) : PinnedPreview
  data class Photo(val caption: String) : PinnedPreview
  data class File(val name: String) : PinnedPreview
  data object Voice : PinnedPreview
  data class Poll(val question: String) : PinnedPreview
  data class Video(val caption: String) : PinnedPreview
  data class Contact(val name: String) : PinnedPreview
  data class Gif(val caption: String) : PinnedPreview
  data object Sticker : PinnedPreview
  /** The message is not on this device yet, or this person cannot read it. */
  data object Unavailable : PinnedPreview
}

/** The banner at the top of a conversation. [position] is 1-based, oldest pin first; [total] counts the pins shown. */
data class PinnedBanner(val messageId: String, val preview: PinnedPreview, val position: Int, val total: Int, val index: Int) {
  val hasMany: Boolean get() = total > 1
}

/** Why a pin cannot be added right now. */
enum class PinBlock { ALREADY_PINNED, LIMIT_REACHED }

/**
 * Pinned messages. The server decides who may pin and enforces the limit; the app uses the same rules to hide
 * entries that would only be refused, and to decide what the banner shows. Direct chats: either person may
 * pin. Groups: admins only. Nothing here changes who can read a message.
 */
object PinnedMessages {
  /** At most this many messages can be pinned in one chat. */
  const val MAX = 3
  private const val PREVIEW_CHARS = 140

  /** Oldest pin first, each message once. A repeated message keeps its earliest pin. */
  fun ordered(pins: List<Pin>): List<Pin> =
    pins.sortedWith(compareBy<Pin> { it.pinnedAt }.thenBy { it.messageId }).distinctBy { it.messageId }

  fun isPinned(pins: List<Pin>, messageId: String): Boolean = pins.any { it.messageId == messageId }

  /** Direct chats: either person. Groups: admins. */
  fun canChange(isGroup: Boolean, isAdmin: Boolean): Boolean = !isGroup || isAdmin

  /** Messages that cannot be pinned: removed, group events, and ones still being sent. */
  fun isPinnable(message: Message): Boolean =
    !message.deleted && !message.pending && !message.failed && message.kind != "system"

  /** Null when [messageId] can be added. */
  fun blockedReason(pins: List<Pin>, messageId: String): PinBlock? = when {
    isPinned(pins, messageId) -> PinBlock.ALREADY_PINNED
    ordered(pins).size >= MAX -> PinBlock.LIMIT_REACHED
    else -> null
  }

  /** A short single-line description of [message] for the banner. */
  fun preview(message: Message?): PinnedPreview {
    if (message == null || message.deleted) return PinnedPreview.Unavailable
    return when (message.kind) {
      "text" -> PinnedPreview.Text(oneLine(message.text))
      "image" -> PinnedPreview.Photo(oneLine(message.text))
      "file" -> PinnedPreview.File(message.media?.name.orEmpty())
      "voice" -> PinnedPreview.Voice
      "poll" -> PinnedPreview.Poll(oneLine(message.poll?.question.orEmpty()))
      "video" -> PinnedPreview.Video(oneLine(message.text))
      "contact" -> PinnedPreview.Contact(oneLine(message.contact?.name.orEmpty()))
      "gif" -> PinnedPreview.Gif(oneLine(message.text))
      "sticker" -> PinnedPreview.Sticker
      else -> PinnedPreview.Unavailable
    }
  }

  /**
   * The banner for [pins], or null when nothing is pinned. [messages] holds the messages known on this device;
   * a pin whose message is known to be deleted or expired is left out (the server removes it a little later).
   * [selected] is the index into the shown pins that was last jumped to; null or out of range shows the newest.
   */
  fun banner(pins: List<Pin>, messages: Map<String, Message?>, nowMs: Long, selected: Int? = null): PinnedBanner? {
    val shown = ordered(pins).filter { pin ->
      val m = messages[pin.messageId]
      m == null || (!m.deleted && !Disappearing.isExpired(m.expiresAt, nowMs))
    }
    if (shown.isEmpty()) return null
    val index = selected?.takeIf { it in shown.indices } ?: shown.lastIndex
    val pin = shown[index]
    return PinnedBanner(pin.messageId, preview(messages[pin.messageId]), index + 1, shown.size, index)
  }

  /** Tapping the banner steps back to the next older pin, and wraps round to the newest. */
  fun nextIndex(index: Int, total: Int): Int = if (total <= 1) 0 else (index - 1 + total) % total

  private fun oneLine(text: String): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS).trimEnd() + "…"
  }
}
