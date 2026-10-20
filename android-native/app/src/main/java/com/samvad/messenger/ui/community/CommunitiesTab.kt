package com.samvad.messenger.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.CommunityException
import com.samvad.messenger.data.CommunityPreview
import com.samvad.messenger.data.CommunityRepository
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.UnreadBadge
import com.samvad.messenger.ui.home.HomeMenu
import com.samvad.messenger.ui.home.TabTopBar
import com.samvad.messenger.ui.theme.SamvadTheme
import com.samvad.core.community.UnreadLabel
import kotlinx.coroutines.launch

/** My communities: a list with unread announcements, "Start a community" and "Join with code". */
@Composable
fun CommunitiesTab(container: AppContainer, me: String, menu: HomeMenu, onOpenCommunity: (String) -> Unit, onNewCommunity: () -> Unit) {
  val colors = SamvadTheme.colors
  val vm: CommunitiesViewModel = viewModel(factory = CommunitiesViewModel.factory(container, me))
  val state = vm.state.collectAsStateWithLifecycle().value
  var joinOpen by rememberSaveable { mutableStateOf(false) }

  Column(Modifier.fillMaxSize().background(colors.background)) {
    TabTopBar(R.string.nav_communities, menu)
    when {
      state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.brand) }
      state.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
          Icons.Outlined.Groups,
          stringResource(R.string.communities_title),
          stringResource(R.string.communities_body),
          action = stringResource(R.string.community_start),
          onAction = onNewCommunity,
        )
      }
      else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item(key = "actions") {
          Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.community_start), onClick = onNewCommunity, modifier = Modifier.weight(1f))
          }
        }
        item(key = "join") {
          Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            QuietButton(stringResource(R.string.community_join_with_code), onClick = { joinOpen = true })
          }
        }
        items(state.rows, key = { it.id }) { row ->
          Row(
            Modifier
              .fillMaxWidth()
              .padding(bottom = 8.dp)
              .background(colors.surface, RoundedCornerShape(16.dp))
              .clickable { onOpenCommunity(row.id) }
              .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Avatar(communityInitials(row.name), row.id, size = 48.dp, photoPath = row.photoPath)
            Column(Modifier.weight(1f)) {
              Text(row.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                pluralStringResource(row.memberCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
              )
            }
            if (UnreadLabel.of(row.unread) != null) {
              UnreadBadge(row.unread, stringResource(R.string.community_unread_announcements, row.unread))
            }
          }
        }
      }
    }
  }
  if (joinOpen) {
    JoinCommunityDialog(container.communities!!, onJoined = { id -> joinOpen = false; onOpenCommunity(id) }, onDismiss = { joinOpen = false })
  }
}

@Composable
private fun pluralStringResource(count: Int): String =
  androidx.compose.ui.res.pluralStringResource(R.plurals.community_members_count, count, count)

/** Enter an invite code, see which community it is (name, description and sizes only), then join. */
@Composable
fun JoinCommunityDialog(communities: CommunityRepository, onJoined: (String) -> Unit, onDismiss: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  var code by rememberSaveable { mutableStateOf("") }
  var preview by remember { mutableStateOf<CommunityPreview?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  val invalid = stringResource(R.string.community_join_invalid)
  val limit = stringResource(R.string.group_error_limit)
  val full = stringResource(R.string.community_error_full)
  val failed = stringResource(R.string.group_error_failed)
  val clean = com.samvad.core.community.CommunityRules.normalizeInvite(code)
  fun run(work: suspend () -> Unit) {
    busy = true
    error = null
    scope.launch {
      error = try {
        work()
        null
      } catch (e: CommunityException.InvalidCode) {
        invalid
      } catch (e: CommunityException.CommunityFull) {
        full
      } catch (e: CommunityException.LimitReached) {
        limit
      } catch (e: Exception) {
        failed
      }
      busy = false
    }
  }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(stringResource(R.string.community_join)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.material3.OutlinedTextField(
          value = code,
          onValueChange = { code = it.take(16); preview = null; error = null },
          label = { Text(stringResource(R.string.community_join_hint)) },
          singleLine = true,
          keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
        )
        preview?.let {
          Column {
            Text(it.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(
              stringResource(R.string.community_join_preview, it.memberCount, it.groupCount),
              style = MaterialTheme.typography.bodySmall,
              color = colors.onSurfaceVariant,
            )
          }
        }
        error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        if (busy) CircularProgressIndicator(Modifier, color = colors.brand)
      }
    },
    confirmButton = {
      val p = preview
      if (p == null) {
        androidx.compose.material3.TextButton(enabled = !busy && clean.length == com.samvad.core.community.CommunityRules.INVITE_LENGTH, onClick = { run { preview = communities.preview(clean) } }) {
          Text(stringResource(R.string.group_join_check))
        }
      } else {
        androidx.compose.material3.TextButton(enabled = !busy, onClick = { run { onJoined(communities.join(clean)) } }) { Text(stringResource(R.string.group_join_confirm)) }
      }
    },
    dismissButton = { androidx.compose.material3.TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
