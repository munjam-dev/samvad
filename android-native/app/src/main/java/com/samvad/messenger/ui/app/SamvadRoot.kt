package com.samvad.messenger.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.samvad.messenger.AppContainer
import com.samvad.messenger.R
import com.samvad.messenger.ui.auth.AuthFlow
import com.samvad.messenger.ui.chat.ConversationScreen
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.home.HomeScreen
import com.samvad.messenger.ui.newchat.NewChatScreen
import com.samvad.messenger.ui.onboarding.OnboardingScreen
import com.samvad.messenger.ui.settings.SettingsScreen
import com.samvad.messenger.ui.setup.ProfileSetupScreen
import com.samvad.messenger.ui.theme.SamvadTheme

@Composable
fun SamvadRoot(container: AppContainer, session: SessionViewModel, state: SessionState) {
  Box(Modifier.fillMaxSize().background(SamvadTheme.colors.background)) {
    AnimatedContent(state.destination, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { dest ->
      when (dest) {
        Destination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SamvadMark(96.dp) }
        Destination.SETUP_REQUIRED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          EmptyState(
            Icons.Outlined.Build,
            stringResource(R.string.setup_required_title),
            stringResource(R.string.setup_required_body),
          )
        }
        Destination.ONBOARDING -> OnboardingScreen(onDone = session::finishOnboarding)
        Destination.SIGN_IN -> AuthFlow(container)
        Destination.PROFILE_SETUP -> ProfileSetupScreen(container, state.uid!!, state.phone.orEmpty(), session::profileCreated)
        Destination.HOME -> SignedInNav(container, session, state.uid!!)
      }
    }
  }
}

@Composable
private fun SignedInNav(container: AppContainer, session: SessionViewModel, uid: String) {
  val nav = rememberNavController()
  NavHost(nav, startDestination = "home") {
    composable("home") {
      HomeScreen(
        container = container,
        me = uid,
        onOpenChat = { cid, other -> nav.navigate("chat/$cid/$other") },
        onNewChat = { nav.navigate("newchat") },
        onOpenSettings = { nav.navigate("settings") },
      )
    }
    composable(
      "chat/{cid}/{other}",
      arguments = listOf(navArgument("cid") { type = NavType.StringType }, navArgument("other") { type = NavType.StringType }),
    ) { entry ->
      ConversationScreen(
        container = container,
        me = uid,
        conversationId = entry.arguments!!.getString("cid")!!,
        otherUid = entry.arguments!!.getString("other")!!,
        onBack = { nav.popBackStack() },
      )
    }
    composable("newchat") {
      NewChatScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onOpenChat = { cid, other ->
          nav.navigate("chat/$cid/$other") { popUpTo("home") }
        },
      )
    }
    composable("settings") {
      SettingsScreen(container = container, me = uid, onBack = { nav.popBackStack() }, onSignOut = session::signOut)
    }
  }
}
