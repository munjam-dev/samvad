package com.samvad.messenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ChatExport
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.ExportLabels
import kotlinx.coroutines.withContext
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.core.chat.TextValidation
import com.samvad.core.chat.Timeline
import com.samvad.core.chat.TimelineItem
import com.samvad.core.presence.PresenceRules
import com.samvad.core.presence.PresenceStatus
import com.samvad.core.presence.TypingThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.app.Application
import android.net.Uri
import com.samvad.core.media.Media
import com.samvad.core.media.MediaKind
import com.samvad.messenger.AppContainer
import com.samvad.messenger.data.MediaException
import com.samvad.messenger.data.PreparedMedia
import com.samvad.messenger.media.VoicePlayer
import com.samvad.messenger.media.VoiceRecorder
import java.io.File
import java.util.UUID
import com.samvad.messenger.data.MessagesPage
import com.samvad.messenger.data.ReportException
import com.samvad.core.safety.ReportReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class ConversationUiState(
  val loading: Boolean = true,
  val error: Boolean = false,
  val offline: Boolean = false,
  val title: String = "",
  val initials: String = "",
  /** Newest first (the list is reverse-laid-out). */
  val items: List<TimelineItem> = emptyList(),
  val hasMore: Boolean = false,
  val muted: Boolean = false,
  val pinned: Boolean = false,
  val latestIncomingId: String? = null,
)

/** An attachment being uploaded; shown as a pending bubble until its message is written. */
data class PendingUpload(
  val id: String,
  val kind: MediaKind,
  val label: String,
  val failed: Boolean = false,
)

enum class MediaError { TOO_LARGE, TYPE_BLOCKED, UNREADABLE, DOWNLOAD, OPEN }

/** One-shot feedback for block / report actions, shown as a snackbar. */
enum class SafetyNotice { REPORTED, REPORTED_AND_BLOCKED, REPORT_LIMIT, FAILED }

/** A chat a message can be forwarded to. */
data class ForwardTarget(val conversationId: String, val otherUid: String, val name: String)

/** Outcome of a forward: how many chats got the copy and how many did not. */
data class ForwardResult(val sent: Int, val failed: Int)

data class ComposerState(
  val text: String = "",
  val replyTo: Message? = null,
  val editing: Message? = null,
  val tooLong: Boolean = false,
)

