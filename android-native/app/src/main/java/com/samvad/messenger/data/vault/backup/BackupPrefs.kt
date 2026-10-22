package com.samvad.messenger.data.vault.backup

import android.content.Context
import com.samvad.core.vault.ArchiveKdf
import com.samvad.core.vault.BackupFailure

/** What the backup screen shows. Contains no vault content, names or keys. */
data class VaultBackupStatus(
  val enabled: Boolean = false,
  /** The Google account the backup is stored with, as Google gave it to us. */
  val account: String? = null,
  val method: ArchiveKdf? = null,
  val autoEnabled: Boolean = true,
  val wifiOnly: Boolean = true,
  val chargingOnly: Boolean = true,
  val lastSuccessAt: Long = 0,
  val lastSizeBytes: Long = 0,
  val lastItemCount: Int = 0,
  val lastAttemptAt: Long = 0,
  val lastFailure: BackupFailure? = null,
  val operation: BackupOperation = BackupOperation.IDLE,
  /** 0..1 while uploading or downloading, null when unknown. */
  val progress: Float? = null,
)

enum class BackupOperation { IDLE, BACKING_UP, RESTORING, TURNING_OFF }

/**
 * The small amount of backup state kept on the phone: whether it is on, the schedule choices and the outcome of
 * the last run. The keys are NOT here (see [VaultBackupKeys]). Plain SharedPreferences are fine: the app is excluded
 * from Android auto backup, and nothing here is secret.
 */
class BackupPrefs(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("vault_backup", Context.MODE_PRIVATE)

  fun read(): VaultBackupStatus = VaultBackupStatus(
    enabled = prefs.getBoolean(ENABLED, false),
    account = prefs.getString(ACCOUNT, null),
    method = ArchiveKdf.of(prefs.getInt(METHOD, 0)),
    autoEnabled = prefs.getBoolean(AUTO, true),
    wifiOnly = prefs.getBoolean(WIFI_ONLY, true),
    chargingOnly = prefs.getBoolean(CHARGING_ONLY, true),
    lastSuccessAt = prefs.getLong(LAST_SUCCESS, 0),
    lastSizeBytes = prefs.getLong(LAST_SIZE, 0),
    lastItemCount = prefs.getInt(LAST_ITEMS, 0),
    lastAttemptAt = prefs.getLong(LAST_ATTEMPT, 0),
    lastFailure = prefs.getString(LAST_FAILURE, null)?.let { name -> BackupFailure.entries.firstOrNull { it.name == name } },
  )

  var account: String?
    get() = prefs.getString(ACCOUNT, null)
    set(value) = prefs.edit().putString(ACCOUNT, value).apply()

  /** Stamp of the vault data key the backup was made for; see VaultRepository.dataKeyStamp. */
  var keyStamp: String?
    get() = prefs.getString(KEY_STAMP, null)
    set(value) = prefs.edit().putString(KEY_STAMP, value).apply()

  /** Fingerprint of the vault at the last successful backup, to skip an automatic backup when nothing changed. */
  var lastFingerprint: String?
    get() = prefs.getString(FINGERPRINT, null)
    set(value) = prefs.edit().putString(FINGERPRINT, value).apply()

  fun enable(method: ArchiveKdf, account: String?, keyStamp: String?) {
    prefs.edit()
      .putBoolean(ENABLED, true)
      .putInt(METHOD, method.id)
      .putString(ACCOUNT, account)
      .putString(KEY_STAMP, keyStamp)
      .remove(FINGERPRINT).remove(LAST_FAILURE).remove(LAST_SUCCESS).remove(LAST_SIZE).remove(LAST_ITEMS)
      .apply()
  }

  fun setSchedule(auto: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) {
    prefs.edit().putBoolean(AUTO, auto).putBoolean(WIFI_ONLY, wifiOnly).putBoolean(CHARGING_ONLY, chargingOnly).apply()
  }

  fun recordSuccess(now: Long, sizeBytes: Long, items: Int, fingerprint: String) {
    prefs.edit()
      .putLong(LAST_SUCCESS, now).putLong(LAST_ATTEMPT, now).putLong(LAST_SIZE, sizeBytes).putInt(LAST_ITEMS, items)
      .putString(FINGERPRINT, fingerprint).remove(LAST_FAILURE)
      .apply()
  }

  /** A run that found nothing to do still counts as a successful check. */
  fun recordUnchanged(now: Long) {
    prefs.edit().putLong(LAST_SUCCESS, now).putLong(LAST_ATTEMPT, now).remove(LAST_FAILURE).apply()
  }

  fun recordFailure(now: Long, failure: BackupFailure) {
    prefs.edit().putLong(LAST_ATTEMPT, now).putString(LAST_FAILURE, failure.name).apply()
  }

  fun clearAll() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val ENABLED = "enabled"
    const val ACCOUNT = "account"
    const val METHOD = "method"
    const val AUTO = "auto"
    const val WIFI_ONLY = "wifi_only"
    const val CHARGING_ONLY = "charging_only"
    const val LAST_SUCCESS = "last_success"
    const val LAST_SIZE = "last_size"
    const val LAST_ITEMS = "last_items"
    const val LAST_ATTEMPT = "last_attempt"
    const val LAST_FAILURE = "last_failure"
    const val KEY_STAMP = "key_stamp"
    const val FINGERPRINT = "fingerprint"
  }
}
