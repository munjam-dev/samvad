package com.samvad.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.samvad.messenger.ui.app.SamvadRoot
import com.samvad.messenger.ui.app.SessionViewModel
import com.samvad.messenger.ui.theme.SamvadTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    val splash = installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val container = (application as SamvadApplication).container
    setContent {
      val session: SessionViewModel = viewModel(factory = SessionViewModel.factory(container))
      val state = session.state.collectAsStateWithLifecycle().value
      splash.setKeepOnScreenCondition { state.loading }
      SamvadTheme(mode = state.preferences.themeMode) {
        SamvadRoot(container, session, state)
      }
    }
  }
}
