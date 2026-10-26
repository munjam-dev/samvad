package com.samvad.core.e2ee

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AttachmentException(message: String) : Exception(message)

/** A file encrypted for upload. The key and [digestHex] travel only inside the encrypted message envelope. */
class EncryptedAttachment(val cipherBytes: ByteArray, val key: ByteArray, val digestHex: String)

/**
 * Attachments are encrypted on the device with a random AES-256-GCM key (one per file) before upload. The stored
 * file is IV (12 bytes) + ciphertext + tag. The SHA-256 digest of the stored bytes is checked before decryption,
 * so a swapped or truncated file is rejected with a clear reason even before the GCM tag check.
 */
object AttachmentCrypto {
  const val KEY_BYTES = 32
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private val AAD = "samvad-attachment-v1".toByteArray(Charsets.US_ASCII)
  private val random = SecureRandom()

  /** Bytes added to a file by encryption (IV and tag). */
  const val OVERHEAD = IV_BYTES + TAG_BITS / 8

  fun encrypt(plain: ByteArray): EncryptedAttachment {
    val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
    val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(AAD)
    val body = cipher.doFinal(plain)
    val out = ByteArray(IV_BYTES + body.size)
    System.arraycopy(iv, 0, out, 0, IV_BYTES)
    System.arraycopy(body, 0, out, IV_BYTES, body.size)
    return EncryptedAttachment(out, key, digestOf(out))
  }

  /** Throws [AttachmentException] when the digest, the key or the data do not match. */
  fun decrypt(stored: ByteArray, key: ByteArray, digestHex: String): ByteArray {
    if (key.size != KEY_BYTES) throw AttachmentException("bad key")
    if (stored.size < IV_BYTES + TAG_BITS / 8) throw AttachmentException("too short")
    val actual = MessageDigest.getInstance("SHA-256").digest(stored)
    val expected = fromHex(digestHex) ?: throw AttachmentException("bad digest")
    if (!MessageDigest.isEqual(actual, expected)) throw AttachmentException("digest mismatch")
    return try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES))
      cipher.updateAAD(AAD)
      cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    } catch (e: AEADBadTagException) {
      throw AttachmentException("authentication failed")
    } catch (e: java.security.GeneralSecurityException) {
      throw AttachmentException("decryption failed")
    }
  }

  fun digestOf(bytes: ByteArray): String = Envelope.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  fun fromHex(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
      val hi = Character.digit(hex[2 * i], 16)
      val lo = Character.digit(hex[2 * i + 1], 16)
      if (hi < 0 || lo < 0) return null
      out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
  }
}
