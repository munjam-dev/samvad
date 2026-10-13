package com.samvad.core.chat

/** Domain models shared by the data layer and the UI. Times are epoch milliseconds. */

data class Profile(val uid: String, val displayName: String, val about: String = "")

data class LastMessage(
  val id: String,
  val senderId: String,
  val preview: String,
  val kind: String,
  val createdAt: Long,
)

data class Conversation(
  val id: String,
  val members: List<String>,
  val lastMessage: LastMessage?,
  val lastMessageAt: Long,
  val isGroup: Boolean = false,
)

data class MemberState(
  val uid: String,
  val lastReadAt: Long? = null,
  val lastDeliveredAt: Long? = null,
  val unreadCount: Int = 0,
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
}

sealed interface TextValidation {
  data class Ok(val text: String) : TextValidation
  data object Empty : TextValidation
  data class TooLong(val length: Int) : TextValidation
}

object MessageRules {
  const val MAX_LENGTH = 4096
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

  fun canEdit(message: Message, me: String, nowMs: Long): Boolean =
    message.senderId == me && !message.deleted && !message.pending && nowMs - message.createdAt < EDIT_WINDOW_MS

  /** Delivery state of one of my messages, from the other member's read/delivered marks. */
  fun deliveryState(message: Message, other: MemberState?): DeliveryState = when {
    message.failed -> DeliveryState.FAILED
    message.pending -> DeliveryState.SENDING
    other?.lastReadAt != null && other.lastReadAt >= message.createdAt -> DeliveryState.READ
    other?.lastDeliveredAt != null && other.lastDeliveredAt >= message.createdAt -> DeliveryState.DELIVERED
    else -> DeliveryState.SENT
  }
}
