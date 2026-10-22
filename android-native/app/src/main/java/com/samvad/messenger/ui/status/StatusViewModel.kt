package com.samvad.messenger.ui.status

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ConversationIds
import com.samvad.core.safety.ReportReason
import com.samvad.core.status.PhotoEdits
import com.samvad.core.status.StatusAudience
import com.samvad.core.status.StatusGroup
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.PreparedMedia
import com.samvad.messenger.data.ReportException
import com.samvad.messenger.data.StatusViewer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatusUiState(
  val loading: Boolean = true,
  val groups: List<StatusGroup> = emptyList(),
  val mine: List<StatusItem> = emptyList(),
  val seen: Set<String> = emptySet(),
  val muted: Set<String> = emptySet(),
  val mutedGroups: List<StatusGroup> = emptyList(),
  val names: Map<String, String> = emptyMap(),
  val now: Long = System.currentTimeMillis(),
)

/** A person I could pick when sharing with "only" or "everyone except". */
data class StatusPerson(val uid: String, val name: String)

/** Why a moment could not be posted, so the screen can say something useful. */
enum class PostError { GENERIC, VIDEO_TOO_LONG, VIDEO_TOO_BIG, VIDEO_UNREADABLE }

/** How reporting a moment ended, so the viewer can say so. */
enum class MomentReportResult { SENT, SENT_AND_BLOCKED, LIMIT, NO_CHAT, FAILED }

sealed interface PostState {
  data object Idle : PostState
  data object Posting : PostState
  data object Done : PostState
  data object Failed : PostState
}

class StatusViewModel(private val container: AppContainer, private val me: String) : ViewModel() {
  private val repo = container.status!!
  private val chats = container.chats!!

  private val clock = MutableStateFlow(System.currentTimeMillis())
  private val rawFeed = repo.feed(me).catch { emit(emptyList()) }
  private val rawMine = repo.mine(me).catch { emit(emptyList()) }

  init {
    // Wake up when the next post expires so it leaves the screen on time.
    viewModelScope.launch {
      combine(rawFeed, rawMine) { a, b -> a + b }.collectLatest { items ->
        val now = System.currentTimeMillis()
        val next = StatusRules.nextExpiry(items, now)
        if (next != null) {
          delay(next - now + 50)
          clock.value = System.currentTimeMillis()
        }
      }
    }
    // Make sure the people who posted have names on this device.
    viewModelScope.launch {
      rawFeed.collect { items -> chats.loadProfiles(items.map { it.authorId }.distinct()) }
    }
  }

