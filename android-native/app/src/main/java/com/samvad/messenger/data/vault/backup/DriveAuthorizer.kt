package com.samvad.messenger.data.vault.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.DriveRules
import kotlinx.coroutines.tasks.await

/** The outcome of asking Google for access to the hidden Drive app-data folder. */
sealed interface DriveAuth {
  /** [accessToken] is short-lived and must not be stored or logged. */
  class Granted(val accessToken: String, val email: String?) : DriveAuth

  /** The person must see Google's consent screen: launch [pendingIntent] and pass the result to [DriveAuthorizer.fromResult]. */
  class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuth
}

/**
 * Google Identity Services Authorization API, asking for ONE scope: drive.appdata (a hidden folder only this app
 * can see). No client secret is in the app: Google matches the app by package name and signing certificate against
 * the Android OAuth client the owner creates (docs/VAULT_BACKUP_SETUP.md). If that client does not exist the
 * request fails with DEVELOPER_ERROR, reported as [BackupFailure.NOT_CONFIGURED].
 */
class DriveAuthorizer(context: Context, private val prefs: BackupPrefs) {
  private val app = context.applicationContext

  private fun request(): AuthorizationRequest {
    val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveRules.SCOPE_APPDATA)))
    prefs.account?.let { builder.setAccount(Account(it, "com.google")) }
    return builder.build()
  }

  /** Asks Google; works without any screen when the person already agreed. */
  suspend fun authorize(): DriveAuth = try {
    map(Identity.getAuthorizationClient(app).authorize(request()).await())
  } catch (e: ApiException) {
    throw map(e)
  }

  /** For scheduled work: never shows a screen, so a revoked grant is reported as [BackupFailure.AUTH_REVOKED]. */
  suspend fun accessToken(): String = when (val r = authorize()) {
    is DriveAuth.Granted -> r.accessToken
    is DriveAuth.NeedsConsent -> throw BackupException(BackupFailure.AUTH_REVOKED)
  }

  /** The result of the consent screen. */
  fun fromResult(data: Intent?): DriveAuth = try {
    map(Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(data))
  } catch (e: ApiException) {
    throw map(e)
  }

  private fun map(result: AuthorizationResult): DriveAuth {
    val pending = result.pendingIntent
    if (result.hasResolution() && pending != null) return DriveAuth.NeedsConsent(pending)
    val token = result.accessToken ?: throw BackupException(BackupFailure.AUTH_REVOKED)
    // The granted scopes must include ours; anything else means the person unticked it.
    if (result.grantedScopes.none { it == DriveRules.SCOPE_APPDATA }) {
      throw BackupException(BackupFailure.AUTH_REVOKED)
    }
    val email = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
    return DriveAuth.Granted(token, email)
  }

  private fun map(e: ApiException): BackupException = BackupException(
    when (e.statusCode) {
      // DEVELOPER_ERROR: no Android OAuth client for this package and certificate (or the Drive API is off).
      CommonStatusCodes.DEVELOPER_ERROR -> BackupFailure.NOT_CONFIGURED
      CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT -> BackupFailure.NO_NETWORK
      CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.CANCELED, CommonStatusCodes.INTERRUPTED -> BackupFailure.AUTH_REVOKED
      else -> BackupFailure.FAILED
    },
    e,
  )
}
