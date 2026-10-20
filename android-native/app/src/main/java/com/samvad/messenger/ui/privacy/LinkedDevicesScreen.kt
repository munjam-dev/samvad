package com.samvad.messenger.ui.privacy

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.privacy.LinkedDevice
import com.samvad.core.time.TimeLabels
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/**
 * Settings → Linked devices. Pattern: List / Index (vectorlab-ux-skills:page-patterns #1): scan this
 * account's installs and sign out the ones that are not this device.
 */
@Composable
fun LinkedDevicesScreen(container: AppContainer, me: String, onBack: () -> Unit) {
  val colors = SamvadTheme.colors
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val devicesRepo = container.devices
  val failed = stringResource(R.string.devices_revoke_failed)
  var confirming by remember { mutableStateOf<LinkedDevice?>(null) }
  var busyId by remember { mutableStateOf<String?>(null) }
  val thisDeviceId = produceState("") { value = runCatching { container.preferences.deviceId() }.getOrDefault("") }.value
  val devices = if (devicesRepo != null && thisDeviceId.isNotEmpty()) {
    remember(me, thisDeviceId) { devicesRepo.devices(me, thisDeviceId) }.collectAsStateWithLifecycle(null).value
  } else {
    null
  }

  Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.statusBarsPadding().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
      }
      Text(
        stringResource(R.string.devices_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, bottom = 8.dp),
      )
      when {
        devices == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(color = colors.brand)
        }
        devices.isEmpty() -> EmptyState(
          Icons.Outlined.Devices,
          stringResource(R.string.devices_title),
          stringResource(R.string.devices_empty),
          modifier = Modifier.fillMaxSize(),
        )
        else -> LazyColumn(
          Modifier.fillMaxSize().navigationBarsPadding(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(devices, key = { it.id }) { device ->
            DeviceRow(
              device = device,
              busy = busyId == device.id,
              onRevoke = { confirming = device },
            )
          }
        }
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
  }

  val target = confirming
  if (target != null) {
    AlertDialog(
      onDismissRequest = { confirming = null },
      title = { Text(stringResource(R.string.devices_revoke_title)) },
      text = { Text(stringResource(R.string.devices_revoke_body)) },
      confirmButton = {
        TextButton(onClick = {
          confirming = null
          busyId = target.id
          scope.launch {
            runCatching { devicesRepo?.revokeDevice(target.id) }
              .onFailure { snackbar.showSnackbar(failed) }
            busyId = null
          }
        }) { Text(stringResource(R.string.devices_revoke_confirm), color = colors.error) }
      },
      dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

@Composable
private fun DeviceRow(device: LinkedDevice, busy: Boolean, onRevoke: () -> Unit) {
  val colors = SamvadTheme.colors
  Row(
    Modifier
      .fillMaxWidth()
      .background(colors.surface, MaterialTheme.shapes.large)
      .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Icon(Icons.Outlined.PhoneAndroid, null, tint = colors.onPrimaryContainer)
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.devices_platform_android), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
        if (device.isThisDevice) {
          Text(
            stringResource(R.string.devices_this_device),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
          )
        }
      }
      Text(
        stringResource(R.string.devices_detail, device.osVersion, device.appVersion),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
      Text(
        stringResource(R.string.devices_last_active, TimeLabels.chatListTime(device.lastActiveMs, System.currentTimeMillis())),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    if (!device.isThisDevice) {
      if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), color = colors.brand, strokeWidth = 2.dp)
      } else {
        TextButton(onClick = onRevoke) {
          Text(stringResource(R.string.devices_sign_out_device), color = colors.error)
        }
      }
    }
  }
}
