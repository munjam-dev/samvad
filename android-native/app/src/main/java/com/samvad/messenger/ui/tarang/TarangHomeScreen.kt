package com.samvad.messenger.ui.tarang

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.chat.ChatList
import com.samvad.core.tarang.TarangAvailability
import com.samvad.core.tarang.TarangNickname
import com.samvad.core.tarang.TarangPeer
import com.samvad.messenger.R
import com.samvad.messenger.tarang.TarangRepository
import com.samvad.messenger.ui.components.Avatar
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.components.UnreadBadge
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
  var c: Context? = this
  while (c is ContextWrapper) {
    if (c is Activity) return c
    c = c.baseContext
  }
  return null
}

/**
 * Tarang home. Pattern: List (people nearby) under a hero status block. While Tarang is off the same route shows
 * an explanation screen first; the permission dialogs follow straight after the "Turn on Tarang" tap.
 */
@Composable
fun TarangHomeScreen(
  repository: TarangRepository,
  onBack: () -> Unit,
  onOpenRoom: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  val colors = SamvadTheme.colors
  val state = repository.state.collectAsStateWithLifecycle().value
  val context = LocalContext.current
  var info by rememberSaveable { mutableStateOf(false) }
  var deniedAfterAsk by rememberSaveable { mutableStateOf(false) }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { repository.refreshAvailability() }

  val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    repository.refreshAvailability()
    if (repository.state.value.availability == TarangAvailability.OFF) repository.start()
  }
  fun continueAfterPermissions() {
    repository.refreshAvailability()
    when (repository.state.value.availability) {
      TarangAvailability.OFF -> repository.start()
      TarangAvailability.BLUETOOTH_OFF -> if (repository.hasRequiredPermissions()) {
        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
      }
      else -> Unit
    }
  }
  val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    deniedAfterAsk = !repository.hasRequiredPermissions()
    if (!deniedAfterAsk) continueAfterPermissions()
  }
  // One batch: Android shows its dialogs one after another, so there is no extra tap per permission.
  fun askAndStart() {
    val all = (TarangRepository.requiredPermissions() + TarangRepository.optionalPermissions()).toTypedArray()
    if (repository.hasRequiredPermissions()) continueAfterPermissions() else permissions.launch(all)
  }

  Column(Modifier.fillMaxSize().background(colors.background)) {
    Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
      Text(stringResource(R.string.tarang_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
      IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.tarang_info_cd), tint = colors.onSurface) }
    }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        when (state.availability) {
          TarangAvailability.UNSUPPORTED -> Column(Modifier.verticalScroll(rememberScrollState())) {
            EmptyState(Icons.Outlined.PhoneAndroid, stringResource(R.string.tarang_unsupported_title), stringResource(R.string.tarang_unsupported_body))
          }
          TarangAvailability.RUNNING -> RunningContent(repository, onOpenRoom, onOpenChat)
          TarangAvailability.BLUETOOTH_OFF -> Column(Modifier.verticalScroll(rememberScrollState())) {
            EmptyState(
              Icons.Outlined.BluetoothDisabled,
              stringResource(R.string.tarang_bt_off_title),
              stringResource(R.string.tarang_bt_off_body),
              action = stringResource(R.string.tarang_bt_turn_on),
              onAction = { askAndStart() },
            )
          }
          TarangAvailability.NEEDS_PERMISSION, TarangAvailability.OFF -> {
            val activity = context.findActivity()
            val canAskAgain = activity == null || TarangRepository.requiredPermissions().any { activity.shouldShowRequestPermissionRationale(it) }
            val blocked = deniedAfterAsk && !canAskAgain
            IntroContent(
              denied = deniedAfterAsk,
              blocked = blocked,
              onTurnOn = { if (blocked) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) else askAndStart() },
              onHow = { info = true },
            )
          }
        }
      }
    }
  }
  if (info) TarangInfoSheet(onDismiss = { info = false }, onReset = { repository.resetIdentity() })
}

