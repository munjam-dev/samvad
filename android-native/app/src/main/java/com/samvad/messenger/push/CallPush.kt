package com.samvad.messenger.push

import android.content.Context
import com.samvad.core.call.CallKind
import com.samvad.messenger.SamvadApplication

/** Routes the call pushes (no message content: only who is calling and whether it is video). */
object CallPush {
  /** True when [data] was a call push and has been handled. */
  fun handle(context: Context, data: Map<String, String>): Boolean {
    val type = data["type"]
    if (type != "incoming_call" && type != "missed_call" && type != "call_cancel") return false
    val callId = data["callId"] ?: return true
    if (callId.length > 64) return true
    if (type == "call_cancel") {
      // Answered or declined on another device: stop this one ringing. Leaves no missed-call line.
      CallNotifications.cancelIncoming(context, callId)
      return true
    }
    val container = (context.applicationContext as SamvadApplication).container
    // Only a signed-in device rings.
    val uid = container.auth?.currentUser?.uid ?: return true
    val kind = CallKind.parse(data["kind"])
    val callerUid = data["callerUid"].orEmpty()
    val name = (container.chats?.profiles?.value?.get(callerUid)?.displayName ?: data["callerName"]).orEmpty().take(64).ifBlank { "Samvad" }
    if (type == "incoming_call") {
      // Already in a call: the server blocks this, but a stale ring must not interrupt the call in progress.
      if (container.callEngine.ui.value.active || uid.isBlank()) return true
      CallNotifications.showIncoming(context, callId, callerUid, name, kind)
    } else {
      CallNotifications.showMissed(context, callId, callerUid, name, kind)
    }
    return true
  }
}
