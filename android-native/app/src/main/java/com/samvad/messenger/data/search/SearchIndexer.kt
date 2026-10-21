package com.samvad.messenger.data.search

import android.content.Context
import com.samvad.core.chat.Message
import com.samvad.core.search.SearchEntries
import com.samvad.core.search.SearchPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the on-device message index up to date. Nothing here ever leaves the phone: it only reads
 * messages the chat screens already loaded through Firestore's normal listeners, and writes their
 * searchable words into the local Room database (see `SearchDatabase`). Call [indexAll] whenever a
 * chat screen receives a page of messages; it is cheap to call often (REPLACE on the primary key).
 */
class SearchIndexer(context: Context) {
  private val dao = SearchDatabase.get(context).searchDao()
  private val trimLock = Mutex()

  suspend fun index(conversationId: String, message: Message) = indexAll(conversationId, listOf(message))

  suspend fun indexAll(conversationId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
    val rows = messages.mapNotNull { SearchEntries.from(conversationId, it)?.toEntity() }
    val toRemove = messages.filter { SearchEntries.from(conversationId, it) == null }
      .map { "${conversationId}_${it.id}" }
    if (rows.isNotEmpty()) dao.upsert(rows)
    if (toRemove.isNotEmpty()) dao.delete(toRemove)
    maybeTrim()
  }

  /** The whole chat's messages were removed (left a group, deleted the chat locally, etc). */
  suspend fun forgetChat(conversationId: String) = withContext(Dispatchers.IO) { dao.deleteChat(conversationId) }

  /** Sign-out or account deletion: nothing from this account's chats should remain searchable. */
  suspend fun clearAll() = withContext(Dispatchers.IO) { dao.clear() }

  private suspend fun maybeTrim() {
    if (trimLock.tryLock()) {
      try {
        val count = dao.count()
        if (count > SearchPolicy.MAX_ENTRIES) {
          val drop = dao.oldest(count - SearchPolicy.TRIM_TO)
          if (drop.isNotEmpty()) dao.delete(drop)
        }
      } finally {
        trimLock.unlock()
      }
    }
  }
}
