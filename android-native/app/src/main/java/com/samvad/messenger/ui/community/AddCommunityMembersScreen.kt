package com.samvad.messenger.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatList
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.groups.Candidate
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddCommunityMembersState(
  val loading: Boolean = true,
  val candidates: List<Candidate> = emptyList(),
  val selected: List<Candidate> = emptyList(),
  val query: String = "",
  val busy: Boolean = false,
)

/** Candidates are the community admin's direct-chat partners and Samvad contacts, minus existing members. */
private class AddCommunityMembersViewModel(private val container: AppContainer, private val me: String, private val id: String) : ViewModel() {
  private val communities = container.communities!!
  private val chats = container.chats!!
  private val _state = MutableStateFlow(AddCommunityMembersState())
  val state: StateFlow<AddCommunityMembersState> = _state

  init {
    viewModelScope.launch {
      val community = runCatching { communities.community(id).first() }.getOrNull()
      val existing = community?.members.orEmpty().toMutableSet().apply { add(me) }
      val found = linkedMapOf<String, String>()
      runCatching {
        val convs = chats.conversations(me).first().value
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
      val list = found.filterKeys { it !in existing }.map { (uid, name) -> Candidate(uid, name) }.sortedBy { it.name.lowercase() }
      _state.update { it.copy(loading = false, candidates = list) }
    }
  }

  fun onQuery(q: String) = _state.update { it.copy(query = q) }

  fun toggle(c: Candidate) = _state.update { s ->
    if (s.selected.any { it.uid == c.uid }) s.copy(selected = s.selected.filterNot { it.uid == c.uid })
    else s.copy(selected = s.selected + c)
  }

  fun add(then: () -> Unit) {
    if (_state.value.busy || _state.value.selected.isEmpty()) return
    viewModelScope.launch {
      _state.update { it.copy(busy = true) }
      runCatching { communities.addMembers(id, _state.value.selected.map { it.uid }) }
      _state.update { it.copy(busy = false) }
      then()
    }
  }

  companion object {
    fun factory(container: AppContainer, me: String, id: String) = viewModelFactory { initializer { AddCommunityMembersViewModel(container, me, id) } }
  }
}

/** Add people directly to a community (admins only; the server checks). */
@Composable
fun AddCommunityMembersScreen(container: AppContainer, me: String, id: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: AddCommunityMembersViewModel = viewModel(key = "communityadd-$id", factory = AddCommunityMembersViewModel.factory(container, me, id))
  val state = vm.state.collectAsStateWithLifecycle().value

  Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.community_add_members), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    SamvadTextField(state.query, vm::onQuery, stringResource(R.string.newchat_search), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    Box(Modifier.weight(1f)) {
      val q = state.query.trim()
      val shown = state.candidates.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
      when {
        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.brand)
        state.candidates.isEmpty() -> EmptyState(
          Icons.Outlined.Groups,
          stringResource(R.string.group_no_people_title),
          stringResource(R.string.group_no_people_body),
          modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
          items(shown, key = { it.uid }) { c ->
            val checked = state.selected.any { it.uid == c.uid }
            Row(
              Modifier
                .fillMaxWidth()
                .toggleable(checked, role = Role.Checkbox) { vm.toggle(c) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Avatar(ChatList.initials(c.name), c.uid, size = 44.dp)
              Text(c.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
              Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
            }
          }
        }
      }
    }
    Column(Modifier.navigationBarsPadding().padding(16.dp)) {
      PrimaryButton(stringResource(R.string.community_add), onClick = { vm.add(onBack) }, enabled = state.selected.isNotEmpty(), loading = state.busy)
    }
  }
}
