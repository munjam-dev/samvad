package com.samvad.messenger.data

import com.samvad.core.media.TransferProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a download of one attachment stands. No entry means it was never started on this run. */
sealed interface DownloadState {
  data class Active(val transferred: Long, val total: Long) : DownloadState {
    /** 0..1, or null while the total is not known. */
    val fraction: Float? get() = TransferProgress.fraction(transferred, total)
  }

  data object Failed : DownloadState
  data object Cancelled : DownloadState
  data object Done : DownloadState
}

/**
 * Downloads attachments in the background with progress, keyed by Storage path. A person can cancel a
 * download, and a failed one stays visible so the chat can offer Retry. Starting is always an explicit call,
 * so the caller (not this class) applies the auto-download policy.
 */
class MediaDownloads(private val media: MediaRepository) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val jobs = mutableMapOf<String, Job>()
  private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

  val state: StateFlow<Map<String, DownloadState>> = _state

  /** Starts downloading [path]; does nothing while it is already downloading. [expectedSize] is used until the SDK knows the total. */
  fun start(path: String, expectedSize: Long) {
    if (jobs.containsKey(path)) return
    set(path, DownloadState.Active(0L, expectedSize))
    // Lazy, so the job is in [jobs] before it can finish and remove itself.
    val job = scope.launch(start = CoroutineStart.LAZY) {
      val self = coroutineContext[Job]
      try {
        media.download(path) { done, total ->
          // A progress report that arrives after Cancel must not bring the ring back.
          if (jobs.containsKey(path)) set(path, DownloadState.Active(done, if (total > 0) total else expectedSize))
        }
        set(path, DownloadState.Done)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        set(path, DownloadState.Failed)
      } finally {
        if (jobs[path] === self) jobs.remove(path)
      }
    }
    jobs[path] = job
    job.start()
  }

  fun cancel(path: String) {
    jobs.remove(path)?.cancel() ?: return
    set(path, DownloadState.Cancelled)
  }

  private fun set(path: String, value: DownloadState) {
    _state.update { it + (path to value) }
  }
}
