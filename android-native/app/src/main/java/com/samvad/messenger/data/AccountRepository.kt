package com.samvad.messenger.data

import android.os.Build
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.chat.Profile
import com.samvad.messenger.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class AccountException(message: String) : Exception(message) {
  class Cooldown : AccountException("cooldown")
  class RecentLoginRequired : AccountException("recent-login-required")
  /** Two-step PIN wrong or locked (account deletion with two-step on). */
  class WrongPin : AccountException("wrong-pin")
  class Failed(cause: Throwable) : AccountException(cause.message ?: "failed")
}

/** users/{uid}, profiles/{uid}, devices and the account callables (B1). */
class AccountRepository(
  private val db: FirebaseFirestore,
  private val functions: FirebaseFunctions,
) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun hasAccount(uid: String): Boolean = db.document("users/$uid").get().await().exists()

  /** Creates users/{uid} and profiles/{uid} in one batch, as the client contract requires. */
  suspend fun createAccount(uid: String, phoneE164: String, displayName: String, about: String) {
    val batch = db.batch()
    batch.set(
      db.document("users/$uid"),
      mapOf(
        "phoneE164" to phoneE164,
        "consent" to mapOf("version" to BuildConfig.CONSENT_VERSION, "acceptedAt" to FieldValue.serverTimestamp()),
        "createdAt" to FieldValue.serverTimestamp(),
      ),
    )
    batch.set(db.document("profiles/$uid"), profileFields(displayName, about))
    batch.commit().await()
  }

  suspend fun updateProfile(uid: String, displayName: String, about: String) {
    db.document("profiles/$uid").update(
      mapOf("displayName" to displayName, "about" to about, "updatedAt" to FieldValue.serverTimestamp()),
    ).await()
  }

  private fun profileFields(displayName: String, about: String) = mapOf(
    "displayName" to displayName,
    "about" to about,
    "photoUrl" to null,
    "updatedAt" to FieldValue.serverTimestamp(),
  )

  fun profile(uid: String): Flow<Profile?> = callbackFlow {
    val reg = db.document("profiles/$uid").addSnapshotListener { snap, _ ->
      trySend(
        snap?.takeIf { it.exists() }?.let {
          Profile(uid, it.getString("displayName") ?: "", it.getString("about") ?: "", it.getString("photoUrl"))
        },
      )
    }
    awaitClose { reg.remove() }
  }

  /**
   * Registers this install, or refreshes lastSeenAt when it is already registered. Reads first:
   * an update() of a missing doc is rejected by the rules as PERMISSION_DENIED, never NOT_FOUND.
   */
  suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String?) {
    val ref = db.document("users/$uid/devices/$deviceId")
    val common = mapOf(
      "appVersion" to BuildConfig.VERSION_NAME.take(32),
      "osVersion" to Build.VERSION.RELEASE.take(32),
      "lastSeenAt" to FieldValue.serverTimestamp(),
      "fcmToken" to fcmToken,
    )
    db.runTransaction { tx ->
      if (tx.get(ref).exists()) tx.update(ref, common)
      else tx.set(ref, common + mapOf("platform" to "android", "createdAt" to FieldValue.serverTimestamp()))
    }.await()
  }

  /** Called when FCM rotates this install's token; may run before the first registration. */
  suspend fun updatePushToken(uid: String, deviceId: String, fcmToken: String) =
    registerDevice(uid, deviceId, fcmToken)

  suspend fun unregisterDevice(uid: String, deviceId: String) {
    runCatching { db.document("users/$uid/devices/$deviceId").delete().await() }
  }

  suspend fun signOutAllDevices() = call("signOutAllDevices")

  /**
   * Sets (or with null removes) the profile photo. The setProfilePhoto function validates the JPEG,
   * strips its metadata and stores it; clients cannot write photos directly.
   */
  suspend fun setProfilePhoto(jpeg: ByteArray?) {
    try {
      val encoded: Any? = jpeg?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
      functions.getHttpsCallable("setProfilePhoto", limitedUse).call(mapOf("jpegBase64" to encoded)).await()
    } catch (e: Exception) {
      throw AccountException.Failed(e)
    }
  }

  /** With two-step on, [pin] replaces the fresh SMS sign-in the server otherwise requires. */
  suspend fun deleteAccount(pin: String? = null) = call("deleteAccount", pin?.let { mapOf("pin" to it) })

  private suspend fun call(name: String, data: Map<String, Any>? = null) {
    try {
      val callable = functions.getHttpsCallable(name, limitedUse)
      if (data == null) callable.call().await() else callable.call(data).await()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.message == "wrong-pin" || e.message == "pin-locked" -> AccountException.WrongPin()
        e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION && e.message == "cooldown" -> AccountException.Cooldown()
        e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION -> AccountException.RecentLoginRequired()
        else -> AccountException.Failed(e)
      }
    }
  }
}
