package com.samvad.messenger.ui.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusGroup
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.PreparedMedia
import com.samvad.messenger.data.StatusViewer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
  val loading: Boolean = true,
  val groups: List<StatusGroup> = emptyList(),
  val mine: List<StatusItem> = emptyList(),
  val seen: Set<String> = emptySet(),
  val muted: Set<String> = emptySet(),
  val mutedGroups: List<StatusGroup> = emptyList(),
  val names: Map<String, String> = emptyMap(),
  val now: Long = System.currentTimeMillis(),
)

/** A person I could pick when sharing with "only" or "everyone except". */
data class StatusPerson(val uid: String, val name: String)

sealed interface PostState {
  data object Idle : PostState
  data object Posting : PostState
  data object Done : PostState
  data object Failed : PostState
}

class StatusViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val repo = container.status!!
  private val chats = container.chats!!

  private val clock = MutableStateFlow(System.currentTimeMillis())
  private val rawFeed = repo.feed(me).catch { emit(emptyList()) }
  private val rawMine = repo.mine(me).catch { emit(emptyList()) }

  init {
    // Wake up when the next post expires so it leaves the screen on time.
    viewModelScope.launch {
      combine(rawFeed, rawMine) { a, b -> a + b }.collectLatest { items ->
        val now = System.currentTimeMillis()
        val next = StatusRules.nextExpiry(items, now)
        if (next != null) {
          delay(next - now + 50)
          clock.value = System.currentTimeMillis()
        }
      }
    }
    // Make sure the people who posted have names on this device.
    viewModelScope.launch {
      rawFeed.collect { items -> chats.loadProfiles(items.map { it.authorId }.distinct()) }
    }
  }

  val state: StateFlow<StatusUiState> = combine(
    rawFeed, rawMine, container.preferences.preferences, clock, chats.profiles,
  ) { feed, mine, prefs, now, profiles ->
    StatusUiState(
      loading = false,
      groups = StatusRules.feed(feed, prefs.statusSeen, me, now, prefs.statusMuted),
      mine = StatusRules.mine(mine, me, now),
      seen = prefs.statusSeen,
      muted = prefs.statusMuted,
      mutedGroups = StatusRules.feed(feed, prefs.statusSeen, me, now).filter { it.authorId in prefs.statusMuted },
      names = profiles.mapValues { it.value.displayName },
      now = now,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

  /** The people I chat with one to one, for choosing an audience. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val people: StateFlow<List<StatusPerson>> = combine(chats.conversations(me).catch { emit(com.samvad.messenger.data.Snapshot(emptyList(), false)) }, chats.profiles) { convs, profiles ->
    convs.value.filter { !it.isGroup }.mapNotNull { c -> ConversationIds.otherMember(c, me) }.distinct()
      .map { uid -> StatusPerson(uid, profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT) }
      .sortedBy { it.name.lowercase() }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _post = MutableStateFlow<PostState>(PostState.Idle)
  val post: StateFlow<PostState> = _post

  fun postText(text: String, style: String, audience: StatusAudience, list: List<String>) {
    if (!StatusRules.validText(text) || _post.value == PostState.Posting) return
    _post.value = PostState.Posting
    viewModelScope.launch {
      _post.value = runCatching { repo.postText(me, text, StatusRules.style(style), audience, list) }
        .fold({ PostState.Done }, { PostState.Failed })
    }
  }

  fun postPhoto(uri: Uri, caption: String, audience: StatusAudience, list: List<String>) {
    if (_post.value == PostState.Posting) return
    _post.value = PostState.Posting
    viewModelScope.launch {
      _post.value = runCatching {
        val saver = container.preferences.preferences.first().dataSaver
        val prepared: PreparedMedia = container.media!!.prepareImage(uri, saver)
        repo.postImage(me, prepared, caption.take(StatusRules.MAX_TEXT), audience, list)
      }.fold({ PostState.Done }, { PostState.Failed })
    }
  }

  fun resetPost() { _post.value = PostState.Idle }

  /** Called when a status comes on screen: remember it locally and, if receipts are on, tell the author. */
  fun seen(item: StatusItem) {
    viewModelScope.launch {
      val live = state.value.let { s -> (s.groups.flatMap { it.items } + s.mine).map { it.id }.toSet() }
      container.preferences.markStatusSeen(item.id, live)
      if (item.authorId != me && container.preferences.preferences.first().readReceipts) {
        runCatching { repo.recordView(item.authorId, item.id, me) }
      }
    }
  }

  fun react(item: StatusItem, emoji: String) {
    viewModelScope.launch { runCatching { repo.react(item.authorId, item.id, me, emoji) } }
  }

  /** A private reply goes into our one-to-one chat, quoting a short piece of the status. */
  fun reply(item: StatusItem, text: String, onSent: () -> Unit) {
    val body = text.trim()
    if (body.isEmpty()) return
    viewModelScope.launch {
      runCatching {
        val cid = chats.openDirect(me, item.authorId, container::applyDefaultTimer)
        val quote = item.text.take(60).let { if (it.isBlank()) "your status" else "\"$it\"" }
        chats.send(cid, me, "Replying to $quote\n$body", replyTo = null)
      }.onSuccess { onSent() }
    }
  }

  fun delete(item: StatusItem) { viewModelScope.launch { runCatching { repo.delete(me, item.id) } } }

  fun mute(author: String, muted: Boolean) { viewModelScope.launch { container.preferences.setStatusMuted(author, muted) } }

  fun viewers(id: String): Flow<List<StatusViewer>> = repo.viewers(me, id).catch { emit(emptyList()) }

  suspend fun photo(path: String) = container.media?.loadBitmap(path, 1280)

  companion object {
    fun factory(container: AppContainer, me: String) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = StatusViewModel(container, me) as T
    }
  }
}
