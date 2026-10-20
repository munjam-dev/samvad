package com.samvad.messenger

import android.content.Context
import com.samvad.messenger.data.AccountRepository
import com.samvad.messenger.data.AuthRepository
import com.samvad.messenger.data.ChatRepository
import com.samvad.messenger.data.StatusRepository
import com.samvad.messenger.data.ChatThemeRepository
import com.samvad.messenger.data.CommunityRepository
import com.samvad.messenger.data.ContactsRepository
import com.samvad.messenger.data.DraftRepository
import com.samvad.messenger.data.FirebaseServices
import com.samvad.messenger.data.GroupRepository
import com.samvad.messenger.data.PreferencesRepository
import com.samvad.messenger.data.PresenceRepository
import com.samvad.messenger.data.MediaRepository
import com.samvad.messenger.data.NetworkMonitor
import com.samvad.messenger.data.SafetyRepository
import com.samvad.messenger.data.SavedNamesRepository
import com.samvad.messenger.data.TwoStepRepository
import com.samvad.messenger.push.PushTokens
import com.samvad.messenger.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Manual dependency container. A DI framework (Hilt) is deferred until the build can be verified
 * with the Android SDK; this keeps the object graph explicit and easy to replace in tests.
 */
class AppContainer(context: Context) {
  val preferences = PreferencesRepository(context)
  val network = NetworkMonitor(context)
  val drafts = DraftRepository(context)
  val chatThemes = ChatThemeRepository(context)
  val appLock = AppLock(preferences, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
  val firebase: FirebaseServices? = FirebaseServices.create(context)

  val auth: AuthRepository? = firebase?.let { AuthRepository(it.auth) }
  val accounts: AccountRepository? = firebase?.let { AccountRepository(it.db, it.functions) }
  val chats: ChatRepository? = firebase?.let { ChatRepository(it.db) }
  val presence: PresenceRepository? = firebase?.let { PresenceRepository(it.rtdb) }
  val media: MediaRepository? = firebase?.let { MediaRepository(context, it.storage) }
  val contacts: ContactsRepository? = firebase?.let { ContactsRepository(context, it.functions) }
  val savedNames: SavedNamesRepository? = contacts?.let { SavedNamesRepository(context, it) }
  val safety: SafetyRepository? = firebase?.let { SafetyRepository(it.db, it.functions) }
  val scamPrefs = com.samvad.messenger.data.ScamPreferences(context)
  val twoStep: TwoStepRepository? = firebase?.let { TwoStepRepository(it.auth, it.functions) }
  val groups: GroupRepository? = firebase?.let { GroupRepository(it.functions) }
  val communities: CommunityRepository? = firebase?.let { CommunityRepository(it.db, it.functions) }
  val status: StatusRepository? = firebase?.let { StatusRepository(it.db, it.storage) }
  val push = PushTokens(enabled = firebase != null && !com.samvad.messenger.BuildConfig.USE_EMULATORS)

  val isConfigured: Boolean get() = firebase != null

  /** Gives a chat the user just created their default disappearing-message timer (off by default). Never throws. */
  suspend fun applyDefaultTimer(cid: String) {
    val seconds = runCatching { preferences.preferences.first().defaultTimer }.getOrDefault(0L)
    if (seconds > 0L) runCatching { groups?.setDisappearingTimer(cid, seconds) }
  }
}
