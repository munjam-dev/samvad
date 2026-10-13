package com.samvad.messenger.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.security.PinProblem
import com.samvad.core.security.TwoStepPin
import com.samvad.messenger.data.TwoStepException
import com.samvad.messenger.data.TwoStepRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PinError { WRONG, LOCKED, NETWORK, TOO_SIMPLE, MISMATCH, RESET_CANCELLED, RESET_NOT_READY }

data class TwoStepVerifyState(
  val pin: String = "",
  val busy: Boolean = false,
  val error: PinError? = null,
  /** Bumped on every rejection so the field shakes again. */
  val errorKey: Int = 0,
  val attemptsLeft: Int? = null,
  val lockedUntilMs: Long? = null,
  val resetAvailableAtMs: Long? = null,
)

/** The PIN screen shown after an SMS sign-in on an account with two-step on. */
class TwoStepVerifyViewModel(private val repo: TwoStepRepository) : ViewModel() {
  private val _state = MutableStateFlow(TwoStepVerifyState())
  val state: StateFlow<TwoStepVerifyState> = _state

  init {
    viewModelScope.launch {
      runCatching { repo.status() }.onSuccess { s ->
        _state.update { it.copy(lockedUntilMs = s.lockedUntilMs, resetAvailableAtMs = s.resetAvailableAtMs) }
      }
    }
  }

  fun onPin(pin: String) {
    if (_state.value.busy) return
    _state.update { it.copy(pin = pin, error = if (pin.isEmpty()) it.error else null) }
    if (pin.length == TwoStepPin.LENGTH) verify(pin)
  }

  private fun verify(pin: String) = viewModelScope.launch {
    _state.update { it.copy(busy = true) }
    try {
      // Success signs in with the PIN session; the session listener then leaves this screen.
      repo.verify(pin)
      _state.update { it.copy(busy = false, pin = "") }
    } catch (e: TwoStepException.WrongPin) {
      fail(if (e.lockedUntilMs != null) PinError.LOCKED else PinError.WRONG) { it.copy(attemptsLeft = e.attemptsLeft, lockedUntilMs = e.lockedUntilMs) }
    } catch (e: TwoStepException.Locked) {
      fail(PinError.LOCKED) { it.copy(lockedUntilMs = e.untilMs) }
    } catch (e: Exception) {
      fail(PinError.NETWORK) { it }
    }
  }

  fun requestReset() = viewModelScope.launch {
    _state.update { it.copy(busy = true, error = null) }
    try {
      val at = repo.requestReset()
      _state.update { it.copy(busy = false, resetAvailableAtMs = at) }
    } catch (e: Exception) {
      fail(PinError.NETWORK) { it }
    }
  }

  fun completeReset() = viewModelScope.launch {
    _state.update { it.copy(busy = true, error = null) }
    try {
      repo.completeReset()
      _state.update { it.copy(busy = false) }
    } catch (e: TwoStepException.ResetNotReady) {
      fail(PinError.RESET_NOT_READY) { it.copy(resetAvailableAtMs = e.availableAtMs ?: it.resetAvailableAtMs) }
    } catch (e: TwoStepException.ResetCancelled) {
      fail(PinError.RESET_CANCELLED) { it.copy(resetAvailableAtMs = null) }
    } catch (e: Exception) {
      fail(PinError.NETWORK) { it }
    }
  }

  private fun fail(error: PinError, change: (TwoStepVerifyState) -> TwoStepVerifyState) {
    _state.update { change(it).copy(busy = false, pin = "", error = error, errorKey = it.errorKey + 1) }
  }

  companion object {
    fun factory(repo: TwoStepRepository) = viewModelFactory { initializer { TwoStepVerifyViewModel(repo) } }
  }
}

enum class TwoStepMode { OVERVIEW, CREATE, CONFIRM, DISABLE }

