package com.samvad.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Base class for every failure of the vault crypto. Messages never contain content. */
open class VaultCryptoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The data is not a vault envelope, or uses a version this build does not know. */
class VaultFormatException(message: String) : VaultCryptoException(message)

/**
 * Authentication failed: the data was changed, cut short, moved to another item, or opened with the
 * wrong key. These cannot be told apart (and must not be), so the result is always the same.
 */
class VaultTamperedException(cause: Throwable? = null) : VaultCryptoException("Vault data failed authentication", cause)

/**
 * Seals and opens vault data. The Android Keystore never sees file content: it only wraps the data key
 * (see [VaultKeyring]), so this interface is implemented once, in plain Java crypto, and tested on the JVM.
 *
 * [context] binds a blob to where it belongs (for example "file:<item id>"). A blob copied to another
 * item, or a thumbnail swapped for a file, fails to open.
 */
interface VaultCrypto {
  fun seal(plain: ByteArray, context: ByteArray): ByteArray

  fun open(sealed: ByteArray, context: ByteArray): ByteArray

  /**
   * Encrypts [input] to [output] in chunks, so a large file is never held in memory whole. Does not close either stream.
   */
  fun encrypt(input: InputStream, output: OutputStream, context: ByteArray)

  /**
   * Decrypts [input] to [output] in chunks. Each chunk is authenticated before it is written, but a later
   * chunk can still fail: on any [VaultCryptoException] the caller must discard everything written to [output].
   * Does not close either stream.
   */
  fun decrypt(input: InputStream, output: OutputStream, context: ByteArray)
}

/** The strings used as [VaultCrypto] context, in one place so that writers and readers cannot disagree. */
object VaultContexts {
  fun file(id: String): ByteArray = "svl:file:$id".toByteArray(Charsets.UTF_8)
  fun thumbnail(id: String): ByteArray = "svl:thumb:$id".toByteArray(Charsets.UTF_8)
  fun meta(id: String): ByteArray = "svl:meta:$id".toByteArray(Charsets.UTF_8)
}

/**
 * Envelope format, version 1 (all numbers big-endian):
 *
 * ```
 * magic        4 bytes   "SVLT"
 * version      1 byte    1
 * algorithm    1 byte    1 = AES-256-GCM, chunked
 * chunk size   4 bytes   plaintext bytes per chunk (the last chunk may be shorter or empty)
 * salt        32 bytes   random per blob
 * chunks...              each is ciphertext + 16-byte GCM tag
 * ```
 *
 * A per-blob key is derived from the data key and the salt with HKDF-SHA256, so a data key can seal an
 * unlimited number of blobs without ever repeating a (key, nonce) pair. The nonce of a chunk is
 * 7 zero bytes, the 4-byte chunk counter and a final byte that is 1 for the last chunk and 0 otherwise.
 * That makes reordering, dropping, repeating or appending chunks fail authentication, including cutting the
 * blob short at a chunk boundary. The associated data of every chunk is the whole header plus [context].
 */
