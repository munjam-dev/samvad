package com.samvad.messenger.data

import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await

sealed class AccountException(message: String) : Exception(message) {
  class Cooldown : AccountException("cooldown")
  class RecentLoginRequired : AccountException("recent-login-required")
  /** Two-step PIN wrong or locked (account deletion with two-step on). */
  class WrongPin : AccountException("wrong-pin")
  class Failed(cause: Throwable) : AccountException(cause.message ?: "failed")
}

sealed interface UsernameCheck {
  data object Available : UsernameCheck
  /** [reason] is the server's code ("taken", "reserved", ...), or null. */
  data class Unavailable(val reason: String?) : UsernameCheck
  data object TooManyChecks : UsernameCheck
  /** Offline or a server error: the name is not known to be free. */
  data object Unknown : UsernameCheck
}

enum class UsernameResult { Saved, Taken, Invalid, TooManyChanges, Failed }

/** users/{uid}, profiles/{uid}, devices and the account callables (B1). */
class AccountRepository(
  private val db: FirebaseFirestore,
  private val functions: FirebaseFunctions,
) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun hasAccount(uid: String): Boolean = db.document("users/$uid").get().await().exists()

  /**
   * Creates users/{uid} and profiles/{uid} in one batch, as the client contract requires.
   * "About" goes to profiles/{uid}/private/about (Phase 3): it has its own visibility setting, so it
   * cannot live on the public profile document that every signed-in person may read.
   */
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
    batch.set(db.document("profiles/$uid"), mapOf("displayName" to displayName, "updatedAt" to FieldValue.serverTimestamp()))
    batch.set(db.document("profiles/$uid/private/about"), mapOf("about" to about, "updatedAt" to FieldValue.serverTimestamp()))
    batch.commit().await()
  }

  suspend fun updateProfile(uid: String, displayName: String, about: String) {
    val batch = db.batch()
    batch.update(db.document("profiles/$uid"), mapOf("displayName" to displayName, "updatedAt" to FieldValue.serverTimestamp()))
    batch.set(db.document("profiles/$uid/private/about"), mapOf("about" to about, "updatedAt" to FieldValue.serverTimestamp()))
    batch.commit().await()
  }

  /**
   * The signed-in person's own profile, live. "About" and the photo path live in sibling documents
   * (profiles/{uid}/private/about, /photo) that only the owner, or the people the owner's privacy
   * setting allows, can read; a denied listener simply reports null for that part, same as absent.
   */
  fun profile(uid: String): Flow<Profile?> {
    val base = callbackFlow<Map<String, Any?>?> {
      val reg = db.document("profiles/$uid").addSnapshotListener { snap, _ -> trySend(snap?.takeIf { it.exists() }?.data) }
      awaitClose { reg.remove() }
    }
    val about = callbackFlow<String?> {
      val reg = db.document("profiles/$uid/private/about").addSnapshotListener { snap, _ -> trySend(snap?.getString("about")) }
      awaitClose { reg.remove() }
    }
    val photo = callbackFlow<String?> {
      val reg = db.document("profiles/$uid/private/photo").addSnapshotListener { snap, _ -> trySend(snap?.getString("photoUrl")) }
      awaitClose { reg.remove() }
    }
    return combine(base, about, photo) { b, a, p ->
      b?.let { Profile(uid, it["displayName"] as? String ?: "", a.orEmpty(), p, it["username"] as? String) }
    }
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

  /**
   * Tells the server whether this person wants new-moment pushes, so it does not send them at all. The phone also drops
   * them when the switch is off (Preferences), so a failed write here only costs a push that is then ignored.
   */
  suspend fun setMomentAlerts(uid: String, enabled: Boolean) {
    db.document("users/$uid/settings/notifications").set(mapOf("momentAlerts" to enabled)).await()
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

  /** Whether [name] can be taken right now (the server's answer, so a name someone just took shows as taken). */
  suspend fun checkUsername(name: String): UsernameCheck = try {
    val r = functions.getHttpsCallable("checkUsername", limitedUse).call(mapOf("username" to name)).await().data as Map<*, *>
    if (r["available"] == true) UsernameCheck.Available else UsernameCheck.Unavailable(r["reason"] as? String)
  } catch (e: FirebaseFunctionsException) {
    Log.w("Username", "checkUsername failed: ${e.code}")
    if (e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) UsernameCheck.TooManyChecks else UsernameCheck.Unknown
  } catch (e: Exception) {
    Log.w("Username", "checkUsername failed: ${e.javaClass.simpleName}")
    UsernameCheck.Unknown
  }

  /** Claims [name], or with null removes the username. Returns the server's reason when it is refused. */
  suspend fun setUsername(name: String?): UsernameResult = try {
    functions.getHttpsCallable("setUsername", limitedUse).call(mapOf("username" to name)).await()
    UsernameResult.Saved
  } catch (e: FirebaseFunctionsException) {
    when {
      e.code == FirebaseFunctionsException.Code.ALREADY_EXISTS -> UsernameResult.Taken
      e.code == FirebaseFunctionsException.Code.INVALID_ARGUMENT -> UsernameResult.Invalid
      e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> UsernameResult.TooManyChanges
      else -> UsernameResult.Failed
    }
  } catch (e: Exception) {
    UsernameResult.Failed
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
