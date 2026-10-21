package com.samvad.messenger.ui.call

import android.content.Context
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallStatus
import com.samvad.core.call.LinkQuality
import com.samvad.messenger.data.CallException
import com.samvad.messenger.data.CallRepository
import com.samvad.messenger.data.JoinInfo
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { IDLE, STARTING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED }

/** Why a call screen ended; the UI turns each into a plain sentence. */
enum class EndReason { HANGUP, DECLINED, NO_ANSWER, BUSY, NOT_CONFIGURED, NOT_ALLOWED, LIMIT, REMOTE_ENDED, CONNECTION_LOST, MEETING_FULL, MEETING_NOT_OPEN, MEETING_ENDED, FAILED }

/** One person on screen. [track] is null when their camera is off. */
data class Tile(val id: String, val name: String, val speaking: Boolean, val micOn: Boolean, val track: VideoTrack?, val local: Boolean)

data class CallUi(
  val phase: Phase = Phase.IDLE,
  val reason: EndReason? = null,
  val kind: CallKind = CallKind.AUDIO,
  val title: String = "",
  val otherUid: String? = null,
  val isMeeting: Boolean = false,
  val incoming: Boolean = false,
  val isHost: Boolean = false,
  val micOn: Boolean = true,
  val camOn: Boolean = false,
  val speakerOn: Boolean = false,
  val tiles: List<Tile> = emptyList(),
  val quality: LinkQuality = LinkQuality.GOOD,
  val answeredAtMs: Long? = null,
) {
  val active: Boolean get() = phase != Phase.IDLE && phase != Phase.ENDED
}

/**
 * Owns the one call or meeting this device is in. Signalling (ringing, answering) is Firestore + callables;
 * media is the LiveKit room. It lives in [com.samvad.messenger.AppContainer] so the call survives the
 * screen being closed; [CallService] keeps the process alive and shows the ongoing notification.
 *
 * Calls are encrypted in transit to the LiveKit server only. They are NOT end-to-end encrypted.
 */
