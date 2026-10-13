package com.samvad.messenger.ui.groups

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.GroupRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.GroupException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupMember(val uid: String, val name: String, val admin: Boolean, val me: Boolean)

data class GroupInfoState(
  val loading: Boolean = true,
  /** I am no longer in the group (left, removed, or it is not mine). */
  val gone: Boolean = false,
  val name: String = "",
  val description: String = "",
  val photoPath: String? = null,
  val members: List<GroupMember> = emptyList(),
  val isAdmin: Boolean = false,
  val muted: Boolean = false,
)

/** Group info (Phase 2): every change goes through a group callable; the server checks admin rights. */
class GroupInfoViewModel(private val container: AppContainer, private val me: String, private val cid: String) : ViewModel() {
  private val chats = container.chats!!
  private val groups = container.groups!!

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy
  private val _error = MutableStateFlow<GroupError?>(null)
  val error: StateFlow<GroupError?> = _error
  private val _invite = MutableStateFlow<String?>(null)
  val invite: StateFlow<String?> = _invite

  val state: StateFlow<GroupInfoState> = combine(
    chats.conversation(cid).catch { emit(null) },
    chats.profiles,
    chats.memberState(cid, me),
  ) { g, profiles, mine ->
    if (g == null) return@combine GroupInfoState(loading = false, gone = true)
    val missing = g.members.filter { it !in profiles }
    if (missing.isNotEmpty()) viewModelScope.launch { chats.loadProfiles(missing) }
    val members = g.members.map { uid ->
      GroupMember(uid, profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT, uid in g.admins, uid == me)
    }.sortedWith(compareByDescending<GroupMember> { it.me }.thenByDescending { it.admin }.thenBy { it.name.lowercase() })
    GroupInfoState(
      loading = false,
      gone = me !in g.members,
      name = g.name.orEmpty(),
      description = g.description,
      photoPath = g.photoPath,
      members = members,
      isAdmin = GroupRules.isAdmin(g, me),
      muted = mine?.muted ?: false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoState())

  fun clearError() { _error.value = null }

  private fun act(work: suspend () -> Unit) = viewModelScope.launch {
    if (_busy.value) return@launch
    _busy.value = true
    _error.value = try {
      work()
      null
    } catch (e: GroupException.LastAdmin) {
      GroupError.NOT_ALLOWED
    } catch (e: GroupException.NotAllowed) {
      GroupError.NOT_ALLOWED
    } catch (e: GroupException.LimitReached) {
      GroupError.LIMIT
    } catch (e: Exception) {
      GroupError.FAILED
    }
    _busy.value = false
  }

  fun rename(name: String) { GroupRules.validName(name)?.let { n -> act { groups.updateInfo(cid, name = n) } } }
  fun describe(text: String) = act { groups.updateInfo(cid, description = text.take(GroupRules.MAX_DESCRIPTION)) }
  fun setPhoto(uri: Uri) = act { groups.setPhoto(cid, container.media!!.prepareAvatar(uri)) }
  fun removePhoto() = act { groups.setPhoto(cid, null) }
  fun setAdmin(uid: String, admin: Boolean) = act { groups.setAdmin(cid, uid, admin) }
  fun remove(uid: String) = act { groups.removeMember(cid, uid) }
  fun leave(then: () -> Unit) = act { groups.leave(cid); then() }
  fun toggleMute() = act { chats.setFlag(cid, me, "muted", !state.value.muted) }
  fun showInvite() = act { _invite.value = groups.createInvite(cid) }
  fun revokeInvite() = act { groups.revokeInvite(cid); _invite.value = null }
  fun hideInvite() { _invite.value = null }

  /** Opens (creating if needed) a direct chat with a member. */
  fun message(uid: String, then: (String, String) -> Unit) = act { then(chats.openDirect(me, uid), uid) }

  companion object {
    fun factory(container: AppContainer, me: String, cid: String) =
      viewModelFactory { initializer { GroupInfoViewModel(container, me, cid) } }
  }
}
