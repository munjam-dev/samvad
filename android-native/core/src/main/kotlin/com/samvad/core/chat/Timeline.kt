package com.samvad.core.chat

import com.samvad.core.time.TimeLabels
import java.time.Instant
import java.time.ZoneId

/** Items rendered in the conversation list: date separators between days, then messages. */
sealed interface TimelineItem {
  val key: String

  data class DateSeparator(val label: String, val day: String) : TimelineItem {
    override val key: String get() = "day-$day"
  }

  data class Bubble(
    val message: Message,
    val mine: Boolean,
    val delivery: DeliveryState?,
    /** True when the previous message is from the same sender within 2 minutes (tighter spacing). */
    val groupedWithPrevious: Boolean,
    val timeLabel: String,
    /** The sender's name above the first incoming bubble of a run (groups and personal chats); null otherwise. */
    val senderName: String? = null,
    /** The sender's round photo beside the bubble: first incoming bubble of a run only. */
    val showAvatar: Boolean = false,
  ) : TimelineItem {
    override val key: String get() = message.id
  }

  /** A centred group event line ("Asha added Ravi"). */
  data class SystemLine(val id: String, val text: String) : TimelineItem {
    override val key: String get() = id
  }
}

object Timeline {
  private const val GROUP_WINDOW_MS = 2 * 60 * 1000L

  /** [messages] in ascending time order. */
  fun build(
    messages: List<Message>,
    me: String,
    other: MemberState?,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    showReadReceipts: Boolean = true,
    /** Groups: names for sender labels and event lines; null for direct chats. */
    groupNames: ((String) -> String)? = null,
    /** Direct chats: the other person's display name for the label above their runs. */
    otherName: String? = null,
  ): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(messages.size + 8)
    var lastDay: String? = null
    var previous: Message? = null
    for (m in messages) {
      if (Disappearing.isExpired(m.expiresAt, nowMs)) continue
      val day = Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate().toString()
      if (day != lastDay) {
        out += TimelineItem.DateSeparator(TimeLabels.dateSeparator(m.createdAt, nowMs, zone), day)
        lastDay = day
        previous = null
      }
      val system = m.system
      if (m.kind == "system" && system != null) {
        out += TimelineItem.SystemLine(m.id, SystemText.describe(system, m.senderId, me, groupNames ?: { it }))
        previous = null
        continue
      }
      val mine = m.senderId == me
      val startsRun = !mine &&
        (previous == null || previous.senderId != m.senderId || m.createdAt - previous.createdAt >= GROUP_WINDOW_MS)
      val grouped = previous != null && previous.senderId == m.senderId && m.createdAt - previous.createdAt < GROUP_WINDOW_MS
      out += TimelineItem.Bubble(
        message = m,
        mine = mine,
        // Groups show sent/sending only: per-member receipts are not tracked.
        delivery = if (mine) MessageRules.deliveryState(m, if (groupNames != null) null else other, showReadReceipts) else null,
        groupedWithPrevious = grouped,
        timeLabel = TimeLabels.clock(m.createdAt, zone),
        senderName = if (startsRun) groupNames?.invoke(m.senderId) ?: otherName else null,
        showAvatar = startsRun,
      )
      previous = m
    }
    return out
  }
}
