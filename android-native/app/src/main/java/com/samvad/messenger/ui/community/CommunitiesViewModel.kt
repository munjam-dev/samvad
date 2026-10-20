package com.samvad.messenger.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.MemberState
import com.samvad.core.community.Community
import com.samvad.core.community.CommunityRow
import com.samvad.core.community.CommunityRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunitiesUiState(val loading: Boolean = true, val rows: List<CommunityRow> = emptyList())

/** The "My communities" list on the Communities tab. */
class CommunitiesViewModel(container: AppContainer, me: String) : ViewModel() {
  private val communities = container.communities!!
  private val chats = container.chats!!

  private val mine = communities.mine(me).catch { emit(Snapshot(emptyList<Community>(), false)) }
  private val annIds = mine.map { it.value.map(Community::announcementsCid) }.distinctUntilChanged()

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val announcements = annIds.flatMapLatest { ids ->
    if (ids.isEmpty()) flowOf<Map<String, Conversation>>(emptyMap())
    else combine(ids.map { id -> chats.conversation(id) }) { convs -> ids.zip(convs.toList()).mapNotNull { (id, c) -> c?.let { id to it } }.toMap() }
  }
  private val annStates = chats.memberStates(me, annIds)

  private val unread = MutableStateFlow<Map<String, Int>>(emptyMap())
  private val counted = mutableMapOf<String, String>()

  val state = combine(mine, announcements, annStates, unread) { snap, convs, states, unreadNow ->
    for (c in snap.value) {
      val conv = convs[c.announcementsCid] ?: continue
      val mine0 = states[c.announcementsCid] ?: MemberState(me)
      val last = conv.lastMessage
      if (last == null || last.senderId == me || last.createdAt <= (mine0.lastReadAt ?: 0L)) {
        if ((unreadNow[c.id] ?: 0) != 0) unread.update { it + (c.id to 0) }
        continue
      }
      val key = "${last.id}:${mine0.lastReadAt}"
      if (counted[c.id] == key) continue
      counted[c.id] = key
      viewModelScope.launch {
        runCatching { chats.countSince(c.announcementsCid, mine0.lastReadAt ?: 0L) }.onSuccess { n -> unread.update { it + (c.id to n) } }
      }
    }
    val rows = snap.value.map { c ->
      CommunityRow(
        id = c.id,
        name = c.name,
        memberCount = c.members.size,
        unread = unreadNow[c.id] ?: 0,
        lastAnnouncementAt = convs[c.announcementsCid]?.lastMessageAt ?: 0L,
        photoPath = c.photoPath,
      )
    }
    CommunitiesUiState(loading = false, rows = CommunityRules.sortCommunities(rows))
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunitiesUiState())

  companion object {
    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { CommunitiesViewModel(container, me) } }
  }
}

internal fun communityInitials(name: String): String = ChatList.initials(name.ifBlank { "?" })