@Composable
private fun IntroContent(denied: Boolean, blocked: Boolean, onTurnOn: () -> Unit, onHow: () -> Unit) {
  val colors = SamvadTheme.colors
  val oldAndroid = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(72.dp).background(colors.primaryContainer, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.Bluetooth, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(36.dp))
    }
    Text(stringResource(R.string.tarang_intro_title), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.tarang_intro_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
    IntroPoint(Icons.Outlined.Groups, R.string.tarang_intro_point_1)
    IntroPoint(Icons.Outlined.Lock, R.string.tarang_intro_point_2)
    IntroPoint(Icons.Outlined.WifiOff, R.string.tarang_intro_point_3)
    Text(stringResource(if (oldAndroid) R.string.tarang_perm_why_old else R.string.tarang_perm_why), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    Text(stringResource(R.string.tarang_battery), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    if (denied) {
      Column(Modifier.fillMaxWidth().background(colors.tertiaryContainer, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(stringResource(R.string.tarang_perm_denied_title), style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
        Text(stringResource(R.string.tarang_perm_denied_body), style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer, modifier = Modifier.padding(top = 4.dp))
      }
    }
    PrimaryButton(stringResource(if (blocked) R.string.tarang_open_settings else R.string.tarang_turn_on), onTurnOn)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { QuietButton(stringResource(R.string.tarang_how_link), onHow) }
  }
}

@Composable
private fun IntroPoint(icon: ImageVector, text: Int) {
  val colors = SamvadTheme.colors
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
    Icon(icon, null, tint = colors.primary, modifier = Modifier.size(24.dp))
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun RunningContent(repository: TarangRepository, onOpenRoom: () -> Unit, onOpenChat: (String) -> Unit) {
  val colors = SamvadTheme.colors
  val state = repository.state.collectAsStateWithLifecycle().value
  var editing by rememberSaveable { mutableStateOf(false) }
  var confirmStop by rememberSaveable { mutableStateOf(false) }
  LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item(key = "hero") { HeroStatus(state.peerCount, state.nickname, onEdit = { editing = true }) }
    item(key = "room") {
      Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(20.dp))
          .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpenRoom).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Icon(Icons.Outlined.Groups, null, tint = colors.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.tarang_room_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
          val last = state.publicMessages.lastOrNull()
          Text(
            last?.let { it.senderName + ": " + it.text } ?: stringResource(R.string.tarang_room_sub),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
          )
        }
        if (state.publicUnread > 0) UnreadBadge(state.publicUnread, stringResource(R.string.tarang_unread_cd, state.publicUnread))
      }
    }
    item(key = "peers_title") {
      Text(stringResource(R.string.tarang_peers_title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    }
    if (state.peers.isEmpty()) {
      item(key = "peers_empty") {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
          Text(stringResource(R.string.tarang_peers_empty_title), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
          Text(stringResource(R.string.tarang_peers_empty_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
      }
    } else {
      items(state.peers, key = { it.peerId }) { p -> PeerRow(p, state.unread[p.peerId] ?: 0, onClick = { onOpenChat(p.peerId) }) }
    }
    item(key = "stop") {
      Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = colors.outlineVariant)
        Text(stringResource(R.string.tarang_battery), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        QuietButton(stringResource(R.string.tarang_stop), { confirmStop = true })
      }
    }
  }
  if (editing) NicknameDialog(state.nickname, repository, onDismiss = { editing = false })
  if (confirmStop) {
    AlertDialog(
      onDismissRequest = { confirmStop = false },
      title = { Text(stringResource(R.string.tarang_stop_confirm_title)) },
      text = { Text(stringResource(R.string.tarang_stop_confirm_body)) },
      confirmButton = { TextButton(onClick = { confirmStop = false; repository.stop() }) { Text(stringResource(R.string.tarang_stop), color = colors.error) } },
      dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.tarang_cancel)) } },
    )
  }
}

