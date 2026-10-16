package com.samvad.messenger.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatFilter
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ChatRow
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.ConversationIds
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Profile
import com.samvad.core.chat.UnreadSummary
import com.samvad.core.design.HomeLayout
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
  val loading: Boolean = true,
  val error: Boolean = false,
  val offline: Boolean = false,
  val rows: List<ChatRow> = emptyList(),
  val recentPeople: List<ChatRow> = emptyList(),
  val hasAnyChat: Boolean = false,
  val summary: UnreadSummary = UnreadSummary(0, 0),
  val filter: ChatFilter = ChatFilter.ALL,
  val query: String = "",
  val searching: Boolean = false,
  val greeting: String = "",
  val me: Profile? = null,
  val layout: HomeLayout = HomeLayout.DEFAULT,
)

private data class Inputs(
  val conversations: Snapshot<List<Conversation>>?,
  val states: Map<String, MemberState>,
  val profiles: Map<String, Profile>,
  val failed: Boolean,
  val groupUnread: Map<String, Int>,
)

class HomeViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val chats = container.chats!!
  private val filter = MutableStateFlow(ChatFilter.ALL)
  private val query = MutableStateFlow("")
  private val searching = MutableStateFlow(false)
  private val failed = MutableStateFlow(false)
  private val retry = MutableStateFlow(0)
  private val conversations = MutableStateFlow<Snapshot<List<Conversation>>?>(null)
  private val delivered = mutableSetOf<String>()

  init {
    viewModelScope.launch {
      retry.collect {
        failed.value = false
        chats.conversations(me)
          .catch { failed.value = true }
          .collect { snap ->
            conversations.value = snap
            chats.loadProfiles(snap.value.flatMap { it.members }.toSet() - me)
          }
      }
    }
  }

  private val ids = conversations.map { it?.value?.map(Conversation::id) ?: emptyList() }.distinctUntilChanged()
  private val states = chats.memberStates(me, ids).onEach(::markDelivered).onEach(::refreshGroupUnread)

  /** Groups keep no unread counter on the server; it is counted here, once per new message. */
  private val groupUnread = MutableStateFlow<Map<String, Int>>(emptyMap())
  private val counted = mutableMapOf<String, String>()

  private fun refreshGroupUnread(states: Map<String, MemberState>) {
    val convs = conversations.value?.value ?: return
    for (c in convs.filter { it.isGroup }) {
      val last = c.lastMessage
      val mine = states[c.id] ?: continue
      val since = maxOf(mine.lastReadAt ?: 0L, mine.joinedAt ?: 0L)
      // Nothing new, or my own message is the latest (sending implies I have seen the chat).
      if (last == null || last.senderId == me || last.createdAt <= since) {
        if ((groupUnread.value[c.id] ?: 0) != 0) groupUnread.update { it + (c.id to 0) }
        continue
      }
      val key = "${last.id}:$since"
      if (counted[c.id] == key) continue
      counted[c.id] = key
      viewModelScope.launch {
        runCatching { chats.countSince(c.id, since) }.onSuccess { n -> groupUnread.update { it + (c.id to n) } }
      }
    }
  }

  val state: StateFlow<HomeUiState> =
    combine(
      combine(conversations, states, chats.profiles, failed, groupUnread) { c, s, p, f, g -> Inputs(c, s, p, f, g) },
      combine(filter, query, searching) { f, q, s -> Triple(f, q, s) },
      container.preferences.preferences,
      container.accounts!!.profile(me),
      container.drafts.all,
    ) { inputs, (f, q, s), prefs, myProfile, drafts ->
      val now = System.currentTimeMillis()
      val convs = inputs.conversations?.value.orEmpty()
      val all = ChatList.build(me, convs, inputs.states, inputs.profiles, ChatFilter.ALL, now, unreadOverride = inputs.groupUnread, drafts = drafts)
      val filtered = ChatList.build(me, convs, inputs.states, inputs.profiles, f, now, unreadOverride = inputs.groupUnread, drafts = drafts)
        .filter { q.isBlank() || it.title.contains(q.trim(), ignoreCase = true) }
      HomeUiState(
        loading = inputs.conversations == null && !inputs.failed,
        error = inputs.failed && inputs.conversations == null,
        offline = inputs.conversations?.fromCache == true,
        rows = filtered,
        recentPeople = all.filter { it.otherUid != null && it.title != ChatList.DELETED_ACCOUNT }.take(8),
        hasAnyChat = all.isNotEmpty(),
        summary = ChatList.unreadSummary(all),
        filter = f,
        query = q,
        searching = s,
        greeting = TimeLabels.greeting(now),
        me = myProfile,
        layout = prefs.homeLayout,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  /** Tells senders their messages reached this device (drives the "delivered" ticks). */
  private fun markDelivered(states: Map<String, MemberState>) {
    val convs = conversations.value?.value ?: return
    for (c in convs) {
      val last = c.lastMessage ?: continue
      if (last.senderId == me) continue
      val mine = states[c.id] ?: continue
      val key = "${c.id}:${last.id}"
      if ((mine.lastDeliveredAt ?: 0) < last.createdAt && delivered.add(key)) {
        viewModelScope.launch { chats.markDelivered(c.id, me) }
      }
    }
  }

  fun setFilter(f: ChatFilter) = filter.update { f }
  fun setQuery(q: String) = query.update { q }
  fun toggleSearch() {
    searching.update { !it }
    if (!searching.value) query.value = ""
  }
  fun retry() = retry.update { it + 1 }

  fun otherOf(row: ChatRow): String = row.otherUid ?: ConversationIds.otherMember(
    Conversation(row.conversationId, row.conversationId.split("_"), null, 0), me,
  ).orEmpty()

  companion object {
    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { HomeViewModel(container, me) } }
  }
}
