package com.samvad.messenger.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.FirebaseFunctionsException.Code
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.security.TwoStepPin
import kotlinx.coroutines.tasks.await

data class TwoStepStatus(
  val enabled: Boolean,
  val verified: Boolean,
  val lockedUntilMs: Long?,
  val resetAvailableAtMs: Long?,
)

sealed class TwoStepException(message: String) : Exception(message) {
  class WrongPin(val attemptsLeft: Int, val lockedUntilMs: Long?) : TwoStepException("wrong-pin")
  class Locked(val untilMs: Long) : TwoStepException("pin-locked")
  class TooSimple : TwoStepException("pin-too-simple")
  class ResetNotReady(val availableAtMs: Long?) : TwoStepException("reset-not-ready")
  class ResetCancelled : TwoStepException("reset-cancelled")
  class Failed(cause: Throwable) : TwoStepException("failed") {
    init { initCause(cause) }
  }
}

/**
 * Two-step verification (Phase 1.5). The server enforces it; this class calls the callables and
 * signs in with the PIN-session custom token they return.
 */
class TwoStepRepository(private val auth: FirebaseAuth, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  /** Whether the account has two-step on and whether this session has passed it, from the ID token. */
  data class Session(val enabled: Boolean, val verified: Boolean)

  suspend fun session(forceRefresh: Boolean = false): Session? {
    val user = auth.currentUser ?: return null
    val token = user.getIdToken(forceRefresh).await()
    val claims = token.claims
    val enabled = claims["ts"] == true
    val verified = TwoStepPin.isVerifiedSession(
      provider = token.signInProvider,
      ts = enabled,
      tsv = claims["tsv"] as? String,
      pv = claims["pv"] as? String,
    )
    return Session(enabled, verified)
  }

  suspend fun status(): TwoStepStatus {
    val data = call("twoStepStatus", emptyMap<String, Any>())
    return TwoStepStatus(
      enabled = data["enabled"] == true,
      verified = data["verified"] == true,
      lockedUntilMs = (data["lockedUntilMs"] as? Number)?.toLong(),
      resetAvailableAtMs = (data["resetAvailableAtMs"] as? Number)?.toLong(),
    )
  }

  /** Turns two-step on, or changes the PIN, then continues in the new PIN session. */
  suspend fun setPin(pin: String) {
    val token = call("setTwoStepPin", mapOf("pin" to pin))["token"] as? String
    adopt(token)
  }

  suspend fun verify(pin: String) {
    val token = call("verifyTwoStepPin", mapOf("pin" to pin))["token"] as? String
    adopt(token)
  }

  suspend fun disable(pin: String) {
    call("disableTwoStep", mapOf("pin" to pin))
    adopt(null)
  }

  /** Starts the 7-day reset clock; returns when the reset becomes available. */
  suspend fun requestReset(): Long? =
    (call("requestTwoStepReset", emptyMap<String, Any>())["resetAvailableAtMs"] as? Number)?.toLong()

  suspend fun completeReset() {
    call("completeTwoStepReset", emptyMap<String, Any>())
    adopt(null)
  }

  /** Signs in with a PIN-session token, or refreshes the ID token so new claims apply. */
  private suspend fun adopt(customToken: String?) {
    try {
      if (customToken != null) auth.signInWithCustomToken(customToken).await()
      else auth.currentUser?.getIdToken(true)?.await()
    } catch (e: Exception) {
      throw TwoStepException.Failed(e)
    }
  }

  private suspend fun call(name: String, data: Map<String, Any>): Map<String, Any?> {
    try {
      @Suppress("UNCHECKED_CAST")
      return (functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<String, Any?>).orEmpty()
    } catch (e: FirebaseFunctionsException) {
      val details = e.details as? Map<*, *>
      fun long(key: String) = (details?.get(key) as? Number)?.toLong()
      throw when {
        e.message == "wrong-pin" -> TwoStepException.WrongPin((details?.get("attemptsLeft") as? Number)?.toInt() ?: 0, long("lockedUntilMs"))
        e.code == Code.RESOURCE_EXHAUSTED -> TwoStepException.Locked(long("lockedUntilMs") ?: 0L)
        e.message == "pin-too-simple" -> TwoStepException.TooSimple()
        e.message == "reset-not-ready" -> TwoStepException.ResetNotReady(long("resetAvailableAtMs"))
        e.message == "reset-cancelled" -> TwoStepException.ResetCancelled()
        else -> TwoStepException.Failed(e)
      }
    } catch (e: Exception) {
      throw TwoStepException.Failed(e)
    }
  }
}
