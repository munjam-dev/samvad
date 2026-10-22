package com.samvad.messenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.gifs.GifKind
import com.samvad.core.gifs.GifRef
import com.samvad.core.gifs.GifRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.GifException
import com.samvad.messenger.data.GifLibraryState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** What went wrong, in terms the picker can explain. */
enum class GifProblem { NOT_CONFIGURED, LIMIT, OFFLINE, FAILED }

enum class GifSection { TRENDING, YOURS }

data class GifPickerUi(
  val kind: GifKind = GifKind.GIF,
  val section: GifSection = GifSection.TRENDING,
  /** What is in the search box right now. */
  val rawQuery: String = "",
  /** The cleaned query the results are for; blank means trending. */
  val query: String = "",
  val items: List<GifRef> = emptyList(),
  val next: String? = null,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val problem: GifProblem? = null,
  val library: GifLibraryState = GifLibraryState(),
) {
  val favouriteIds: Set<String> get() = library.favourites.mapTo(HashSet()) { it.id }
  val showingYours: Boolean get() = section == GifSection.YOURS && query.isEmpty()
}

/**
 * State of the GIF and sticker picker: tab, search box (debounced), trending, "Yours", paging and errors. Searches go
 * through our own server (the provider key is never in the app); recents and favourites stay on this phone.
 */
class GifPickerViewModel(private val container: AppContainer) : ViewModel() {
  private val kind = MutableStateFlow(GifKind.GIF)
  private val section = MutableStateFlow(GifSection.TRENDING)
  private val rawQuery = MutableStateFlow("")
  private val ui = MutableStateFlow(GifPickerUi())
  private var loadJob: Job? = null
  private var moreJob: Job? = null
  private var generation = 0
  private val firstPages = HashMap<Pair<GifKind, String>, com.samvad.core.gifs.GifPage>()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val library: StateFlow<GifLibraryState> = kind.flatMapLatest { container.gifLibrary.state(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GifLibraryState())

  val state: StateFlow<GifPickerUi> = combine(ui, library) { s, lib -> s.copy(library = lib) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GifPickerUi())

  @OptIn(FlowPreview::class)
  private val settledQuery = rawQuery.map(GifRules::normalizeQuery)
    .debounce { if (it.isEmpty()) 0L else GifRules.DEBOUNCE_MS }
    .distinctUntilChanged()

  init {
    viewModelScope.launch {
      combine(kind, section, settledQuery) { k, s, q -> Triple(k, s, q) }
        .distinctUntilChanged()
        .collect { (k, s, q) -> reload(k, s, q) }
    }
  }

  fun setKind(value: GifKind) {
    if (kind.value == value) return
    kind.value = value
    ui.value = ui.value.copy(kind = value)
  }

  fun setSection(value: GifSection) {
    section.value = value
    ui.value = ui.value.copy(section = value)
  }

  fun setQuery(value: String) {
    val limited = value.take(GifRules.MAX_QUERY_LENGTH * 2)
    rawQuery.value = limited
    ui.value = ui.value.copy(rawQuery = limited)
  }

  fun retry() {
    firstPages.clear()
    val s = ui.value
    reload(s.kind, section.value, GifRules.normalizeQuery(rawQuery.value))
  }

  fun toggleFavourite(item: GifRef) {
    viewModelScope.launch { container.gifLibrary.toggleFavourite(kind.value, item) }
  }

  private fun reload(k: GifKind, s: GifSection, q: String) {
    loadJob?.cancel()
    moreJob?.cancel()
    val id = ++generation
    if (s == GifSection.YOURS && q.isEmpty()) {
      ui.value = ui.value.copy(kind = k, section = s, query = q, items = emptyList(), next = null, loading = false, loadingMore = false, problem = null)
      return
    }
    firstPages[k to q]?.let { page ->
      ui.value = ui.value.copy(kind = k, section = s, query = q, items = page.items, next = page.next, loading = false, loadingMore = false, problem = null)
      return
    }
    ui.value = ui.value.copy(kind = k, section = s, query = q, items = emptyList(), next = null, loading = true, loadingMore = false, problem = null)
    loadJob = viewModelScope.launch {
      try {
        val page = fetch(k, q, null)
        if (id != generation) return@launch
        firstPages[k to q] = page
        ui.value = ui.value.copy(items = page.items, next = page.next, loading = false, problem = null)
      } catch (e: GifException) {
        if (id == generation) ui.value = ui.value.copy(loading = false, problem = problemOf(e))
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        if (id == generation) ui.value = ui.value.copy(loading = false, problem = problemOf(null))
      }
    }
  }

  /** Loads the next page when the grid scrolls near its end. A failure keeps what is already shown. */
  fun loadMore() {
    val s = ui.value
    val cursor = s.next ?: return
    if (s.loading || s.loadingMore || s.problem != null || s.showingYours) return
    val id = generation
    ui.value = s.copy(loadingMore = true)
    moreJob = viewModelScope.launch {
      try {
        val page = fetch(s.kind, s.query, cursor)
        if (id != generation) return@launch
        val known = ui.value.items.mapTo(HashSet()) { it.id }
        ui.value = ui.value.copy(items = ui.value.items + page.items.filter { it.id !in known }, next = page.next, loadingMore = false)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        if (id == generation) ui.value = ui.value.copy(loadingMore = false, next = null)
      }
    }
  }

  private suspend fun fetch(k: GifKind, q: String, cursor: String?) = run {
    val repo = container.gifs ?: throw GifException.NotConfigured()
    if (q.isEmpty()) repo.trending(k, cursor) else repo.search(q, k, cursor)
  }

  private fun problemOf(e: GifException?): GifProblem = when {
    e is GifException.NotConfigured -> GifProblem.NOT_CONFIGURED
    e is GifException.LimitReached -> GifProblem.LIMIT
    !container.network.isOnline -> GifProblem.OFFLINE
    else -> GifProblem.FAILED
  }

  companion object {
    fun factory(container: AppContainer) = viewModelFactory { initializer { GifPickerViewModel(container) } }
  }
}
