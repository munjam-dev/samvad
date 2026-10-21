package com.samvad.messenger.ui.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.samvad.messenger.SamvadApplication
import com.samvad.messenger.push.CallNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The Decline button on the ringing notification and the Hang up button on the ongoing one. */
class CallActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val container = (context.applicationContext as SamvadApplication).container
    when (intent.action) {
      ACTION_DECLINE -> {
        val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        CallNotifications.cancelIncoming(context, id)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
          try { runCatching { container.calls?.decline(id) } } finally { pending.finish() }
        }
      }
      ACTION_HANG_UP -> container.callEngine.hangUp()
    }
  }

  companion object {
    const val ACTION_DECLINE = "samvad.call.DECLINE"
    const val ACTION_HANG_UP = "samvad.call.HANG_UP"
    const val EXTRA_CALL_ID = "callId"
  }
}
