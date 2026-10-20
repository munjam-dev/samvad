package com.samvad.core.chat

/** A person's answer to a group event. [wire] is what the server stores. */
enum class Rsvp(val wire: String) {
  GOING("going"), MAYBE("maybe"), NO("no");

  companion object {
    fun fromWire(value: String?): Rsvp? = entries.firstOrNull { it.wire == value }
  }
}

data class RsvpCounts(val going: Int = 0, val maybe: Int = 0, val no: Int = 0) {
  val total: Int get() = going + maybe + no
}

/** An event in a group (conversations/{cid}/events/{id}). Times are epoch milliseconds. */
data class GroupEvent(
  val id: String,
  val title: String,
  val description: String,
  val place: String,
  val startsAt: Long,
  val createdBy: String,
  val cancelled: Boolean,
  val counts: RsvpCounts,
)

/** What the create-event form can be told. */
sealed interface EventCheck {
  data class Ok(val title: String, val description: String, val place: String, val startsAt: Long) : EventCheck
  data object TitleMissing : EventCheck
  data object TitleTooLong : EventCheck
  data object DescriptionTooLong : EventCheck
  data object PlaceTooLong : EventCheck
  data object StartMissing : EventCheck
  data object StartInPast : EventCheck
  data object StartTooFar : EventCheck
}

/** Client mirror of the server's event rules (groupAdvanced.ts), so the form can explain before it sends. */
object EventRules {
  const val MAX_TITLE = 80
  const val MAX_DESCRIPTION = 300
  const val MAX_PLACE = 80
  const val MAX_LEAD_MS = 2L * 365 * 24 * 60 * 60 * 1000
  /** The reminder push goes out this long before the start. */
  const val REMINDER_LEAD_MS = 30L * 60 * 1000

  fun check(title: String, description: String, place: String, startsAt: Long?, nowMs: Long): EventCheck {
    val t = title.trim()
    return when {
      t.isEmpty() -> EventCheck.TitleMissing
      t.length > MAX_TITLE -> EventCheck.TitleTooLong
      description.trim().length > MAX_DESCRIPTION -> EventCheck.DescriptionTooLong
      place.trim().length > MAX_PLACE -> EventCheck.PlaceTooLong
      startsAt == null -> EventCheck.StartMissing
      startsAt <= nowMs -> EventCheck.StartInPast
      startsAt > nowMs + MAX_LEAD_MS -> EventCheck.StartTooFar
      else -> EventCheck.Ok(t, description.trim(), place.trim(), startsAt)
    }
  }

  fun hasStarted(event: GroupEvent, nowMs: Long): Boolean = event.startsAt <= nowMs

  /** Answers are closed once an event has started or been cancelled. */
  fun canAnswer(event: GroupEvent, nowMs: Long): Boolean = !event.cancelled && !hasStarted(event, nowMs)

  /** The creator or an admin. */
  fun canCancel(event: GroupEvent, group: Conversation, uid: String): Boolean =
    !event.cancelled && (event.createdBy == uid || uid in group.admins)

  /** Upcoming (not cancelled, not started) soonest first; then earlier and cancelled ones, newest first. */
  fun split(events: List<GroupEvent>, nowMs: Long): Pair<List<GroupEvent>, List<GroupEvent>> {
    val (upcoming, earlier) = events.partition { !it.cancelled && it.startsAt > nowMs }
    return upcoming.sortedBy { it.startsAt } to earlier.sortedByDescending { it.startsAt }
  }

  /** The count shown next to "Events" in group info: only upcoming ones. */
  fun upcomingCount(events: List<GroupEvent>, nowMs: Long): Int = events.count { !it.cancelled && it.startsAt > nowMs }

  /** Counts for display after the local person's answer changes from [before] to [after] (optimistic). */
  fun adjust(counts: RsvpCounts, before: Rsvp?, after: Rsvp?): RsvpCounts {
    if (before == after) return counts
    fun bump(c: RsvpCounts, r: Rsvp?, by: Int) = when (r) {
      Rsvp.GOING -> c.copy(going = (c.going + by).coerceAtLeast(0))
      Rsvp.MAYBE -> c.copy(maybe = (c.maybe + by).coerceAtLeast(0))
      Rsvp.NO -> c.copy(no = (c.no + by).coerceAtLeast(0))
      null -> c
    }
    return bump(bump(counts, before, -1), after, +1)
  }
}

/** A person waiting for an admin to approve them. */
data class JoinRequest(val uid: String, val cid: String, val groupName: String, val requestedAt: Long)
