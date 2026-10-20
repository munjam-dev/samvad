package com.samvad.messenger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.samvad.messenger.SamvadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Receives the backend's data-only pushes and keeps this install's FCM token current. */
class SamvadMessagingService : FirebaseMessagingService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    if (data["type"] == "security" && data["event"] == "two_step_reset_requested") {
      Notifications.showSecurityAlert(this)
      return
    }
    // onMessageReceived runs on a background thread, so a short blocking read of the settings is fine.
    val prefs = (application as SamvadApplication).container.preferences
    val now = System.currentTimeMillis()
    val quiet = runCatching { runBlocking { prefs.preferences.first().quietHours } }.getOrDefault(com.samvad.core.notify.QuietHours()).isQuiet(now)
    if (data["type"] == "event_reminder") {
      val cid = data["cid"] ?: return
      Notifications.showMessage(this, cid, data["eid"].orEmpty(), data["title"].orEmpty(), data["body"].orEmpty(), showPreview = true, group = true, silent = quiet)
      return
    }
    if (data["type"] != "message") return
    val cid = data["cid"] ?: return
    val sender = data["senderId"] ?: return
    val showPreview = runCatching { runBlocking { prefs.preferences.first().notificationPreviews } }.getOrDefault(false)
    Notifications.showMessage(this, cid, sender, data["title"].orEmpty(), data["body"].orEmpty(), showPreview, group = data["group"] == "1", silent = quiet)
  }

  override fun onNewToken(token: String) {
    val container = (application as SamvadApplication).container
    val uid = container.auth?.currentUser?.uid ?: return
    scope.launch {
      runCatching { container.accounts?.updatePushToken(uid, container.preferences.deviceId(), token) }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