class ConversationViewModel(
  private val app: Application,
  container: AppContainer,
  private val me: String,
  private val conversationId: String,
  private val otherUid: String,
) : ViewModel() {
  private val chats = container.chats!!
  private val mediaRepo = container.media!!
  private class Outgoing(val media: PreparedMedia, val caption: String, val replyTo: String?)
  private val prepared = mutableMapOf<String, Outgoing>()
  private val _uploads = MutableStateFlow<List<PendingUpload>>(emptyList())
  val uploads: StateFlow<List<PendingUpload>> = _uploads
  private val _mediaError = MutableStateFlow<MediaError?>(null)
  val mediaError: StateFlow<MediaError?> = _mediaError
  val recorder = VoiceRecorder(app, viewModelScope)
  val player = VoicePlayer(viewModelScope)
  private val presence = container.presence
  private val safety = container.safety
  private var reportRequestId: String? = null
  private val _notice = MutableStateFlow<SafetyNotice?>(null)
  val notice: StateFlow<SafetyNotice?> = _notice
  private val _reporting = MutableStateFlow(false)
  val reporting: StateFlow<Boolean> = _reporting

  /** True while I have blocked the other person. Rules also stop messages if they blocked me. */
  val blocked: StateFlow<Boolean> = safety?.blocked(me)?.map { otherUid in it }
    ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    ?: MutableStateFlow(false)
  private val throttle = TypingThrottle { System.currentTimeMillis() }
  private val limit = MutableStateFlow(PAGE)
  private val failed = MutableStateFlow(false)
  private val reload = MutableStateFlow(0)

  /** My read-receipts setting: off means I neither send read marks nor see other people's. */
  private val receipts: StateFlow<Boolean> = container.preferences.preferences.map { it.readReceipts }
    .stateIn(viewModelScope, SharingStarted.Eagerly, true)

  private val _composer = MutableStateFlow(ComposerState())
  val composer: StateFlow<ComposerState> = _composer

  init {
    viewModelScope.launch { chats.loadProfiles(listOf(otherUid)) }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private val page = combine(limit, reload) { n, _ -> n }.flatMapLatest { n ->
    chats.messages(conversationId, n).catch { failed.value = true }
  }

  val state: StateFlow<ConversationUiState> = combine(
    page,
    chats.memberState(conversationId, otherUid),
    chats.memberState(conversationId, me),
    chats.profiles.map { it[otherUid] },
    combine(failed, receipts) { f, r -> f to r },
  ) { p: MessagesPage, other, mine, profile, (err, showRead) ->
    val title = profile?.displayName ?: ChatList.DELETED_ACCOUNT
    ConversationUiState(
      loading = false,
      error = err,
      offline = p.fromCache,
      title = title,
      initials = ChatList.initials(title),
      items = Timeline.build(p.messages, me, other, System.currentTimeMillis(), showReadReceipts = showRead).asReversed(),
      hasMore = p.hasMore,
      muted = mine?.muted ?: false,
      pinned = mine?.pinned ?: false,
      latestIncomingId = p.messages.lastOrNull { it.senderId != me }?.id,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

  /** Header line: typing…, online, last seen …, or nothing when hidden. Re-evaluated every second so stale typing expires. */
  val status: StateFlow<PresenceStatus> = run {
    val p = presence ?: return@run MutableStateFlow<PresenceStatus>(PresenceStatus.Unknown)
    val ticker = flow { while (true) { emit(Unit); delay(1_000) } }
    combine(p.typing(conversationId, otherUid), p.presence(otherUid), blocked, ticker) { typingAt, pres, isBlocked, _ ->
      if (isBlocked) PresenceStatus.Unknown else PresenceRules.status(typingAt, pres, p.serverNow())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresenceStatus.Unknown)
  }

  private fun onTyping(text: String) {
    val p = presence ?: return
    if (blocked.value) return
    if (text.isBlank()) {
      throttle.reset()
      viewModelScope.launch { p.clearTyping(conversationId, me) }
    } else if (throttle.onKeystroke()) {
      viewModelScope.launch { p.setTyping(conversationId, me) }
    }
  }

  private fun stopTyping() {
    throttle.reset()
    presence?.let { p -> viewModelScope.launch { p.clearTyping(conversationId, me) } }
  }

  fun sendImage(uri: Uri) = prepareAndSend { mediaRepo.prepareImage(uri) }
  fun sendDocument(uri: Uri) = prepareAndSend { mediaRepo.prepareDocument(uri) }

  fun startRecording(): Boolean = recorder.start()
  fun cancelRecording() = recorder.cancel()
  fun finishRecording() {
    val voice = recorder.stop() ?: return
    prepareAndSend { mediaRepo.prepareVoice(voice.file, voice.durationMs, voice.waveform) }
  }

  private fun prepareAndSend(prepare: suspend () -> PreparedMedia) = viewModelScope.launch {
    if (blocked.value) return@launch
    val media = try {
      prepare()
    } catch (e: MediaException.TooLarge) {
      _mediaError.value = MediaError.TOO_LARGE; return@launch
    } catch (e: MediaException.TypeBlocked) {
      _mediaError.value = MediaError.TYPE_BLOCKED; return@launch
    } catch (e: Exception) {
      _mediaError.value = MediaError.UNREADABLE; return@launch
    }
    val id = UUID.randomUUID().toString()
    val caption = _composer.value.text.trim().takeIf { media.kind != MediaKind.VOICE && it.length <= MessageRules.MAX_LENGTH }.orEmpty()
    val replyTo = _composer.value.replyTo?.id
    if (caption.isNotEmpty() || replyTo != null) _composer.value = ComposerState()
    prepared[id] = Outgoing(media, caption, replyTo)
    _uploads.update { it + PendingUpload(id, media.kind, media.displayName) }
    upload(id)
  }

  private suspend fun upload(id: String) {
    val out = prepared[id] ?: return
    val media = out.media
    val caption = out.caption
    val replyTo = out.replyTo
    try {
      val path = mediaRepo.upload(conversationId, id, me, media)
      chats.sendMedia(
        conversationId, me, id, media.kind.wire,
        Media(path, media.displayName, media.bytes.size.toLong(), media.mime, media.width, media.height, media.durationMs, media.waveform),
        caption, replyTo,
      )
      prepared.remove(id)
      _uploads.update { list -> list.filterNot { it.id == id } }
    } catch (e: Exception) {
      _uploads.update { list -> list.map { if (it.id == id) it.copy(failed = true) else it } }
    }
  }

  fun retryUpload(id: String) = viewModelScope.launch {
    _uploads.update { list -> list.map { if (it.id == id) it.copy(failed = false) else it } }
    upload(id)
  }

  fun cancelUpload(id: String) {
    prepared.remove(id)
    _uploads.update { list -> list.filterNot { it.id == id } }
  }

  fun clearMediaError() { _mediaError.value = null }
  fun reportMediaError(e: MediaError) { _mediaError.value = e }

  suspend fun localFile(path: String): File? = runCatching { mediaRepo.localFile(path) }.getOrNull()
  suspend fun bitmap(path: String, maxEdgePx: Int) = mediaRepo.loadBitmap(path, maxEdgePx)

  fun togglePlayback(message: Message) = viewModelScope.launch {
    val path = message.media?.path ?: return@launch
    val file = localFile(path) ?: return@launch reportMediaError(MediaError.DOWNLOAD)
    player.toggle(message.id, file)
  }

  override fun onCleared() {
    recorder.cancel()
    player.release()
    // viewModelScope is already cancelled here, so clear the typing mark on a short-lived scope.
    presence?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.clearTyping(conversationId, me) } }
    super.onCleared()
  }

  fun loadMore() {
    if (state.value.hasMore) limit.update { it + PAGE }
  }

  fun markRead() = viewModelScope.launch { chats.markRead(conversationId, me, sendReceipt = receipts.value) }

  /**
   * Writes the chat as text (newest [ChatExport.MAX_MESSAGES] messages, media described, not
   * included) to the private exports cache, for the share sheet. Returns null on failure.
   */
  suspend fun exportChat(labels: ExportLabels, myName: String): File? = runCatching {
    val messages = chats.exportMessages(conversationId, ChatExport.MAX_MESSAGES)
    val names = mapOf(me to myName, otherUid to state.value.title)
    withContext(Dispatchers.IO) {
      val dir = File(app.cacheDir, "exports").apply { mkdirs() }
      // Keep only the latest export on the device.
      dir.listFiles()?.forEach { it.delete() }
      File(dir, ChatExport.fileName(state.value.title)).apply {
        writeText(ChatExport.format(messages, names, labels, java.time.ZoneId.systemDefault()))
      }
    }
  }.getOrNull()

  fun onText(text: String) {
    _composer.update { it.copy(text = text, tooLong = text.length > MessageRules.MAX_LENGTH) }
    onTyping(text)
  }

  fun reply(message: Message) = _composer.update { it.copy(replyTo = message, editing = null) }

  fun startEdit(message: Message) = _composer.update { it.copy(editing = message, replyTo = null, text = message.text) }

  fun cancelContext() = _composer.update { it.copy(replyTo = null, editing = null, text = if (it.editing != null) "" else it.text) }

  fun submit() {
    if (blocked.value) return
    val c = _composer.value
    val text = (MessageRules.validate(c.text) as? TextValidation.Ok)?.text ?: return
    _composer.value = ComposerState()
    stopTyping()
    viewModelScope.launch {
      runCatching {
        if (c.editing != null) chats.edit(conversationId, c.editing.id, text)
        else chats.send(conversationId, me, text, c.replyTo?.id)
      }
    }
  }

  fun retry(message: Message) = viewModelScope.launch { runCatching { chats.retry(conversationId, me, message) } }

  fun delete(message: Message) = viewModelScope.launch { runCatching { chats.deleteForEveryone(conversationId, message.id) } }

  fun react(message: Message, emoji: String) = viewModelScope.launch {
    val current = message.reactions[me]
    runCatching { chats.react(conversationId, message.id, me, if (current == emoji) null else emoji) }
  }

  fun toggle(field: String, value: Boolean) = viewModelScope.launch { runCatching { chats.setFlag(conversationId, me, field, value) } }

  fun block() {
    val s = safety ?: return
    stopTyping()
    if (recorder.state.value.active) recorder.cancel()
    viewModelScope.launch { runCatching { s.block(me, otherUid) }.onFailure { _notice.value = SafetyNotice.FAILED } }
  }

  fun unblock() {
    val s = safety ?: return
    viewModelScope.launch { runCatching { s.unblock(me, otherUid) }.onFailure { _notice.value = SafetyNotice.FAILED } }
  }

  /** Sends a report; a retry after a failure reuses the request ID so the server stores it once. */
  fun report(reason: ReportReason, alsoBlock: Boolean) {
    val s = safety ?: return
    if (_reporting.value) return
    val requestId = reportRequestId ?: UUID.randomUUID().toString().also { reportRequestId = it }
    _reporting.value = true
    if (alsoBlock) stopTyping()
    viewModelScope.launch {
      _notice.value = try {
        s.report(requestId, conversationId, reason, alsoBlock)
        reportRequestId = null
        if (alsoBlock) SafetyNotice.REPORTED_AND_BLOCKED else SafetyNotice.REPORTED
      } catch (e: ReportException.LimitReached) {
        SafetyNotice.REPORT_LIMIT
      } catch (e: Exception) {
        SafetyNotice.FAILED
      }
      _reporting.value = false
    }
  }

  fun clearNotice() { _notice.value = null }

  private val _forwardResult = MutableStateFlow<ForwardResult?>(null)
  val forwardResult: StateFlow<ForwardResult?> = _forwardResult
  fun clearForwardResult() { _forwardResult.value = null }

  /** My direct chats, most recent first, with the other person's name. */
  fun forwardTargets(): kotlinx.coroutines.flow.Flow<List<ForwardTarget>> =
    combine(chats.conversations(me).catch { }, chats.profiles) { snap, profiles ->
      snap.value.filter { !it.isGroup }.mapNotNull { c ->
        val other = c.members.firstOrNull { it != me } ?: return@mapNotNull null
        ForwardTarget(c.id, other, profiles[other]?.displayName ?: ChatList.DELETED_ACCOUNT)
      }
    }.onEach { targets -> chats.loadProfiles(targets.map { it.otherUid }) }

  /**
   * Sends a copy of [message] to up to [MessageRules.MAX_FORWARD_TARGETS] chats, labelled forwarded.
   * Media is uploaded again into each target chat. Chats where it fails (for example a block) are
   * counted, not retried.
   */
  fun forward(message: Message, targets: List<ForwardTarget>) = viewModelScope.launch {
    val chosen = targets.distinctBy { it.conversationId }.take(MessageRules.MAX_FORWARD_TARGETS)
    if (chosen.isEmpty() || !MessageRules.canForward(message)) return@launch
    val kind = MediaKind.fromWire(message.kind)
    val media = message.media
    val prepared = if (kind != null && media != null) {
      runCatching { mediaRepo.prepareForward(media, kind) }.getOrElse {
        _forwardResult.value = ForwardResult(0, chosen.size)
        return@launch
      }
    } else {
      null
    }
    val outcomes = chosen.map { t ->
      async {
        runCatching {
          if (prepared == null) {
            chats.send(t.conversationId, me, message.text, replyTo = null, forwarded = true)
          } else {
            val id = UUID.randomUUID().toString()
            val path = mediaRepo.upload(t.conversationId, id, me, prepared)
            chats.sendMedia(
              t.conversationId, me, id, prepared.kind.wire,
              Media(path, prepared.displayName, prepared.bytes.size.toLong(), prepared.mime, prepared.width, prepared.height, prepared.durationMs, prepared.waveform),
              message.text, replyTo = null, forwarded = true,
            )
          }
        }.isSuccess
      }
    }.awaitAll()
    _forwardResult.value = ForwardResult(sent = outcomes.count { it }, failed = outcomes.count { !it })
  }

  fun canEdit(message: Message) = MessageRules.canEdit(message, me, System.currentTimeMillis())

  fun retryLoad() {
    failed.value = false
    reload.update { it + 1 }
  }

  companion object {
    const val PAGE = 30L
    fun factory(app: Application, container: AppContainer, me: String, cid: String, other: String) =
      viewModelFactory { initializer { ConversationViewModel(app, container, me, cid, other) } }
  }
}
