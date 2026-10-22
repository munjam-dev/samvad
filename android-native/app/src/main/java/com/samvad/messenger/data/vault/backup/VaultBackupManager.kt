package com.samvad.messenger.data.vault.backup

import android.content.Context
import com.samvad.core.vault.ArchiveHeader
import com.samvad.core.vault.ArchiveKdf
import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.BackupKdf
import com.samvad.core.vault.BackupPassphrase
import com.samvad.core.vault.RecoveryCode
import com.samvad.core.vault.RecoverySecretFormatException
import com.samvad.core.vault.RemoteBackups
import com.samvad.core.vault.VaultArchive
import com.samvad.core.vault.VaultBackupTooNewException
import com.samvad.core.vault.VaultFormatException
import com.samvad.core.vault.VaultTamperedException
import com.samvad.messenger.data.vault.VaultException
import com.samvad.messenger.data.vault.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A recovery secret that has been made but not yet saved by the person. Held only in memory until they confirm
 * they wrote it down; if they leave first, nothing was stored. [recoveryCode] is null for a passphrase.
 */
class BackupSetupDraft(val header: ArchiveHeader, internal val archiveKey: ByteArray, val recoveryCode: String?) {
  val method: ArchiveKdf get() = header.kdf

  fun wipe() = archiveKey.fill(0)
}

enum class ScheduledOutcome { DONE, RETRY }

/**
 * Backup of the Personal Vault to the person's own Google Drive (hidden app-data folder). Everything is encrypted on
 * this phone first with a key made from a recovery code or passphrase that only the person has. See
 * docs/VAULT_DESIGN.md. One operation runs at a time. Never logs vault content, names or keys.
 */
