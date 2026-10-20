package com.samvad.messenger.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.SharedMedia
import com.samvad.core.chat.SharedMediaBuilder
import com.samvad.messenger.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * "Media, links and documents": built only from the messages already loaded for this chat on this
 * device (the window below), never a fresh scan of the whole chat history.
 */
class SharedMediaViewModel(container: AppContainer, conversationId: String) : ViewModel() {
  private val chats = container.chats!!
  private val mediaRepo = container.media!!

  suspend fun bitmap(path: String, maxEdgePx: Int) = mediaRepo.loadBitmap(path, maxEdgePx)
  suspend fun localFile(path: String) = runCatching { mediaRepo.localFile(path) }.getOrNull()
  fun isCached(path: String): Boolean = mediaRepo.isCached(path)

  companion object {
    /** Generous enough for a normal chat window; still "what is loaded", not the full history. */
    private const val WINDOW = 1000L

    fun factory(container: AppContainer, conversationId: String) =
      viewModelFactory { initializer { SharedMediaViewModel(container, conversationId) } }
  }

  val shared: StateFlow<SharedMedia?> = chats.messages(conversationId, WINDOW)
    .map { page -> SharedMediaBuilder.build(page.messages, System.currentTimeMillis()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
