package com.samvad.messenger.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Confirms the person holding the phone with its own fingerprint, face or screen lock. Samvad never
 * sees or stores the PIN, pattern or biometric data; the system does the check.
 */
object BiometricGate {
  /** Strong biometrics or the screen lock; API 28–29 cannot combine STRONG with DEVICE_CREDENTIAL. */
  private fun authenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

  /** False when the phone has no screen lock, so app lock could never be unlocked. */
  fun available(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

  fun prompt(
    activity: FragmentActivity,
    lock: AppLock,
    title: String,
    subtitle: String,
    onResult: (Boolean) -> Unit = {},
  ) {
    lock.beginAuthentication()
    val prompt = BiometricPrompt(
      activity,
      ContextCompat.getMainExecutor(activity),
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
          lock.endAuthentication(true)
          onResult(true)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
          lock.endAuthentication(false)
          onResult(false)
        }
        // onAuthenticationFailed is one rejected attempt; the prompt stays open for another try.
      },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setAllowedAuthenticators(authenticators())
      .build()
    runCatching { prompt.authenticate(info) }.onFailure {
      lock.endAuthentication(false)
      onResult(false)
    }
  }
}
