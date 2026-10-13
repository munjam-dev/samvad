package com.samvad.messenger.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * FCM token access. Disabled for emulator builds (the demo project has no FCM) and when the
 * build has no Firebase configuration; callers then simply register no token.
 */
class PushTokens(private val enabled: Boolean) {
  suspend fun currentToken(): String? =
    if (!enabled) null else runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()

  suspend fun forgetToken() {
    if (enabled) runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
  }
}
