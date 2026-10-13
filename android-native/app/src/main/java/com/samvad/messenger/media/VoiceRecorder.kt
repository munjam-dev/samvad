package com.samvad.messenger.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.samvad.core.media.MediaRules
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecordingState(val active: Boolean = false, val elapsedMs: Long = 0, val levels: List<Int> = emptyList())

class RecordedVoice(val file: File, val durationMs: Long, val waveform: List<Int>)

/** AAC voice notes (.m4a, 64 kbps mono). Samples the input level every 100 ms for a waveform. */
class VoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
  private var recorder: MediaRecorder? = null
  private var file: File? = null
  private var sampler: Job? = null
  private var startedAt = 0L
  private val amplitudes = mutableListOf<Int>()
  private val _state = MutableStateFlow(RecordingState())
  val state: StateFlow<RecordingState> = _state.asStateFlow()

  fun start(): Boolean {
    if (recorder != null) return false
    val out = File(context.cacheDir, "rec-${System.currentTimeMillis()}.m4a")
    val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    return try {
      r.setAudioSource(MediaRecorder.AudioSource.MIC)
      r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      r.setAudioChannels(1)
      r.setAudioEncodingBitRate(64_000)
      r.setAudioSamplingRate(44_100)
      r.setMaxDuration(MediaRules.MAX_VOICE_MS.toInt())
      r.setOutputFile(out.path)
      r.prepare()
      r.start()
      recorder = r
      file = out
      startedAt = System.currentTimeMillis()
      amplitudes.clear()
      _state.value = RecordingState(active = true)
      sampler = scope.launch {
        while (isActive) {
          delay(100)
          val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
          amplitudes += amp
          _state.value = RecordingState(true, System.currentTimeMillis() - startedAt, MediaRules.waveform(amplitudes.takeLast(120), 32))
        }
      }
      true
    } catch (_: Exception) {
      r.release()
      out.delete()
      false
    }
  }

  /** Stops and returns the recording, or null if it was too short to be useful (< 1 s). */
  fun stop(): RecordedVoice? {
    val r = recorder ?: return null
    val f = file
    val duration = System.currentTimeMillis() - startedAt
    sampler?.cancel()
    recorder = null
    file = null
    _state.value = RecordingState()
    val ok = runCatching { r.stop() }.isSuccess
    r.release()
    if (!ok || f == null || duration < 1_000) {
      f?.delete()
      return null
    }
    return RecordedVoice(f, duration, MediaRules.waveform(amplitudes))
  }

  fun cancel() {
    stop()?.file?.delete()
  }
}
