package com.samvad.core.search

/**
 * A person the searcher already knows: registered address-book contacts and people they chat with. [samvadName] is
 * the profile name, [savedName] the name in the phone's contacts. There is deliberately no way to search anyone else.
 */
data class PersonCandidate(
  val uid: String,
  val samvadName: String,
  val savedName: String? = null,
  val inChat: Boolean = false,
)

data class GroupCandidate(val conversationId: String, val name: String)

object PeopleSearch {
  /** Highest first: a name that starts with the query, a word that starts with it, then any substring. */
  private fun score(name: String?, q: String): Int {
    if (name.isNullOrBlank()) return 0
    val n = name.lowercase()
    return when {
      n.startsWith(q) -> 3
      n.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 2
      n.contains(q) -> 1
      else -> 0
    }
  }

  fun people(query: String, candidates: List<PersonCandidate>, limit: Int = 20): List<PersonCandidate> {
    val q = query.trim().lowercase()
    if (q.length < SearchPolicy.MIN_QUERY_CHARS) return emptyList()
    return candidates
      .map { it to maxOf(score(it.samvadName, q), score(it.savedName, q)) }
      .filter { it.second > 0 }
      .sortedWith(
        compareByDescending<Pair<PersonCandidate, Int>> { it.second }
          .thenByDescending { it.first.inChat }
          .thenBy { (it.first.savedName ?: it.first.samvadName).lowercase() },
      )
      .map { it.first }
      .take(limit)
  }

  fun groups(query: String, candidates: List<GroupCandidate>, limit: Int = 10): List<GroupCandidate> {
    val q = query.trim().lowercase()
    if (q.length < SearchPolicy.MIN_QUERY_CHARS) return emptyList()
    return candidates
      .map { it to score(it.name, q) }
      .filter { it.second > 0 }
      .sortedWith(compareByDescending<Pair<GroupCandidate, Int>> { it.second }.thenBy { it.first.name.lowercase() })
      .map { it.first }
      .take(limit)
  }

  /** Merges address-book contacts with chat partners, one entry per uid (the saved name is kept when there is one). */
  fun merge(contacts: Map<String, String>, chatPartners: Map<String, String>): List<PersonCandidate> {
    val uids = LinkedHashSet<String>().apply { addAll(chatPartners.keys); addAll(contacts.keys) }
    return uids.map { uid ->
      val saved = contacts[uid]
      PersonCandidate(uid, chatPartners[uid] ?: saved.orEmpty(), saved, inChat = uid in chatPartners)
    }
  }
}
