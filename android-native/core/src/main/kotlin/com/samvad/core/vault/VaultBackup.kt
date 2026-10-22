package com.samvad.core.vault

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest

/**
 * Backup of the Personal Vault to the person's own Google Drive (hidden app-data folder). Everything that
 * leaves the phone is first sealed on the phone with a key made from a recovery code or passphrase that only
 * the person holds; see [VaultArchive] and docs/VAULT_DESIGN.md. This file holds the pure parts: the
 * remote storage interface, failure kinds, Drive rules and small helpers.
 */

/** Why a backup, restore or turn-off step failed, in terms the screen can explain. */
enum class BackupFailure {
  NO_NETWORK,
  QUOTA_FULL,
  AUTH_REVOKED,
  NOT_CONFIGURED,
  TOO_NEW,
  WRONG_SECRET,
  NO_BACKUP,
  NO_SPACE,
  VAULT_LOCKED,
  DAMAGED,
  TRANSIENT,
  FAILED,
}

class BackupException(val failure: BackupFailure, cause: Throwable? = null) : Exception(failure.name, cause)

data class RemoteBackupFile(val id: String, val name: String, val sizeBytes: Long, val modifiedMillis: Long)

/** Free space on the remote side; null limit means unlimited. */
data class RemoteQuota(val limitBytes: Long?, val usedBytes: Long) {
  fun hasRoomFor(bytes: Long): Boolean = limitBytes == null || usedBytes + bytes <= limitBytes
}

/** The place the sealed archive is kept. Implemented over the Drive REST API in the app; faked in tests. */
interface VaultBackupRemote {
  suspend fun list(): List<RemoteBackupFile>

  /** Uploads [source] as [name]. Throws [BackupException]. */
  suspend fun upload(source: File, name: String, onProgress: (sent: Long, total: Long) -> Unit): RemoteBackupFile

  suspend fun download(file: RemoteBackupFile, target: File, onProgress: (received: Long, total: Long) -> Unit)

  /** Deleting something already gone is not an error. */
  suspend fun delete(id: String)

  suspend fun quota(): RemoteQuota?
}

object RemoteBackups {
  /**
   * Uploads the new archive first and only then removes the older ones, so a failed upload never leaves the
   * person with no backup. Returns the new file.
   */
  suspend fun replace(remote: VaultBackupRemote, source: File, onProgress: (Long, Long) -> Unit): RemoteBackupFile {
    val before = remote.list().filter { it.name == DriveRules.BACKUP_FILE_NAME }
    val uploaded = remote.upload(source, DriveRules.BACKUP_FILE_NAME, onProgress)
    // A failed clean-up leaves a stale copy that the next backup removes; it is not a failed backup.
    for (old in before) if (old.id != uploaded.id) runCatching { remote.delete(old.id) }
    return uploaded
  }

  /** The newest backup file, or null. */
  suspend fun latest(remote: VaultBackupRemote): RemoteBackupFile? =
    remote.list().filter { it.name == DriveRules.BACKUP_FILE_NAME }.maxByOrNull { it.modifiedMillis }

  /** Removes every backup file. Returns how many were removed. */
  suspend fun deleteAll(remote: VaultBackupRemote): Int {
    val files = remote.list()
    for (f in files) remote.delete(f.id)
    return files.size
  }
}

/** Google Drive details that need no Android: scope, names, error mapping, resumable-upload arithmetic. */
object DriveRules {
  /** The only scope Samvad asks for: a hidden per-app folder, invisible in the person's Drive listing. */
  const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
  const val SPACE = "appDataFolder"
  const val BACKUP_FILE_NAME = "samvad-vault-backup.svbk"

  /** A multiple of 256 KiB as Drive requires for all but the last chunk. */
  const val UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024
  const val MAX_ATTEMPTS = 5

