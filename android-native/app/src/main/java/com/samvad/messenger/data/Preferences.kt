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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_prefs")

data class AppPreferences(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val homeLayout: HomeLayout = HomeLayout.BENTO,
  val onboardingDone: Boolean = false,
)

/** Local, non-sensitive preferences. Never stores verification IDs, codes or tokens. */
class PreferencesRepository(private val context: Context) {
  private val theme = stringPreferencesKey("theme_mode")
  private val layout = stringPreferencesKey("home_layout")
  private val onboarding = booleanPreferencesKey("onboarding_done")
  private val deviceId = stringPreferencesKey("device_id")

  val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
    AppPreferences(
      themeMode = p[theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
      homeLayout = p[layout]?.let { runCatching { HomeLayout.valueOf(it) }.getOrNull() } ?: HomeLayout.BENTO,
      onboardingDone = p[onboarding] ?: false,
    )
  }

  suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[theme] = mode.name }
  suspend fun setHomeLayout(value: HomeLayout) = context.dataStore.edit { it[layout] = value.name }
  suspend fun setOnboardingDone() = context.dataStore.edit { it[onboarding] = true }

  /** Random per-install ID (lowercase UUID); no hardware identifiers. */
  suspend fun deviceId(): String {
    context.dataStore.data.first()[deviceId]?.let { return it }
    val id = UUID.randomUUID().toString()
    context.dataStore.edit { it[deviceId] = id }
    return id
  }

  suspend fun clearDeviceId() = context.dataStore.edit { it.remove(deviceId) }
}
