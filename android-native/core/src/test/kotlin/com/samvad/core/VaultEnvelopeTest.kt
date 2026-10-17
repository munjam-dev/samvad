package com.samvad.core

import com.samvad.core.vault.AesGcmVaultCrypto
import com.samvad.core.vault.VaultContexts
import com.samvad.core.vault.VaultCryptoException
import com.samvad.core.vault.VaultFormatException
import com.samvad.core.vault.VaultTamperedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random

/** Real AES-256-GCM with software keys: no Android needed. */
class VaultEnvelopeTest {
  private val rnd = Random(42)
  private fun key(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
  private fun bytes(n: Int): ByteArray = ByteArray(n).also(rnd::nextBytes)
  private val ctx = VaultContexts.file("item-1")

  /** Small chunks so that multi-chunk behaviour is exercised without megabytes of test data. */
  private fun crypto(k: ByteArray = key(), chunk: Int = 64) = AesGcmVaultCrypto(k, chunkSize = chunk)

  private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
    try {
      block()
      fail("Expected ${T::class.simpleName}")
    } catch (e: Throwable) {
      if (e !is T) throw e
    }
  }

  @Test fun `round trip for empty, tiny, exact-chunk and multi-chunk data`() {
    val c = crypto()
    for (size in listOf(0, 1, 15, 16, 63, 64, 65, 127, 128, 129, 1000, 64 * 50)) {
      val plain = bytes(size)
      val sealed = c.seal(plain, ctx)
      assertEquals("size $size", size + AesGcmVaultCrypto.overhead(size.toLong(), 64), sealed.size.toLong())
      assertArrayEquals("size $size", plain, c.open(sealed, ctx))
    }
  }

  @Test fun `default chunk size round trip through streams`() {
    val c = AesGcmVaultCrypto(key())
    val plain = bytes(64 * 1024 * 2 + 123)
    val sealed = ByteArrayOutputStream()
    c.encrypt(ByteArrayInputStream(plain), sealed, ctx)
    val back = ByteArrayOutputStream()
    c.decrypt(ByteArrayInputStream(sealed.toByteArray()), back, ctx)
    assertArrayEquals(plain, back.toByteArray())
  }

