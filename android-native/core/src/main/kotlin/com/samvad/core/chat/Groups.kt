package com.samvad.core.chat

/** A server-written group event, e.g. event "added" with the added people in [targets]. */
data class SystemInfo(val event: String, val targets: List<String> = emptyList(), val name: String? = null)

/** A poll message. Option i is identified by ids[i]; votes store ids. */
data class Poll(val question: String, val options: List<String>, val ids: List<String>, val multi: Boolean)

data class PollResult(
  /** Votes per option id. */
  val counts: Map<String, Int>,
  /** People who voted at all. */
  val voters: Int,
  val mine: Set<String>,
  /** Option ids with the most votes (empty when nobody voted). */
  val leading: Set<String>,
)

object GroupRules {
  const val MAX_MEMBERS = 256
  const val MAX_NAME = 64
  const val MAX_DESCRIPTION = 512

  fun isAdmin(group: Conversation, uid: String): Boolean = uid in group.admins

  fun validName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
}

object Polls {
  const val MIN_OPTIONS = 2
  const val MAX_OPTIONS = 12
  const val MAX_QUESTION = 300
  const val MAX_OPTION = 100

  /** A poll from what the user typed: trimmed, blanks and duplicates dropped; null when not valid. */
  fun create(question: String, options: List<String>, multi: Boolean): Poll? {
    val q = question.trim()
    val opts = options.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    if (q.isEmpty() || q.length > MAX_QUESTION) return null
    if (opts.size !in MIN_OPTIONS..MAX_OPTIONS || opts.any { it.length > MAX_OPTION }) return null
    return Poll(q, opts, opts.indices.map { it.toString() }, multi)
  }

  /** Counts [votes] (voter → chosen ids), ignoring ids the poll does not have. */
  fun tally(poll: Poll, votes: Map<String, List<String>>, me: String): PollResult {
    val valid = poll.ids.toSet()
    val counts = poll.ids.associateWith { 0 }.toMutableMap()
    var voters = 0
    for ((_, choices) in votes) {
      val picked = choices.filter { it in valid }.toSet()
      if (picked.isEmpty()) continue
      voters++
      picked.forEach { counts[it] = counts.getValue(it) + 1 }
    }
    val top = counts.values.maxOrNull() ?: 0
    return PollResult(
      counts = counts,
      voters = voters,
      mine = votes[me].orEmpty().filter { it in valid }.toSet(),
      leading = if (top == 0) emptySet() else counts.filterValues { it == top }.keys,
    )
  }

  /** The next choice set after tapping [id]: single choice switches (or clears), multi toggles. */
  fun toggle(poll: Poll, current: Set<String>, id: String): Set<String> = when {
    id !in poll.ids -> current
    !poll.multi -> if (current == setOf(id)) emptySet() else setOf(id)
    id in current -> current - id
    else -> current + id
  }
}

object Mentions {
  private const val MAX_QUERY = 30

  /** The "@query" being typed at the end of [text], or null. "@" must start a word. */
  fun activeQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0 || (at > 0 && !text[at - 1].isWhitespace())) return null
    val query = text.substring(at + 1)
    if (query.length > MAX_QUERY || query.contains('\n') || query.startsWith(' ')) return null
    return query
  }

  /** Replaces the trailing "@query" with "@Name ". */
  fun insert(text: String, name: String): String {
    val at = text.lastIndexOf('@')
    return if (at < 0) "$text@$name " else text.substring(0, at) + "@$name "
  }

  /** The picked people (uid → name) whose "@Name" is still in [text], in UID order. */
  fun resolve(text: String, picked: Map<String, String>): List<String> =
    picked.filter { (_, name) -> text.contains("@$name") }.keys.sorted()

  /** Ranges of "@Name" for every name in [names], for highlighting. */
  fun spans(text: String, names: Collection<String>): List<IntRange> =
    names.filter { it.isNotBlank() }.flatMap { name ->
      Regex(Regex.escape("@$name")).findAll(text).map { it.range }.toList()
    }.sortedBy { it.first }
}

object SystemText {
  /** English line for a group event, e.g. "Asha added Ravi and Meena". [names] maps UID → name. */
  fun describe(info: SystemInfo, actor: String, me: String, names: (String) -> String): String {
    fun who(uid: String) = if (uid == me) "You" else names(uid)
    fun list(uids: List<String>): String {
      val n = uids.map(::who)
      return when (n.size) {
        0 -> "someone"
        1 -> n[0]
        2 -> "${n[0]} and ${n[1]}"
        else -> n.dropLast(1).joinToString(", ") + " and " + n.last()
      }
    }
    val a = who(actor)
    return when (info.event) {
      "created" -> "$a created the group \"${info.name.orEmpty()}\""
      "added" -> "$a added ${list(info.targets)}"
      "removed" -> "$a removed ${list(info.targets)}"
      "left" -> "$a left"
      "joined" -> "$a joined using an invite code"
      "promoted" -> "${list(info.targets)} ${if (info.targets == listOf(me)) "are" else "is"} now an admin"
      "demoted" -> "${list(info.targets)} ${if (info.targets == listOf(me)) "are" else "is"} no longer an admin"
      "renamed" -> "$a changed the group name to \"${info.name.orEmpty()}\""
      "photo" -> "$a changed the group photo"
      else -> "$a updated the group"
    }
  }
}
