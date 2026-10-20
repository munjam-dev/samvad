package com.samvad.messenger.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.messenger.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AnnouncementsState(
  val loading: Boolean = true,
  val communityName: String = "",
  val canPost: Boolean = false,
  val messages: List<Message> = emptyList(),
)

/** The Announcements conversation: everyone reads, only community admins post (the rules enforce it too). */
class AnnouncementsViewModel(container: AppContainer, private val me: String, private val communityId: String, private val cid: String) : ViewModel() {
  private val chats = container.chats!!
  private val communities = container.communities!!
  private val _sending = MutableStateFlow(false)
  val sending = _sending

  val state = combine(
    communities.community(communityId).catch { emit(null) },
    chats.messages(cid, 200),
    chats.profiles,
  ) { community, page, profiles ->
    val missing = page.messages.map { it.senderId }.filter { it !in profiles }.toSet()
    if (missing.isNotEmpty()) viewModelScope.launch { chats.loadProfiles(missing) }
    AnnouncementsState(
      loading = false,
      communityName = community?.name.orEmpty(),
      canPost = community != null && com.samvad.core.community.CommunityRules.canPostAnnouncement(community, me),
      messages = page.messages,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnouncementsState())

  fun nameOf(uid: String): String = chats.profiles.value[uid]?.displayName ?: ChatList.DELETED_ACCOUNT

  fun send(text: String) {
    val ok = (MessageRules.validate(text) as? com.samvad.core.chat.TextValidation.Ok)?.text ?: return
    if (_sending.value) return
    viewModelScope.launch {
      _sending.value = true
      runCatching { chats.send(cid, me, ok, replyTo = null) }
      _sending.value = false
    }
  }

  fun react(messageId: String, emoji: String?) = viewModelScope.launch { runCatching { chats.react(cid, messageId, me, emoji) } }

  fun markRead() = viewModelScope.launch { runCatching { chats.markRead(cid, me) } }

  companion object {
    fun factory(container: AppContainer, me: String, communityId: String, cid: String) =
      viewModelFactory { initializer { AnnouncementsViewModel(container, me, communityId, cid) } }
  }
}
