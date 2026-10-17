package com.samvad.messenger.ui.vault

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.data.AppPreferences
import com.samvad.messenger.data.vault.VaultState
import com.samvad.messenger.security.BiometricGate
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

/**
 * Keeps FLAG_SECURE on while any vault screen is composed: no screenshots, screen recording or recents
 * thumbnail. Several vault screens can overlap during a transition, so the flag is reference counted and
 * only released (back to what Settings asks for) when the last one is gone.
 */
internal object VaultWindowGuard {
  private var holders = 0

  fun acquire(activity: Activity) {
    if (holders++ == 0) {
      activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
    }
  }

  /** [secureScreen] and [appLock] are the same Settings that MainActivity.applyWindowSecurity follows. */
  fun release(activity: Activity, secureScreen: Boolean, appLock: Boolean) {
    holders = maxOf(0, holders - 1)
    if (holders > 0) return
    val secure = secureScreen || (appLock && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    if (!secure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(!appLock)
  }
}

/**
 * Everything in the vault sits behind this. It blocks screen capture, asks for fingerprint, face or screen
 * lock each time the vault is opened (with a one minute grace after leaving it), and shows [content] only while
 * the vault is unlocked. Each screen that uses it must be a destination of its own, so that leaving it is seen.
 */
@Composable
internal fun VaultGate(container: AppContainer, onBack: () -> Unit, content: @Composable () -> Unit) {
  val repo = container.vault
  val context = LocalContext.current
  val activity = LocalActivity.current
  val scope = rememberCoroutineScope()
  val state = repo.state.collectAsStateWithLifecycle().value
  val prefs = container.preferences.preferences.collectAsStateWithLifecycle(AppPreferences()).value
  val secureScreen = rememberUpdatedState(prefs.secureScreen)
  val appLockOn = rememberUpdatedState(prefs.appLockEnabled)

  if (activity != null) {
    DisposableEffect(activity) {
      VaultWindowGuard.acquire(activity)
      onDispose { VaultWindowGuard.release(activity, secureScreen.value, appLockOn.value) }
    }
  }

  var canAuthenticate by remember { mutableStateOf(BiometricGate.available(context)) }
  var promptWanted by remember { mutableStateOf(false) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, repo) {
    var entered = false
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> if (!entered) {
          entered = true
          canAuthenticate = BiometricGate.available(context)
          if (!repo.enter()) promptWanted = true
        }
        Lifecycle.Event.ON_STOP -> if (entered) {
          entered = false
          repo.leave()
        }
        else -> Unit
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      if (entered) repo.leave()
    }
  }

  val promptTitle = stringResource(R.string.vault_prompt_title)
  val promptSubtitle = stringResource(R.string.vault_prompt_subtitle)
  val authenticate: () -> Unit = {
    val host = activity as? FragmentActivity
    if (host != null) {
      BiometricGate.prompt(host, container.appLock, promptTitle, promptSubtitle) { ok ->
        if (ok) scope.launch { repo.unlock() }
      }
    }
  }
  LaunchedEffect(promptWanted, state, canAuthenticate) {
    if (promptWanted && state == VaultState.LOCKED && canAuthenticate) {
      promptWanted = false
      lifecycle.withResumed { authenticate() }
    }
  }

  var confirmErase by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().background(SamvadTheme.colors.background)) {
    when {
      state == VaultState.UNLOCKED -> content()
      else -> {
        VaultTopBar(stringResource(R.string.vault_title), onBack)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
          when {
            state == VaultState.KEY_LOST -> EmptyState(
              Icons.Outlined.LockReset,
              stringResource(R.string.vault_key_lost_title),
              stringResource(R.string.vault_key_lost_body),
              action = stringResource(R.string.vault_erase),
              onAction = { confirmErase = true },
            )
            !canAuthenticate -> EmptyState(
              Icons.Outlined.Lock,
              stringResource(R.string.vault_no_screen_lock_title),
              stringResource(R.string.vault_no_screen_lock_body),
            )
            else -> EmptyState(
              Icons.Outlined.Lock,
              stringResource(R.string.vault_locked_title),
              stringResource(R.string.vault_locked_body),
              action = stringResource(R.string.vault_unlock),
              onAction = authenticate,
            )
          }
        }
      }
    }
  }

  if (confirmErase) {
    AlertDialog(
      onDismissRequest = { confirmErase = false },
      title = { Text(stringResource(R.string.vault_erase_title)) },
      text = { Text(stringResource(R.string.vault_erase_body)) },
      confirmButton = {
        TextButton(onClick = { confirmErase = false; scope.launch { repo.eraseAll() } }) {
          Text(stringResource(R.string.vault_erase_confirm), color = SamvadTheme.colors.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
    )
  }
}

/** Back arrow, title and optional trailing actions; the top bar of every vault screen. */
@Composable
internal fun VaultTopBar(
  title: String,
  onBack: () -> Unit,
  subtitle: String? = null,
  actions: @Composable () -> Unit = {},
) {
  val colors = SamvadTheme.colors
  Row(
    Modifier.statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.cd_back), tint = colors.onSurface) }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    actions()
  }
}
