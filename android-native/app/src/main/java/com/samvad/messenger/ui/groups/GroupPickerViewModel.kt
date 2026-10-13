package com.samvad.messenger.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.GroupRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.GroupException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Candidate(val uid: String, val name: String)

enum class GroupError { NO_ELIGIBLE, FULL, LIMIT, NOT_ALLOWED, FAILED }

data class GroupPickerState(
  val loading: Boolean = true,
  val candidates: List<Candidate> = emptyList(),
  val selected: List<Candidate> = emptyList(),
  val query: String = "",
  /** New group: second step, naming it. */
  val naming: Boolean = false,
  val name: String = "",
  val busy: Boolean = false,
  val error: GroupError? = null,
)

/**
 * Picks people for a new group, or to add to [existingGroup]. Candidates are the people I already chat
 * with plus contacts on Samvad (when contacts access is allowed; lookups are cached for the session).
 */
class GroupPickerViewModel(
  private val container: AppContainer,
  private val me: String,
  private val existingGroup: String?,
) : ViewModel() {
  private val chats = container.chats!!
  private val groups = container.groups!!
  private val _state = MutableStateFlow(GroupPickerState())
  val state: StateFlow<GroupPickerState> = _state

  init { load() }

  private fun load() = viewModelScope.launch {
    val found = linkedMapOf<String, String>()
    val exclude = mutableSetOf(me)
    runCatching {
      val convs = chats.conversations(me).first().value
      if (existingGroup != null) convs.firstOrNull { it.id == existingGroup }?.members?.let(exclude::addAll)
      val people = convs.filter { !it.isGroup }.mapNotNull { c -> c.members.firstOrNull { it != me } }
      chats.loadProfiles(people)
      val profiles = chats.profiles.value
      people.forEach { uid -> profiles[uid]?.displayName?.let { found[uid] = it } }
    }
    val contacts = container.contacts
    if (contacts != null && contacts.hasPermission()) {
      runCatching {
        val device = contacts.deviceContacts()
        val matches = contacts.lookup(device.map { it.e164 })
        device.forEach { c -> matches[c.e164]?.let { uid -> found[uid] = c.name } }
      }
    }
    val list = found.filterKeys { it !in exclude }.map { (uid, name) -> Candidate(uid, name) }.sortedBy { it.name.lowercase() }
    _state.update { it.copy(loading = false, candidates = list) }
  }

  fun onQuery(q: String) = _state.update { it.copy(query = q) }

  fun toggle(c: Candidate) = _state.update { s ->
    val max = GroupRules.MAX_MEMBERS - 1
    when {
      s.selected.any { it.uid == c.uid } -> s.copy(selected = s.selected.filterNot { it.uid == c.uid })
      s.selected.size >= max -> s
      else -> s.copy(selected = s.selected + c)
    }
  }

  fun next() = _state.update { if (it.selected.isEmpty()) it else it.copy(naming = true, error = null) }
  fun back() = _state.update { it.copy(naming = false) }
  fun onName(n: String) = _state.update { it.copy(name = n.take(GroupRules.MAX_NAME), error = null) }

  /** New group: creates it and returns its ID through [then]. */
  fun create(then: (String) -> Unit) {
    val name = GroupRules.validName(_state.value.name) ?: return
    submit { then(groups.create(name, _state.value.selected.map { it.uid })) }
  }

  /** Add mode: adds the selection to the existing group. */
  fun add(then: () -> Unit) {
    val cid = existingGroup ?: return
    submit { groups.addMembers(cid, _state.value.selected.map { it.uid }); then() }
  }

  private fun submit(work: suspend () -> Unit) = viewModelScope.launch {
    if (_state.value.busy) return@launch
    _state.update { it.copy(busy = true, error = null) }
    val error = try {
      work()
      null
    } catch (e: GroupException.NoEligibleMembers) {
      GroupError.NO_ELIGIBLE
    } catch (e: GroupException.Full) {
      GroupError.FULL
    } catch (e: GroupException.LimitReached) {
      GroupError.LIMIT
    } catch (e: GroupException.NotAllowed) {
      GroupError.NOT_ALLOWED
    } catch (e: Exception) {
      GroupError.FAILED
    }
    _state.update { it.copy(busy = false, error = error) }
  }

  companion object {
    fun factory(container: AppContainer, me: String, existingGroup: String? = null) =
      viewModelFactory { initializer { GroupPickerViewModel(container, me, existingGroup) } }
  }
}
