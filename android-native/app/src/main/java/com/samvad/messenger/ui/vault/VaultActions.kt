package com.samvad.messenger.ui.vault

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultTypes
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.vault.VaultException
import com.samvad.messenger.data.vault.VaultViewCopy
import com.samvad.messenger.ui.components.SamvadTextField
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/** Short results the vault screens report in a snackbar. */
internal enum class VaultMessage(@param:StringRes val text: Int) {
  ADDED(R.string.vault_added),
  EXPORTED(R.string.vault_exported),
  DELETED(R.string.vault_deleted),
  NOTE_SAVED(R.string.vault_note_saved),
  LOCKED(R.string.vault_error_locked),
  TOO_LARGE(R.string.vault_error_too_large),
  EMPTY(R.string.vault_error_empty),
  UNREADABLE(R.string.vault_error_unreadable),
  FAILED(R.string.vault_error_failed),
  OPEN_FAILED(R.string.vault_open_failed),
  OPEN_UNSUPPORTED(R.string.vault_open_unsupported),
  MISSING(R.string.vault_missing),
}

internal fun VaultException.toMessage(): VaultMessage = when (reason) {
  VaultException.Reason.LOCKED -> VaultMessage.LOCKED
  VaultException.Reason.TOO_LARGE -> VaultMessage.TOO_LARGE
  VaultException.Reason.EMPTY -> VaultMessage.EMPTY
  VaultException.Reason.UNREADABLE -> VaultMessage.UNREADABLE
  VaultException.Reason.NOT_FOUND -> VaultMessage.MISSING
  VaultException.Reason.FAILED -> VaultMessage.FAILED
}

/** The things a person can do to one item. Each one asks first when it is destructive or leaves the vault. */
internal sealed interface VaultAction {
  val item: VaultItem

  class Rename(override val item: VaultItem) : VaultAction
  class Delete(override val item: VaultItem) : VaultAction
  class Export(override val item: VaultItem) : VaultAction
  class OpenExternally(override val item: VaultItem) : VaultAction
}

/**
 * Shows the dialog for [action] and carries it out. Shared by the list and the viewers so that confirmation
 * and cleanup behave the same everywhere. [onDone] is called when the action is finished or cancelled.
 */
@Composable
internal fun VaultActionHost(
  container: AppContainer,
  action: VaultAction?,
  onDone: () -> Unit,
  onMessage: (VaultMessage) -> Unit,
  onDeleted: () -> Unit = {},
) {
  val repo = container.vault
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val colors = SamvadTheme.colors
  val fallbackTitle = stringResource(R.string.vault_default_title)

  // Export: confirmation first, then the system "create document" screen.
  var exportItem by remember { mutableStateOf<VaultItem?>(null) }
  val exportLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(exportItem?.mime?.takeIf { it.contains('/') } ?: "*/*"),
  ) { uri ->
    val item = exportItem
    exportItem = null
    if (uri != null && item != null) {
      scope.launch {
        try {
          repo.exportTo(item.id, uri)
          onMessage(VaultMessage.EXPORTED)
        } catch (e: VaultException) {
          onMessage(e.toMessage())
        }
      }
    }
    onDone()
  }

  // Opening in another app: the one-time copy is removed as soon as the person is back.
  var viewing by remember { mutableStateOf<VaultViewCopy?>(null) }
  val currentViewing = rememberUpdatedState(viewing)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    var stoppedWhileViewing = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> if (currentViewing.value != null) stoppedWhileViewing = true
        Lifecycle.Event.ON_START -> if (stoppedWhileViewing) {
          stoppedWhileViewing = false
          currentViewing.value?.let(repo::release)
          viewing = null
        }
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      currentViewing.value?.let(repo::release)
    }
  }

  when (action) {
    null -> Unit
    is VaultAction.Rename -> {
      var title by remember(action) { mutableStateOf(action.item.title) }
      AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.vault_rename_title)) },
        text = { SamvadTextField(title, { title = it }, stringResource(R.string.vault_title_label), maxLength = 80) },
        confirmButton = {
          TextButton(onClick = {
            scope.launch {
              try {
                repo.rename(action.item.id, title, fallbackTitle)
              } catch (e: VaultException) {
                onMessage(e.toMessage())
              }
            }
            onDone()
          }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
      )
    }
    is VaultAction.Delete -> AlertDialog(
      onDismissRequest = onDone,
      title = { Text(stringResource(R.string.vault_delete_title)) },
      text = { Text(stringResource(R.string.vault_delete_body, action.item.title)) },
      confirmButton = {
        TextButton(onClick = {
          scope.launch {
            try {
              repo.delete(action.item.id)
              onMessage(VaultMessage.DELETED)
              onDeleted()
            } catch (e: VaultException) {
              onMessage(e.toMessage())
            }
          }
          onDone()
        }) { Text(stringResource(R.string.vault_delete), color = colors.error) }
      },
      dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
    is VaultAction.Export -> AlertDialog(
      onDismissRequest = onDone,
      title = { Text(stringResource(R.string.vault_export_title)) },
      text = { Text(stringResource(R.string.vault_export_body)) },
      confirmButton = {
        TextButton(onClick = {
          exportItem = action.item
          container.appLock.expectExternal()
          exportLauncher.launch(repo.exportName(action.item))
        }) { Text(stringResource(R.string.vault_export_choose)) }
      },
      dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
    is VaultAction.OpenExternally -> AlertDialog(
      onDismissRequest = onDone,
      title = { Text(stringResource(R.string.vault_open_title)) },
      text = { Text(stringResource(R.string.vault_open_body)) },
      confirmButton = {
        TextButton(onClick = {
          val item = action.item
          onDone()
          if (!VaultTypes.canOpenInAnotherApp(item.mime)) {
            onMessage(VaultMessage.OPEN_UNSUPPORTED)
            return@TextButton
          }
          scope.launch {
            try {
              val copy = repo.decryptForViewing(item.id)
              val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(copy.uri, copy.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              container.appLock.expectExternal()
              try {
                viewing = copy
                context.startActivity(intent)
              } catch (_: ActivityNotFoundException) {
                repo.release(copy)
                viewing = null
                onMessage(VaultMessage.OPEN_FAILED)
              }
            } catch (e: VaultException) {
              onMessage(e.toMessage())
            }
          }
        }) { Text(stringResource(R.string.vault_open)) }
      },
      dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
  }
}
