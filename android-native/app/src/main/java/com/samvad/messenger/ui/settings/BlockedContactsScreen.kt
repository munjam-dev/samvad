package com.samvad.messenger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.ChatList
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/** Settings → Privacy → Blocked contacts (Phase 1.3). */
@Composable
fun BlockedContactsScreen(container: AppContainer, me: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val safety = container.safety!!
  val chats = container.chats!!
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val failed = stringResource(R.string.safety_failed)
  val blocked = remember(me) { safety.blocked(me) }.collectAsStateWithLifecycle(null).value
  val profiles = chats.profiles.collectAsStateWithLifecycle().value
  LaunchedEffect(blocked) { blocked?.let { chats.loadProfiles(it) } }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.settings_blocked), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
      }
      when {
        blocked == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = colors.brand)
        }
        blocked.isEmpty() -> EmptyState(
          Icons.Outlined.Block,
          stringResource(R.string.settings_blocked),
          stringResource(R.string.blocked_empty),
          modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(
          Modifier.fillMaxSize().navigationBarsPadding(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(blocked.sorted(), key = { it }) { uid ->
            val name = profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT
            Row(
              Modifier
                .fillMaxWidth()
                .animateItem()
                .background(colors.surface, MaterialTheme.shapes.large)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              Avatar(ChatList.initials(name), uid, size = 44.dp)
              Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = {
                scope.launch { runCatching { safety.unblock(me, uid) }.onFailure { snackbar.showSnackbar(failed) } }
              }) { Text(stringResource(R.string.chat_unblock), color = colors.primary, fontWeight = FontWeight.SemiBold) }
            }
          }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }
}
