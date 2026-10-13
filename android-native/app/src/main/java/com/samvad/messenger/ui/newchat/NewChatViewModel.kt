package com.samvad.messenger.ui.newchat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import com.samvad.core.phone.PhoneNumbers
import com.samvad.messenger.AppContainer
import com.samvad.messenger.BuildConfig
import com.samvad.messenger.data.DeviceContact
import com.samvad.messenger.data.LookupLimitException
import com.samvad.messenger.data.SamvadContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewChatState(
  val hasPermission: Boolean = false,
  val permissionDenied: Boolean = false,
  val loading: Boolean = false,
  val onSamvad: List<SamvadContact> = emptyList(),
  val invite: List<DeviceContact> = emptyList(),
  val query: String = "",
  val number: String = "",
  val numberNotFound: Boolean = false,
  val limitReached: Boolean = false,
  val error: Boolean = false,
  val opening: Boolean = false,
)

class NewChatViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val contacts = container.contacts!!
  private val _state = MutableStateFlow(NewChatState(hasPermission = contacts.hasPermission()))
  val state: StateFlow<NewChatState> = _state.asStateFlow()

  init {
    if (_state.value.hasPermission) load()
  }

  fun onPermissionResult(granted: Boolean) {
    _state.update { it.copy(hasPermission = granted, permissionDenied = !granted) }
    if (granted) {
      load()
      viewModelScope.launch { container.savedNames?.refresh(force = true) }
    }
  }

  fun load() = viewModelScope.launch {
    _state.update { it.copy(loading = true, error = false) }
    try {
      val device = contacts.deviceContacts()
      val matches = contacts.lookup(device.map { it.e164 })
      val onSamvad = device.mapNotNull { c -> matches[c.e164]?.takeIf { it != me }?.let { SamvadContact(c.name, c.e164, it) } }
      _state.update {
        it.copy(loading = false, onSamvad = onSamvad, invite = device.filter { c -> c.e164 !in matches })
      }
    } catch (_: LookupLimitException) {
      _state.update { it.copy(loading = false, limitReached = true) }
    } catch (_: Exception) {
      _state.update { it.copy(loading = false, error = true) }
    }
  }

  fun onQuery(q: String) = _state.update { it.copy(query = q) }
  fun onNumber(n: String) = _state.update { it.copy(number = n, numberNotFound = false, error = false) }

  fun open(uid: String, then: (String, String) -> Unit) = viewModelScope.launch {
    _state.update { it.copy(opening = true, error = false) }
    runCatching { container.chats!!.openDirect(me, uid) }
      .onSuccess { then(it, uid) }
      .onFailure { e -> logFailure("open", e); _state.update { s -> s.copy(error = true) } }
    _state.update { it.copy(opening = false) }
  }

  fun openByNumber(then: (String, String) -> Unit) = viewModelScope.launch {
    if (_state.value.opening) return@launch
    val e164 = PhoneNumbers.normalize(_state.value.number) ?: return@launch _state.update { it.copy(numberNotFound = true) }
    _state.update { it.copy(opening = true, error = false, numberNotFound = false) }
    try {
      val uid = contacts.lookup(listOf(e164))[e164]
      if (uid == null || uid == me) _state.update { it.copy(numberNotFound = true) } else open(uid, then).join()
    } catch (_: LookupLimitException) {
      _state.update { it.copy(limitReached = true) }
    } catch (e: Exception) {
      logFailure("lookup", e)
      _state.update { it.copy(error = true) }
    } finally {
      _state.update { it.copy(opening = false) }
    }
  }

  /** Logs the failure type only: never the number, UID or message text. */
  private fun logFailure(step: String, e: Throwable) {
    val code = (e as? FirebaseFunctionsException)?.code ?: (e as? FirebaseFirestoreException)?.code
    val frames = if (BuildConfig.DEBUG) e.stackTrace.take(6).joinToString(prefix = " at ", separator = " < ") else ""
    Log.w(TAG, "$step failed: ${e.javaClass.simpleName}${code?.let { " ($it)" }.orEmpty()}$frames")
  }

  companion object {
    private const val TAG = "NewChat"

    fun factory(container: AppContainer, me: String) = viewModelFactory { initializer { NewChatViewModel(container, me) } }
  }
}