@Composable
private fun HeroStatus(count: Int, nickname: String, onEdit: () -> Unit) {
  val colors = SamvadTheme.colors
  val reduced = LocalReducedMotion.current
  // A short pulse while looking for people (three sweeps), then it rests. No endless loop.
  val sweep = remember { Animatable(0f) }
  LaunchedEffect(count == 0, reduced) {
    if (count == 0 && !reduced) {
      repeat(3) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, androidx.compose.animation.core.tween(1600))
      }
    } else sweep.snapTo(0f)
  }
  Column(Modifier.fillMaxWidth().background(colors.primaryContainer, RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      val ring = colors.onPrimaryContainer
      Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
          val c = Offset(size.width / 2, size.height / 2)
          val base = size.minDimension / 2
          drawCircle(ring.copy(alpha = 0.18f), radius = base * 0.55f, center = c, style = Stroke(2.dp.toPx()))
          drawCircle(ring.copy(alpha = 0.12f), radius = base * 0.85f, center = c, style = Stroke(2.dp.toPx()))
          if (sweep.value > 0f) drawCircle(ring.copy(alpha = 0.5f * (1f - sweep.value)), radius = base * (0.3f + 0.7f * sweep.value), center = c, style = Stroke(3.dp.toPx()))
        }
        Icon(if (count == 0) Icons.Outlined.BluetoothSearching else Icons.Outlined.Bluetooth, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(28.dp))
      }
      Column(Modifier.weight(1f)) {
        Text(
          if (count == 0) stringResource(R.string.tarang_status_scanning) else pluralStringResource(R.plurals.tarang_peers_count, count, count),
          style = MaterialTheme.typography.titleLarge, color = colors.onPrimaryContainer,
        )
        if (count == 0) Text(stringResource(R.string.tarang_status_scanning_sub), style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
      }
    }
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.tarang_nickname_change), onClick = onEdit),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.tarang_you_are), style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
        Text(nickname, style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      Icon(Icons.Outlined.Edit, stringResource(R.string.tarang_nickname_change), tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
    }
  }
}

@Composable
private fun PeerRow(peer: TarangPeer, unread: Int, onClick: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Avatar(ChatList.initials(peer.nickname), peer.peerId, size = 48.dp, photoPath = null)
    Column(Modifier.weight(1f)) {
      Text(peer.nickname, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(
        stringResource(if (peer.direct) R.string.tarang_peer_direct else R.string.tarang_peer_relayed),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
      )
    }
    if (peer.encrypted) Icon(Icons.Outlined.Lock, stringResource(R.string.tarang_peer_encrypted_cd), tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    if (unread > 0) UnreadBadge(unread, stringResource(R.string.tarang_unread_cd, unread))
  }
}

@Composable
private fun NicknameDialog(current: String, repository: TarangRepository, onDismiss: () -> Unit) {
  var value by rememberSaveable { mutableStateOf(current) }
  var error by rememberSaveable { mutableStateOf<Int?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.tarang_nickname_title)) },
    text = {
      SamvadTextField(
        value = value,
        onValueChange = { value = it; error = null },
        label = stringResource(R.string.tarang_nickname_hint),
        supportingText = error?.let { stringResource(it) } ?: stringResource(R.string.tarang_nickname_help),
        isError = error != null,
        maxLength = TarangNickname.MAX_LENGTH + 8,
      )
    },
    confirmButton = {
      TextButton(onClick = {
        when (repository.setNickname(value)) {
          is TarangNickname.Result.Valid -> onDismiss()
          TarangNickname.Result.TooShort -> error = R.string.tarang_nickname_too_short
          TarangNickname.Result.TooLong -> error = R.string.tarang_nickname_too_long
          TarangNickname.Result.InvalidCharacters -> error = R.string.tarang_nickname_invalid
        }
      }) { Text(stringResource(R.string.tarang_save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tarang_cancel)) } },
  )
}

/** The honest "how it works and what nearby people can see" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TarangInfoSheet(onDismiss: () -> Unit, onReset: () -> Unit) {
  val colors = SamvadTheme.colors
  var confirmReset by rememberSaveable { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
    Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(stringResource(R.string.tarang_info_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.semantics { heading() })
      listOf(R.string.tarang_info_1, R.string.tarang_info_2, R.string.tarang_info_3, R.string.tarang_info_4, R.string.tarang_info_5, R.string.tarang_info_6).forEach {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
      }
      QuietButton(stringResource(R.string.tarang_reset), { confirmReset = true })
    }
  }
  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text(stringResource(R.string.tarang_reset_confirm_title)) },
      text = { Text(stringResource(R.string.tarang_reset_confirm_body)) },
      confirmButton = { TextButton(onClick = { confirmReset = false; onReset(); onDismiss() }) { Text(stringResource(R.string.tarang_reset), color = colors.error) } },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.tarang_cancel)) } },
    )
  }
}
