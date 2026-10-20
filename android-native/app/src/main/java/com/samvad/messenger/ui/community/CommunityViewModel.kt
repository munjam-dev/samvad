package com.samvad.messenger.ui.community

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.core.community.Community
import com.samvad.core.community.CommunityRole
import com.samvad.core.community.CommunityRules
import com.samvad.core.community.MemberAction
import com.samvad.core.community.MemberRow
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.CommunityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CommunityError { FULL, GROUPS_FULL, GROUP_FULL, ADMIN_LIMIT, LAST_ADMIN, ALREADY_LINKED, NOT_ALLOWED, LIMIT, FAILED }

data class LinkedGroupRow(val cid: String, val name: String, val joined: Boolean)

data class CommunityUiState(
  val loading: Boolean = true,
  val gone: Boolean = false,
  val name: String = "",
  val description: String = "",
  val photoPath: String? = null,
  val announcementsCid: String = "",
  val isAdmin: Boolean = false,
  val memberCount: Int = 0,
  val groups: List<LinkedGroupRow> = emptyList(),
  val members: List<MemberRow> = emptyList(),
)

/** One community screen: header, linked groups, roster and admin actions. Every change is a callable (server-checked). */
class CommunityViewModel(private val container: AppContainer, private val me: String, private val id: String) : ViewModel() {
  private val communities = container.communities!!
  private val chats = container.chats!!

  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy
  private val _error = MutableStateFlow<CommunityError?>(null)
  val error: StateFlow<CommunityError?> = _error
  private val _invite = MutableStateFlow<String?>(null)
  val invite: StateFlow<String?> = _invite
  private val myGroupMemberships = MutableStateFlow<Set<String>>(emptySet())

  init {
    viewModelScope.launch {
      chats.conversations(me).catch { }.collect { snap ->
        myGroupMemberships.value = snap.value.filter { it.isGroup }.map { it.id }.toSet()
      }
    }
  }

  val state: StateFlow<CommunityUiState> = combine(
    communities.community(id).catch { emit(null) },
    chats.profiles,
    myGroupMemberships,
  ) { c, profiles, myGroups ->
    if (c == null) return@combine CommunityUiState(loading = false, gone = true)
    val missing = (c.members + c.groups.map(com.samvad.core.community.LinkedGroup::linkedBy)).filter { it !in profiles }
    if (missing.isNotEmpty()) viewModelScope.launch { chats.loadProfiles(missing) }
    val members = c.members.map { uid ->
      MemberRow(uid, profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT, CommunityRules.roleOf(c, uid) ?: CommunityRole.MEMBER, uid == me)
    }
    CommunityUiState(
      loading = false,
      name = c.name,
      description = c.description,
      photoPath = c.photoPath,
      announcementsCid = c.announcementsCid,
      isAdmin = CommunityRules.isAdmin(c, me),
      memberCount = c.members.size,
      groups = c.groups.map { LinkedGroupRow(it.cid, it.name, it.cid in myGroups) },
      members = CommunityRules.sortMembers(members),
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommunityUiState())

  fun clearError() { _error.value = null }

  fun memberActions(uid: String): List<MemberAction> {
    // Re-derived from the live state so the sheet never offers an action the server would refuse.
    val s = state.value
    val me0 = s.members.firstOrNull { it.me } ?: return emptyList()
    if (!s.isAdmin || uid == me) return emptyList()
    val target = s.members.firstOrNull { it.uid == uid } ?: return emptyList()
    val admins = s.members.count { it.role == CommunityRole.ADMIN }
    val promote = when {
      target.role == CommunityRole.ADMIN -> MemberAction.DEMOTE
      admins < com.samvad.core.community.CommunityRules.MAX_ADMINS -> MemberAction.PROMOTE
      else -> null
    }
    return listOfNotNull(promote, MemberAction.REMOVE)
  }

  private fun act(work: suspend () -> Unit) = viewModelScope.launch {
    if (_busy.value) return@launch
    _busy.value = true
    _error.value = try {
      work()
      null
    } catch (e: CommunityException.CommunityFull) {
      CommunityError.FULL
    } catch (e: CommunityException.GroupsFull) {
      CommunityError.GROUPS_FULL
    } catch (e: CommunityException.GroupFull) {
      CommunityError.GROUP_FULL
    } catch (e: CommunityException.AdminLimit) {
      CommunityError.ADMIN_LIMIT
    } catch (e: CommunityException.LastAdmin) {
      CommunityError.LAST_ADMIN
    } catch (e: CommunityException.AlreadyLinked) {
      CommunityError.ALREADY_LINKED
    } catch (e: CommunityException.NotAllowed) {
      CommunityError.NOT_ALLOWED
    } catch (e: CommunityException.LimitReached) {
      CommunityError.LIMIT
    } catch (e: Exception) {
      CommunityError.FAILED
    }
    _busy.value = false
  }

  fun rename(name: String) { CommunityRules.validName(name)?.let { n -> act { communities.updateInfo(id, name = n) } } }
  fun describe(text: String) { CommunityRules.validDescription(text)?.let { d -> act { communities.updateInfo(id, description = d) } } }
  fun setPhoto(uri: Uri) = act { communities.setPhoto(id, container.media!!.prepareAvatar(uri)) }
  fun removePhoto() = act { communities.setPhoto(id, null) }
  fun setAdmin(uid: String, admin: Boolean) = act { communities.setAdmin(id, uid, admin) }
  fun removeMember(uid: String) = act { communities.removeMember(id, uid) }
  fun unlinkGroup(cid: String) = act { communities.unlinkGroup(id, cid) }
  fun deleteCommunity(then: () -> Unit) = act { communities.delete(id); then() }

  fun leave(then: () -> Unit) = act { communities.leave(id); then() }

  /** Joins a linked group, respecting its own cap and blocks (checked on the server); opens it on success. */
  fun joinGroup(cid: String, then: (String) -> Unit) = act { then(communities.joinGroup(id, cid)) }

  fun showInvite() = act { _invite.value = communities.invite(id) }
  fun regenerateInvite() = act { _invite.value = communities.invite(id, regenerate = true) }
  fun revokeInvite() = act { communities.revokeInvite(id); _invite.value = null }
  fun hideInvite() { _invite.value = null }

  companion object {
    fun factory(container: AppContainer, me: String, id: String) =
      viewModelFactory { initializer { CommunityViewModel(container, me, id) } }
  }
}
