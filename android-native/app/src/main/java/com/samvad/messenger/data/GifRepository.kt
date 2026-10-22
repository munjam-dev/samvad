package com.samvad.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.gifs.GifKind
import com.samvad.core.gifs.GifLibrary
import com.samvad.core.gifs.GifPage
import com.samvad.core.gifs.GifRef
import com.samvad.core.gifs.GifRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/** Why GIF search failed, in terms the picker can explain. */
sealed class GifException(message: String) : Exception(message) {
  /** The server has no provider key yet ("GIFs aren't set up yet"). */
  class NotConfigured : GifException("gifs-not-configured")
  /** The daily search limit for this account is used up. */
  class LimitReached : GifException("limit")
  class Unavailable(cause: Throwable? = null) : GifException("unavailable") { init { cause?.let { initCause(it) } } }
}

/**
 * GIF and sticker search. The provider's API key lives on the server; the app only calls our own callables, so the
 * key never ships in the app. Results are checked against the same host allow-list the server and the rules use.
 */
class GifRepository(private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun trending(kind: GifKind, cursor: String?): GifPage =
    call("trendingGifs", mapOf("kind" to kind.wire, "cursor" to cursor))

  suspend fun search(query: String, kind: GifKind, cursor: String?): GifPage =
    call("searchGifs", mapOf("query" to query, "kind" to kind.wire, "cursor" to cursor))

  private suspend fun call(name: String, data: Map<String, Any?>): GifPage = try {
    val r = functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<*, *>
    parse(r)
  } catch (e: FirebaseFunctionsException) {
    throw when {
      e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION && e.message == "gifs-not-configured" -> GifException.NotConfigured()
      e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> GifException.LimitReached()
      else -> GifException.Unavailable(e)
    }
  } catch (e: GifException) {
    throw e
  } catch (e: Exception) {
    throw GifException.Unavailable(e)
  }

  internal fun parse(r: Map<*, *>?): GifPage {
    val items = (r?.get("items") as? List<*>).orEmpty().mapNotNull { raw ->
      val m = raw as? Map<*, *> ?: return@mapNotNull null
      GifRef(
        id = m["id"] as? String ?: return@mapNotNull null,
        title = m["title"] as? String ?: "",
        previewUrl = m["previewUrl"] as? String ?: return@mapNotNull null,
        url = m["url"] as? String ?: return@mapNotNull null,
        width = (m["width"] as? Number)?.toInt() ?: return@mapNotNull null,
        height = (m["height"] as? Number)?.toInt() ?: return@mapNotNull null,
      ).takeIf(GifRules::isValid)
    }
    return GifPage(items, r?.get("next") as? String)
  }
}

private val Context.gifStore: DataStore<Preferences> by preferencesDataStore(name = "samvad_gifs")

/** What the picker shows under "Yours": favourites first, then recently sent. */
data class GifLibraryState(val recents: List<GifRef> = emptyList(), val favourites: List<GifRef> = emptyList()) {
  val yours: List<GifRef> get() = GifLibrary.yours(favourites, recents)
}

/** Recent and favourite GIFs and stickers, kept on this phone only (never uploaded) and cleared on sign-out. */
class GifLibraryStore(private val context: Context) {
  private fun recentsKey(kind: GifKind) = stringPreferencesKey("recents_${kind.wire}")
  private fun favouritesKey(kind: GifKind) = stringPreferencesKey("favourites_${kind.wire}")

  fun state(kind: GifKind): Flow<GifLibraryState> = context.gifStore.data.map { p ->
    GifLibraryState(
      recents = GifLibrary.decode(p[recentsKey(kind)]),
      favourites = GifLibrary.decode(p[favouritesKey(kind)]),
    )
  }

  suspend fun addRecent(kind: GifKind, item: GifRef) {
    context.gifStore.edit { p -> p[recentsKey(kind)] = GifLibrary.encode(GifLibrary.addRecent(GifLibrary.decode(p[recentsKey(kind)]), item)) }
  }

  suspend fun toggleFavourite(kind: GifKind, item: GifRef) {
    context.gifStore.edit { p ->
      p[favouritesKey(kind)] = GifLibrary.encode(GifLibrary.toggleFavourite(GifLibrary.decode(p[favouritesKey(kind)]), item))
    }
  }

  suspend fun clear() {
    context.gifStore.edit { it.clear() }
  }
}
