package com.samvad.messenger

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.core.chat.ConversationIds
import com.samvad.messenger.push.Notifications
import com.samvad.messenger.security.BiometricGate
import com.samvad.messenger.security.LockState
import com.samvad.messenger.ui.app.Destination
import com.samvad.messenger.ui.app.SamvadRoot
import com.samvad.messenger.ui.app.SessionViewModel
import com.samvad.messenger.ui.security.LockScreen
import com.samvad.messenger.ui.theme.SamvadTheme

/** A chat to open because the user tapped its notification. */
data class ChatLink(val conversationId: String, val otherUid: String)

/** A FragmentActivity because BiometricPrompt (app lock) needs one. */
class MainActivity : FragmentActivity() {
  private val pendingLink = mutableStateOf<ChatLink?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (savedInstanceState == null) pendingLink.value = linkFrom(intent)
    val container = (application as SamvadApplication).container
    setContent {
      val session: SessionViewModel = viewModel(factory = SessionViewModel.factory(container))
      val state = session.state.collectAsStateWithLifecycle().value
      val lock = container.appLock.state.collectAsStateWithLifecycle().value
      splash.setKeepOnScreenCondition { state.loading || lock == LockState.UNKNOWN }
      val prefs = state.preferences
      LaunchedEffect(prefs.secureScreen, prefs.appLockEnabled) {
        applyWindowSecurity(secureScreen = prefs.secureScreen, appLock = prefs.appLockEnabled)
      }
      // Only a signed-in app has anything to protect.
      val locked = lock == LockState.LOCKED && state.destination == Destination.HOME
      SamvadTheme(mode = prefs.themeMode) {
        Box(Modifier.fillMaxSize()) {
          // While locked, the app underneath is hidden from screen readers too.
          Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics { } else Modifier)) {
            SamvadRoot(container, session, state, pendingLink.value) { pendingLink.value = null }
          }
          if (locked) {
            LockScreen(
              onUnlock = {
                BiometricGate.prompt(
                  this@MainActivity,
                  container.appLock,
                  getString(R.string.lock_prompt_title),
                  getString(R.string.lock_prompt_subtitle),
                )
              },
              onLeave = { moveTaskToBack(true) },
            )
          }
        }
      }
    }
  }

  /**
   * "Block screenshots" sets FLAG_SECURE (no screenshots, recordings or recents preview). With app
   * lock on, the recents preview is hidden too: directly on Android 13+, via FLAG_SECURE before that.
   */
  private fun applyWindowSecurity(secureScreen: Boolean, appLock: Boolean) {
    val secure = secureScreen || (appLock && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!appLock)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    linkFrom(intent)?.let { pendingLink.value = it }
  }

  /** The activity is exported (launcher), so any app can send extras: accept only well-formed chat links. */
  private fun linkFrom(intent: Intent?): ChatLink? {
    val cid = intent?.getStringExtra(Notifications.EXTRA_CONVERSATION) ?: return null
    val other = intent.getStringExtra(Notifications.EXTRA_OTHER) ?: return null
    return if (ConversationIds.isDirectWith(cid, other)) ChatLink(cid, other) else null
  }
}
