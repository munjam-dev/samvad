package com.samvad.messenger.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.GroupRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme

@Composable
internal fun groupErrorText(error: GroupError): String = stringResource(
  when (error) {
    GroupError.NO_ELIGIBLE -> R.string.group_error_no_eligible
    GroupError.FULL -> R.string.group_error_full
    GroupError.LIMIT -> R.string.group_error_limit
    GroupError.NOT_ALLOWED -> R.string.group_error_not_allowed
    GroupError.FAILED -> R.string.group_error_failed
  },
)

/** New group: choose people, then name it. */
@Composable
fun NewGroupScreen(container: AppContainer, me: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
  val vm: GroupPickerViewModel = viewModel(key = "newgroup", factory = GroupPickerViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  BackHandler(enabled = state.naming) { vm.back() }
  AnimatedContent(state.naming, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "newGroupStep") { naming ->
    if (!naming) {
      PeoplePicker(
        title = stringResource(R.string.group_new),
        state = state,
        onBack = onBack,
        onQuery = vm::onQuery,
        onToggle = vm::toggle,
        action = stringResource(R.string.group_next),
        onAction = vm::next,
      )
    } else {
      NameStep(state, onBack = vm::back, onName = vm::onName, onCreate = { vm.create(onCreated) })
    }
  }
}

/** Add people to an existing group (admins only; the server checks). */
@Composable
fun AddMembersScreen(container: AppContainer, me: String, cid: String, onBack: () -> Unit) {
  val vm: GroupPickerViewModel = viewModel(key = "add-$cid", factory = GroupPickerViewModel.factory(container, me, cid))
  val state = vm.state.collectAsStateWithLifecycle().value
  PeoplePicker(
    title = stringResource(R.string.group_add_members),
    state = state,
    onBack = onBack,
    onQuery = vm::onQuery,
    onToggle = vm::toggle,
    action = stringResource(R.string.group_add),
    onAction = { vm.add(onBack) },
  )
}

@Composable
private fun PeoplePicker(
  title: String,
  state: GroupPickerState,
  onBack: () -> Unit,
  onQuery: (String) -> Unit,
  onToggle: (Candidate) -> Unit,
  action: String,
  onAction: () -> Unit,
) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(
          stringResource(R.string.group_selected, state.selected.size, GroupRules.MAX_MEMBERS - 1),
          style = MaterialTheme.typography.labelMedium,
          color = colors.onSurfaceVariant,
        )
      }
    }
    if (state.selected.isNotEmpty()) {
      LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.selected, key = { it.uid }) { c ->
          InputChip(selected = true, onClick = { onToggle(c) }, label = { Text(c.name, maxLines = 1) })
        }
      }
    }
    SamvadTextField(state.query, onQuery, stringResource(R.string.newchat_search), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
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
                .toggleable(checked, role = Role.Checkbox) { onToggle(c) }
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
    Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.error?.let { Text(groupErrorText(it), color = colors.error, style = MaterialTheme.typography.bodySmall) }
      PrimaryButton(action, onAction, enabled = state.selected.isNotEmpty(), loading = state.busy)
    }
  }
}

@Composable
private fun NameStep(state: GroupPickerState, onBack: () -> Unit, onName: (String) -> Unit, onCreate: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.group_name_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
    }
    Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      SamvadTextField(state.name, onName, stringResource(R.string.group_name_hint))
      Text(
        pluralStringResource(R.plurals.group_members_count, state.selected.size + 1, state.selected.size + 1),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
      )
      Text(state.selected.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.error?.let { Text(groupErrorText(it), color = colors.error, style = MaterialTheme.typography.bodySmall) }
      PrimaryButton(
        stringResource(R.string.group_create),
        onCreate,
        enabled = GroupRules.validName(state.name) != null,
        loading = state.busy,
      )
    }
  }
}
