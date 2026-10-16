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
  /** Group photo (Storage path); people's photos come from their profiles. */
  val photoPath: String? = null,
  /** Unsent text kept on this device (one line); when set, the row shows it instead of the last message. */
  val draft: String? = null,
)

object ChatList {
  const val DELETED_ACCOUNT = "Deleted account"
  const val DELETED_MESSAGE = "Message deleted"
  const val EXPIRED_MESSAGE = "This message has disappeared"
  const val NEW_CHAT = "Say hello 👋"
  const val GROUP = "Group"

  fun build(
    me: String,
    conversations: List<Conversation>,
    memberStates: Map<String, MemberState>,
    profiles: Map<String, Profile>,
    filter: ChatFilter,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Groups: unread counts computed on the device (the server keeps no per-member counter). */
    unreadOverride: Map<String, Int> = emptyMap(),
    /** Unsent text per conversation id, kept on this device only. */
    drafts: Map<String, String> = emptyMap(),
  ): List<ChatRow> = conversations
    .mapNotNull { c ->
      val state = memberStates[c.id] ?: MemberState(me)
      if (state.archived) return@mapNotNull null
      val other = if (c.isGroup) null else ConversationIds.otherMember(c, me)
      val title = if (c.isGroup) c.name?.takeIf { it.isNotBlank() } ?: GROUP else other?.let { profiles[it]?.displayName } ?: DELETED_ACCOUNT
      val last = c.lastMessage
      val mine = last?.senderId == me
      val nameOf = { uid: String -> profiles[uid]?.displayName ?: DELETED_ACCOUNT }
      val system = last?.system
      val body = when {
        last == null -> NEW_CHAT
        system != null -> SystemText.describe(system, last.senderId, me, nameOf)
        Disappearing.isExpired(last.expiresAt, nowMs) -> EXPIRED_MESSAGE
        last.preview.isEmpty() -> DELETED_MESSAGE
        else -> last.preview
      }
      val preview = when {
        last == null || system != null -> body
        mine -> "You: $body"
        c.isGroup -> "${nameOf(last.senderId)}: $body"
        else -> body
      }
      ChatRow(
        conversationId = c.id,
        title = title,
        initials = initials(title),
        otherUid = other,
        preview = preview,
        previewIsMine = mine,
        timeLabel = TimeLabels.chatListTime(last?.createdAt ?: c.lastMessageAt, nowMs, zone),
        sortKey = last?.createdAt ?: c.lastMessageAt,
        unreadCount = unreadOverride[c.id] ?: state.unreadCount,
        pinned = state.pinned,
        muted = state.muted,
        isGroup = c.isGroup,
        lastMessageMine = mine,
        photoPath = if (c.isGroup) c.photoPath else null,
        draft = Drafts.rowText(drafts[c.id]),
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
