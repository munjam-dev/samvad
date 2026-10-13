package com.samvad.messenger.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.samvad.messenger.SamvadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives the backend's data-only pushes and keeps this install's FCM token current. */
class SamvadMessagingService : FirebaseMessagingService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    if (data["type"] != "message") return
    val cid = data["cid"] ?: return
    val sender = data["senderId"] ?: return
    Notifications.showMessage(this, cid, sender, data["title"].orEmpty(), data["body"].orEmpty())
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