  val state: StateFlow<StatusUiState> = combine(
    rawFeed, rawMine, container.preferences.preferences, clock, chats.profiles,
  ) { feed, mine, prefs, now, profiles ->
    StatusUiState(
      loading = false,
      groups = StatusRules.feed(feed, prefs.statusSeen, me, now, prefs.statusMuted),
      mine = StatusRules.mine(mine, me, now),
      seen = prefs.statusSeen,
      muted = prefs.statusMuted,
      mutedGroups = StatusRules.feed(feed, prefs.statusSeen, me, now).filter { it.authorId in prefs.statusMuted },
      names = profiles.mapValues { it.value.displayName },
      now = now,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

  /** The people I chat with one to one, for choosing an audience. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val people: StateFlow<List<StatusPerson>> = combine(chats.conversations(me).catch { emit(com.samvad.messenger.data.Snapshot(emptyList(), false)) }, chats.profiles) { convs, profiles ->
    convs.value.filter { !it.isGroup }.mapNotNull { c -> ConversationIds.otherMember(c, me) }.distinct()
      .map { uid -> StatusPerson(uid, profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT) }
      .sortedBy { it.name.lowercase() }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _post = MutableStateFlow<PostState>(PostState.Idle)
  val post: StateFlow<PostState> = _post
  private val _progress = MutableStateFlow(0f)
  /** Upload progress 0..1 while a photo, video or voice moment is going up. */
  val progress: StateFlow<Float> = _progress
  private val _error = MutableStateFlow(PostError.GENERIC)
  val error: StateFlow<PostError> = _error

  private fun fail(e: Throwable) {
    _error.value = when ((e as? com.samvad.messenger.data.MediaRepository.VideoProblemException)?.problem) {
      com.samvad.core.status.VideoProblem.TOO_LONG -> PostError.VIDEO_TOO_LONG
      com.samvad.core.status.VideoProblem.TOO_BIG -> PostError.VIDEO_TOO_BIG
      com.samvad.core.status.VideoProblem.UNREADABLE -> PostError.VIDEO_UNREADABLE
      null -> PostError.GENERIC
    }
    _post.value = PostState.Failed
  }

  private fun start(): Boolean {
    if (_post.value == PostState.Posting) return false
    _progress.value = 0f
    _post.value = PostState.Posting
    return true
  }

  fun postText(text: String, style: String, audience: StatusAudience, list: List<String>, repliesOff: Boolean = false) {
    if (!StatusRules.validText(text) || !start()) return
    viewModelScope.launch {
      runCatching { repo.postText(me, text, StatusRules.style(style), audience, list, repliesOff) }.fold({ _post.value = PostState.Done }, ::fail)
    }
  }

  fun postPhoto(uri: Uri, caption: String, audience: StatusAudience, list: List<String>, edits: PhotoEdits = PhotoEdits.NONE, repliesOff: Boolean = false) {
    if (!start()) return
    viewModelScope.launch {
      runCatching {
        val saver = container.preferences.preferences.first().dataSaver
        val prepared: PreparedMedia = container.media!!.prepareImage(uri, saver, edits)
        repo.postImage(me, prepared, caption.take(StatusRules.MAX_TEXT), audience, list, { _progress.value = it }, repliesOff)
      }.fold({ _post.value = PostState.Done }, ::fail)
    }
  }

  fun postVideo(uri: Uri, caption: String, audience: StatusAudience, list: List<String>, repliesOff: Boolean = false) {
    if (!start()) return
    viewModelScope.launch {
      runCatching {
        val video = container.media!!.prepareVideo(uri)
        repo.postVideo(me, video, caption.take(StatusRules.MAX_TEXT), audience, list, { _progress.value = it }, repliesOff)
      }.fold({ _post.value = PostState.Done }, ::fail)
    }
  }

  fun postVoice(voice: com.samvad.messenger.media.RecordedVoice, audience: StatusAudience, list: List<String>, repliesOff: Boolean = false) {
    if (!start()) return
    viewModelScope.launch {
      runCatching {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { voice.file.readBytes().also { voice.file.delete() } }
        repo.postVoice(me, bytes, voice.durationMs, voice.waveform, audience, list, { _progress.value = it }, repliesOff)
      }.fold({ _post.value = PostState.Done }, ::fail)
    }
  }

  fun resetPost() { _post.value = PostState.Idle }

  /** Called when a status comes on screen: remember it locally and, if receipts are on, tell the author. */
  fun seen(item: StatusItem) {
    viewModelScope.launch {
      val live = state.value.let { s -> (s.groups.flatMap { it.items } + s.mine).map { it.id }.toSet() }
      container.preferences.markStatusSeen(item.id, live)
      if (item.authorId != me && container.preferences.preferences.first().readReceipts) {
        runCatching { repo.recordView(item.authorId, item.id, me) }
      }
    }
  }

  fun react(item: StatusItem, emoji: String) {
    viewModelScope.launch { runCatching { repo.react(item.authorId, item.id, me, emoji) } }
  }

  /** A private reply goes into our one-to-one chat, quoting a short piece of the status. */
  fun reply(item: StatusItem, text: String, onSent: () -> Unit) {
    val body = text.trim()
    if (body.isEmpty() || !StatusRules.canReply(item, me)) return
    viewModelScope.launch {
      runCatching {
        val cid = chats.openDirect(me, item.authorId, container::applyDefaultTimer)
        val quote = item.text.take(60).let { if (it.isBlank()) "your status" else "\"$it\"" }
        chats.send(cid, me, "Replying to $quote\n$body", replyTo = null, momentRef = item.authorId to item.id)
      }.onSuccess { onSent() }
    }
  }

  private val _replySending = MutableStateFlow(false)
  val replySending: StateFlow<Boolean> = _replySending

  /**
   * A voice reply (up to 30 seconds) goes into our one-to-one chat like a voice message, tagged with the moment it
   * answers. The Storage rules for conversation media apply; the Firestore rules refuse it when replies are off.
   */
  fun replyVoice(item: StatusItem, voice: com.samvad.messenger.media.RecordedVoice, onDone: (Boolean) -> Unit) {
    if (!StatusRules.canReply(item, me) || !StatusRules.voiceReplyOk(voice.durationMs) || _replySending.value) {
      voice.file.delete()
      onDone(false)
      return
    }
    _replySending.value = true
    viewModelScope.launch {
      val ok = runCatching {
        val media = container.media!!
        val prepared = media.prepareVoice(voice.file, voice.durationMs, voice.waveform)
        val cid = chats.openDirect(me, item.authorId, container::applyDefaultTimer)
        val mid = java.util.UUID.randomUUID().toString()
        val path = media.upload(cid, mid, me, prepared)
        val quote = item.text.take(60).let { if (it.isBlank()) "your status" else "\"$it\"" }
        chats.sendMedia(
          cid, me, mid, "voice",
          com.samvad.core.media.Media(path, prepared.displayName, prepared.bytes.size.toLong(), prepared.mime, durationMs = prepared.durationMs, waveform = prepared.waveform),
          "Replying to $quote", replyTo = null, momentRef = item.authorId to item.id,
        )
      }.isSuccess
      _replySending.value = false
      onDone(ok)
    }
  }

  /** Author only: change who can see one of my live moments. The server re-stamps who may see it. */
  fun setAudience(item: StatusItem, audience: StatusAudience, list: List<String>, onDone: (Boolean) -> Unit) {
    viewModelScope.launch { onDone(runCatching { repo.setAudience(item.id, audience, list) }.isSuccess) }
  }

  // ---- Report a moment ---------------------------------------------------------------------

  private val safety = container.safety

  /** People I have blocked, so the report dialog can leave out "also block" for them. */
  val blockedUids: StateFlow<Set<String>> = (safety?.blocked(me) ?: flowOf(emptySet()))
    .catch { emit(emptySet()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

  private val _reporting = MutableStateFlow(false)
  val reporting: StateFlow<Boolean> = _reporting
  private val _reportResult = MutableStateFlow<MomentReportResult?>(null)
  val reportResult: StateFlow<MomentReportResult?> = _reportResult
  private var reportRequest: Pair<String, String>? = null

  /**
   * Reports the author of a moment through the same reportContact flow as the chat report. The server takes a
   * one-to-one chat, so the report names our chat with [author]. A retry reuses the request ID so it is stored once.
   */
  fun reportMoment(author: String, reason: ReportReason, alsoBlock: Boolean) {
    val s = safety ?: return
    if (author == me || _reporting.value) return
    val conversationId = ConversationIds.direct(me, author)
    val requestId = reportRequest?.takeIf { it.first == conversationId }?.second
      ?: java.util.UUID.randomUUID().toString().also { reportRequest = conversationId to it }
    _reporting.value = true
    viewModelScope.launch {
      _reportResult.value = try {
        s.report(requestId, conversationId, reason, alsoBlock)
        reportRequest = null
        if (alsoBlock) MomentReportResult.SENT_AND_BLOCKED else MomentReportResult.SENT
      } catch (e: ReportException.LimitReached) {
        MomentReportResult.LIMIT
      } catch (e: ReportException.Failed) {
        val code = (e.cause as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND) MomentReportResult.NO_CHAT else MomentReportResult.FAILED
      } catch (e: Exception) {
        MomentReportResult.FAILED
      }
      _reporting.value = false
    }
  }

  fun clearReportResult() { _reportResult.value = null }

  fun delete(item: StatusItem) { viewModelScope.launch { runCatching { repo.delete(me, item.id) } } }

  fun mute(author: String, muted: Boolean) { viewModelScope.launch { container.preferences.setStatusMuted(author, muted) } }

  fun viewers(id: String): Flow<List<StatusViewer>> = repo.viewers(me, id).catch { emit(emptyList()) }

  suspend fun photo(path: String) = container.media?.loadBitmap(path, 1280)

  /** Local copy of a video or voice file, downloaded through the rules-checked path and cached. */
  suspend fun localFile(path: String): java.io.File? = runCatching { container.media?.localFile(path) }.getOrNull()

  companion object {
    fun factory(container: AppContainer, me: String) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = StatusViewModel(container, me) as T
    }
  }
}