  /** Maps an HTTP status and (optionally) the error body to what the person should be told. */
  fun classify(status: Int, body: String?): BackupFailure {
    val text = body.orEmpty()
    return when {
      status == 401 -> BackupFailure.AUTH_REVOKED
      status == 403 && (text.contains("storageQuotaExceeded") || text.contains("quotaExceeded")) -> BackupFailure.QUOTA_FULL
      status == 403 && (text.contains("rateLimitExceeded") || text.contains("userRateLimitExceeded")) -> BackupFailure.TRANSIENT
      status == 403 && (text.contains("accessNotConfigured") || text.contains("SERVICE_DISABLED")) -> BackupFailure.NOT_CONFIGURED
      status == 403 && (text.contains("insufficientPermissions") || text.contains("forbidden")) -> BackupFailure.AUTH_REVOKED
      status == 403 || status == 429 -> BackupFailure.TRANSIENT
      status == 408 || status in 500..599 -> BackupFailure.TRANSIENT
      status == 404 -> BackupFailure.NO_BACKUP
      else -> BackupFailure.FAILED
    }
  }

  /** Content-Range for a chunk covering [start] until [endExclusive] of [total] bytes. */
  fun contentRange(start: Long, endExclusive: Long, total: Long): String {
    require(start in 0 until endExclusive && endExclusive <= total)
    return "bytes $start-${endExclusive - 1}/$total"
  }

  /** Content-Range for the "how much did you get" query. */
  fun statusQueryRange(total: Long): String = "bytes */$total"

  /**
   * Next byte to send, from the Range header of a 308 reply (`bytes=0-12345`); no header means nothing
   * has been received yet.
   */
  fun nextOffset(rangeHeader: String?): Long {
    if (rangeHeader.isNullOrBlank()) return 0
    val last = rangeHeader.substringAfter('-', "").trim().toLongOrNull() ?: return 0
    return if (last < 0) 0 else last + 1
  }

  /** Waits before retry number [attempt] (1-based): 2, 4, 8 ... seconds, capped at 60. */
  fun backoffMillis(attempt: Int): Long = minOf(60_000L, 1_000L shl minOf(attempt, 6))
}

/** A short, stable fingerprint of the vault's contents, to skip an automatic backup when nothing changed. */
object VaultFingerprint {
  fun of(items: List<ArchivedItem>): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (i in items.sortedBy { it.id }) md.update("${i.id}|${i.updatedAt}|${i.sizeBytes}|${i.hasThumb}\n".toByteArray(Charsets.UTF_8))
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}

/** Moves a vault file from one data key to another without writing plain text anywhere. */
object VaultReseal {
  /**
   * Decrypts [input] (sealed under [from] with [fromContext]) and encrypts it to [output] under [to] with [toContext].
   * Plain text only ever exists inside an in-memory pipe. On any failure the caller must discard [output].
   */
  fun reseal(from: VaultCrypto, fromContext: ByteArray, to: VaultCrypto, toContext: ByteArray, input: InputStream, output: OutputStream) {
    val sink = PipedOutputStream()
    val source = PipedInputStream(sink, 64 * 1024)
    var failure: Throwable? = null
    val producer = Thread({
      try {
        from.decrypt(input, sink, fromContext)
      } catch (t: Throwable) {
        failure = t
      } finally {
        runCatching { sink.close() }
      }
    }, "vault-reseal")
    producer.isDaemon = true
    producer.start()
    var consumerFailure: Throwable? = null
    try {
      to.encrypt(source, output, toContext)
    } catch (t: Throwable) {
      consumerFailure = t
    } finally {
      runCatching { source.close() }
      producer.join()
    }
    // A decryption failure explains an encryption that was cut short, so it wins.
    val producerFailure = failure
    val problem = if (producerFailure is VaultCryptoException) producerFailure else consumerFailure ?: producerFailure
    when (problem) {
      null -> Unit
      is IOException -> throw problem
      else -> throw VaultCryptoException("Re-encryption failed", problem)
    }
  }
}
