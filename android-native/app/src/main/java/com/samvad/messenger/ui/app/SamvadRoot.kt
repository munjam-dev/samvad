package com.samvad.messenger.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.draw.rotate
import com.samvad.messenger.ui.theme.LocalReducedMotion
import com.samvad.messenger.ui.theme.Motion
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
import androidx.compose.runtime.LaunchedEffect
import com.samvad.messenger.AppContainer
import com.samvad.messenger.ChatLink
import com.samvad.messenger.R
import com.samvad.messenger.ui.auth.AuthFlow
import com.samvad.messenger.ui.chat.ConversationScreen
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.home.HomeScreen
import com.samvad.messenger.ui.newchat.NewChatScreen
import com.samvad.messenger.ui.onboarding.OnboardingScreen
import com.samvad.messenger.ui.security.TwoStepSettingsScreen
import com.samvad.messenger.ui.security.TwoStepVerifyScreen
import com.samvad.messenger.ui.settings.BlockedContactsScreen
import com.samvad.messenger.ui.settings.SettingsScreen
import com.samvad.messenger.ui.setup.ProfileSetupScreen
import com.samvad.messenger.ui.theme.SamvadTheme

@Composable
fun SamvadRoot(
  container: AppContainer,
  session: SessionViewModel,
  state: SessionState,
  link: ChatLink? = null,
  onLinkHandled: () -> Unit = {},
) {
  Box(Modifier.fillMaxSize().background(SamvadTheme.colors.background)) {
    AnimatedContent(
      state.destination,
      transitionSpec = { (fadeIn(tween(320)) + scaleIn(initialScale = 0.97f)) togetherWith (fadeOut(tween(180)) + scaleOut(targetScale = 1.02f)) },
      label = "root",
    ) { dest ->
      when (dest) {
        Destination.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingMark() }
        Destination.SETUP_REQUIRED -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          EmptyState(
            Icons.Outlined.Build,
            stringResource(R.string.setup_required_title),
            stringResource(R.string.setup_required_body),
          )
        }
        Destination.ONBOARDING -> OnboardingScreen(onDone = session::finishOnboarding)
        Destination.SIGN_IN -> AuthFlow(container)
        Destination.TWO_STEP -> TwoStepVerifyScreen(container, state.phone.orEmpty(), onSignOut = session::signOut)
        Destination.PROFILE_SETUP -> ProfileSetupScreen(container, state.uid!!, state.phone.orEmpty(), session::profileCreated)
        Destination.HOME -> SignedInNav(container, session, state.uid!!, link, onLinkHandled)
      }
    }
  }
}

@Composable
private fun SignedInNav(
  container: AppContainer,
  session: SessionViewModel,
  uid: String,
  link: ChatLink?,
  onLinkHandled: () -> Unit,
) {
  val nav = rememberNavController()
  LaunchedEffect(link) {
    if (link != null) {
      val route = link.otherUid?.let { "chat/${link.conversationId}/$it" } ?: "group/${link.conversationId}"
      nav.navigate(route) { popUpTo("home") }
      onLinkHandled()
    }
  }
  val spring = Motion.offsetSpring
  NavHost(
    nav,
    startDestination = "home",
    enterTransition = { slideInHorizontally(spring) { it / 3 } + fadeIn(tween(220)) },
    exitTransition = { slideOutHorizontally(spring) { -it / 10 } + fadeOut(tween(160)) },
    popEnterTransition = { slideInHorizontally(spring) { -it / 10 } + fadeIn(tween(220)) },
    popExitTransition = { slideOutHorizontally(spring) { it / 3 } + fadeOut(tween(160)) },
  ) {
    composable("home") {
      HomeScreen(
        container = container,
        me = uid,
        onOpenChat = { cid, other -> nav.navigate(if (other == null) "group/$cid" else "chat/$cid/$other") },
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
    composable("group/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      ConversationScreen(
        container = container,
        me = uid,
        conversationId = entry.arguments!!.getString("cid")!!,
        otherUid = null,
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
      SettingsScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onSignOut = session::signOut,
        onOpenBlocked = { nav.navigate("blocked") },
        onOpenTwoStep = { nav.navigate("twostep") },
      )
    }
    composable("twostep") {
      TwoStepSettingsScreen(container = container, onBack = { nav.popBackStack() })
    }
    composable("blocked") {
      BlockedContactsScreen(container = container, me = uid, onBack = { nav.popBackStack() })
    }
  }
}

/** The pinwheel turns slowly only while the app is deciding where to go (the load window). */
@Composable
private fun LoadingMark() {
  if (LocalReducedMotion.current) {
    SamvadMark(96.dp)
    return
  }
  val spin = rememberInfiniteTransition(label = "loading")
  val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "angle")
  SamvadMark(96.dp, Modifier.rotate(angle.value))
}