class VaultBackupManager(
  context: Context,
  private val vault: VaultRepository,
  private val scope: CoroutineScope,
) {
  private val app = context.applicationContext
  private val prefs = BackupPrefs(app)
  val authorizer = DriveAuthorizer(app, prefs)
  private val keys = VaultBackupKeys(vault.backupKeyFile, vault.keyWrapper())
  private val lock = Mutex()
  private val _status = MutableStateFlow(prefs.read())
  val status: StateFlow<VaultBackupStatus> = _status.asStateFlow()

  /** A finished download kept for another try after a wrong recovery code. */
  private var downloaded: File? = null
  private var job: Job? = null

  init {
    scope.launch(Dispatchers.IO) {
      // Scratch files from a run that was cut short. They only ever hold ciphertext.
      vault.backupWorkDir.listFiles()?.forEach { it.delete() }
      if (prefs.read().enabled) VaultBackupScheduler.apply(app, _status.value)
    }
  }

  private fun publish(operation: BackupOperation = BackupOperation.IDLE, progress: Float? = null) {
    _status.value = prefs.read().copy(operation = operation, progress = progress)
  }

  private fun appVersion(): Int = runCatching {
    app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
  }.getOrDefault(0)

  // ---- setting up ----

  /** Makes a new random recovery code. Nothing is stored. */
  suspend fun newRecoveryKeyDraft(): BackupSetupDraft = withContext(Dispatchers.Default) {
    val raw = RecoveryCode.generate()
    val code = RecoveryCode.format(raw)
    raw.fill(0)
    val header = ArchiveHeader.newRecoveryKey()
    BackupSetupDraft(header, header.deriveKey(code), code)
  }

  /** Derives the key from a passphrase with PBKDF2 (slow on purpose, so run off the main thread). Nothing is stored. */
  suspend fun newPassphraseDraft(passphrase: String): BackupSetupDraft = withContext(Dispatchers.Default) {
    require(BackupPassphrase.isAcceptable(passphrase)) { "Passphrase too short" }
    val header = ArchiveHeader.newPassphrase(BackupKdf.PBKDF2_MIN_ITERATIONS)
    BackupSetupDraft(header, header.deriveKey(passphrase), null)
  }

  /**
   * Turns backup on after the person confirmed they kept the recovery secret. The vault must be unlocked. Stores
   * the archive key wrapped by the Android Keystore, never the secret. Then starts the first backup in the background.
   */
  suspend fun enable(draft: BackupSetupDraft, email: String?) {
    lock.withLock {
      if (!vault.isUnlocked) throw BackupException(BackupFailure.VAULT_LOCKED)
      withContext(Dispatchers.IO) {
        val sealed = vault.withDataKey { VaultArchive.sealDataKey(draft.header, draft.archiveKey, it) }
        keys.save(BackupKeyMaterial(draft.header, draft.archiveKey.copyOf(), sealed))
        prefs.enable(draft.header.kdf, email, vault.dataKeyStamp())
      }
      draft.wipe()
      publish()
      VaultBackupScheduler.apply(app, _status.value)
    }
    startBackup()
  }

  /** After the person agreed again on Google's screen (for example after they removed access in their Google account). */
  fun onReauthorized(email: String?) {
    if (email != null) prefs.account = email
    publish()
  }

  fun setSchedule(auto: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) {
    prefs.setSchedule(auto, wifiOnly, chargingOnly)
    publish(_status.value.operation, _status.value.progress)
    VaultBackupScheduler.apply(app, _status.value)
  }

  // ---- backing up ----

  /** Starts a backup that carries on even if the person leaves the screen. */
  fun startBackup() {
    if (job?.isActive == true) return
    job = scope.launch { runBackup(auto = false) }
  }

  /** @return null when the backup worked (or nothing had changed), otherwise why not. */
  suspend fun runBackup(auto: Boolean): BackupFailure? = lock.withLock {
    if (!prefs.read().enabled) return@withLock null
    publish(BackupOperation.BACKING_UP, null)
    try {
      doBackup(auto)
      null
    } catch (e: BackupException) {
      prefs.recordFailure(System.currentTimeMillis(), e.failure)
      e.failure
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      prefs.recordFailure(System.currentTimeMillis(), BackupFailure.FAILED)
      BackupFailure.FAILED
    } finally {
      publish()
    }
  }

  private suspend fun doBackup(auto: Boolean) {
    if (vault.dataKeyStamp() != prefs.keyStamp) throw BackupException(BackupFailure.VAULT_CHANGED)
    val material = keys.load()
    val dir = vault.backupWorkDir.also { it.mkdirs() }
    val tmp = File(dir, "archive.svbk")
    try {
      val before = prefs.read()
      val fingerprint = vault.backupFingerprint()
      if (auto && before.lastSuccessAt > 0 && before.lastFailure == null && fingerprint == prefs.lastFingerprint) {
        prefs.recordUnchanged(System.currentTimeMillis())
        return
      }
      val archive = vault.writeBackupArchive(tmp, material.header, material.archiveKey, material.sealedDataKey, appVersion())
      val remote = DriveAppDataClient { authorizer.accessToken() }
      val quota = remote.quota()
      if (quota != null && !quota.hasRoomFor(archive.bytes)) throw BackupException(BackupFailure.QUOTA_FULL)
      var lastShown = -1
      RemoteBackups.replace(remote, tmp) { sent, total ->
        val percent = if (total > 0) (sent * 100 / total).toInt() else 0
        if (percent != lastShown) {
          lastShown = percent
          publish(BackupOperation.BACKING_UP, percent / 100f)
        }
      }
      prefs.recordSuccess(System.currentTimeMillis(), archive.bytes, archive.itemCount, archive.fingerprint)
    } finally {
      material.wipe()
      tmp.delete()
    }
  }

  /** For the scheduled worker. */
  suspend fun runScheduled(attempt: Int): ScheduledOutcome {
    val s = prefs.read()
    if (!s.enabled || !s.autoEnabled) return ScheduledOutcome.DONE
    return when (runBackup(auto = true)) {
      BackupFailure.TRANSIENT, BackupFailure.NO_NETWORK -> if (attempt < 3) ScheduledOutcome.RETRY else ScheduledOutcome.DONE
      else -> ScheduledOutcome.DONE
    }
  }

  // ---- restoring ----

  /**
   * Downloads the newest backup and restores it into this (unlocked) vault, replacing nothing: items that are already here
   * are kept. [secret] is the recovery code or passphrase as typed. Throws [BackupException].
   */
  suspend fun restore(secret: String): VaultRepository.RestoreResult = lock.withLock {
    if (!vault.isUnlocked) throw BackupException(BackupFailure.VAULT_LOCKED)
    publish(BackupOperation.RESTORING, null)
    var keep = false
    try {
      val dir = vault.backupWorkDir.also { it.mkdirs() }
      val tmp = File(dir, "restore.svbk")
      val existing = downloaded?.takeIf { it.isFile }
      if (existing == null) {
        val remote = DriveAppDataClient { authorizer.accessToken() }
        val latest = RemoteBackups.latest(remote) ?: throw BackupException(BackupFailure.NO_BACKUP)
        // The archive, the verified copy and the re-sealed files are never all on disk at once, but two are.
        if (dir.usableSpace < latest.sizeBytes * 2 + 60L * 1024 * 1024) throw BackupException(BackupFailure.NO_SPACE)
        var lastShown = -1
        remote.download(latest, tmp) { received, total ->
          val percent = if (total > 0) (received * 100 / total).toInt() else 0
          if (percent != lastShown) {
            lastShown = percent
            publish(BackupOperation.RESTORING, percent / 100f)
          }
        }
        downloaded = tmp
      }
      publish(BackupOperation.RESTORING, null)
      try {
        val result = vault.restoreFromArchive(downloaded!!, secret)
        downloaded = null
        result
      } catch (e: VaultBackupTooNewException) {
        throw BackupException(BackupFailure.TOO_NEW, e)
      } catch (e: RecoverySecretFormatException) {
        keep = true
        throw BackupException(BackupFailure.WRONG_SECRET, e)
      } catch (e: VaultTamperedException) {
        keep = true
        throw BackupException(BackupFailure.WRONG_SECRET, e)
      } catch (e: VaultFormatException) {
        throw BackupException(BackupFailure.DAMAGED, e)
      } catch (e: VaultException) {
        throw BackupException(if (e.reason == VaultException.Reason.LOCKED) BackupFailure.VAULT_LOCKED else BackupFailure.FAILED, e)
      }
    } finally {
      if (!keep) discardDownloadUnlocked()
      publish()
    }
  }

  /** Deletes a downloaded backup that was kept for another try. Call when the restore dialog closes. */
  fun discardDownload() {
    if (lock.isLocked) return
    discardDownloadUnlocked()
  }

  private fun discardDownloadUnlocked() {
    downloaded?.delete()
    downloaded = null
    File(vault.backupWorkDir, "restore.svbk").delete()
  }

  // ---- turning off ----

  /**
   * Turns backup off. With [deleteFromDrive] the backup is removed from the person's Drive first; if that fails
   * (no network, access revoked) backup stays on so nothing is half done, and the caller can offer to turn it off without deleting.
   */
  suspend fun turnOff(deleteFromDrive: Boolean) = lock.withLock {
    publish(BackupOperation.TURNING_OFF, null)
    try {
      if (deleteFromDrive) RemoteBackups.deleteAll(DriveAppDataClient { authorizer.accessToken() })
      VaultBackupScheduler.cancel(app)
      withContext(Dispatchers.IO) {
        keys.clear()
        prefs.clearAll()
        vault.backupWorkDir.listFiles()?.forEach { it.delete() }
      }
    } finally {
      publish()
    }
  }
}
