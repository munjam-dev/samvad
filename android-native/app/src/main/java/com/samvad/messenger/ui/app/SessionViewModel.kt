package com.samvad.messenger.ui.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.AppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Destination { LOADING, SETUP_REQUIRED, ONBOARDING, SIGN_IN, PROFILE_SETUP, HOME }

data class SessionState(
  val loading: Boolean = true,
  val destination: Destination = Destination.LOADING,
  val uid: String? = null,
  val phone: String? = null,
  val preferences: AppPreferences = AppPreferences(),
  val accountCheckFailed: Boolean = false,
)

/** Decides which part of the app the user sees, from preferences and the Firebase session. */
class SessionViewModel(private val container: AppContainer) : ViewModel() {
  private val refresh = MutableStateFlow(0)

  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<SessionState> = run {
    val auth = container.auth
    val users = auth?.userChanges() ?: flowOf(null)
    combine(container.preferences.preferences, users, refresh) { prefs, user, _ -> prefs to user }
      .flatMapLatest { (prefs, user) ->
        flow {
          when {
            !container.isConfigured -> emit(SessionState(false, Destination.SETUP_REQUIRED, preferences = prefs))
            !prefs.onboardingDone -> emit(SessionState(false, Destination.ONBOARDING, preferences = prefs))
            user == null -> emit(SessionState(false, Destination.SIGN_IN, preferences = prefs))
            else -> {
              val hasAccount = runCatching { container.accounts!!.hasAccount(user.uid) }
              val dest = if (hasAccount.getOrDefault(true)) Destination.HOME else Destination.PROFILE_SETUP
              emit(
                SessionState(
                  loading = false,
                  destination = dest,
                  uid = user.uid,
                  phone = user.phoneNumber,
                  preferences = prefs,
                  accountCheckFailed = hasAccount.isFailure,
                ),
              )
              if (dest == Destination.HOME) registerDevice(user.uid)
            }
          }
        }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, SessionState())
  }

  private var registeredUid: String? = null

  private fun registerDevice(uid: String) {
    if (registeredUid == uid) return
    registeredUid = uid
    viewModelScope.launch {
      val token = container.push.currentToken()
      runCatching { container.accounts?.registerDevice(uid, container.preferences.deviceId(), token) }
        .onFailure { registeredUid = null }
    }
  }

  fun finishOnboarding() = viewModelScope.launch { container.preferences.setOnboardingDone() }

  fun profileCreated() {
    refresh.value++
  }

  fun signOut() = viewModelScope.launch {
    val uid = state.value.uid
    if (uid != null) container.accounts?.unregisterDevice(uid, container.preferences.deviceId())
    registeredUid = null
    container.push.forgetToken()
    container.auth?.signOut()
  }

  companion object {
    fun factory(container: AppContainer) = viewModelFactory { initializer { SessionViewModel(container) } }
  }
}
