package com.samvad.messenger.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.privacy.LinkedDevice
import com.samvad.core.privacy.LinkedDevices
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Phase 3 linked devices. Firebase cannot revoke one install's refresh token, so "sign out this
 * device" works in three steps: the revokeDevice callable deletes that device's document and writes
 * a server-only marker (users/{uid}/revokedDevices/{id}), then sends that device a data message. The
 * app signs itself out when it receives that message, or on its next start if its own device document
 * is gone or a marker for it exists (checked by [isThisDeviceRevoked], called before registering).
 */
class DevicesRepository(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  fun devices(uid: String, thisDeviceId: String): Flow<List<LinkedDevice>> = callbackFlow {
    val reg = db.collection("users/$uid/devices").addSnapshotListener { snap, _ ->
      val list = snap?.documents.orEmpty().mapNotNull { d ->
        val platform = d.getString("platform") ?: return@mapNotNull null
        LinkedDevice(
          id = d.id,
          platform = platform,
          appVersion = d.getString("appVersion") ?: "",
          osVersion = d.getString("osVersion") ?: "",
          createdAtMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
          lastActiveMs = d.getTimestamp("lastSeenAt")?.toDate()?.time ?: 0L,
          isThisDevice = d.id == thisDeviceId,
        )
      }
      trySend(LinkedDevices.sorted(list))
    }
    awaitClose { reg.remove() }
  }

  /** Whether this install was signed out remotely; checked once at startup, before registering the device. */
  suspend fun isThisDeviceRevoked(uid: String, deviceId: String): Boolean = runCatching {
    val device = db.document("users/$uid/devices/$deviceId").get().await()
    val marker = db.document("users/$uid/revokedDevices/$deviceId").get().await()
    !device.exists() && marker.exists()
  }.getOrDefault(false)

  /** Signs out [deviceId], which must not be this device; the server tells it to sign itself out. */
  suspend fun revokeDevice(deviceId: String) {
    functions.getHttpsCallable("revokeDevice", limitedUse).call(mapOf("deviceId" to deviceId)).await()
  }
}
