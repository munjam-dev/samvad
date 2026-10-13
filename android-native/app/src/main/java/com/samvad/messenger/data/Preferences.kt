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

  /** Random per-install ID (lowercase UUID); no hardware identifiers. */
  suspend fun deviceId(): String {
    context.dataStore.data.first()[deviceId]?.let { return it }
    val id = UUID.randomUUID().toString()
    context.dataStore.edit { it[deviceId] = id }
    return id
  }

  suspend fun clearDeviceId() = context.dataStore.edit { it.remove(deviceId) }
}
