package com.samvad.messenger.security

import android.os.SystemClock
import com.samvad.core.security.AppLockPolicy
import com.samvad.core.security.LockTimeout
import com.samvad.messenger.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LockState { UNKNOWN, LOCKED, UNLOCKED }

/**
 * App lock (Phase 1.4). Locked at process start when enabled, and again on returning from the
 * background after the chosen timeout. All calls happen on the main thread.
 */
class AppLock(
  prefs: PreferencesRepository,
  scope: CoroutineScope,
  private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
  private val _state = MutableStateFlow(LockState.UNKNOWN)
  val state: StateFlow<LockState> = _state.asStateFlow()

  private var enabled = false
  private var timeout = LockTimeout.ONE_MINUTE
  private var backgroundedAt: Long? = null
  private var leftForExternal = false
  private var externalLaunchAt: Long? = null
  private var authenticating = false

  init {
    scope.launch {
      prefs.preferences.collect { p ->
        enabled = p.appLockEnabled
        timeout = p.lockTimeout
        _state.value = when {
          !enabled -> LockState.UNLOCKED
          _state.value == LockState.UNKNOWN -> LockState.LOCKED
          else -> _state.value
        }
      }
    }
  }

  /** Call right before launching a picker or viewer, so returning from it gets the short grace period. */
  fun expectExternal() {
    externalLaunchAt = clock()
  }

  fun onBackground() {
    // The device-credential screen is another app; leaving for it must not count.
    if (authenticating) return
    val now = clock()
    backgroundedAt = now
    leftForExternal = externalLaunchAt?.let { now - it in 0..EXTERNAL_LAUNCH_WINDOW_MS } ?: false
    externalLaunchAt = null
  }

  fun onForeground() {
    if (AppLockPolicy.shouldLock(enabled, timeout, backgroundedAt, clock(), leftForExternal)) {
      _state.value = LockState.LOCKED
    }
    backgroundedAt = null
    leftForExternal = false
  }

  fun beginAuthentication() {
    authenticating = true
  }

  fun endAuthentication(success: Boolean) {
    authenticating = false
    if (success) {
      backgroundedAt = null
      if (_state.value == LockState.LOCKED) _state.value = LockState.UNLOCKED
    }
  }

  private companion object {
    /** A background within this long of expectExternal() is treated as caused by that launch. */
    const val EXTERNAL_LAUNCH_WINDOW_MS = 5_000L
  }
}
