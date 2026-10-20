package com.samvad.core.chat

import com.samvad.core.media.Media

/** Domain models shared by the data layer and the UI. Times are epoch milliseconds. */

/** [photoPath] is a Storage path (avatars/{uid}/…) readable through the SDK, never a public URL. */
data class Profile(val uid: String, val displayName: String, val about: String = "", val photoPath: String? = null, val username: String? = null)

data class LastMessage(
  val id: String,
  val senderId: String,
  val preview: String,
  val kind: String,
  val createdAt: Long,
  /** Set when the last message is a group event ("added", "left"…). */
  val system: SystemInfo? = null,
  /** When the disappearing-message timer will remove it; null when none. */
  val expiresAt: Long? = null,
)

data class Conversation(
  val id: String,
  val members: List<String>,
  val lastMessage: LastMessage?,
  val lastMessageAt: Long,
  val isGroup: Boolean = false,
  /** Groups only (Phase 2). */
  val name: String? = null,
  val admins: List<String> = emptyList(),
  val description: String = "",
  val photoPath: String? = null,
  /** Disappearing-message timer in seconds; 0 means off. Changed only through the server. */
  val disappearAfterSec: Long = 0L,
  /** Pinned messages, set only by the server. Use [PinnedMessages.ordered] before showing them. */
  val pinnedMessages: List<Pin> = emptyList(),
)

data class MemberState(
  val uid: String,
  val lastReadAt: Long? = null,
  val lastDeliveredAt: Long? = null,
  val unreadCount: Int = 0,
  /** Groups: when this member joined; earlier messages are not readable. */
  val joinedAt: Long? = null,
  val muted: Boolean = false,
  val pinned: Boolean = false,
  val archived: Boolean = false,
)

data class Message(
  val id: String,
  val senderId: String,
  val text: String,
  val createdAt: Long,
  val replyTo: String? = null,
  val editedAt: Long? = null,
  val deleted: Boolean = false,
  val reactions: Map<String, String> = emptyMap(),
  /** True while the write is only in the local cache (offline or in flight). */
  val pending: Boolean = false,
  /** True when the local write was rejected and needs a retry. */
  val failed: Boolean = false,
  /** "text", "image", "file", "voice", "video", "contact", "poll" or "system". */
  val kind: String = "text",
  val media: Media? = null,
  /** A copy forwarded from another chat (Phase 1.6). */
  val forwarded: Boolean = false,
  /** Phase 2: @mentioned members, a server-written group event, or a poll. */
  val mentions: List<String> = emptyList(),
  val system: SystemInfo? = null,
  /** Disappearing messages: when the server will delete it; null when the chat has no timer. */
  val expiresAt: Long? = null,
  val poll: Poll? = null,
  /** A shared contact card (kind "contact"). */
  val contact: ContactCard? = null,
)

enum class DeliveryState { SENDING, SENT, DELIVERED, READ, FAILED }

enum class ChatFilter { ALL, UNREAD, GROUPS, FAVORITES }

object ConversationIds {
  /** Direct conversations are keyed by the two sorted UIDs, so a pair has exactly one chat. */
  fun direct(a: String, b: String): String {
    require(a != b) { "A direct conversation needs two different users." }
    return if (a < b) "${a}_$b" else "${b}_$a"
  }

  fun otherMember(conversation: Conversation, me: String): String? = conversation.members.firstOrNull { it != me }

  private val UID = Regex("^[A-Za-z0-9]{1,128}$")

  /**
   * Whether [conversationId] is a well-formed direct chat ID that includes [otherUid]. Used to
   * validate chat links arriving in intents before navigating (rules still guard the data).
   */
  private val GROUP_ID = Regex("^[A-Za-z0-9]{20}$")

  /** Group IDs are Firestore auto IDs (20 letters and digits). */
  fun isGroupId(conversationId: String): Boolean = GROUP_ID.matches(conversationId)

  fun isDirectWith(conversationId: String, otherUid: String): Boolean {
    val parts = conversationId.split('_')
    return parts.size == 2 && parts[0] < parts[1] && parts.all(UID::matches) && otherUid in parts
  }
}

sealed interface TextValidation {
  data class Ok(val text: String) : TextValidation
  data object Empty : TextValidation
  data class TooLong(val length: Int) : TextValidation
}

object MessageRules {
  const val MAX_LENGTH = 4096
  /** Like WhatsApp, a message can be forwarded to at most this many chats at once. */
  const val MAX_FORWARD_TARGETS = 5
  const val EDIT_WINDOW_MS = 15 * 60 * 1000L

  /** Trims trailing whitespace and enforces the server's 1–4096 non-blank rule. */
  fun validate(raw: String): TextValidation {
    val text = raw.trimEnd()
    return when {
      text.isBlank() -> TextValidation.Empty
      text.length > MAX_LENGTH -> TextValidation.TooLong(text.length)
      else -> TextValidation.Ok(text)
    }
  }

  fun canForward(message: Message): Boolean = !message.deleted && !message.pending && !message.failed

  fun canEdit(message: Message, me: String, nowMs: Long): Boolean =
    message.kind == "text" && message.senderId == me && !message.deleted && !message.pending &&
      nowMs - message.createdAt < EDIT_WINDOW_MS

  /**
   * Delivery state of one of my messages, from the other member's read/delivered marks. With my
   * read receipts off I do not see theirs either ([showRead] false caps the state at delivered).
   */
  fun deliveryState(message: Message, other: MemberState?, showRead: Boolean = true): DeliveryState = when {
    message.failed -> DeliveryState.FAILED
    message.pending -> DeliveryState.SENDING
    showRead && other?.lastReadAt != null && other.lastReadAt >= message.createdAt -> DeliveryState.READ
    other?.lastDeliveredAt != null && other.lastDeliveredAt >= message.createdAt -> DeliveryState.DELIVERED
    else -> DeliveryState.SENT
  }
}
