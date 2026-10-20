package com.samvad.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samvad.core.design.HomeLayout
import com.samvad.core.design.ThemeMode
import com.samvad.core.media.AutoDownload
import com.samvad.core.notify.QuietHours
import com.samvad.core.security.LockTimeout
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_prefs")

data class AppPreferences(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val homeLayout: HomeLayout = HomeLayout.DEFAULT,
  val onboardingDone: Boolean = false,
  val notificationPromptDismissed: Boolean = false,
  val appLockEnabled: Boolean = false,
  val lockTimeout: LockTimeout = LockTimeout.ONE_MINUTE,
  val secureScreen: Boolean = false,
  val notificationPreviews: Boolean = true,
  val readReceipts: Boolean = true,
  /** Chat rows show a dot instead of a number. */
  val hideUnreadCounts: Boolean = false,
  /** Quiet home: no counts, no unread summary. */
  val calmMode: Boolean = false,
  /** When chat photos download by themselves. */
  val autoDownload: AutoDownload = AutoDownload.ALWAYS,
  /** Smaller uploads, and photos wait for Wi-Fi unless the setting is stricter. */
  val dataSaver: Boolean = false,
  /** Disappearing-message timer given to chats the user creates from now on, in seconds; 0 = off. */
  val defaultTimer: Long = 0L,
  /** Per-device: a daily window in which message notifications are delivered silently. */
  val quietHours: QuietHours = QuietHours(),
  /** Status posts I have already looked at (kept only while they are still live). */
  val statusSeen: Set<String> = emptySet(),
  /** People whose status I have muted. */
  val statusMuted: Set<String> = emptySet(),
)

/** Local, non-sensitive preferences. Never stores verification IDs, codes or tokens. */
class PreferencesRepository(private val context: Context) {
  private val theme = stringPreferencesKey("theme_mode")
  private val layout = stringPreferencesKey("home_layout")
  private val onboarding = booleanPreferencesKey("onboarding_done")
  private val deviceId = stringPreferencesKey("device_id")
  private val notifPrompt = booleanPreferencesKey("notification_prompt_dismissed")
  private val appLock = booleanPreferencesKey("app_lock_enabled")
  private val lockAfter = stringPreferencesKey("lock_timeout")
  private val secure = booleanPreferencesKey("secure_screen")
  private val previews = booleanPreferencesKey("notification_previews")
  private val receipts = booleanPreferencesKey("read_receipts")
  private val hideCounts = booleanPreferencesKey("hide_unread_counts")
  private val calm = booleanPreferencesKey("calm_mode")
  private val autoDl = stringPreferencesKey("auto_download")
  private val saver = booleanPreferencesKey("data_saver")
  private val defaultTimerKey = androidx.datastore.preferences.core.longPreferencesKey("default_timer")
  private val statusSeenKey = androidx.datastore.preferences.core.stringSetPreferencesKey("status_seen")
  private val statusMutedKey = androidx.datastore.preferences.core.stringSetPreferencesKey("status_muted")
  private val quietEnabled = booleanPreferencesKey("quiet_hours_enabled")
  private val quietStart = androidx.datastore.preferences.core.intPreferencesKey("quiet_hours_start")
  private val quietEnd = androidx.datastore.preferences.core.intPreferencesKey("quiet_hours_end")

  val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
    AppPreferences(
      themeMode = p[theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
      homeLayout = HomeLayout.parse(p[layout]),
      onboardingDone = p[onboarding] ?: false,
      notificationPromptDismissed = p[notifPrompt] ?: false,
      appLockEnabled = p[appLock] ?: false,
      lockTimeout = p[lockAfter]?.let { runCatching { LockTimeout.valueOf(it) }.getOrNull() } ?: LockTimeout.ONE_MINUTE,
      secureScreen = p[secure] ?: false,
      notificationPreviews = p[previews] ?: true,
      readReceipts = p[receipts] ?: true,
      hideUnreadCounts = p[hideCounts] ?: false,
      calmMode = p[calm] ?: false,
      autoDownload = AutoDownload.parse(p[autoDl]),
      dataSaver = p[saver] ?: false,
      statusSeen = p[statusSeenKey] ?: emptySet(),
      statusMuted = p[statusMutedKey] ?: emptySet(),
      defaultTimer = (p[defaultTimerKey] ?: 0L).takeIf { com.samvad.core.chat.Disappearing.isValid(it) } ?: 0L,
      quietHours = QuietHours.sanitized(p[quietEnabled] ?: false, p[quietStart], p[quietEnd]),
    )
  }

  suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[theme] = mode.name }
  suspend fun setHomeLayout(value: HomeLayout) = context.dataStore.edit { it[layout] = value.name }
  suspend fun setOnboardingDone() = context.dataStore.edit { it[onboarding] = true }
  suspend fun dismissNotificationPrompt() = context.dataStore.edit { it[notifPrompt] = true }
  suspend fun setAppLock(enabled: Boolean) = context.dataStore.edit { it[appLock] = enabled }
  suspend fun setLockTimeout(value: LockTimeout) = context.dataStore.edit { it[lockAfter] = value.name }
  suspend fun setSecureScreen(enabled: Boolean) = context.dataStore.edit { it[secure] = enabled }
  suspend fun setNotificationPreviews(enabled: Boolean) = context.dataStore.edit { it[previews] = enabled }
  suspend fun setReadReceipts(enabled: Boolean) = context.dataStore.edit { it[receipts] = enabled }
  suspend fun setHideUnreadCounts(enabled: Boolean) = context.dataStore.edit { it[hideCounts] = enabled }
  suspend fun setCalmMode(enabled: Boolean) = context.dataStore.edit { it[calm] = enabled }
  suspend fun setAutoDownload(value: AutoDownload) = context.dataStore.edit { it[autoDl] = value.name }
  suspend fun setDataSaver(enabled: Boolean) = context.dataStore.edit { it[saver] = enabled }
  /** Remembers that I saw [id]; anything no longer in [liveIds] is dropped so the set never grows without end. */
  suspend fun markStatusSeen(id: String, liveIds: Set<String>) = context.dataStore.edit {
    it[statusSeenKey] = ((it[statusSeenKey] ?: emptySet()) + id).filter { s -> s == id || s in liveIds }.toSet()
  }

  suspend fun setStatusMuted(author: String, muted: Boolean) = context.dataStore.edit {
    val now = it[statusMutedKey] ?: emptySet()
    it[statusMutedKey] = if (muted) now + author else now - author
  }

  suspend fun setDefaultTimer(seconds: Long) = context.dataStore.edit {
    it[defaultTimerKey] = if (com.samvad.core.chat.Disappearing.isValid(seconds)) seconds else 0L
  }

  suspend fun setQuietHours(enabled: Boolean, startMinute: Int, endMinute: Int) = context.dataStore.edit {
    it[quietEnabled] = enabled
    it[quietStart] = startMinute
    it[quietEnd] = endMinute
  }

  /** Random per-install ID (lowercase UUID); no hardware identifiers. */
  suspend fun deviceId(): String {
    context.dataStore.data.first()[deviceId]?.let { return it }
    val id = UUID.randomUUID().toString()
    context.dataStore.edit { it[deviceId] = id }
    return id
  }

  suspend fun clearDeviceId() = context.dataStore.edit { it.remove(deviceId) }
}
