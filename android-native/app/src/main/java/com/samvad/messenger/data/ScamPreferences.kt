package com.samvad.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.scamStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_scam_shield")

/**
 * Local Scam Shield choices. Today: the chats where I dismissed the "new contact" banner. Chat IDs only, no message
 * content. Kept on this device and deleted on sign-out (conversation IDs of direct chats contain both people's IDs).
 */
class ScamPreferences(private val context: Context) {
  private val dismissedKey = stringSetPreferencesKey("first_contact_dismissed")

  /** Chat IDs whose banner I dismissed. */
  val dismissedChats: Flow<Set<String>> = context.scamStore.data.map { it[dismissedKey] ?: emptySet() }

  suspend fun dismissFirstContact(conversationId: String) {
    context.scamStore.edit { it[dismissedKey] = (it[dismissedKey] ?: emptySet()) + conversationId }
  }

  suspend fun clear() {
    context.scamStore.edit { it.clear() }
  }
}