class CallEngine(private val app: Context, private val repo: CallRepository?) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _ui = MutableStateFlow(CallUi())
  val ui: StateFlow<CallUi> = _ui.asStateFlow()

  /** The room, for the video views (null outside a call). */
  @Volatile var room: Room? = null
    private set

  private var audio: AudioSwitchHandler? = null
  private var callId: String? = null
  private var meetingId: String? = null
  private var docStatus: CallStatus? = null
  private var roomUp = false
  private var reconnecting = false
  private var jobs = mutableListOf<Job>()
  private var ending = false

  fun reset() { if (_ui.value.phase == Phase.ENDED) _ui.value = CallUi() }

  // ---- starting --------------------------------------------------------------------------------

  fun startOutgoing(conversationId: String, otherUid: String, title: String, kind: CallKind) {
    if (!begin(CallUi(Phase.STARTING, kind = kind, title = title, otherUid = otherUid, camOn = kind == CallKind.VIDEO, speakerOn = kind == CallKind.VIDEO))) return
    launch {
      val api = repo ?: return@launch finish(EndReason.NOT_CONFIGURED)
      val started = api.start(conversationId, kind)
      callId = started.callId
      watchCall(started.callId, outgoing = true)
      docStatus = CallStatus.RINGING
      publish()
      join(api.token(started.callId), kind)
    }
  }

  fun acceptIncoming(id: String, title: String, otherUid: String?, kind: CallKind) {
    if (!begin(CallUi(Phase.CONNECTING, kind = kind, title = title, otherUid = otherUid, incoming = true, camOn = kind == CallKind.VIDEO, speakerOn = kind == CallKind.VIDEO))) return
    callId = id
    launch {
      val api = repo ?: return@launch finish(EndReason.NOT_CONFIGURED)
      api.answer(id)
      watchCall(id, outgoing = false)
      join(api.token(id), kind)
    }
  }

  fun joinMeeting(id: String, title: String, kind: CallKind, host: Boolean) {
    if (!begin(CallUi(Phase.CONNECTING, kind = kind, title = title, isMeeting = true, isHost = host, camOn = kind == CallKind.VIDEO, speakerOn = true))) return
    meetingId = id
    launch {
      val api = repo ?: return@launch finish(EndReason.NOT_CONFIGURED)
      join(api.meetingToken(id), kind)
      jobs += scope.launch {
        api.meeting(id).collect { m -> if (m != null && m.ended) finish(EndReason.MEETING_ENDED) }
      }
    }
  }

  /** True when this device can take another call. */
  private fun begin(initial: CallUi): Boolean {
    if (_ui.value.active) return false
    cleanup()
    ending = false
    docStatus = null
    roomUp = false
    reconnecting = false
    _ui.value = initial
    return true
  }

  private fun launch(block: suspend () -> Unit) {
    jobs += scope.launch {
      try {
        block()
      } catch (e: CallException) {
        finish(
          when (e) {
            is CallException.NotConfigured -> EndReason.NOT_CONFIGURED
            is CallException.CalleeBusy, is CallException.AlreadyInCall -> EndReason.BUSY
            is CallException.NotAllowed -> EndReason.NOT_ALLOWED
            is CallException.LimitReached -> EndReason.LIMIT
            is CallException.Ended -> EndReason.REMOTE_ENDED
            is CallException.MeetingFull -> EndReason.MEETING_FULL
            is CallException.MeetingNotOpen -> EndReason.MEETING_NOT_OPEN
            is CallException.MeetingEnded -> EndReason.MEETING_ENDED
            is CallException.Failed -> EndReason.FAILED
          },
        )
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (_: Exception) {
        finish(EndReason.FAILED)
      }
    }
  }

  private fun watchCall(id: String, outgoing: Boolean) {
    val api = repo ?: return
    jobs += scope.launch {
      api.call(id).collect { rec ->
        if (rec == null) return@collect
        docStatus = rec.status
        if (rec.status == CallStatus.ACTIVE) _ui.update { it.copy(answeredAtMs = rec.answeredAtMs) }
        when (rec.status) {
          CallStatus.DECLINED -> finish(EndReason.DECLINED)
          CallStatus.MISSED -> finish(if (outgoing) EndReason.NO_ANSWER else EndReason.REMOTE_ENDED)
          CallStatus.ENDED -> finish(EndReason.REMOTE_ENDED)
          else -> publish()
        }
      }
    }
    if (outgoing) {
      // The server sweep runs once a minute; the caller stops ringing at the ring limit on their own.
      jobs += scope.launch {
        delay(com.samvad.core.call.CallRules.RING_MS + 1_000)
        if (docStatus == CallStatus.RINGING) hangUp(EndReason.NO_ANSWER)
      }
    }
  }

  private suspend fun join(info: JoinInfo, kind: CallKind) {
    val handler = AudioSwitchHandler(app)
    audio = handler
    val r = LiveKit.create(app, overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = handler)))
    room = r
    jobs += scope.launch { r.events.collect { onRoomEvent(it) } }
    r.connect(info.url, info.token)
    roomUp = true
    r.localParticipant.setMicrophoneEnabled(true)
    if (kind == CallKind.VIDEO) r.localParticipant.setCameraEnabled(true)
    setSpeaker(_ui.value.speakerOn)
    CallService.start(app, _ui.value.title, kind == CallKind.VIDEO)
    publish()
  }

  // ---- room events -----------------------------------------------------------------------------

  private fun onRoomEvent(e: RoomEvent) {
    when (e) {
      is RoomEvent.Reconnecting -> { reconnecting = true }
      is RoomEvent.Reconnected -> { reconnecting = false }
      is RoomEvent.ConnectionQualityChanged ->
        if (e.participant == room?.localParticipant) _ui.update { it.copy(quality = LinkQuality.fromName(e.quality.name)) }
      is RoomEvent.Disconnected -> {
        if (!ending) finish(if (e.error != null) EndReason.CONNECTION_LOST else EndReason.REMOTE_ENDED)
        return
      }
      is RoomEvent.FailedToConnect -> { finish(EndReason.CONNECTION_LOST); return }
      is RoomEvent.ParticipantDisconnected -> {
        // 1:1: when the other person drops without hanging up, give them a few seconds to come back.
        if (!_ui.value.isMeeting && docStatus == CallStatus.ACTIVE) {
          jobs += scope.launch {
            delay(8_000)
            if (room?.remoteParticipants?.isEmpty() == true) hangUp(EndReason.REMOTE_ENDED)
          }
        }
      }
      else -> Unit
    }
    publish()
  }

  /** Recomputes the screen state from the signalling doc and the room. */
  private fun publish() {
    val r = room
    val local = r?.localParticipant
    val tiles = buildList {
      if (local != null) add(tile(local, localTile = true))
      r?.remoteParticipants?.values?.forEach { add(tile(it, localTile = false)) }
    }
    val phase = when {
      ending || _ui.value.phase == Phase.ENDED -> Phase.ENDED
      !_ui.value.isMeeting && docStatus == CallStatus.RINGING && !_ui.value.incoming -> Phase.RINGING
      reconnecting -> Phase.RECONNECTING
      roomUp -> Phase.CONNECTED
      else -> if (docStatus == null && !_ui.value.incoming && !_ui.value.isMeeting) Phase.STARTING else Phase.CONNECTING
    }
    _ui.update {
      it.copy(
        phase = phase,
        tiles = tiles,
        micOn = local?.isMicrophoneEnabled ?: it.micOn,
        camOn = local?.isCameraEnabled ?: it.camOn,
      )
    }
  }

  private fun tile(p: Participant, localTile: Boolean): Tile {
    val pub = p.getTrackPublication(Track.Source.CAMERA)
    val video = if (pub != null && !pub.muted) pub.track as? VideoTrack else null
    return Tile(p.identity?.value ?: "", p.name?.takeIf { it.isNotBlank() } ?: "", p.isSpeaking, p.isMicrophoneEnabled, video, localTile)
  }

  // ---- controls --------------------------------------------------------------------------------

  fun toggleMic() { val lp = room?.localParticipant ?: return; scope.launch { runCatching { lp.setMicrophoneEnabled(!lp.isMicrophoneEnabled) }; publish() } }

  fun toggleCamera() { val lp = room?.localParticipant ?: return; scope.launch { runCatching { lp.setCameraEnabled(!lp.isCameraEnabled) }; publish() } }

  fun flipCamera() {
    val track = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
    runCatching { track.switchCamera() }
  }

  fun toggleSpeaker() = setSpeaker(!_ui.value.speakerOn)

  private fun setSpeaker(on: Boolean) {
    val h = audio
    if (h != null) {
      val wanted = h.availableAudioDevices.firstOrNull { if (on) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece || it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
      if (wanted != null) runCatching { h.selectDevice(wanted) }
    }
    _ui.update { it.copy(speakerOn = on) }
  }

  /** Declines a ringing call without ever joining the room. */
  fun decline(id: String) {
    scope.launch { runCatching { repo?.decline(id) } }
  }

  /** Hang up (or cancel a ring). A meeting is only left, not ended. */
  fun hangUp(reason: EndReason = EndReason.HANGUP) {
    val id = callId
    val api = repo
    if (id != null && api != null && !ending) scope.launch { runCatching { api.end(id) } }
    finish(reason)
  }

  /** The host ends the meeting for everyone. */
  fun endMeetingForAll() {
    val id = meetingId
    val api = repo
    if (id != null && api != null) scope.launch { runCatching { api.endMeeting(id) } }
    finish(EndReason.HANGUP)
  }

  private fun finish(reason: EndReason) {
    if (ending) return
    ending = true
    cleanup()
    _ui.update { it.copy(phase = Phase.ENDED, reason = reason, tiles = emptyList()) }
  }

  private fun cleanup() {
    jobs.forEach { it.cancel() }
    jobs = mutableListOf()
    runCatching { room?.disconnect(); room?.release() }
    room = null
    audio = null
    callId = null
    meetingId = null
    CallService.stop(app)
  }
}
