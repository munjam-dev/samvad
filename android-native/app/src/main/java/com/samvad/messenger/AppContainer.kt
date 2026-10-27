package com.samvad.messenger

import android.content.Context
import com.samvad.messenger.data.AccountRepository
import com.samvad.messenger.data.AppealsRepository
import com.samvad.messenger.data.AuthRepository
import com.samvad.messenger.data.GifLibraryStore
import com.samvad.messenger.data.GifRepository
import com.samvad.messenger.data.ChatRepository
import com.samvad.messenger.data.StatusRepository
import com.samvad.messenger.data.ChatThemeRepository
import com.samvad.messenger.data.CommunityRepository
import com.samvad.messenger.data.ContactsRepository
import com.samvad.messenger.data.DevicesRepository
import com.samvad.messenger.data.DraftRepository
import com.samvad.messenger.data.FirebaseServices
import com.samvad.messenger.data.GroupRepository
import com.samvad.messenger.data.PreferencesRepository
import com.samvad.messenger.data.PresenceRepository
import com.samvad.messenger.data.PrivacyRepository
import com.samvad.messenger.data.MediaRepository
import com.samvad.messenger.data.MediaDownloads
import com.samvad.messenger.data.NetworkHints
import com.samvad.messenger.data.NetworkMonitor
import com.samvad.messenger.data.PendingUploadManager
import com.samvad.messenger.data.SafetyRepository
import com.samvad.messenger.data.SavedNamesRepository
import com.samvad.messenger.data.TwoStepRepository
import com.samvad.messenger.data.search.SearchIndexer
import com.samvad.messenger.data.search.SearchRepository
import com.samvad.messenger.push.PushTokens
import com.samvad.messenger.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
  /** Personal Vault: local-only, never uses [firebase]. */
  val vault = com.samvad.messenger.data.vault.VaultRepository(context, appLock, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
  /** Optional encrypted backup of the vault to the person's own Google Drive app-data folder (off until they turn it on). */
  val vaultBackup = com.samvad.messenger.data.vault.backup.VaultBackupManager(context, vault, CoroutineScope(SupervisorJob() + Dispatchers.Default))

  val auth: AuthRepository? = firebase?.let { AuthRepository(it.auth) }
  val accounts: AccountRepository? = firebase?.let { AccountRepository(it.db, it.functions) }
  /** End-to-end encryption: keys, sessions and decrypted messages on this phone (docs/security/E2EE_DESIGN.md). */
  val attachmentKeys = com.samvad.messenger.data.e2ee.AttachmentKeys()
  val e2ee: com.samvad.messenger.data.e2ee.E2eeService? = firebase?.let { com.samvad.messenger.data.e2ee.E2eeService(context, it.functions) }
  val chats: ChatRepository? = firebase?.let { ChatRepository(it.db, e2ee, attachmentKeys) }
  val presence: PresenceRepository? = firebase?.let { PresenceRepository(it.rtdb) }
  val media: MediaRepository? = firebase?.let { MediaRepository(context, it.storage, attachmentKeys) }
  val downloads: MediaDownloads? = media?.let { MediaDownloads(it) }
  val networkHints = NetworkHints(context)
  val pendingUploads: PendingUploadManager? = firebase?.let { fb ->
    PendingUploadManager(context, media!!, chats!!, network, e2ee) { fb.auth.currentUser?.uid }
  }
  val contacts: ContactsRepository? = firebase?.let { ContactsRepository(context, it.functions) }
  val permissionOnboarding = com.samvad.messenger.data.PermissionOnboardingStore(context)
  val savedNames: SavedNamesRepository? = contacts?.let { SavedNamesRepository(context, it) }
  val safety: SafetyRepository? = firebase?.let { SafetyRepository(it.db, it.functions) }
  val scamPrefs = com.samvad.messenger.data.ScamPreferences(context)
  val twoStep: TwoStepRepository? = firebase?.let { TwoStepRepository(it.auth, it.functions) }
  val groups: GroupRepository? = firebase?.let { GroupRepository(it.functions) }
  val communities: CommunityRepository? = firebase?.let { CommunityRepository(it.db, it.functions) }
  val status: StatusRepository? = firebase?.let { StatusRepository(it.db, it.storage, it.functions) }
  val privacy: PrivacyRepository? = firebase?.let { PrivacyRepository(it.db, it.rtdb) }
  val devices: DevicesRepository? = firebase?.let { DevicesRepository(it.db, it.functions) }
  val appeals: AppealsRepository? = firebase?.let { AppealsRepository(it.db, it.functions) }
  val calls: com.samvad.messenger.data.CallRepository? = firebase?.let { com.samvad.messenger.data.CallRepository(it.db, it.functions) }
  val gifs: GifRepository? = firebase?.let { GifRepository(it.functions) }
  /** Recent and favourite GIFs: on this phone only, cleared on sign-out. */
  val gifLibrary = GifLibraryStore(context)
  val callEngine = com.samvad.messenger.ui.call.CallEngine(context.applicationContext, calls)
  val push = PushTokens(enabled = firebase != null && !com.samvad.messenger.BuildConfig.USE_EMULATORS)
  /** Local, on-device message search index (Room/FTS). Never uploaded; cleared on sign-out and account deletion. */
  val searchIndexer = SearchIndexer(context)
  val searchRepo = SearchRepository(context)

  val isConfigured: Boolean get() = firebase != null

  init {
    // Whatever way the session ends (sign-out, account deletion, a device signed out remotely), the keys and the
    // decrypted messages of that account are erased from this phone.
    firebase?.auth?.let { auth ->
      var lastUid: String? = auth.currentUser?.uid
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      auth.addAuthStateListener { a ->
        val uid = a.currentUser?.uid
        if (lastUid != null && uid == null) {
          attachmentKeys.clear()
          scope.launch { e2ee?.wipe() }
        }
        lastUid = uid
      }
    }
  }

  /** Gives a chat the user just created their default disappearing-message timer (off by default). Never throws. */
  suspend fun applyDefaultTimer(cid: String) {
    val seconds = runCatching { preferences.preferences.first().defaultTimer }.getOrDefault(0L)
    if (seconds > 0L) runCatching { groups?.setDisappearingTimer(cid, seconds) }
  }
}
