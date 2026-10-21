package com.samvad.messenger.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.search.ChatHits
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

data class GlobalSearchState(
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
      _state.update { it.copy(groups = emptyList(), searched = false, tooShort = query.isNotBlank()) }
      return
    }
    viewModelScope.launch {
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
      _state.update { it.copy(groups = groups, searched = true, tooShort = false) }
    }
  }

  fun senderName(uid: String): String = if (uid == me) "" else chats.profiles.value[uid]?.displayName ?: ChatList.DELETED_ACCOUNT

  companion object {
    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { GlobalSearchViewModel(container, me) } }
  }
}