  @Test fun `streams that return short reads are handled`() {
    val c = crypto()
    val plain = bytes(500)
    val trickle = object : java.io.InputStream() {
      private val src = ByteArrayInputStream(plain)
      override fun read(): Int = src.read()
      override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, minOf(len, 7))
    }
    val sealed = ByteArrayOutputStream()
    c.encrypt(trickle, sealed, ctx)
    assertArrayEquals(plain, c.open(sealed.toByteArray(), ctx))
  }

  @Test fun `header is versioned and carries the magic`() {
    val sealed = crypto().seal(bytes(10), ctx)
    assertArrayEquals(AesGcmVaultCrypto.MAGIC, sealed.copyOfRange(0, 4))
    assertEquals(AesGcmVaultCrypto.VERSION, sealed[4].toInt())
    assertEquals(AesGcmVaultCrypto.ALGORITHM_AES_GCM_CHUNKED, sealed[5].toInt())
  }

  @Test fun `the same plaintext never produces the same output twice`() {
    val c = crypto()
    val plain = bytes(200)
    val seen = HashSet<String>()
    val salts = HashSet<String>()
    repeat(50) {
      val sealed = c.seal(plain, ctx)
      assertTrue(seen.add(sealed.joinToString(",")))
      assertTrue(salts.add(sealed.copyOfRange(10, 42).joinToString(",")))
    }
  }

  @Test fun `ciphertext does not contain the plaintext`() {
    val plain = "PASSPORT-NUMBER-1234567".repeat(10).toByteArray()
    val sealed = crypto().seal(plain, ctx)
    assertEquals(-1, indexOf(sealed, "PASSPORT".toByteArray()))
  }

  @Test fun `wrong key is rejected as tampering`() {
    val sealed = crypto().seal(bytes(300), ctx)
    assertFails<VaultTamperedException> { crypto().open(sealed, ctx) }
  }

  @Test fun `wrong context is rejected, so a blob cannot be moved to another item`() {
    val k = key()
    val sealed = crypto(k).seal(bytes(300), VaultContexts.file("a"))
    assertFails<VaultTamperedException> { crypto(k).open(sealed, VaultContexts.file("b")) }
    assertFails<VaultTamperedException> { crypto(k).open(sealed, VaultContexts.thumbnail("a")) }
    assertFails<VaultTamperedException> { crypto(k).open(sealed, ByteArray(0)) }
  }

  @Test fun `flipping any single byte is detected`() {
    val k = key()
    val c = crypto(k)
    val sealed = c.seal(bytes(150), ctx) // header 42 + three chunks
    for (i in sealed.indices) {
      val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
      try {
        c.open(bad, ctx)
        fail("byte $i was changed without being noticed")
      } catch (_: VaultCryptoException) {
        // Either a format error (magic, version, algorithm, chunk size) or an authentication failure.
      }
    }
  }

  @Test fun `truncation is detected, also at a chunk boundary`() {
    val c = crypto()
    val sealed = c.seal(bytes(64 * 4), ctx)
    val unit = 64 + AesGcmVaultCrypto.TAG_BYTES
    // Drop the last chunk, leaving only whole chunks behind.
    val cut = sealed.copyOf(sealed.size - unit)
    assertFails<VaultTamperedException> { c.open(cut, ctx) }
    for (n in listOf(AesGcmVaultCrypto.HEADER_BYTES, AesGcmVaultCrypto.HEADER_BYTES + 5, sealed.size - 1)) {
      assertFails<VaultCryptoException> { c.open(sealed.copyOf(n), ctx) }
    }
    assertFails<VaultFormatException> { c.open(sealed.copyOf(10), ctx) }
    assertFails<VaultFormatException> { c.open(ByteArray(0), ctx) }
  }

  @Test fun `appended data, reordered chunks and repeated chunks are detected`() {
    val c = crypto()
    val sealed = c.seal(bytes(64 * 3), ctx)
    val head = AesGcmVaultCrypto.HEADER_BYTES
    val unit = 64 + AesGcmVaultCrypto.TAG_BYTES
    assertFails<VaultTamperedException> { c.open(sealed + bytes(20), ctx) }
    assertFails<VaultTamperedException> { c.open(sealed + sealed.copyOfRange(head, head + unit), ctx) }
    val chunks = (0 until 4).map { sealed.copyOfRange(head + it * unit, minOf(sealed.size, head + (it + 1) * unit)) }
    val swapped = sealed.copyOfRange(0, head) + chunks[1] + chunks[0] + chunks[2] + chunks[3]
    assertFails<VaultTamperedException> { c.open(swapped, ctx) }
    val repeated = sealed.copyOfRange(0, head) + chunks[0] + chunks[0] + chunks[2] + chunks[3]
    assertFails<VaultTamperedException> { c.open(repeated, ctx) }
  }

  @Test fun `a chunk from one blob cannot be spliced into another with the same key`() {
    val k = key()
    val c = crypto(k)
    val a = c.seal(bytes(64 * 2), ctx)
    val b = c.seal(bytes(64 * 2), ctx)
    val head = AesGcmVaultCrypto.HEADER_BYTES
    val unit = 64 + AesGcmVaultCrypto.TAG_BYTES
    val spliced = a.copyOfRange(0, head + unit) + b.copyOfRange(head + unit, b.size)
    assertFails<VaultTamperedException> { c.open(spliced, ctx) }
  }

  @Test fun `unknown versions, algorithms and bad chunk sizes are format errors`() {
    val c = crypto()
    val sealed = c.seal(bytes(10), ctx)
    assertFails<VaultFormatException> { c.open(sealed.copyOf().also { it[4] = 2 }, ctx) }
    assertFails<VaultFormatException> { c.open(sealed.copyOf().also { it[5] = 9 }, ctx) }
    assertFails<VaultFormatException> { c.open(sealed.copyOf().also { it[0] = 'X'.code.toByte() }, ctx) }
    // Chunk size 0x7FFFFFFF would make a reader allocate 2 GB: refused before any allocation.
    val huge = sealed.copyOf().also { it[6] = 0x7F; it[7] = -1; it[8] = -1; it[9] = -1 }
    assertFails<VaultFormatException> { c.open(huge, ctx) }
    assertFails<VaultFormatException> { c.open("not a vault file at all, just text".toByteArray(), ctx) }
  }

  @Test fun `key length is enforced and the key is copied`() {
    try {
      AesGcmVaultCrypto(ByteArray(16))
      fail("16-byte key accepted")
    } catch (_: IllegalArgumentException) {
    }
    val k = key()
    val c = crypto(k)
    val sealed = c.seal(bytes(30), ctx)
    k.fill(0) // the caller wiping its array must not break the instance
    c.open(sealed, ctx)
  }

  @Test fun `a wiped instance refuses to work and never encrypts with a zero key`() {
    val c = crypto()
    val sealed = c.seal(bytes(30), ctx)
    c.wipe()
    try {
      c.seal(bytes(5), ctx)
      fail("sealed after wipe")
    } catch (_: IllegalStateException) {
    }
    try {
      c.open(sealed, ctx)
      fail("opened after wipe")
    } catch (_: IllegalStateException) {
    }
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }
}
