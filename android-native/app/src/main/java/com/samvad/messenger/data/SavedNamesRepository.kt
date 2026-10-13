package com.samvad.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.savedNamesStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_saved_names")

/** Names from my phone's contacts, keyed by Samvad UID. [available] is false without contacts access. */
data class SavedNames(val available: Boolean = false, val byUid: Map<String, String> = emptyMap())

/**
 * The phone-contacts name for each Samvad user I have saved (US-002). Built on the device from the
 * address book and the existing number lookup; never uploaded, and deleted on sign-out. Refreshed at
 * most once a day so the lookup budget (500 numbers/day on the server) lasts.
 */
class SavedNamesRepository(private val context: Context, private val contacts: ContactsRepository) {
  private val json = stringPreferencesKey("names_json")
  private val refreshedAt = longPreferencesKey("refreshed_at")
  private val _state = MutableStateFlow(SavedNames())
  val state: StateFlow<SavedNames> = _state.asStateFlow()

  /** Loads the stored map, then refreshes it from the address book when it is stale or [force]d. */
  suspend fun refresh(force: Boolean = false) {
    if (!contacts.hasPermission()) {
      _state.value = SavedNames(available = false)
      return
    }
    val stored = context.savedNamesStore.data.first()
    val last = stored[refreshedAt] ?: 0L
    _state.value = SavedNames(available = true, byUid = decode(stored[json]))
    if (!force && System.currentTimeMillis() - last < DAY_MS) return

    runCatching {
      val device = contacts.deviceContacts()
      val numbers = device.map { it.e164 }
      val matches = try {
        contacts.lookup(numbers)
      } catch (_: LookupLimitException) {
        contacts.cached(numbers)
      }
      // One name per person: the first contact entry that carries their number.
      val byUid = LinkedHashMap<String, String>()
      device.forEach { c -> matches[c.e164]?.let { uid -> byUid.putIfAbsent(uid, c.name) } }
      context.savedNamesStore.edit {
        it[json] = JSONObject(byUid as Map<*, *>).toString()
        it[refreshedAt] = System.currentTimeMillis()
      }
      _state.value = SavedNames(available = true, byUid = byUid)
    }
  }

  suspend fun clear() {
    context.savedNamesStore.edit { it.clear() }
    _state.value = SavedNames()
  }

  private fun decode(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      val o = JSONObject(raw)
      o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
  }

  private companion object {
    const val DAY_MS = 24 * 60 * 60 * 1000L
  }
}
