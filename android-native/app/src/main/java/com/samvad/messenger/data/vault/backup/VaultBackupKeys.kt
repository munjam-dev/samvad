package com.samvad.messenger.data.vault.backup

import com.samvad.core.vault.ArchiveHeader
import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.KeyWrapper
import com.samvad.core.vault.VaultCryptoException
import com.samvad.core.vault.VaultKeyUnavailableException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** What an automatic backup needs. The caller wipes [archiveKey] with [wipe] when done. */
class BackupKeyMaterial(val header: ArchiveHeader, val archiveKey: ByteArray, val sealedDataKey: ByteArray) {
  fun wipe() = archiveKey.fill(0)
}

/**
 * The archive key made from the recovery code or passphrase, kept so scheduled backups can run without the person
 * being there. It is wrapped by the same Android Keystore key as the vault data key, so it is never stored in plain
 * text. The recovery code or passphrase itself is never stored anywhere.
 *
 * Also holds the vault data key sealed with the archive key (the first record of every archive), so a background
 * backup never has to load the vault data key. File layout: version byte, archive header (42 bytes), length and
 * Keystore-wrapped archive key, length and sealed data key record.
 */
class VaultBackupKeys(private val file: File, private val wrapper: KeyWrapper) {
  fun exists(): Boolean = file.isFile

  fun save(material: BackupKeyMaterial) {
    val header = material.header.encode()
    val wrapped = wrapper.wrap(material.archiveKey, context(header))
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeByte(VERSION)
      it.write(header)
      it.writeInt(wrapped.size)
      it.write(wrapped)
      it.writeInt(material.sealedDataKey.size)
      it.write(material.sealedDataKey)
    }
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { out ->
      out.write(bytes.toByteArray())
      out.flush()
      out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
      tmp.delete()
      throw IOException("Could not store the backup key")
    }
  }

  /** @throws BackupException DAMAGED when the file is missing or unreadable, or the Keystore key is gone. */
  fun load(): BackupKeyMaterial {
    try {
      val data = DataInputStream(ByteArrayInputStream(file.readBytes()))
      if (data.readUnsignedByte() != VERSION) throw BackupException(BackupFailure.DAMAGED)
      val headerBytes = ByteArray(ArchiveHeader.SIZE).also(data::readFully)
      val header = ArchiveHeader.decode(headerBytes)
      val wrapped = ByteArray(data.readInt().also { check(it in 1..1024) }).also(data::readFully)
      val sealed = ByteArray(data.readInt().also { check(it in 1..4096) }).also(data::readFully)
      val key = wrapper.unwrap(wrapped, context(headerBytes))
      return BackupKeyMaterial(header, key, sealed)
    } catch (e: BackupException) {
      throw e
    } catch (e: VaultKeyUnavailableException) {
      throw BackupException(BackupFailure.DAMAGED, e)
    } catch (e: VaultCryptoException) {
      throw BackupException(BackupFailure.DAMAGED, e)
    } catch (e: IOException) {
      throw BackupException(BackupFailure.DAMAGED, e)
    } catch (e: IllegalStateException) {
      throw BackupException(BackupFailure.DAMAGED, e)
    }
  }

  fun clear() {
    file.delete()
  }

  private fun context(header: ByteArray): ByteArray = "svl:backupkey:v1".toByteArray(Charsets.US_ASCII) + header

  private companion object {
    const val VERSION = 1
  }
}
