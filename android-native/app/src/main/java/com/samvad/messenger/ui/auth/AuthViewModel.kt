package com.samvad.messenger.ui.auth

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.samvad.core.auth.AuthError
import com.samvad.core.auth.OtpPolicy
import com.samvad.core.phone.Country
import com.samvad.core.phone.PhoneNumbers
import com.samvad.core.phone.PhoneValidation
import com.samvad.messenger.data.AuthRepository
import com.samvad.messenger.data.PhoneVerificationEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { PHONE, CODE, DONE }

data class AuthUiState(
  val step: AuthStep = AuthStep.PHONE,
  val country: Country = PhoneNumbers.countryFor(PhoneNumbers.DEFAULT_REGION),
  val number: String = "",
  val validation: PhoneValidation = PhoneValidation.Empty,
  val consent: Boolean = false,
  val sending: Boolean = false,
  val code: String = "",
  val verifying: Boolean = false,
  val resendInSeconds: Int = 0,
  val resendsLeft: Int = OtpPolicy.MAX_RESENDS_PER_SESSION,
  val attemptsLeft: Int = OtpPolicy.MAX_WRONG_ATTEMPTS,
  val error: AuthError? = null,
  val hourlyLimit: Boolean = false,
  val resendLimit: Boolean = false,
) {
  val e164: String? get() = (validation as? PhoneValidation.Valid)?.e164
  val display: String get() = (validation as? PhoneValidation.Valid)?.international ?: number
  val canSend: Boolean get() = e164 != null && consent && !sending
}

/**
 * Phone sign-in. The verification ID and resend token live only in [SavedStateHandle] (never on
 * disk, never logged). In [reauth] mode a fresh code re-authenticates the current user instead.
 */
class AuthViewModel(
  private val auth: AuthRepository,
  private val handle: SavedStateHandle,
  private val reauth: Boolean,
  presetE164: String?,
) : ViewModel() {
  private val policy = OtpPolicy { System.currentTimeMillis() }
  private val _state = MutableStateFlow(AuthUiState(consent = reauth))
  val state: StateFlow<AuthUiState> = _state.asStateFlow()
  private var verifyJob: Job? = null
  private var countdownJob: Job? = null

  private var verificationId: String?
    get() = handle["verificationId"]
    set(value) { handle["verificationId"] = value }
  private var resendToken: PhoneAuthProvider.ForceResendingToken?
    get() = handle["resendToken"]
    set(value) { handle["resendToken"] = value }

  init {
    if (presetE164 != null) onNumberChange(presetE164)
  }

  fun onCountry(country: Country) = _state.update {
    it.copy(country = country, validation = PhoneNumbers.validate(it.number, country.regionCode), error = null)
  }

  fun onNumberChange(input: String) {
    val cleaned = input.filter { it.isDigit() || it == '+' || it == ' ' }.take(20)
    _state.update { it.copy(number = cleaned, validation = PhoneNumbers.validate(cleaned, it.country.regionCode), error = null) }
  }

  fun onConsent(value: Boolean) = _state.update { it.copy(consent = value) }

  fun sendCode(activity: Activity, isResend: Boolean = false) {
    val e164 = _state.value.e164 ?: return
    val decision = if (isResend) policy.canResend() else policy.canStart(e164)
    when (decision) {
      OtpPolicy.Decision.HourlyLimit -> return _state.update { it.copy(hourlyLimit = true) }
      OtpPolicy.Decision.SessionResendLimit -> return _state.update { it.copy(resendLimit = true) }
      is OtpPolicy.Decision.WaitSeconds -> return
      OtpPolicy.Decision.Allowed -> Unit
    }
    _state.update { it.copy(sending = true, error = null, hourlyLimit = false) }
    verifyJob?.cancel()
    verifyJob = viewModelScope.launch {
      auth.verify(activity, e164, if (isResend) resendToken else null).collect { event ->
        when (event) {
          is PhoneVerificationEvent.CodeSent -> {
            verificationId = event.verificationId
            resendToken = event.resendToken
            policy.onCodeSent(e164, isResend)
            _state.update {
              it.copy(
                step = AuthStep.CODE, sending = false, code = "",
                resendsLeft = policy.resendsLeft(), attemptsLeft = policy.attemptsLeft(),
              )
            }
            startCountdown()
          }
          is PhoneVerificationEvent.AutoVerified -> complete(event.credential)
          is PhoneVerificationEvent.Failed -> _state.update { it.copy(sending = false, error = event.error) }
        }
      }
    }
  }

  private fun startCountdown() {
    countdownJob?.cancel()
    countdownJob = viewModelScope.launch {
      while (true) {
        val d = policy.canResend()
        val wait = (d as? OtpPolicy.Decision.WaitSeconds)?.seconds ?: 0
        _state.update { it.copy(resendInSeconds = wait, resendLimit = d == OtpPolicy.Decision.SessionResendLimit) }
        if (wait == 0) break
        delay(1000)
      }
    }
  }

  fun onCodeChange(code: String) {
    val digits = code.filter { it.isDigit() }.take(6)
    _state.update { it.copy(code = digits, error = null) }
  }

  fun verify() {
    val id = verificationId ?: return _state.update { it.copy(error = AuthError.CODE_EXPIRED) }
    val code = _state.value.code
    if (code.length != 6 || _state.value.verifying) return
    complete(auth.credential(id, code))
  }

  private fun complete(credential: PhoneAuthCredential) {
    _state.update { it.copy(verifying = true, error = null, code = credential.smsCode ?: it.code) }
    viewModelScope.launch {
      try {
        if (reauth) auth.reauthenticate(credential) else auth.signIn(credential)
        verificationId = null
        resendToken = null
        policy.reset()
        _state.update { it.copy(verifying = false, step = AuthStep.DONE) }
      } catch (e: Exception) {
        val error = AuthRepository.mapError(e)
        val forceNew = error == AuthError.INVALID_CODE && policy.onWrongCode()
        _state.update {
          it.copy(
            verifying = false,
            code = "",
            error = if (forceNew) AuthError.NEW_CODE_REQUIRED else error,
            attemptsLeft = policy.attemptsLeft(),
          )
        }
        if (forceNew) verificationId = null
      }
    }
  }

  fun backToPhone() {
    verifyJob?.cancel()
    countdownJob?.cancel()
    verificationId = null
    resendToken = null
    _state.update { it.copy(step = AuthStep.PHONE, code = "", error = null, sending = false) }
  }

  companion object {
    fun factory(auth: AuthRepository, reauth: Boolean = false, presetE164: String? = null) = viewModelFactory {
      initializer { AuthViewModel(auth, createSavedStateHandle(), reauth, presetE164) }
    }
  }
}
