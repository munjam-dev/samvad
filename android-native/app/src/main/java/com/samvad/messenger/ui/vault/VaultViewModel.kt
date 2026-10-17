package com.samvad.messenger.ui.vault

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.vault.VaultFilter
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultItemType
import com.samvad.core.vault.VaultSearch
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.vault.VaultException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the list needs: everything decrypted in memory, the filter, and the filtered result. */
internal data class VaultListState(
  /** Null until the first read finishes. */
  val all: List<VaultItem>? = null,
  val shown: List<VaultItem> = emptyList(),
  val filter: VaultFilter = VaultFilter(),
  val counts: Map<VaultItemType, Int> = emptyMap(),
)

internal class VaultViewModel(private val container: AppContainer) : ViewModel() {
  private val repo = container.vault
  private val filter = MutableStateFlow(VaultFilter())
  private val _messages = Channel<VaultMessage>(Channel.BUFFERED)
  val messages: Flow<VaultMessage> = _messages.receiveAsFlow()

  val state: StateFlow<VaultListState> = combine(repo.items, filter) { items, f ->
    if (items == null) VaultListState(filter = f)
    else VaultListState(items, VaultSearch.apply(items, f), f, VaultSearch.counts(items))
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultListState())

  /** The current filter, kept even while the list is empty. */
  val filterState: StateFlow<VaultFilter> = filter.asStateFlow()

  fun setQuery(query: String) = filter.update { it.copy(query = query.take(80)) }

  fun toggleType(type: VaultItemType) = filter.update { it.toggle(type) }

  fun clearFilters() {
    filter.value = VaultFilter()
  }

  private val importing = MutableStateFlow(0)

  /** True while a file is being encrypted into the vault. */
  val busy: Flow<Boolean> = importing.map { it > 0 }

  fun import(uri: Uri, asIdCopy: Boolean, fallbackTitle: String) {
    viewModelScope.launch {
      importing.update { it + 1 }
      try {
        repo.import(uri, asIdCopy, fallbackTitle)
        _messages.send(VaultMessage.ADDED)
      } catch (e: VaultException) {
        _messages.send(e.toMessage())
      } finally {
        importing.update { it - 1 }
      }
    }
  }

  fun report(message: VaultMessage) {
    _messages.trySend(message)
  }

  fun lockNow() = repo.lockNow()

  private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
    value = block(value)
  }

  companion object {
    fun factory(container: AppContainer) = viewModelFactory { initializer { VaultViewModel(container) } }
  }
}
