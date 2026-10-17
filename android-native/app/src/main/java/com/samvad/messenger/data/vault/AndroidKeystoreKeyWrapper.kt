package com.samvad.messenger.data.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.samvad.core.vault.KeyWrapper
import com.samvad.core.vault.VaultKeyUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Where the Keystore key that wraps the vault data key lives. */
enum class VaultKeyProtection { STRONGBOX, HARDWARE, SOFTWARE, UNKNOWN }

/**
 * Wraps the 32-byte vault data key with an AES-256-GCM key held by the Android Keystore. The Keystore
 * key is hardware-backed where the phone has a trusted environment, and in StrongBox where it has one.
 *
 * It deliberately does NOT require user authentication for each use. A key bound to the screen lock is
 * deleted by Android when the person removes or changes that lock, which would destroy the vault for good;
 * the vault is protected by the in-app gate (fingerprint, face or screen lock before it opens) instead.
 */
class AndroidKeystoreKeyWrapper(
  private val appContext: Context,
  private val alias: String = ALIAS,
) : KeyWrapper {
  override fun wrap(plain: ByteArray, context: ByteArray): ByteArray {
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))
      cipher.updateAAD(context)
      val iv = cipher.iv
      val ct = cipher.doFinal(plain)
      return byteArrayOf(iv.size.toByte()) + iv + ct
    } catch (e: GeneralSecurityException) {
      throw VaultKeyUnavailableException("Keystore could not wrap the vault key", e)
    } catch (e: ProviderException) {
      throw VaultKeyUnavailableException("Keystore could not wrap the vault key", e)
    }
  }

  override fun unwrap(wrapped: ByteArray, context: ByteArray): ByteArray {
    try {
      if (wrapped.size < 2) throw VaultKeyUnavailableException("Wrapped key is damaged")
      val ivLen = wrapped[0].toInt() and 0xFF
      if (ivLen != IV_BYTES || wrapped.size < 1 + ivLen + TAG_BYTES) throw VaultKeyUnavailableException("Wrapped key is damaged")
      val key = key(createIfMissing = false) ?: throw VaultKeyUnavailableException("The Keystore key is gone")
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, wrapped, 1, ivLen))
      cipher.updateAAD(context)
      return cipher.doFinal(wrapped, 1 + ivLen, wrapped.size - 1 - ivLen)
    } catch (e: VaultKeyUnavailableException) {
      throw e
    } catch (e: GeneralSecurityException) {
      // Key invalidated, key replaced, or the blob was changed.
      throw VaultKeyUnavailableException("Keystore could not unwrap the vault key", e)
    } catch (e: ProviderException) {
      throw VaultKeyUnavailableException("Keystore could not unwrap the vault key", e)
    }
  }

  /** Where the wrapping key lives, for the "About this vault" text. Never throws. */
  fun protection(): VaultKeyProtection = runCatching {
    val key = key(createIfMissing = false) ?: return VaultKeyProtection.UNKNOWN
    val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> VaultKeyProtection.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> VaultKeyProtection.HARDWARE
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> VaultKeyProtection.SOFTWARE
        else -> VaultKeyProtection.UNKNOWN
      }
    } else {
      @Suppress("DEPRECATION")
      if (info.isInsideSecureHardware) VaultKeyProtection.HARDWARE else VaultKeyProtection.SOFTWARE
    }
  }.getOrDefault(VaultKeyProtection.UNKNOWN)

  private fun key(createIfMissing: Boolean): SecretKey? {
    val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return if (createIfMissing) generate() else null
  }

  private fun generate(): SecretKey {
    val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (strongBox) {
      // StrongBoxUnavailableException is a ProviderException; any phone that says it has StrongBox but cannot
      // use it right now falls back to the normal hardware-backed Keystore.
      try {
        return generate(strongBox = true)
      } catch (_: ProviderException) {
      } catch (_: GeneralSecurityException) {
      }
    }
    return generate(strongBox = false)
  }

  private fun generate(strongBox: Boolean): SecretKey {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
      .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
  }

  /** Removes the Keystore key. Used only when the whole vault is erased. */
  fun deleteKey() {
    runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias) }
  }

  companion object {
    const val ALIAS = "samvad_vault_wrap_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
  }
}

/** The wrapped data key as a small file in no-backup storage. Written atomically. */
class FileWrappedKeyStore(private val file: File) : com.samvad.core.vault.WrappedKeyStore {
  override fun read(): ByteArray? = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()

  override fun write(bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { out ->
      out.write(bytes)
      out.flush()
      out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
      tmp.delete()
      throw java.io.IOException("Could not store the vault key")
    }
  }

  override fun clear() {
    file.delete()
  }
}
