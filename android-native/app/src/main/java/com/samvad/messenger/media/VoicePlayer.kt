package com.samvad.messenger.media

import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackState(val messageId: String? = null, val playing: Boolean = false, val positionMs: Long = 0)

/** One voice note plays at a time; starting another stops the current one. */
class VoicePlayer(private val scope: CoroutineScope) {
  private var player: MediaPlayer? = null
  private var ticker: Job? = null
  private val _state = MutableStateFlow(PlaybackState())
  val state: StateFlow<PlaybackState> = _state.asStateFlow()

  fun toggle(messageId: String, file: File) {
    val current = _state.value
    if (current.messageId == messageId && player != null) {
      if (current.playing) pause() else resume()
      return
    }
    release()
    val p = MediaPlayer()
    runCatching {
      p.setDataSource(file.path)
      p.setOnCompletionListener { release() }
      p.prepare()
      p.start()
    }.onFailure {
      p.release()
      return
    }
    player = p
    _state.value = PlaybackState(messageId, true, 0)
    ticker = scope.launch {
      while (isActive) {
        _state.value = _state.value.copy(positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(0))
        delay(100)
      }
    }
  }

  private fun pause() {
    player?.pause()
    _state.value = _state.value.copy(playing = false)
  }

  private fun resume() {
    player?.start()
    _state.value = _state.value.copy(playing = true)
  }

  fun release() {
    ticker?.cancel()
    player?.release()
    player = null
    _state.value = PlaybackState()
  }
}
