package com.samvad.core.vault

import java.security.SecureRandom

/** The wrapping key (on Android, an Android Keystore key) cannot be used any more. */
class VaultKeyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The thin part that touches the Android Keystore: it encrypts and decrypts one 32-byte value with a
 * key that never leaves secure hardware where the phone has it. Everything else about the data key is
 * plain logic in [VaultKeyring]. [context] is bound as associated data.
 */
interface KeyWrapper {
  fun wrap(plain: ByteArray, context: ByteArray): ByteArray

  /** @throws VaultKeyUnavailableException when the wrapping key is gone, changed, or the blob was altered. */
  fun unwrap(wrapped: ByteArray, context: ByteArray): ByteArray
}

/** Where the wrapped data key is kept (a small file in app-private, no-backup storage). */
interface WrappedKeyStore {
  fun read(): ByteArray?

  fun write(bytes: ByteArray)

  fun clear()
}

/**
 * Creates, stores and loads the random vault data key. The data key is generated once, only ever
 * stored wrapped, and loaded into memory when the vault is unlocked.
 */
class VaultKeyring(
  private val wrapper: KeyWrapper,
  private val store: WrappedKeyStore,
  private val random: SecureRandom = SecureRandom(),
) {
  fun exists(): Boolean = store.read() != null

  /**
   * The data key. When none exists yet and [createIfMissing] is true, a new random one is generated and
   * stored. The caller owns the returned array and should [ByteArray.fill] it with zeros when done.
   *
   * @throws VaultKeyUnavailableException when a key exists but cannot be unwrapped (for example the Keystore
   * key was deleted by a factory reset or a security change), or when none exists and [createIfMissing] is false.
   */
  fun load(createIfMissing: Boolean): ByteArray {
    val stored = store.read()
    if (stored == null) {
      if (!createIfMissing) throw VaultKeyUnavailableException("No vault key")
      val fresh = ByteArray(AesGcmVaultCrypto.KEY_BYTES).also(random::nextBytes)
      store.write(frame(wrapper.wrap(fresh, CONTEXT)))
      return fresh
    }
    val wrapped = unframe(stored)
    val key = wrapper.unwrap(wrapped, CONTEXT)
    if (key.size != AesGcmVaultCrypto.KEY_BYTES) {
      key.fill(0)
      throw VaultKeyUnavailableException("Vault key has an unexpected size")
    }
    return key
  }

  /** Forgets the data key. Everything sealed with it can never be opened again. */
  fun destroy() = store.clear()

  private fun frame(wrapped: ByteArray): ByteArray = byteArrayOf(FRAME_VERSION) + wrapped

  private fun unframe(stored: ByteArray): ByteArray {
    if (stored.isEmpty() || stored[0] != FRAME_VERSION) throw VaultKeyUnavailableException("Unknown vault key format")
    return stored.copyOfRange(1, stored.size)
  }

  private companion object {
    const val FRAME_VERSION: Byte = 1
    val CONTEXT = "svl:datakey:v1".toByteArray(Charsets.US_ASCII)
  }
}
