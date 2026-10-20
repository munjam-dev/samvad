package com.samvad.messenger.ui.community

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.community.CommunityRules
import com.samvad.core.community.CreateFlow
import com.samvad.core.community.CreateStep
import com.samvad.core.community.LinkedGroup
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.CommunityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateCommunityState(
  val step: CreateStep = CreateStep.NAME_AND_PHOTO,
  val name: String = "",
  val description: String = "",
  val photoUri: Uri? = null,
  val loadingGroups: Boolean = true,
  /** Groups I admin that are not already linked elsewhere. */
  val selectableGroups: List<LinkedGroup> = emptyList(),
  val selected: List<String> = emptyList(),
  val busy: Boolean = false,
  val error: CommunityError? = null,
)

/** Create a community in three calm steps: name & photo, description, choose groups. */
class CreateCommunityViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val communities = container.communities!!
  private val chats = container.chats!!
  private val _state = MutableStateFlow(CreateCommunityState())
  val state: StateFlow<CreateCommunityState> = _state

  init {
    viewModelScope.launch {
      val convs = runCatching { chats.conversations(me).first().value }.getOrDefault(emptyList())
      val mine = convs.filter { it.isGroup && me in it.admins && it.communityId == null }
        .map { LinkedGroup(it.id, it.name.orEmpty(), me) }
      _state.update { it.copy(loadingGroups = false, selectableGroups = CreateFlow.selectable(mine, emptySet())) }
    }
  }

  fun onName(v: String) = _state.update { it.copy(name = v.take(CommunityRules.MAX_NAME), error = null) }
  fun onDescription(v: String) = _state.update { it.copy(description = v.take(CommunityRules.MAX_DESCRIPTION), error = null) }
  fun onPhoto(uri: Uri?) = _state.update { it.copy(photoUri = uri) }
  fun toggleGroup(cid: String) = _state.update { it.copy(selected = CreateFlow.toggle(it.selected, cid)) }

  fun canContinue(): Boolean = _state.value.let { CreateFlow.canContinue(it.step, it.name, it.description) }

  fun next() = _state.update { s -> s.step.next()?.let { s.copy(step = it) } ?: s }
  fun back(): Boolean {
    val previous = _state.value.step.previous() ?: return false
    _state.update { it.copy(step = previous) }
    return true
  }

  fun create(then: (id: String, announcementsCid: String) -> Unit) {
    val s = _state.value
    val name = CommunityRules.validName(s.name) ?: return
    val description = CommunityRules.validDescription(s.description) ?: return
    if (s.busy) return
    viewModelScope.launch {
      _state.update { it.copy(busy = true, error = null) }
      try {
        val (id, announcementsCid) = communities.create(name, description, s.selected)
        s.photoUri?.let { uri -> runCatching { communities.setPhoto(id, container.media!!.prepareAvatar(uri)) } }
        then(id, announcementsCid)
      } catch (e: Exception) {
        _state.update { it.copy(error = toError(e)) }
      } finally {
        _state.update { it.copy(busy = false) }
      }
    }
  }

  private fun toError(e: Exception): CommunityError = when (e) {
    is CommunityException.CommunityFull -> CommunityError.FULL
    is CommunityException.GroupsFull -> CommunityError.GROUPS_FULL
    is CommunityException.AlreadyLinked -> CommunityError.ALREADY_LINKED
    is CommunityException.NotAllowed -> CommunityError.NOT_ALLOWED
    is CommunityException.LimitReached -> CommunityError.LIMIT
    else -> CommunityError.FAILED
  }

  companion object {
    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { CreateCommunityViewModel(container, me) } }
  }
}
