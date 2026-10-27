package com.samvad.messenger.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.search.ChatHits
import com.samvad.core.search.GroupCandidate
import com.samvad.core.search.PeopleSearch
import com.samvad.core.search.PersonCandidate
import com.samvad.core.search.SearchFilter
import com.samvad.messenger.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One chat's results, with the title and sender names the screen needs (the pure [ChatHits] has only ids). */
data class SearchResultGroup(
  val hits: ChatHits,
  val title: String,
  val isGroup: Boolean,
  val otherUid: String?,
  val photoPath: String?,
) {
  val conversationId: String get() = hits.conversationId
}

/** A known person (registered contact or chat partner) matching the query. */
data class PersonResult(val uid: String, val title: String, val savedName: String?, val photoPath: String?)

data class GlobalSearchState(
  val people: List<PersonResult> = emptyList(),
  val groupChats: List<GroupCandidate> = emptyList(),
  val query: String = "",
  val filter: SearchFilter = SearchFilter.ALL,
  val groups: List<SearchResultGroup> = emptyList(),
  val searched: Boolean = false,
  /** Shown while the query is non-empty but under [com.samvad.core.search.SearchPolicy.MIN_QUERY_CHARS]. */
  val tooShort: Boolean = false,
  /** Chat ids whose "Show more" the person has tapped. */
  val expanded: Set<String> = emptySet(),
)

/**
 * Global message search, backed entirely by the on-device index ([com.samvad.messenger.data.search.SearchRepository]):
 * nothing here calls the server. A search only ever finds messages this device has already loaded through
 * the normal chat screens.
 */
class GlobalSearchViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val chats = container.chats!!
  private val repo = container.searchRepo
  private val _state = MutableStateFlow(GlobalSearchState())
  val state: StateFlow<GlobalSearchState> = _state.asStateFlow()
  private var generation = 0

  fun setFilter(f: SearchFilter) {
    _state.update { it.copy(filter = f) }
    runSearch()
  }

  fun setQuery(q: String) {
    _state.update { it.copy(query = q, expanded = emptySet()) }
    runSearch()
  }

  fun expand(conversationId: String) = _state.update { it.copy(expanded = it.expanded + conversationId) }

  private fun runSearch() {
    val gen = ++generation
    val query = _state.value.query
    if (query.trim().length < com.samvad.core.search.SearchPolicy.MIN_QUERY_CHARS) {
      _state.update { it.copy(groups = emptyList(), people = emptyList(), groupChats = emptyList(), searched = false, tooShort = query.isNotBlank()) }
      return
    }
    viewModelScope.launch {
      val (people, groupChats) = findKnown(query)
      if (gen != generation) return@launch
      _state.update { it.copy(people = people, groupChats = groupChats) }
      val hits = repo.search(query, _state.value.filter)
      if (gen != generation) return@launch
      // One-shot read of what is already cached, so results can show a title without a live subscription per row.
      val snapshot = runCatching { chats.conversations(me).first() }.getOrNull()?.value.orEmpty().associateBy { it.id }
      val missingUids = hits.flatMap { h -> snapshot[h.conversationId]?.let { c -> if (!c.isGroup) listOfNotNull(ConversationIds.otherMember(c, me)) else emptyList() } ?: emptyList() }
      if (missingUids.isNotEmpty()) runCatching { chats.loadProfiles(missingUids) }
      if (gen != generation) return@launch
      val groups = hits.map { h ->
        val c = snapshot[h.conversationId]
        val other = c?.let { if (it.isGroup) null else ConversationIds.otherMember(it, me) }
        val title = when {
          c == null -> ChatList.DELETED_ACCOUNT
          c.isGroup -> c.name?.takeIf { it.isNotBlank() } ?: ChatList.GROUP
          else -> other?.let { chats.profiles.value[it]?.displayName } ?: ChatList.DELETED_ACCOUNT
        }
        SearchResultGroup(h, title, c?.isGroup ?: false, other, if (c?.isGroup == true) c.photoPath else null)
      }
      _state.update { it.copy(groups = groups, people = people, groupChats = groupChats, searched = true, tooShort = false) }
    }
  }

  private var contactsLoaded = false

  /**
   * People the searcher already knows, never a directory: registered address-book contacts (profile name and the name
   * saved on the phone) and the people they chat with, plus group chats by name.
   */
  private suspend fun findKnown(query: String): Pair<List<PersonResult>, List<GroupCandidate>> {
    val saved = container.savedNames
    if (!contactsLoaded) {
      contactsLoaded = true
      runCatching { saved?.refresh() }
    }
    val savedByUid = saved?.state?.value?.byUid.orEmpty()
    val conversations = runCatching { chats.conversations(me).first() }.getOrNull()?.value.orEmpty()
    val partners = conversations.filter { !it.isGroup }.mapNotNull { ConversationIds.otherMember(it, me) }
    val missing = (partners + savedByUid.keys).filter { it !in chats.profiles.value }.distinct()
    if (missing.isNotEmpty()) runCatching { chats.loadProfiles(missing) }
    val names = chats.profiles.value
    val partnerNames = partners.associateWith { names[it]?.displayName.orEmpty() }
    val contactNames = savedByUid.filterKeys { it != me }
    val candidates = PeopleSearch.merge(contactNames, partnerNames).map { c ->
      if (c.samvadName.isBlank()) c.copy(samvadName = names[c.uid]?.displayName.orEmpty()) else c
    }
    val people = PeopleSearch.people(query, candidates).map { c: PersonCandidate ->
      PersonResult(
        c.uid,
        c.samvadName.ifBlank { c.savedName.orEmpty() },
        c.savedName?.takeIf { it != c.samvadName },
        names[c.uid]?.photoPath,
      )
    }
    val groupChats = PeopleSearch.groups(
      query,
      conversations.filter { it.isGroup }.mapNotNull { c -> c.name?.takeIf { it.isNotBlank() }?.let { GroupCandidate(c.id, it) } },
    )
    return people to groupChats
  }

  /** Opens (creating if needed) the direct chat with a person found by name. */
  suspend fun openPerson(uid: String): String? =
    runCatching { chats.openDirect(me, uid, container::applyDefaultTimer) }.getOrNull()

  fun senderName(uid: String): String = if (uid == me) "" else chats.profiles.value[uid]?.displayName ?: ChatList.DELETED_ACCOUNT

  companion object {
    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { GlobalSearchViewModel(container, me) } }
  }
}
