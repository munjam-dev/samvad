package com.samvad.messenger.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.samvad.core.chat.ScheduleRules
import com.samvad.core.chat.ScheduleCheck
import com.samvad.core.chat.Profile
import com.samvad.core.chat.ChatExport
import com.samvad.core.chat.ChatList
import com.samvad.core.chat.Conversation
import com.samvad.core.chat.GroupRules
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Mentions
import com.samvad.core.chat.Polls
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import com.samvad.messenger.data.GroupException
import com.samvad.messenger.data.ScheduledItem
import kotlinx.coroutines.flow.collectLatest
import com.samvad.messenger.AppContainer
import com.samvad.core.media.ImageQuality
import com.samvad.core.media.NetworkInfo
import com.samvad.messenger.data.DownloadState
import com.samvad.messenger.data.MediaException
import com.samvad.messenger.data.UploadItem
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
  /** Phase 2: group chats. */
  val isGroup: Boolean = false,
  val groupPhotoPath: String? = null,
  val memberCount: Int = 0,
  val isAdmin: Boolean = false,
  /** I was removed or left: the group is read-only and shows why. */
  val removed: Boolean = false,
)

/** Group header line: who is typing, otherwise who is in the group. */
data class GroupStatus(
  val typing: List<String> = emptyList(),
  val members: List<String> = emptyList(),
  /** Everyone's names, me included, for highlighting @mentions. */
  val allNames: List<String> = emptyList(),
)

enum class MediaError { TOO_LARGE, TYPE_BLOCKED, UNREADABLE, DOWNLOAD, OPEN }

/** One-shot feedback for block / report actions, shown as a snackbar. */
enum class SafetyNotice { REPORTED, REPORTED_AND_BLOCKED, REPORT_LIMIT, FAILED }

/** A chat a message can be forwarded to. */
data class ForwardTarget(val conversationId: String, val otherUid: String?, val name: String, val photoPath: String? = null)

/** Outcome of a forward: how many chats got the copy and how many did not. */
data class ForwardResult(val sent: Int, val failed: Int)

data class ComposerState(
  val text: String = "",
  val replyTo: Message? = null,
  val editing: Message? = null,
  val tooLong: Boolean = false,
  /** Groups: the "@query" being typed, for the member picker. */
  val mentionQuery: String? = null,
)

/** Result of asking the server to change the disappearing-message timer. */
sealed interface TimerChange {
  data object Idle : TimerChange
  data object Saving : TimerChange
  sealed interface Failed : TimerChange {
    data object NotAllowed : Failed
    data object Limit : Failed
    data object Other : Failed
  }
}

