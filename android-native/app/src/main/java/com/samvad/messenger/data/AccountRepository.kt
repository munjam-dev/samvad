package com.samvad.messenger.data

import android.os.Build
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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
      trySend(snap?.takeIf { it.exists() }?.let { Profile(uid, it.getString("displayName") ?: "", it.getString("about") ?: "") })
    }
    awaitClose { reg.remove() }
  }

  /** Registers this install, or refreshes lastSeenAt when it is already registered. */
  suspend fun registerDevice(uid: String, deviceId: String, fcmToken: String?) {
    val ref = db.document("users/$uid/devices/$deviceId")
    val common = mapOf(
      "appVersion" to BuildConfig.VERSION_NAME.take(32),
      "osVersion" to Build.VERSION.RELEASE.take(32),
      "lastSeenAt" to FieldValue.serverTimestamp(),
      "fcmToken" to fcmToken,
    )
    try {
      ref.update(common).await()
    } catch (e: FirebaseFirestoreException) {
      if (e.code != FirebaseFirestoreException.Code.NOT_FOUND) throw e
      ref.set(common + mapOf("platform" to "android", "createdAt" to FieldValue.serverTimestamp())).await()
    }
  }

  /** Called when FCM rotates this install's token. */
  suspend fun updatePushToken(uid: String, deviceId: String, fcmToken: String) {
    db.document("users/$uid/devices/$deviceId").update(
      mapOf("fcmToken" to fcmToken, "lastSeenAt" to FieldValue.serverTimestamp()),
    ).await()
  }

  suspend fun unregisterDevice(uid: String, deviceId: String) {
    runCatching { db.document("users/$uid/devices/$deviceId").delete().await() }
  }

  suspend fun signOutAllDevices() = call("signOutAllDevices")

  suspend fun deleteAccount() = call("deleteAccount")

  private suspend fun call(name: String) {
    try {
      functions.getHttpsCallable(name, limitedUse).call().await()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION && e.message == "cooldown" -> AccountException.Cooldown()
        e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION -> AccountException.RecentLoginRequired()
        else -> AccountException.Failed(e)
      }
    }
  }
}
