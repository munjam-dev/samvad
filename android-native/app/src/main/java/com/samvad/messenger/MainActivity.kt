package com.samvad.messenger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.samvad.messenger.push.Notifications
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.messenger.ui.app.SamvadRoot
import com.samvad.messenger.ui.app.SessionViewModel
import com.samvad.messenger.ui.theme.SamvadTheme

/** A chat to open because the user tapped its notification. */
data class ChatLink(val conversationId: String, val otherUid: String)

class MainActivity : ComponentActivity() {
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
      splash.setKeepOnScreenCondition { state.loading }
      SamvadTheme(mode = state.preferences.themeMode) {
        SamvadRoot(container, session, state, pendingLink.value) { pendingLink.value = null }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    linkFrom(intent)?.let { pendingLink.value = it }
  }

  private fun linkFrom(intent: Intent?): ChatLink? {
    val cid = intent?.getStringExtra(Notifications.EXTRA_CONVERSATION) ?: return null
    val other = intent.getStringExtra(Notifications.EXTRA_OTHER) ?: return null
    return ChatLink(cid, other)
  }
}