class ConversationViewModel(
  private val app: Application,
  private val container: AppContainer,
  private val me: String,
  private val conversationId: String,
  /** The other person in a direct chat; null for a group. */
  private val otherUid: String?,
) : ViewModel() {
  val isGroup = otherUid == null
  private val chats = container.chats!!
  private val mediaRepo = container.media!!
  private val pendingUploads = container.pendingUploads!!

  /** Attachments of this chat still being sent (also after the app was closed); see [com.samvad.messenger.data.PendingUploadManager]. */
  val uploads: StateFlow<List<UploadItem>> = pendingUploads.itemsFor(conversationId)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
  val blocked: StateFlow<Boolean> = safety?.blocked(me)?.map { otherUid != null && otherUid in it }
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

  /** The group document (name, members, admins, photo); null for direct chats. */
  val group: StateFlow<Conversation?> =
    (if (isGroup) chats.conversation(conversationId).catch { emit(null) } else flowOf(null))
      .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  private val myState: StateFlow<MemberState?> = chats.memberState(conversationId, me)
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** Mentions picked from the member list for the message being typed (uid → name). */
  private val picked = mutableMapOf<String, String>()

  init {
    // Continue attachments left over from an earlier run of the app.
    pendingUploads.resumeAll()
  }

  init {
    // Re-read on open so a changed name or photo shows up.
    if (otherUid != null) viewModelScope.launch { chats.refreshProfiles(listOf(otherUid)) }
    else viewModelScope.launch { group.collect { g -> g?.let { chats.loadProfiles(it.members) } } }
  }

  /** Groups read from my joinedAt (the rules allow nothing earlier); null until my member doc loads. */
  private val since: kotlinx.coroutines.flow.Flow<Long?> =
    if (isGroup) myState.map { it?.joinedAt }.distinctUntilChanged() else flowOf(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  private val page = combine(limit, reload, since) { n, _, from -> n to from }.flatMapLatest { (n, from) ->
    if (isGroup && from == null) flowOf(MessagesPage(emptyList(), hasMore = false, fromCache = true))
    else chats.messages(conversationId, n, from).catch { failed.value = true }
  }

  /** Re-reads "now" when the next message expires, so it leaves the screen on time. */
  private val clock = MutableStateFlow(System.currentTimeMillis())

  init {
    viewModelScope.launch {
      page.collectLatest { p ->
        val now = System.currentTimeMillis()
        val next = com.samvad.core.chat.Disappearing.nextExpiry(p.messages, now)
        if (next != null) {
          kotlinx.coroutines.delay(next - now + 50)
          clock.value = System.currentTimeMillis()
        }
      }
    }
  }

  /** The chat's disappearing-message timer in seconds (0 = off). */
  val timer: StateFlow<Long> = chats.conversation(conversationId)
    .map { it?.disappearAfterSec ?: 0L }
    .catch { emit(0L) }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

  private val _timerChange = MutableStateFlow<TimerChange>(TimerChange.Idle)
  val timerChange: StateFlow<TimerChange> = _timerChange

  fun setTimer(seconds: Long) {
    val groups = container.groups ?: return
    if (_timerChange.value == TimerChange.Saving) return
    _timerChange.value = TimerChange.Saving
    viewModelScope.launch {
      _timerChange.value = try {
        groups.setDisappearingTimer(conversationId, seconds)
        TimerChange.Idle
      } catch (e: GroupException) {
        when (e) {
          is GroupException.NotAllowed -> TimerChange.Failed.NotAllowed
          is GroupException.LimitReached -> TimerChange.Failed.Limit
          else -> TimerChange.Failed.Other
        }
      }
    }
  }

  fun dismissTimerError() { if (_timerChange.value is TimerChange.Failed) _timerChange.value = TimerChange.Idle }

  val state: StateFlow<ConversationUiState> = combine(
    page,
    if (otherUid != null) chats.memberState(conversationId, otherUid) else flowOf(null),
    myState,
    combine(chats.profiles, group) { profiles, g -> profiles to g },
    combine(failed, receipts, clock) { f, r, now -> Triple(f, r, now) },
  ) { p: MessagesPage, other, mine, (profiles, g), (err, showRead, now) ->
    val title = if (isGroup) g?.name ?: ChatList.GROUP else profiles[otherUid]?.displayName ?: ChatList.DELETED_ACCOUNT
    val names: ((String) -> String)? =
      if (isGroup) { uid -> profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT } else null
    ConversationUiState(
      loading = isGroup && g == null,
      error = err,
      offline = p.fromCache,
      title = title,
      initials = ChatList.initials(title),
      items = Timeline.build(
        p.messages, me, other, now, showReadReceipts = showRead, groupNames = names,
        otherName = if (isGroup) null else title,
      ).asReversed(),
      hasMore = p.hasMore,
      muted = mine?.muted ?: false,
      pinned = mine?.pinned ?: false,
      latestIncomingId = p.messages.lastOrNull { it.senderId != me }?.id,
      isGroup = isGroup,
      groupPhotoPath = g?.photoPath,
      memberCount = g?.members?.size ?: 0,
      isAdmin = g?.let { GroupRules.isAdmin(it, me) } ?: false,
      removed = isGroup && g != null && me !in g.members,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

  /** Groups: who is typing (names), otherwise the member names, me as "You" last. */
  val groupStatus: StateFlow<GroupStatus> = run {
    val p = presence
    if (!isGroup || p == null) return@run MutableStateFlow(GroupStatus())
    val ticker = flow { while (true) { emit(Unit); delay(1_000) } }
    combine(p.typingIn(conversationId), group, chats.profiles, chats.rawProfiles, ticker) { typing, g, profiles, raw, _ ->
      val name = { uid: String -> profiles[uid]?.displayName ?: ChatList.DELETED_ACCOUNT }
      val now = p.serverNow()
      GroupStatus(
        typing = typing.filter { (uid, at) -> uid != me && PresenceRules.isTyping(at, now) }.keys.map(name),
        members = g?.members.orEmpty().filter { it != me }.map(name).sorted(),
        // Mention text carries profile names; highlight those and this device's names for the same people.
        allNames = g?.members.orEmpty().flatMap { listOfNotNull(raw[it]?.displayName, profiles[it]?.displayName) }.distinct(),
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupStatus())
  }

  /** Groups: members matching the "@query" being typed (uid → name), at most 8. */
  val mentionCandidates: StateFlow<List<Pair<String, String>>> =
    // Profile names, not saved contact names: the inserted "@Name" is read by everyone in the group.
    combine(_composer, group, chats.rawProfiles) { c, g, profiles ->
      val query = c.mentionQuery ?: return@combine emptyList()
      g?.members.orEmpty().filter { it != me }
        .mapNotNull { uid -> profiles[uid]?.displayName?.let { uid to it } }
        .filter { (_, n) -> n.contains(query, ignoreCase = true) }
        .sortedBy { it.second }
        .take(8)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** A person's profile as named on this device, if loaded. */
  fun profile(uid: String): Profile? = chats.profiles.value[uid]

  /** A person's name in this chat: the other person, or a group member. */
  fun displayName(uid: String): String =
    chats.profiles.value[uid]?.displayName ?: if (isGroup) ChatList.DELETED_ACCOUNT else state.value.title

  fun pickMention(uid: String, name: String) {
    picked[uid] = name
    val text = Mentions.insert(_composer.value.text, name)
    _composer.update { it.copy(text = text, mentionQuery = null) }
  }

  /** Header line: typing…, online, last seen …, or nothing when hidden. Re-evaluated every second so stale typing expires. */
  val status: StateFlow<PresenceStatus> = run {
    val p = presence
    if (p == null || otherUid == null) return@run MutableStateFlow<PresenceStatus>(PresenceStatus.Unknown)
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

  fun sendImage(uri: Uri, original: Boolean = false) =
    prepareAndSend { mediaRepo.prepareImage(uri, container.preferences.preferences.first().dataSaver, original) }
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
    val caption = _composer.value.text.trim().takeIf { media.kind != MediaKind.VOICE && it.length <= MessageRules.MAX_LENGTH }.orEmpty()
    val replyTo = _composer.value.replyTo?.id
    if (caption.isNotEmpty() || replyTo != null) _composer.value = ComposerState()
    media.imageOutcome?.takeIf { it != ImageQuality.Outcome.AS_CHOSEN }?.let { _photoReduced.value = it }
    // The pending-upload queue saves the file and the client message ID first, then sends in the background.
    try {
      pendingUploads.enqueue(conversationId, me, media, caption, replyTo)
    } catch (e: java.io.IOException) {
      _mediaError.value = MediaError.UNREADABLE
    }
  }

  fun retryUpload(id: String) = pendingUploads.retry(id)

  fun cancelUpload(id: String) = pendingUploads.cancel(id)

  fun clearMediaError() { _mediaError.value = null }

  // Phase 5: weak-network media (original quality, downloads with progress, network hints).
  private val _photoReduced = MutableStateFlow<ImageQuality.Outcome?>(null)

  /** Set when a photo sent at original quality had to be made smaller to fit 16 MB; shown once. */
  val photoReduced: StateFlow<ImageQuality.Outcome?> = _photoReduced
  fun clearPhotoReduced() { _photoReduced.value = null }

  val downloads: StateFlow<Map<String, DownloadState>> = (container.downloads?.state ?: MutableStateFlow(emptyMap()))
  fun startDownload(path: String, size: Long) { container.downloads?.start(path, size) }
  fun cancelDownload(path: String) { container.downloads?.cancel(path) }

  val network: StateFlow<NetworkInfo> = container.network.state
  val dataSaverHintAnswered: StateFlow<Boolean> = container.networkHints.dataSaverHintAnswered
  val dataSaverOn: StateFlow<Boolean> = container.preferences.preferences.map { it.dataSaver }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** The one-time Data saver suggestion was answered; [turnOn] also switches Data saver on. */
  fun answerDataSaverHint(turnOn: Boolean) {
    container.networkHints.markDataSaverHintAnswered()
    if (turnOn) viewModelScope.launch { container.preferences.setDataSaver(true) }
  }

  suspend fun photoPreview(uri: Uri) = mediaRepo.loadPreview(uri)
  suspend fun photoSourceSize(uri: Uri) = mediaRepo.sourceSize(uri)
  fun reportMediaError(e: MediaError) { _mediaError.value = e }

  suspend fun localFile(path: String): File? = runCatching { mediaRepo.localFile(path) }.getOrNull()
  suspend fun bitmap(path: String, maxEdgePx: Int) = mediaRepo.loadBitmap(path, maxEdgePx)
  fun isCached(path: String) = mediaRepo.isCached(path)
  fun isMetered() = container.network.isMetered()

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
    val messages = chats.exportMessages(conversationId, ChatExport.MAX_MESSAGES, if (isGroup) myState.value?.joinedAt else null)
    val names = if (otherUid != null) mapOf(me to myName, otherUid to state.value.title)
    else chats.profiles.value.mapValues { it.value.displayName } + (me to myName)
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
    _composer.update {
      it.copy(text = text, tooLong = text.length > MessageRules.MAX_LENGTH, mentionQuery = if (isGroup) Mentions.activeQuery(text) else null)
    }
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
    val mentions = Mentions.resolve(text, picked)
    picked.clear()
    viewModelScope.launch {
      runCatching {
        if (c.editing != null) chats.edit(conversationId, c.editing.id, text)
        else chats.send(conversationId, me, text, c.replyTo?.id, mentions = mentions)
      }
    }
  }

  fun retry(message: Message) = viewModelScope.launch { runCatching { chats.retry(conversationId, me, message) } }

  fun delete(message: Message) = viewModelScope.launch { runCatching { chats.deleteForEveryone(conversationId, message.id) } }

  fun react(message: Message, emoji: String) = viewModelScope.launch {
    val current = message.reactions[me]
    runCatching { chats.react(conversationId, message.id, me, if (current == emoji) null else emoji) }
  }

  /** IDs of my starred messages in this chat (Phase 3). */
  val starredIds: StateFlow<Set<String>> = chats.starred(me)
    .map { refs -> refs.filter { it.conversationId == conversationId }.map { it.messageId }.toSet() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

  fun toggleStar(message: Message) = viewModelScope.launch {
    runCatching {
      if (message.id in starredIds.value) chats.unstar(me, conversationId, message.id)
      else chats.star(me, conversationId, message.id)
    }
  }

  /** This chat's scheduled messages still waiting to send, or that could not be sent. */
  val scheduledHere: StateFlow<List<ScheduledItem>> = chats.scheduled(me)
    .map { all -> all.filter { it.conversationId == conversationId && (it.status == "pending" || it.status == "failed") } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /**
   * Schedules the composer's text for [sendAtMs]. Returns the check result; on OK the composer is
   * cleared. The server sends it at that time (sendScheduledMessages).
   */
  fun schedule(sendAtMs: Long): ScheduleCheck {
    val c = _composer.value
    val text = (MessageRules.validate(c.text) as? TextValidation.Ok)?.text ?: return ScheduleCheck.TOO_SOON
    val check = ScheduleRules.check(sendAtMs, System.currentTimeMillis())
    if (check != ScheduleCheck.OK) return check
    _composer.value = ComposerState()
    stopTyping()
    picked.clear()
    viewModelScope.launch { runCatching { chats.schedule(me, conversationId, text, c.replyTo?.id, sendAtMs) } }
    return check
  }

  fun reschedule(item: ScheduledItem, sendAtMs: Long): ScheduleCheck {
    val check = ScheduleRules.check(sendAtMs, System.currentTimeMillis())
    if (check == ScheduleCheck.OK) viewModelScope.launch { runCatching { chats.updateScheduled(item.id, sendAtMs = sendAtMs) } }
    return check
  }

  fun editScheduled(item: ScheduledItem, text: String) {
    val valid = (MessageRules.validate(text) as? TextValidation.Ok)?.text ?: return
    viewModelScope.launch { runCatching { chats.updateScheduled(item.id, text = valid) } }
  }

  fun cancelScheduled(item: ScheduledItem) = viewModelScope.launch { runCatching { chats.cancelScheduled(item.id) } }

  fun removeScheduled(item: ScheduledItem) = viewModelScope.launch { runCatching { chats.deleteScheduled(item.id) } }

  /** Cancels the scheduled copy first, so it can never also be sent by the server, then sends now. */
  fun sendScheduledNow(item: ScheduledItem) = viewModelScope.launch {
    runCatching {
      chats.cancelScheduled(item.id)
      chats.send(conversationId, me, item.text, null)
    }
  }

  fun toggle(field: String, value: Boolean) = viewModelScope.launch { runCatching { chats.setFlag(conversationId, me, field, value) } }

  fun block() {
    val s = safety ?: return
    stopTyping()
    if (recorder.state.value.active) recorder.cancel()
    val other = otherUid ?: return
    viewModelScope.launch { runCatching { s.block(me, other) }.onFailure { _notice.value = SafetyNotice.FAILED } }
  }

  fun unblock() {
    val s = safety ?: return
    val other = otherUid ?: return
    viewModelScope.launch { runCatching { s.unblock(me, other) }.onFailure { _notice.value = SafetyNotice.FAILED } }
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

  /** Phase 2: posts a poll; false when the question or options are not valid. */
  fun sendPoll(question: String, options: List<String>, multi: Boolean): Boolean {
    val poll = Polls.create(question, options, multi) ?: return false
    viewModelScope.launch { runCatching { chats.sendPoll(conversationId, me, poll) } }
    return true
  }

  fun votes(messageId: String): kotlinx.coroutines.flow.Flow<Map<String, List<String>>> =
    chats.votes(conversationId, messageId).catch { emit(emptyMap()) }

  fun vote(message: Message, optionId: String, current: Set<String>) {
    val poll = message.poll ?: return
    val next = Polls.toggle(poll, current, optionId)
    viewModelScope.launch { runCatching { chats.vote(conversationId, message.id, me, next) } }
  }

  private val _forwardResult = MutableStateFlow<ForwardResult?>(null)
  val forwardResult: StateFlow<ForwardResult?> = _forwardResult
  fun clearForwardResult() { _forwardResult.value = null }

  /** My direct chats, most recent first, with the other person's name. */
  fun forwardTargets(): kotlinx.coroutines.flow.Flow<List<ForwardTarget>> =
    combine(chats.conversations(me).catch { }, chats.profiles) { snap, profiles ->
      snap.value.mapNotNull { c ->
        if (c.isGroup) return@mapNotNull ForwardTarget(c.id, null, c.name ?: ChatList.GROUP, c.photoPath)
        val other = c.members.firstOrNull { it != me } ?: return@mapNotNull null
        ForwardTarget(c.id, other, profiles[other]?.displayName ?: ChatList.DELETED_ACCOUNT)
      }
    }.onEach { targets -> chats.loadProfiles(targets.mapNotNull { it.otherUid }) }

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
    fun factory(app: Application, container: AppContainer, me: String, cid: String, other: String?) =
      viewModelFactory { initializer { ConversationViewModel(app, container, me, cid, other) } }
  }
}
