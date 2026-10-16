package com.samvad.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.samvad.core.chat.DraftChange
import com.samvad.core.chat.Drafts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.draftStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_drafts")

/**
 * Unsent message text, one entry per conversation id. Local only: it lives in this app's private
 * storage, is never uploaded, and is erased on sign out.
 */
class DraftRepository(private val context: Context) {
  private fun key(conversationId: String) = stringPreferencesKey(PREFIX + conversationId)

  /** Writes run one after another in the order they were requested, even after a screen is gone. */
  private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

  init {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      for (write in writes) runCatching { write() }
    }
  }

  /** Drafts for every chat, keyed by conversation id (blank entries never exist). */
  val all: Flow<Map<String, String>> = context.draftStore.data.map { p ->
    buildMap {
      for ((k, v) in p.asMap()) {
        val name = k.name
        if (name.startsWith(PREFIX) && v is String) Drafts.normalize(v)?.let { put(name.removePrefix(PREFIX), it) }
      }
    }
  }

  suspend fun get(conversationId: String): String? =
    Drafts.normalize(context.draftStore.data.first()[key(conversationId)])

  /** Queues the result of [Drafts.onLeave]; returns at once. */
  fun save(conversationId: String, change: DraftChange) {
    when (change) {
      DraftChange.Keep -> Unit
      DraftChange.Clear -> writes.trySend { context.draftStore.edit { it.remove(key(conversationId)) } }
      is DraftChange.Save -> writes.trySend { context.draftStore.edit { it[key(conversationId)] = change.text } }
    }
  }

  /** Forgets every draft (sign out, account deletion). Queued behind any pending save. */
  fun clearAll() {
    writes.trySend { context.draftStore.edit { it.clear() } }
  }

  private companion object {
    const val PREFIX = "draft_"
  }
}
