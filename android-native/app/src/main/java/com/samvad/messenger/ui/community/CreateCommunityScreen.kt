package com.samvad.messenger.ui.community

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ChatList
import com.samvad.core.community.CommunityRules
import com.samvad.core.community.CreateStep
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme

/** Create a community: name & photo, description, choose groups — one calm step at a time. */
@Composable
fun CreateCommunityScreen(container: AppContainer, me: String, onBack: () -> Unit, onCreated: (id: String, announcementsCid: String) -> Unit) {
  val colors = SamvadTheme.colors
  val vm: CreateCommunityViewModel = viewModel(key = "newcommunity", factory = CreateCommunityViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.onPhoto(uri) }
  BackHandler(enabled = state.step != CreateStep.NAME_AND_PHOTO) { vm.back() }

  Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { if (!vm.back()) onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Column {
        Text(stringResource(R.string.community_new), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text(
          stringResource(R.string.community_step_label, state.step.number, com.samvad.core.community.CreateFlow.TOTAL),
          style = MaterialTheme.typography.labelMedium,
          color = colors.onSurfaceVariant,
        )
      }
    }
    AnimatedContent(state.step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "createCommunityStep", modifier = Modifier.weight(1f)) { step ->
      when (step) {
        CreateStep.NAME_AND_PHOTO -> NameAndPhotoStep(state.name, state.photoUri, container, onName = vm::onName, onPickPhoto = {
          container.appLock.expectExternal()
          pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        CreateStep.DESCRIPTION -> DescriptionStep(state.description, onDescription = vm::onDescription)
        CreateStep.GROUPS -> GroupsStep(state, onToggle = vm::toggleGroup)
      }
    }
    Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      state.error?.let { Text(communityErrorText(it), color = colors.error, style = MaterialTheme.typography.bodySmall) }
      if (state.step == CreateStep.GROUPS) {
        PrimaryButton(stringResource(R.string.community_create), onClick = { vm.create(onCreated) }, enabled = !state.busy, loading = state.busy)
      } else {
        PrimaryButton(stringResource(R.string.group_next), onClick = vm::next, enabled = vm.canContinue())
      }
    }
  }
}

@Composable
private fun NameAndPhotoStep(name: String, photoUri: android.net.Uri?, container: AppContainer, onName: (String) -> Unit, onPickPhoto: () -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.clickable(onClickLabel = stringResource(R.string.community_change_photo), onClick = onPickPhoto)) {
      if (photoUri != null) {
        Avatar(communityInitials(name), "new", size = 96.dp, photoPath = null)
      } else {
        Box(Modifier.size(96.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
          Icon(Icons.Outlined.AddAPhoto, stringResource(R.string.community_change_photo), tint = colors.onPrimaryContainer)
        }
      }
    }
    SamvadTextField(name, onName, stringResource(R.string.community_name_hint), maxLength = CommunityRules.MAX_NAME)
    Text(stringResource(R.string.community_name_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
  }
}

@Composable
private fun DescriptionStep(description: String, onDescription: (String) -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    androidx.compose.material3.OutlinedTextField(
      value = description,
      onValueChange = { if (it.length <= CommunityRules.MAX_DESCRIPTION) onDescription(it) },
      label = { Text(stringResource(R.string.community_description_hint)) },
      minLines = 4,
      modifier = Modifier.fillMaxWidth(),
      supportingText = { Text(stringResource(R.string.a11y_character_count, description.length, CommunityRules.MAX_DESCRIPTION)) },
    )
    Text(stringResource(R.string.community_description_help), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
  }
}

@Composable
private fun GroupsStep(state: CreateCommunityState, onToggle: (String) -> Unit) {
  val colors = SamvadTheme.colors
  Column(Modifier.fillMaxSize()) {
    Text(
      stringResource(R.string.community_groups_help, com.samvad.core.community.CommunityRules.MAX_GROUPS),
      style = MaterialTheme.typography.bodyMedium,
      color = colors.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    when {
      state.loadingGroups -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
      state.selectableGroups.isEmpty() -> EmptyState(
        Icons.Outlined.Groups,
        stringResource(R.string.community_no_groups_title),
        stringResource(R.string.community_no_groups_body),
        modifier = Modifier.fillMaxSize(),
      )
      else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(state.selectableGroups, key = { it.cid }) { g ->
          val checked = g.cid in state.selected
          Row(
            Modifier
              .fillMaxWidth()
              .toggleable(checked, role = Role.Checkbox) { onToggle(g.cid) }
              .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Avatar(ChatList.initials(g.name), g.cid, size = 44.dp)
            Text(g.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
          }
        }
      }
    }
  }
}
