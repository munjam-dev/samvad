package com.samvad.messenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.core.chat.TextValidation
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.MessagesPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
  val loading: Boolean = true,
  val error: Boolean = false,
  val offline: Boolean = false,
  val title: String = "",
  val initials: String = "",
  /** Newest first (the list is reverse-laid-out). */
  val items: List<TimelineItem> = emptyList(),
  val hasMore: Boolean = false,
  val muted: Boolean = false,
  val pinned: Boolean = false,
  val latestIncomingId: String? = null,
)

data class ComposerState(
  val text: String = "",
  val replyTo: Message? = null,
  val editing: Message? = null,
  val tooLong: Boolean = false,
)

class ConversationViewModel(
  container: AppContainer,
  private val me: String,
  private val conversationId: String,
  private val otherUid: String,
) : ViewModel() {
  private val chats = container.chats!!
  private val limit = MutableStateFlow(PAGE)
  private val failed = MutableStateFlow(false)
  private val reload = MutableStateFlow(0)

  private val _composer = MutableStateFlow(ComposerState())
  val composer: StateFlow<ComposerState> = _composer

  init {
    viewModelScope.launch { chats.loadProfiles(listOf(otherUid)) }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private val page = combine(limit, reload) { n, _ -> n }.flatMapLatest { n ->
    chats.messages(conversationId, n).catch { failed.value = true }
  }

  val state: StateFlow<ConversationUiState> = combine(
    page,
    chats.memberState(conversationId, otherUid),
    chats.memberState(conversationId, me),
    chats.profiles.map { it[otherUid] },
    failed,
  ) { p: MessagesPage, other, mine, profile, err ->
    val title = profile?.displayName ?: ChatList.DELETED_ACCOUNT
    ConversationUiState(
      loading = false,
      error = err,
      offline = p.fromCache,
      title = title,
      initials = ChatList.initials(title),
      items = Timeline.build(p.messages, me, other, System.currentTimeMillis()).asReversed(),
      hasMore = p.hasMore,
      muted = mine?.muted ?: false,
      pinned = mine?.pinned ?: false,
      latestIncomingId = p.messages.lastOrNull { it.senderId != me }?.id,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

  fun loadMore() {
    if (state.value.hasMore) limit.update { it + PAGE }
  }

  fun markRead() = viewModelScope.launch { chats.markRead(conversationId, me) }

  fun onText(text: String) = _composer.update { it.copy(text = text, tooLong = text.length > MessageRules.MAX_LENGTH) }

  fun reply(message: Message) = _composer.update { it.copy(replyTo = message, editing = null) }

  fun startEdit(message: Message) = _composer.update { it.copy(editing = message, replyTo = null, text = message.text) }

  fun cancelContext() = _composer.update { it.copy(replyTo = null, editing = null, text = if (it.editing != null) "" else it.text) }

  fun submit() {
    val c = _composer.value
    val text = (MessageRules.validate(c.text) as? TextValidation.Ok)?.text ?: return
    _composer.value = ComposerState()
    viewModelScope.launch {
      runCatching {
        if (c.editing != null) chats.edit(conversationId, c.editing.id, text)
        else chats.send(conversationId, me, text, c.replyTo?.id)
      }
    }
  }

  fun retry(message: Message) = viewModelScope.launch { runCatching { chats.retry(conversationId, me, message) } }

  fun delete(message: Message) = viewModelScope.launch { runCatching { chats.deleteForEveryone(conversationId, message.id) } }

  fun react(message: Message, emoji: String) = viewModelScope.launch {
    val current = message.reactions[me]
    runCatching { chats.react(conversationId, message.id, me, if (current == emoji) null else emoji) }
  }

  fun toggle(field: String, value: Boolean) = viewModelScope.launch { runCatching { chats.setFlag(conversationId, me, field, value) } }

  fun canEdit(message: Message) = MessageRules.canEdit(message, me, System.currentTimeMillis())

  fun retryLoad() {
    failed.value = false
    reload.update { it + 1 }
  }

  companion object {
    const val PAGE = 30L
    fun factory(container: AppContainer, me: String, cid: String, other: String) =
      viewModelFactory { initializer { ConversationViewModel(container, me, cid, other) } }
  }
}
