package com.samvad.core.chat

import com.samvad.core.time.TimeLabels
import java.time.ZoneId

/** One row of the chat list, ready to render. */
data class ChatRow(
  val conversationId: String,
  val title: String,
  val initials: String,
  val otherUid: String?,
  val preview: String,
  val previewIsMine: Boolean,
  val timeLabel: String,
  val sortKey: Long,
  val unreadCount: Int,
  val pinned: Boolean,
  val muted: Boolean,
  val isGroup: Boolean,
  val lastMessageMine: Boolean,
)

object ChatList {
  const val DELETED_ACCOUNT = "Deleted account"
  const val DELETED_MESSAGE = "Message deleted"
  const val NEW_CHAT = "Say hello 👋"

  fun build(
    me: String,
    conversations: List<Conversation>,
    memberStates: Map<String, MemberState>,
    profiles: Map<String, Profile>,
    filter: ChatFilter,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
  ): List<ChatRow> = conversations
    .mapNotNull { c ->
      val state = memberStates[c.id] ?: MemberState(me)
      if (state.archived) return@mapNotNull null
      val other = ConversationIds.otherMember(c, me)
      val title = other?.let { profiles[it]?.displayName } ?: DELETED_ACCOUNT
      val last = c.lastMessage
      val mine = last?.senderId == me
      val body = when {
        last == null -> NEW_CHAT
        last.preview.isEmpty() -> DELETED_MESSAGE
        else -> last.preview
      }
      ChatRow(
        conversationId = c.id,
        title = title,
        initials = initials(title),
        otherUid = other,
        preview = if (mine) "You: $body" else body,
        previewIsMine = mine,
        timeLabel = TimeLabels.chatListTime(last?.createdAt ?: c.lastMessageAt, nowMs, zone),
        sortKey = last?.createdAt ?: c.lastMessageAt,
        unreadCount = state.unreadCount,
        pinned = state.pinned,
        muted = state.muted,
        isGroup = c.isGroup,
        lastMessageMine = mine,
      )
    }
    .filter { row ->
      when (filter) {
        ChatFilter.ALL -> true
        ChatFilter.UNREAD -> row.unreadCount > 0
        ChatFilter.GROUPS -> row.isGroup
        ChatFilter.FAVORITES -> row.pinned
      }
    }
    .sortedWith(compareByDescending<ChatRow> { it.pinned }.thenByDescending { it.sortKey })

  fun unreadSummary(rows: List<ChatRow>): UnreadSummary =
    UnreadSummary(messages = rows.sumOf { it.unreadCount }, chats = rows.count { it.unreadCount > 0 })

  fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.first().isLetterOrDigit() }
    return when {
      parts.isEmpty() -> "?"
      parts.size == 1 -> parts[0].take(1).uppercase()
      else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
  }
}

data class UnreadSummary(val messages: Int, val chats: Int) {
  val allCaughtUp: Boolean get() = messages == 0
}