class AesGcmVaultCrypto(
  dataKey: ByteArray,
  private val random: SecureRandom = SecureRandom(),
  private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : VaultCrypto {
  private val key: ByteArray
  @Volatile private var wiped = false

  init {
    require(dataKey.size == KEY_BYTES) { "The vault data key must be $KEY_BYTES bytes" }
    require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) { "Unsupported chunk size" }
    key = dataKey.copyOf()
  }

  /** Overwrites the copy of the data key held here. The instance cannot be used afterwards. */
  fun wipe() {
    wiped = true
    key.fill(0)
  }

  override fun seal(plain: ByteArray, context: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(plain.size + HEADER_BYTES + TAG_BYTES)
    encrypt(ByteArrayInputStream(plain), out, context)
    return out.toByteArray()
  }

  override fun open(sealed: ByteArray, context: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(maxOf(0, sealed.size - HEADER_BYTES - TAG_BYTES))
    decrypt(ByteArrayInputStream(sealed), out, context)
    return out.toByteArray()
  }

  override fun encrypt(input: InputStream, output: OutputStream, context: ByteArray) {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val header = header(chunkSize, salt)
    output.write(header)
    val chunkKey = deriveKey(salt)
    val aad = aad(header, context)
    val buf = ByteArray(chunkSize + 1)
    var index = 0L
    // One byte of look-ahead tells which chunk is the last one without knowing the length up front.
    var have = readFully(input, buf, 0, buf.size)
    while (true) {
      val last = have <= chunkSize
      val take = if (last) have else chunkSize
      output.write(crypt(Cipher.ENCRYPT_MODE, chunkKey, index, last, aad, buf, 0, take))
      if (last) break
      buf[0] = buf[chunkSize]
      have = 1 + readFully(input, buf, 1, chunkSize)
      index++
      if (index > MAX_CHUNKS) throw VaultCryptoException("Too large to encrypt")
    }
  }

  override fun decrypt(input: InputStream, output: OutputStream, context: ByteArray) {
    val header = ByteArray(HEADER_BYTES)
    if (readFully(input, header, 0, header.size) != header.size) throw VaultFormatException("Not a vault file")
    val buf = ByteBuffer.wrap(header)
    val magic = ByteArray(MAGIC.size).also(buf::get)
    if (!magic.contentEquals(MAGIC)) throw VaultFormatException("Not a vault file")
    val version = buf.get().toInt()
    if (version != VERSION) throw VaultFormatException("Unsupported vault file version $version")
    if (buf.get().toInt() != ALGORITHM_AES_GCM_CHUNKED) throw VaultFormatException("Unsupported vault algorithm")
    val size = buf.int
    if (size !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) throw VaultFormatException("Unsupported chunk size")
    val salt = ByteArray(SALT_BYTES).also(buf::get)

    val chunkKey = deriveKey(salt)
    val aad = aad(header, context)
    val unit = size + TAG_BYTES
    val data = ByteArray(unit + 1)
    var index = 0L
    var have = readFully(input, data, 0, data.size)
    while (true) {
      val last = have <= unit
      val take = if (last) have else unit
      if (take < TAG_BYTES) throw VaultTamperedException()
      output.write(crypt(Cipher.DECRYPT_MODE, chunkKey, index, last, aad, data, 0, take))
      if (last) break
      data[0] = data[unit]
      have = 1 + readFully(input, data, 1, unit)
      index++
      if (index > MAX_CHUNKS) throw VaultTamperedException()
    }
  }

  private fun crypt(mode: Int, chunkKey: ByteArray, index: Long, last: Boolean, aad: ByteArray, src: ByteArray, off: Int, len: Int): ByteArray {
    try {
      val nonce = ByteArray(NONCE_BYTES)
      ByteBuffer.wrap(nonce, 7, 4).putInt(index.toInt())
      nonce[NONCE_BYTES - 1] = if (last) 1 else 0
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(mode, SecretKeySpec(chunkKey, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
      cipher.updateAAD(aad)
      return cipher.doFinal(src, off, len)
    } catch (e: GeneralSecurityException) {
      // AEADBadTagException is the usual one; every other failure of a decrypt is treated the same way.
      if (mode == Cipher.DECRYPT_MODE) throw VaultTamperedException(e)
      throw VaultCryptoException("Encryption failed", e)
    }
  }

  /** HKDF-SHA256 (RFC 5869), one output block: a fresh 256-bit key for this blob. */
  private fun deriveKey(salt: ByteArray): ByteArray {
    val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
    check(!wiped) { "The vault is locked" }
    val prk = extract.doFinal(key)
    val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
    expand.update(INFO)
    expand.update(1)
    prk.fill(0)
    return expand.doFinal()
  }

  companion object {
    val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    const val VERSION = 1
    const val ALGORITHM_AES_GCM_CHUNKED = 1
    const val KEY_BYTES = 32
    const val SALT_BYTES = 32
    const val TAG_BYTES = 16
    const val NONCE_BYTES = 12
    const val HEADER_BYTES = 4 + 1 + 1 + 4 + SALT_BYTES
    const val DEFAULT_CHUNK_SIZE = 64 * 1024
    const val MIN_CHUNK_SIZE = 16
    const val MAX_CHUNK_SIZE = 1024 * 1024
    private const val MAX_CHUNKS = 0x7FFF_FFFFL
    private val INFO = "samvad-vault-v1".toByteArray(Charsets.US_ASCII)

    /** Bytes a sealed blob adds to its plaintext, for a plaintext of [plainBytes] bytes. */
    fun overhead(plainBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long {
      val chunks = maxOf(1L, (plainBytes + chunkSize - 1) / chunkSize)
      return HEADER_BYTES + chunks * TAG_BYTES
    }

    private fun header(chunkSize: Int, salt: ByteArray): ByteArray =
      ByteBuffer.allocate(HEADER_BYTES)
        .put(MAGIC).put(VERSION.toByte()).put(ALGORITHM_AES_GCM_CHUNKED.toByte())
        .putInt(chunkSize).put(salt).array()

    private fun aad(header: ByteArray, context: ByteArray): ByteArray =
      ByteBuffer.allocate(header.size + 4 + context.size).put(header).putInt(context.size).put(context).array()

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
      var read = 0
      while (read < len) {
        val n = input.read(buf, off + read, len - read)
        if (n < 0) break
        read += n
      }
      return read
    }
  }
}
