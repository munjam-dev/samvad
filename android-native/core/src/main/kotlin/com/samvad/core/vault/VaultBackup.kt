package com.samvad.core.vault

import java.io.InputStream

/**
 * Backup of the Personal Vault to a place outside the phone. NOT IMPLEMENTED, and nothing in the app uses
 * it: it is blocked on two owner decisions (the Google Drive scope, and how the recovery key is held), see
 * docs/VAULT_DESIGN.md. The vault is local-only until a real implementation exists, and no screen,
 * string or document may say otherwise.
 *
 * Rules any implementation must follow:
 * - Only already-encrypted blobs leave the phone. A backup is sealed with a recovery key that is NOT the
 *   Keystore-wrapped data key (that one is bound to this phone), and Samvad's servers never see it.
 * - The person chooses to turn it on, and sees what is uploaded and where.
 * - It is opt-in per vault, and "delete" in the app also deletes the remote copy.
 */
interface VaultBackup {
  /** Whether a backup destination is signed in and usable right now. */
  suspend fun isAvailable(): Boolean

  /** Uploads one sealed blob under [name]. [sealed] must be ciphertext produced by a [VaultCrypto]. */
  suspend fun upload(name: String, sealed: InputStream, length: Long)

  /** Names of the blobs currently in the backup. */
  suspend fun list(): List<String>

  /** Downloads one sealed blob. The caller opens it with the recovery key. */
  suspend fun download(name: String): InputStream

  /** Removes one blob from the backup. */
  suspend fun delete(name: String)
}
