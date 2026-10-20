package com.samvad.messenger.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Remembers that the person has answered the one-time "turn on Data saver" suggestion, so it is never shown
 * again. Holds one yes/no value and nothing personal, so a plain private SharedPreferences file is enough.
 */
class NetworkHints(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("samvad_network_hints", Context.MODE_PRIVATE)
  private val _answered = MutableStateFlow(prefs.getBoolean(KEY, false))

  /** True once the suggestion was turned on or dismissed. */
  val dataSaverHintAnswered: StateFlow<Boolean> = _answered

  fun markDataSaverHintAnswered() {
    _answered.value = true
    prefs.edit().putBoolean(KEY, true).apply()
  }

  private companion object {
    const val KEY = "data_saver_hint_answered"
  }
}