data class TwoStepSettingsState(
  val loading: Boolean = true,
  val loadFailed: Boolean = false,
  val enabled: Boolean = false,
  val mode: TwoStepMode = TwoStepMode.OVERVIEW,
  val pin: String = "",
  val busy: Boolean = false,
  val error: PinError? = null,
  val errorKey: Int = 0,
  /** One-shot: "Two-step verification is on" / "PIN changed" / "turned off". */
  val done: TwoStepMode? = null,
)

/** Settings → Two-step verification: turn on (create + confirm), change PIN, turn off (PIN). */
class TwoStepSettingsViewModel(private val repo: TwoStepRepository) : ViewModel() {
  private val _state = MutableStateFlow(TwoStepSettingsState())
  val state: StateFlow<TwoStepSettingsState> = _state
  private var firstPin = ""
  private var changing = false

  init { load() }

  fun load() = viewModelScope.launch {
    _state.update { it.copy(loading = true, loadFailed = false) }
    runCatching { repo.status() }
      .onSuccess { s -> _state.update { it.copy(loading = false, enabled = s.enabled) } }
      .onFailure { _state.update { it.copy(loading = false, loadFailed = true) } }
  }

  fun startCreate() {
    changing = _state.value.enabled
    firstPin = ""
    _state.update { it.copy(mode = TwoStepMode.CREATE, pin = "", error = null) }
  }

  fun startDisable() = _state.update { it.copy(mode = TwoStepMode.DISABLE, pin = "", error = null) }

  fun back() = _state.update { it.copy(mode = TwoStepMode.OVERVIEW, pin = "", error = null) }

  fun consumeDone() = _state.update { it.copy(done = null) }

  fun onPin(pin: String) {
    if (_state.value.busy) return
    _state.update { it.copy(pin = pin, error = if (pin.isEmpty()) it.error else null) }
    if (pin.length != TwoStepPin.LENGTH) return
    when (_state.value.mode) {
      TwoStepMode.CREATE -> when (TwoStepPin.problem(pin)) {
        PinProblem.TOO_SIMPLE -> reject(PinError.TOO_SIMPLE)
        PinProblem.FORMAT -> reject(PinError.WRONG)
        null -> {
          firstPin = pin
          _state.update { it.copy(mode = TwoStepMode.CONFIRM, pin = "") }
        }
      }
      TwoStepMode.CONFIRM ->
        if (pin != firstPin) {
          firstPin = ""
          _state.update { it.copy(mode = TwoStepMode.CREATE) }
          reject(PinError.MISMATCH)
        } else {
          submit(TwoStepMode.CREATE) { repo.setPin(pin) }
        }
      TwoStepMode.DISABLE -> submit(TwoStepMode.DISABLE) { repo.disable(pin) }
      TwoStepMode.OVERVIEW -> Unit
    }
  }

  private fun submit(kind: TwoStepMode, work: suspend () -> Unit) = viewModelScope.launch {
    _state.update { it.copy(busy = true) }
    try {
      work()
      val nowEnabled = kind == TwoStepMode.CREATE
      // done = CONFIRM marks "PIN changed" as opposed to "turned on".
      val done = if (kind == TwoStepMode.CREATE && changing) TwoStepMode.CONFIRM else kind
      firstPin = ""
      _state.update { it.copy(busy = false, enabled = nowEnabled, mode = TwoStepMode.OVERVIEW, pin = "", done = done) }
    } catch (e: TwoStepException.WrongPin) {
      reject(if (e.lockedUntilMs != null) PinError.LOCKED else PinError.WRONG)
    } catch (e: TwoStepException.Locked) {
      reject(PinError.LOCKED)
    } catch (e: TwoStepException.TooSimple) {
      _state.update { it.copy(mode = TwoStepMode.CREATE) }
      reject(PinError.TOO_SIMPLE)
    } catch (e: Exception) {
      reject(PinError.NETWORK)
    }
  }

  private fun reject(error: PinError) {
    _state.update { it.copy(busy = false, pin = "", error = error, errorKey = it.errorKey + 1) }
  }

  companion object {
    fun factory(repo: TwoStepRepository) = viewModelFactory { initializer { TwoStepSettingsViewModel(repo) } }
  }
}
