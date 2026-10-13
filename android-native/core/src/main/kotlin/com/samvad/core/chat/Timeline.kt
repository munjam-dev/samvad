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
  ) : TimelineItem {
    override val key: String get() = message.id
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
  ): List<TimelineItem> {
    val out = ArrayList<TimelineItem>(messages.size + 8)
    var lastDay: String? = null
    var previous: Message? = null
    for (m in messages) {
      val day = Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate().toString()
      if (day != lastDay) {
        out += TimelineItem.DateSeparator(TimeLabels.dateSeparator(m.createdAt, nowMs, zone), day)
        lastDay = day
        previous = null
      }
      val mine = m.senderId == me
      out += TimelineItem.Bubble(
        message = m,
        mine = mine,
        delivery = if (mine) MessageRules.deliveryState(m, other, showReadReceipts) else null,
        groupedWithPrevious = previous != null && previous.senderId == m.senderId &&
          m.createdAt - previous.createdAt < GROUP_WINDOW_MS,
        timeLabel = TimeLabels.clock(m.createdAt, zone),
      )
      previous = m
    }
    return out
  }
}
