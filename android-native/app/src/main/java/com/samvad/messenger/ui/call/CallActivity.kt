package com.samvad.messenger.ui.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallStatus
import com.samvad.messenger.AppContainer
import com.samvad.messenger.SamvadApplication
import com.samvad.messenger.push.CallNotifications
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.delay

/** What this screen was opened for. */
sealed interface CallLaunch {
  data class Outgoing(val conversationId: String, val otherUid: String, val name: String, val kind: CallKind) : CallLaunch
  data class Incoming(val callId: String, val callerUid: String, val name: String, val kind: CallKind, val accept: Boolean) : CallLaunch
  data class Meeting(val meetingId: String, val title: String, val kind: CallKind, val host: Boolean) : CallLaunch
  data object Resume : CallLaunch
}

/**
 * The call screen: ringing, in-call and meeting. Its own activity so it can show over the lock screen for an
 * incoming call without touching the app lock or the rest of the app's navigation.
 */
class CallActivity : ComponentActivity() {
  private val launch = mutableStateOf<CallLaunch>(CallLaunch.Resume)

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(com.samvad.messenger.ui.language.AppLanguage.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    launch.value = parse(intent)
    val container = (application as SamvadApplication).container
    setContent {
      val prefs = container.preferences.preferences.collectAsStateWithLifecycle(com.samvad.messenger.data.AppPreferences()).value
      SamvadTheme(mode = prefs.themeMode) {
        CallRoot(container, launch.value, onClose = { finishAndRemoveTask() })
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    launch.value = parse(intent)
  }

  /** The activity is not exported, but extras are still checked for shape. */
  private fun parse(i: Intent): CallLaunch {
    val kind = CallKind.parse(i.getStringExtra(EXTRA_KIND))
    return when (i.action) {
      ACTION_OUTGOING -> CallLaunch.Outgoing(
        i.getStringExtra(EXTRA_CID) ?: return CallLaunch.Resume,
        i.getStringExtra(EXTRA_UID) ?: return CallLaunch.Resume,
        i.getStringExtra(EXTRA_NAME).orEmpty(), kind,
      )
      ACTION_INCOMING -> CallLaunch.Incoming(
        i.getStringExtra(EXTRA_CALL_ID) ?: return CallLaunch.Resume,
        i.getStringExtra(EXTRA_UID).orEmpty(), i.getStringExtra(EXTRA_NAME).orEmpty(), kind, i.getBooleanExtra(EXTRA_ACCEPT, false),
      )
      ACTION_MEETING -> CallLaunch.Meeting(
        i.getStringExtra(EXTRA_MEETING_ID) ?: return CallLaunch.Resume,
        i.getStringExtra(EXTRA_NAME).orEmpty(), kind, i.getBooleanExtra(EXTRA_HOST, false),
      )
      else -> CallLaunch.Resume
    }
  }

  companion object {
    private const val ACTION_OUTGOING = "samvad.call.OUTGOING"
    private const val ACTION_INCOMING = "samvad.call.INCOMING"
    private const val ACTION_MEETING = "samvad.call.MEETING"
    private const val ACTION_RESUME = "samvad.call.RESUME"
    private const val EXTRA_CID = "cid"
    private const val EXTRA_UID = "uid"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_CALL_ID = "callId"
    private const val EXTRA_MEETING_ID = "meetingId"
    private const val EXTRA_ACCEPT = "accept"
    private const val EXTRA_HOST = "host"

    private fun base(c: Context, action: String) =
      Intent(c, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun resumeIntent(c: Context) = base(c, ACTION_RESUME)

    fun outgoingIntent(c: Context, cid: String, otherUid: String, name: String, kind: CallKind) =
      base(c, ACTION_OUTGOING).putExtra(EXTRA_CID, cid).putExtra(EXTRA_UID, otherUid).putExtra(EXTRA_NAME, name).putExtra(EXTRA_KIND, kindName(kind))

    fun incomingIntent(c: Context, callId: String, callerUid: String, name: String, kind: CallKind, accept: Boolean = false) =
      base(c, ACTION_INCOMING).putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_UID, callerUid).putExtra(EXTRA_NAME, name)
        .putExtra(EXTRA_KIND, kindName(kind)).putExtra(EXTRA_ACCEPT, accept)

    fun meetingIntent(c: Context, meetingId: String, title: String, kind: CallKind, host: Boolean) =
      base(c, ACTION_MEETING).putExtra(EXTRA_MEETING_ID, meetingId).putExtra(EXTRA_NAME, title).putExtra(EXTRA_KIND, kindName(kind)).putExtra(EXTRA_HOST, host)

    private fun kindName(k: CallKind) = if (k == CallKind.VIDEO) "video" else "audio"

    // Entry points for the rest of the app.
    fun startCall(c: Context, cid: String, otherUid: String, name: String, kind: CallKind) = c.startActivity(outgoingIntent(c, cid, otherUid, name, kind))
    fun joinMeeting(c: Context, meetingId: String, title: String, kind: CallKind, host: Boolean) = c.startActivity(meetingIntent(c, meetingId, title, kind, host))
  }
}

@Composable
private fun CallRoot(container: AppContainer, launch: CallLaunch, onClose: () -> Unit) {
  val engine = container.callEngine
  val ui = engine.ui.collectAsStateWithLifecycle().value

  // A call in progress always wins the screen (for example after tapping the ongoing notification).
  if (ui.phase != Phase.IDLE) {
    if (ui.phase == Phase.ENDED) {
      val reason = ui.reason ?: EndReason.HANGUP
      LaunchedEffect(Unit) {
        if (reason == EndReason.HANGUP || reason == EndReason.REMOTE_ENDED) { delay(1200); engine.reset(); onClose() }
      }
      CallEndedScreen(reason, ui.title, onClose = { engine.reset(); onClose() })
    } else {
      InCallScreen(
        ui, engine.room, ui.otherUid ?: ui.title,
        CallActions(engine::toggleMic, engine::toggleCamera, engine::flipCamera, engine::toggleSpeaker, { engine.hangUp() }, engine::endMeetingForAll),
      )
    }
    return
  }

  when (launch) {
    is CallLaunch.Outgoing -> PermissionGate(video = launch.kind == CallKind.VIDEO, onCancel = onClose) {
      LaunchedEffect(launch) { engine.startOutgoing(launch.conversationId, launch.otherUid, launch.name, launch.kind) }
    }
    is CallLaunch.Meeting -> PermissionGate(video = launch.kind == CallKind.VIDEO, onCancel = onClose) {
      LaunchedEffect(launch) { engine.joinMeeting(launch.meetingId, launch.title, launch.kind, launch.host) }
    }
    is CallLaunch.Incoming -> IncomingFlow(container, launch, onClose)
    CallLaunch.Resume -> LaunchedEffect(Unit) { onClose() }
  }
}

@Composable
private fun IncomingFlow(container: AppContainer, launch: CallLaunch.Incoming, onClose: () -> Unit) {
  val engine = container.callEngine
  val context = LocalContext.current
  var accepted by remember(launch.callId) { mutableStateOf(launch.accept) }
  val name = container.chats?.profiles?.collectAsStateWithLifecycle()?.value?.get(launch.callerUid)?.displayName ?: launch.name

  // The ring is over (caller hung up, answered elsewhere, timed out): close quietly.
  LaunchedEffect(launch.callId) {
    container.calls?.call(launch.callId)?.collect { rec ->
      if (rec != null && rec.status != CallStatus.RINGING && !accepted) {
        CallNotifications.cancelIncoming(context, launch.callId)
        onClose()
      }
    }
  }
  if (!accepted) {
    IncomingCallScreen(
      name, launch.callerUid.ifBlank { name }, launch.kind,
      onAccept = { accepted = true },
      onDecline = {
        CallNotifications.cancelIncoming(context, launch.callId)
        engine.decline(launch.callId)
        onClose()
      },
    )
  } else {
    PermissionGate(video = launch.kind == CallKind.VIDEO, onCancel = {
      CallNotifications.cancelIncoming(context, launch.callId)
      engine.decline(launch.callId)
      onClose()
    }) {
      LaunchedEffect(launch.callId) {
        CallNotifications.cancelIncoming(context, launch.callId)
        engine.acceptIncoming(launch.callId, name, launch.callerUid, launch.kind)
      }
    }
  }
}

/** Asks for the microphone (and camera for video) only now, after saying why. Runs [granted] once allowed. */
@Composable
private fun PermissionGate(video: Boolean, onCancel: () -> Unit, granted: @Composable () -> Unit) {
  val context = LocalContext.current
  val needed = remember(video) { if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO) }
  fun allowed() = needed.all { CallService.granted(context, it) }
  var ok by remember { mutableStateOf(allowed()) }
  var blocked by remember { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    ok = result.values.all { it }
    // Denied without a system dialog next time means the person has to use Settings.
    blocked = !ok
  }
  if (ok) {
    granted()
  } else {
    PermissionRationale(
      video, blocked,
      onContinue = { launcher.launch(needed) },
      onOpenSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
      },
      onCancel = onCancel,
    )
  }
  // Coming back from Settings with the permission on.
  androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (allowed()) ok = true }
}
