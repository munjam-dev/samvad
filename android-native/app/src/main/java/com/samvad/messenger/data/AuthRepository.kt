package com.samvad.messenger.data

import android.app.Activity
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.samvad.core.auth.AuthError
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed interface PhoneVerificationEvent {
  data class CodeSent(val verificationId: String, val resendToken: PhoneAuthProvider.ForceResendingToken) : PhoneVerificationEvent
  data class AutoVerified(val credential: PhoneAuthCredential) : PhoneVerificationEvent
  data class Failed(val error: AuthError) : PhoneVerificationEvent
}

/** Firebase phone authentication. OTP sending and checking are Firebase's; nothing is simulated. */
class AuthRepository(private val auth: FirebaseAuth) {

  val currentUser: FirebaseUser? get() = auth.currentUser

  fun userChanges(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
  }

  fun verify(
    activity: Activity,
    e164: String,
    resendToken: PhoneAuthProvider.ForceResendingToken? = null,
  ): Flow<PhoneVerificationEvent> = callbackFlow {
    val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
      override fun onVerificationCompleted(credential: PhoneAuthCredential) {
        trySend(PhoneVerificationEvent.AutoVerified(credential))
      }

      override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
        trySend(PhoneVerificationEvent.Failed(mapError(e)))
      }

      override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
        trySend(PhoneVerificationEvent.CodeSent(verificationId, token))
      }
    }
    val builder = PhoneAuthOptions.newBuilder(auth)
      .setPhoneNumber(e164)
      .setTimeout(60L, TimeUnit.SECONDS)
      .setActivity(activity)
      .setCallbacks(callbacks)
    resendToken?.let { builder.setForceResendingToken(it) }
    PhoneAuthProvider.verifyPhoneNumber(builder.build())
    awaitClose { }
  }

  fun credential(verificationId: String, code: String): PhoneAuthCredential =
    PhoneAuthProvider.getCredential(verificationId, code)

  suspend fun signIn(credential: PhoneAuthCredential): FirebaseUser =
    requireNotNull(auth.signInWithCredential(credential).await().user)

  /** Refreshes auth_time, which deleteAccount requires to be under five minutes old. */
  suspend fun reauthenticate(credential: PhoneAuthCredential) {
    val user = requireNotNull(auth.currentUser)
    user.reauthenticate(credential).await()
    user.getIdToken(true).await()
  }

  suspend fun secondsSinceSignIn(): Long {
    val token = requireNotNull(auth.currentUser).getIdToken(false).await()
    return System.currentTimeMillis() / 1000 - token.authTimestamp
  }

  fun signOut() = auth.signOut()

  companion object {
    fun mapError(e: Throwable): AuthError = when (e) {
      is FirebaseNetworkException -> AuthError.NETWORK
      is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_REQUESTS
      is FirebaseAuthMissingActivityForRecaptchaException -> AuthError.APP_VERIFICATION_FAILED
      is FirebaseAuthInvalidUserException -> AuthError.USER_DISABLED
      is FirebaseAuthInvalidCredentialsException -> when (e.errorCode) {
        "ERROR_INVALID_PHONE_NUMBER" -> AuthError.INVALID_NUMBER
        "ERROR_SESSION_EXPIRED" -> AuthError.CODE_EXPIRED
        else -> AuthError.INVALID_CODE
      }
      is FirebaseAuthException -> when (e.errorCode) {
        "ERROR_SESSION_EXPIRED" -> AuthError.CODE_EXPIRED
        "ERROR_QUOTA_EXCEEDED", "ERROR_TOO_MANY_REQUESTS" -> AuthError.TOO_MANY_REQUESTS
        "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_APP_CREDENTIAL", "ERROR_MISSING_CLIENT_IDENTIFIER" ->
          AuthError.APP_VERIFICATION_FAILED
        else -> AuthError.UNKNOWN
      }
      else -> AuthError.UNKNOWN
    }
  }
}
