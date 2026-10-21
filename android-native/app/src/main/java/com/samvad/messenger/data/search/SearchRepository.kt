package com.samvad.messenger.data.search

import android.content.Context
import com.samvad.core.search.ChatHits
import com.samvad.core.search.SearchFilter
import com.samvad.core.search.SearchQuery
import com.samvad.core.search.SearchRanking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Global message search: reads candidates from the on-device Room/FTS index and hands them to the
 * pure ranking logic in `core` (tokenising, scoring, snippets are tested there, not here). Nothing in
 * this class makes a network call; a search only ever looks at what [SearchIndexer] has already written.
 */
class SearchRepository(context: Context) {
  private val dao = SearchDatabase.get(context).searchDao()

  suspend fun search(raw: String, filter: SearchFilter, nowMs: Long = System.currentTimeMillis()): List<ChatHits> {
    val query = SearchQuery.parse(raw) ?: return emptyList()
    val candidates = withContext(Dispatchers.IO) { dao.search(query.matchExpression()) }.map { it.toSearchEntry() }
    val hits = SearchRanking.rank(candidates, query, filter, nowMs)
    return SearchRanking.groupByChat(hits)
  }
}
