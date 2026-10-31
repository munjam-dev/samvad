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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.samvad.messenger.AppContainer
import com.samvad.messenger.ChatLink
import com.samvad.messenger.R
import com.samvad.messenger.ui.auth.AuthFlow
import com.samvad.messenger.ui.chat.StarredScreen
import com.samvad.messenger.ui.chat.ConversationScreen
import com.samvad.messenger.ui.components.EmptyState
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.home.HomeScreen
import com.samvad.messenger.ui.newchat.NewChatScreen
import com.samvad.messenger.ui.onboarding.OnboardingScreen
import com.samvad.messenger.ui.groups.AddMembersScreen
import com.samvad.messenger.ui.groups.GroupInfoScreen
import com.samvad.messenger.ui.groups.NewGroupScreen
import com.samvad.messenger.ui.security.TwoStepSettingsScreen
import com.samvad.messenger.ui.security.TwoStepVerifyScreen
import com.samvad.messenger.ui.settings.ChatThemeScreen
import com.samvad.messenger.ui.settings.BlockedContactsScreen
import com.samvad.messenger.ui.settings.SettingsScreen
import com.samvad.messenger.ui.language.LanguageScreen
import com.samvad.messenger.ui.privacy.AppealScreen
import com.samvad.messenger.ui.privacy.LinkedDevicesScreen
import com.samvad.messenger.ui.privacy.PrivacySettingsScreen
import com.samvad.messenger.ui.setup.ProfileSetupScreen
import com.samvad.messenger.ui.theme.SamvadTheme
import com.samvad.messenger.ui.vault.VAULT_ROUTE
import com.samvad.messenger.ui.vault.vaultRoutes

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
        Destination.PROFILE_SETUP -> {
          // The account exists once the last question is answered; the language comes before home.
          var chooseLanguage by rememberSaveable { mutableStateOf(false) }
          if (chooseLanguage) LanguageScreen(onContinue = session::profileCreated)
          else ProfileSetupScreen(container, state.uid!!, state.phone.orEmpty(), onDone = { chooseLanguage = true })
        }
        Destination.HOME -> {
          // One-time, skippable permissions screen before the main screen; a notification tap goes straight in.
          var permissionsDone by rememberSaveable { mutableStateOf(link != null || com.samvad.core.permissions.PermissionOnboarding.isDone(container.permissionOnboarding.state())) }
          if (permissionsDone) SignedInNav(container, session, state.uid!!, link, onLinkHandled)
          else com.samvad.messenger.ui.onboarding.PermissionsScreen(container.permissionOnboarding, onDone = { permissionsDone = true })
        }
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
    if (link != null && link.moments) {
      // A new-moment notification: back to home, on the Moments tab.
      nav.popBackStack("home", false)
      com.samvad.messenger.ui.home.HomeTabRequest.openMoments()
      onLinkHandled()
    } else if (link != null) {
      val route = when {
        link.otherUid != null -> "chat/${link.conversationId}/${link.otherUid}"
        link.communityId != null -> "communityannouncements/${link.communityId}/${link.conversationId}"
        else -> "group/${link.conversationId}"
      }
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
        onOpenStatus = { author -> nav.navigate("status/view/$author") },
        onNewStatus = { nav.navigate("status/new?photo=false") },
        onNewPhotoStatus = { nav.navigate("status/new?photo=true") },
        onNewGroup = { nav.navigate("newgroup") },
        onOpenStarred = { nav.navigate("starred") },
        onOpenChatTheme = { nav.navigate("chattheme") },
        onOpenVault = { nav.navigate(VAULT_ROUTE) },
        onOpenTarang = { nav.navigate("tarang") },
        onOpenCommunity = { id -> nav.navigate("community/$id") },
        onNewCommunity = { nav.navigate("newcommunity") },
        onOpenSearchMessages = { nav.navigate("search") },
      )
    }
    composable("tarang") {
      com.samvad.messenger.ui.tarang.TarangHomeScreen(
        repository = container.tarang,
        onBack = { nav.popBackStack() },
        onOpenRoom = { nav.navigate("tarang/room") },
        onOpenChat = { peer -> nav.navigate("tarang/chat/$peer") },
      )
    }
    composable("tarang/room") {
      com.samvad.messenger.ui.tarang.TarangRoomScreen(container.tarang, onBack = { nav.popBackStack() })
    }
    composable("tarang/chat/{peer}", arguments = listOf(navArgument("peer") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.tarang.TarangPrivateChatScreen(container.tarang, entry.arguments!!.getString("peer")!!, onBack = { nav.popBackStack() })
    }
    composable("search") {
      com.samvad.messenger.ui.search.GlobalSearchScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onOpenMessage = { cid, other, mid ->
          val route = if (other == null) "group/$cid?mid=$mid" else "chat/$cid/$other?mid=$mid"
          nav.navigate(route)
        },
        onOpenChat = { cid, other -> nav.navigate(if (other == null) "group/$cid" else "chat/$cid/$other") },
      )
    }
    composable(
      "chat/{cid}/{other}?mid={mid}",
      arguments = listOf(
        navArgument("cid") { type = NavType.StringType },
        navArgument("other") { type = NavType.StringType },
        navArgument("mid") { type = NavType.StringType; nullable = true; defaultValue = null },
      ),
    ) { entry ->
      ConversationScreen(
        container = container,
        me = uid,
        conversationId = entry.arguments!!.getString("cid")!!,
        otherUid = entry.arguments!!.getString("other")!!,
        onBack = { nav.popBackStack() },
        onOpenChatTheme = { nav.navigate("chattheme/${entry.arguments!!.getString("cid")}") },
        onOpenStarred = { nav.navigate("starred/${entry.arguments!!.getString("cid")}") },
        onOpenMedia = { nav.navigate("media/${entry.arguments!!.getString("cid")}") },
        onOpenContact = { cid, other -> nav.navigate("chat/$cid/$other") },
        initialJumpId = entry.arguments?.getString("mid"),
        onOpenVerify = { other -> nav.navigate("verify/$other") },
      )
    }
    composable("verify/{other}", arguments = listOf(navArgument("other") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.chat.VerifyScreen(
        container = container,
        me = uid,
        other = entry.arguments!!.getString("other")!!,
        onBack = { nav.popBackStack() },
      )
    }
    composable(
      "group/{cid}?mid={mid}",
      arguments = listOf(
        navArgument("cid") { type = NavType.StringType },
        navArgument("mid") { type = NavType.StringType; nullable = true; defaultValue = null },
      ),
    ) { entry ->
      val cid = entry.arguments!!.getString("cid")!!
      ConversationScreen(
        container = container,
        me = uid,
        conversationId = cid,
        otherUid = null,
        onBack = { nav.popBackStack() },
        onOpenGroupInfo = { nav.navigate("groupinfo/$cid") },
        onOpenChatTheme = { nav.navigate("chattheme/$cid") },
        onOpenStarred = { nav.navigate("starred/$cid") },
        onOpenEvents = { nav.navigate("groupevents/$cid") },
        onOpenMedia = { nav.navigate("media/$cid") },
        onOpenContact = { chat, other -> nav.navigate("chat/$chat/$other") },
        initialJumpId = entry.arguments?.getString("mid"),
      )
    }
    composable("groupinfo/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      val cid = entry.arguments!!.getString("cid")!!
      GroupInfoScreen(
        container = container,
        me = uid,
        cid = cid,
        onBack = { nav.popBackStack() },
        onAddMembers = { nav.navigate("groupadd/$cid") },
        onLeft = { nav.popBackStack("home", inclusive = false) },
        onOpenChat = { chat, other -> nav.navigate("chat/$chat/$other") },
        onOpenEvents = { nav.navigate("groupevents/$cid") },
        onOpenMedia = { nav.navigate("media/$cid") },
      )
    }
    composable("groupevents/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.groups.EventsScreen(container = container, me = uid, cid = entry.arguments!!.getString("cid")!!, onBack = { nav.popBackStack() })
    }
    composable("media/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.media.SharedMediaScreen(container = container, conversationId = entry.arguments!!.getString("cid")!!, onBack = { nav.popBackStack() })
    }
    composable("groupadd/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      AddMembersScreen(container = container, me = uid, cid = entry.arguments!!.getString("cid")!!, onBack = { nav.popBackStack() })
    }
    composable("community/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
      val id = entry.arguments!!.getString("id")!!
      com.samvad.messenger.ui.community.CommunityScreen(
        container = container,
        me = uid,
        id = id,
        onBack = { nav.popBackStack() },
        onOpenAnnouncements = { cid -> nav.navigate("communityannouncements/$id/$cid") },
        onOpenGroup = { cid -> nav.navigate("group/$cid") },
        onAddMembers = { nav.navigate("communityadd/$id") },
        onLeft = { nav.popBackStack("home", inclusive = false) },
      )
    }
    composable(
      "communityannouncements/{id}/{cid}",
      arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("cid") { type = NavType.StringType }),
    ) { entry ->
      com.samvad.messenger.ui.community.AnnouncementsScreen(
        container = container,
        me = uid,
        communityId = entry.arguments!!.getString("id")!!,
        cid = entry.arguments!!.getString("cid")!!,
        onBack = { nav.popBackStack() },
      )
    }
    composable("communityadd/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.community.AddCommunityMembersScreen(container = container, me = uid, id = entry.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
    }
    composable("newcommunity") {
      com.samvad.messenger.ui.community.CreateCommunityScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onCreated = { id, _ -> nav.navigate("community/$id") { popUpTo("home") } },
      )
    }
    composable("newgroup") {
      NewGroupScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onCreated = { cid -> nav.navigate("group/$cid") { popUpTo("home") } },
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
        onNewGroup = { nav.navigate("newgroup") },
        onOpenGroup = { cid -> nav.navigate("group/$cid") { popUpTo("home") } },
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
        onOpenChatTheme = { nav.navigate("chattheme") },
        onOpenStarred = { nav.navigate("starred") },
        onOpenVault = { nav.navigate(VAULT_ROUTE) },
        onOpenPrivacy = { nav.navigate("privacy") },
        onOpenDevices = { nav.navigate("devices") },
        onOpenAppeal = { nav.navigate("appeal") },
        onOpenLicences = { nav.navigate("licences") },
        onOpenProfile = { nav.navigate("profile") },
      )
    }
    composable("profile") {
      com.samvad.messenger.ui.profile.ProfileScreen(
        container = container,
        me = uid,
        onBack = { nav.popBackStack() },
        onOpenPrivacy = { nav.navigate("privacy") },
      )
    }
    composable("licences") {
      com.samvad.messenger.ui.settings.LicencesScreen(onBack = { nav.popBackStack() })
    }
    vaultRoutes(nav, container)
    composable("privacy") {
      PrivacySettingsScreen(container = container, me = uid, onBack = { nav.popBackStack() })
    }
    composable("devices") {
      LinkedDevicesScreen(container = container, me = uid, onBack = { nav.popBackStack() })
    }
    composable("appeal") {
      AppealScreen(container = container, me = uid, onBack = { nav.popBackStack() })
    }
    composable(
      "status/new?photo={photo}",
      arguments = listOf(navArgument("photo") { type = NavType.BoolType; defaultValue = false }),
    ) { entry ->
      com.samvad.messenger.ui.status.StatusComposerScreen(container, uid, startWithPhoto = entry.arguments?.getBoolean("photo") ?: false, onBack = { nav.popBackStack() })
    }
    composable("status/view/{author}", arguments = listOf(navArgument("author") { type = NavType.StringType })) { entry ->
      com.samvad.messenger.ui.status.StatusViewerScreen(container, uid, entry.arguments!!.getString("author")!!, onBack = { nav.popBackStack() })
    }
    composable("starred") {
      StarredScreen(container, uid, onlyChat = null, onBack = { nav.popBackStack() }, onOpenChat = { cid, other ->
        nav.navigate(if (other == null) "group/$cid" else "chat/$cid/$other")
      })
    }
    composable("starred/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      StarredScreen(container, uid, onlyChat = entry.arguments!!.getString("cid")!!, onBack = { nav.popBackStack() }, onOpenChat = { _, _ ->
        nav.popBackStack()
      })
    }
    composable("chattheme") {
      ChatThemeScreen(container = container, conversationId = null, onBack = { nav.popBackStack() })
    }
    composable("chattheme/{cid}", arguments = listOf(navArgument("cid") { type = NavType.StringType })) { entry ->
      ChatThemeScreen(container = container, conversationId = entry.arguments!!.getString("cid")!!, onBack = { nav.popBackStack() })
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
