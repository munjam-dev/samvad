package com.samvad.messenger.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.EventRules
import com.samvad.core.chat.GroupEvent
import com.samvad.core.chat.GroupRules
import com.samvad.core.chat.Rsvp
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.GroupException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class EventError { PAST, TOO_FAR, GONE, LIMIT, DAILY_LIMIT, NOT_ALLOWED, FAILED }

data class EventsUiState(
  val loading: Boolean = true,
  val group: Conversation? = null,
  val upcoming: List<GroupEvent> = emptyList(),
  val earlier: List<GroupEvent> = emptyList(),
  val canCreate: Boolean = false,
)

class EventsViewModel(private val container: AppContainer, private val me: String, private val cid: String) : ViewModel() {
  private val chats = container.chats!!
  private val groups = container.groups!!

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy
  private val _error = MutableStateFlow<EventError?>(null)
  val error: StateFlow<EventError?> = _error
  val state: StateFlow<EventsUiState> = combine(
    chats.conversation(cid).catch { emit(null) },
    chats.groupEvents(cid),
  ) { g, events ->
    val now = System.currentTimeMillis()
    val (upcoming, earlier) = EventRules.split(events, now)
    EventsUiState(
      loading = false,
      group = g,
      upcoming = upcoming,
      earlier = earlier,
      canCreate = g != null && GroupRules.canCreateEvent(g, me),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsUiState())

  fun rsvpFlow(eventId: String) = chats.myRsvp(cid, eventId, me)

  fun create(title: String, description: String, place: String, startsAtMs: Long, then: () -> Unit) = act {
    groups.createEvent(cid, title, description, place, startsAtMs)
    then()
  }

  fun rsvp(eventId: String, answer: Rsvp?) = viewModelScope.launch {
    runCatching { groups.rsvpEvent(cid, eventId, answer) }
  }

  fun cancel(eventId: String) = act { groups.cancelEvent(cid, eventId) }

  fun clearError() { _error.value = null }

  private fun act(work: suspend () -> Unit) = viewModelScope.launch {
    if (_busy.value) return@launch
    _busy.value = true
    _error.value = try {
      work()
      null
    } catch (e: GroupException.EventInPast) {
      EventError.PAST
    } catch (e: GroupException.EventTooFar) {
      EventError.TOO_FAR
    } catch (e: GroupException.EventGone) {
      EventError.GONE
    } catch (e: GroupException.TooManyEvents) {
      EventError.LIMIT
    } catch (e: GroupException.LimitReached) {
      EventError.DAILY_LIMIT
    } catch (e: GroupException.NotAllowed) {
      EventError.NOT_ALLOWED
    } catch (e: Exception) {
      EventError.FAILED
    }
    _busy.value = false
  }

  companion object {
    fun factory(container: AppContainer, me: String, cid: String) =
      viewModelFactory { initializer { EventsViewModel(container, me, cid) } }
  }
}
