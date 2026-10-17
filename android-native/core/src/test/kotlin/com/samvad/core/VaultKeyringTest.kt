package com.samvad.core

import com.samvad.core.vault.AesGcmVaultCrypto
import com.samvad.core.vault.KeyWrapper
import com.samvad.core.vault.SecureDelete
import com.samvad.core.vault.VaultAccess
import com.samvad.core.vault.VaultContexts
import com.samvad.core.vault.VaultFormatException
import com.samvad.core.vault.VaultKeyUnavailableException
import com.samvad.core.vault.VaultKeyring
import com.samvad.core.vault.VaultMeta
import com.samvad.core.vault.VaultMetaCodec
import com.samvad.core.vault.WrappedKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A software stand-in for the Android Keystore wrapper: AES-GCM with a key it holds itself. */
private class SoftwareWrapper(private val wrapKey: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : KeyWrapper {
  override fun wrap(plain: ByteArray, context: ByteArray): ByteArray {
    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
    c.updateAAD(context)
    return iv + c.doFinal(plain)
  }

  override fun unwrap(wrapped: ByteArray, context: ByteArray): ByteArray = try {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, wrapped.copyOf(12)))
    c.updateAAD(context)
    c.doFinal(wrapped, 12, wrapped.size - 12)
  } catch (e: GeneralSecurityException) {
    throw VaultKeyUnavailableException("cannot unwrap", e)
  }
}

private class MemoryStore : WrappedKeyStore {
  var bytes: ByteArray? = null
  override fun read() = bytes?.copyOf()
  override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf() }
  override fun clear() { bytes = null }
}

class VaultKeyringTest {
  @Test fun `a key is created once and then loaded again`() {
    val store = MemoryStore()
    val ring = VaultKeyring(SoftwareWrapper(), store)
    assertFalse(ring.exists())
    val first = ring.load(createIfMissing = true)
    assertEquals(32, first.size)
    assertTrue(ring.exists())
    assertArrayEquals(first, ring.load(createIfMissing = false))
  }

  @Test fun `the stored key is wrapped, never the plain key`() {
    val store = MemoryStore()
    val key = VaultKeyring(SoftwareWrapper(), store).load(true)
    val stored = store.bytes!!
    assertTrue(stored.size > key.size)
    assertTrue(indexOf(stored, key) < 0)
  }

  @Test fun `two vaults get different keys`() {
    val a = VaultKeyring(SoftwareWrapper(), MemoryStore()).load(true)
    val b = VaultKeyring(SoftwareWrapper(), MemoryStore()).load(true)
    assertNotEquals(a.toList(), b.toList())
  }

  @Test fun `an existing vault never silently gets a new key`() {
    val ring = VaultKeyring(SoftwareWrapper(), MemoryStore())
    try {
      ring.load(createIfMissing = false)
      fail("created a key when told not to")
    } catch (_: VaultKeyUnavailableException) {
    }
  }

  @Test fun `a lost wrapping key is reported, not papered over`() {
    val store = MemoryStore()
    VaultKeyring(SoftwareWrapper(), store).load(true)
    val replaced = VaultKeyring(SoftwareWrapper(), store) // new Keystore key, same stored blob
    try {
      replaced.load(createIfMissing = true)
      fail("loaded a key it cannot unwrap")
    } catch (_: VaultKeyUnavailableException) {
    }
    assertTrue(store.bytes != null) // nothing was overwritten
  }

  @Test fun `a damaged or unknown stored key is reported`() {
    val store = MemoryStore()
    val wrapper = SoftwareWrapper()
    val ring = VaultKeyring(wrapper, store)
    ring.load(true)
    store.bytes = store.bytes!!.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    try { ring.load(true); fail() } catch (_: VaultKeyUnavailableException) { }
    store.bytes = byteArrayOf(9, 1, 2, 3)
    try { ring.load(true); fail() } catch (_: VaultKeyUnavailableException) { }
    store.bytes = ByteArray(0)
    try { ring.load(true); fail() } catch (_: VaultKeyUnavailableException) { }
  }

  @Test fun `destroying the key makes old data unreadable for good`() {
    val store = MemoryStore()
    val wrapper = SoftwareWrapper()
    val ring = VaultKeyring(wrapper, store)
    val ctx = VaultContexts.file("x")
    val sealed = AesGcmVaultCrypto(ring.load(true)).seal("secret".toByteArray(), ctx)
    ring.destroy()
    assertFalse(ring.exists())
    val newKey = ring.load(true)
    try {
      AesGcmVaultCrypto(newKey).open(sealed, ctx)
      fail("old data opened with a new key")
    } catch (_: java.io.IOException) {
    }
  }

  @Test fun `data sealed with the loaded key opens after a reload`() {
    val store = MemoryStore()
    val wrapper = SoftwareWrapper()
    val ctx = VaultContexts.meta("m")
    val sealed = AesGcmVaultCrypto(VaultKeyring(wrapper, store).load(true)).seal("hello".toByteArray(), ctx)
    val reopened = AesGcmVaultCrypto(VaultKeyring(wrapper, store).load(false))
    assertEquals("hello", String(reopened.open(sealed, ctx)))
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }
}

class VaultAccessTest {
  @Test fun `locked until authenticated`() {
    val a = VaultAccess()
    assertFalse(a.isOpen(0))
    assertFalse(a.entered(0))
    a.unlocked()
    assertTrue(a.isOpen(1))
  }

  @Test fun `stays open while the person is inside, however long`() {
    val a = VaultAccess()
    a.unlocked()
    assertTrue(a.isOpen(10 * 60_000L))
  }

  @Test fun `coming back within the minute needs no new authentication`() {
    val a = VaultAccess()
    a.unlocked()
    a.left(1_000)
    assertTrue(a.entered(1_000 + 59_999))
    // Being inside again clears the timer: a long stay is fine and the next absence starts fresh.
    assertTrue(a.isOpen(1_000 + 10 * 60_000L))
  }

  @Test fun `coming back after the minute locks it`() {
    val a = VaultAccess()
    a.unlocked()
    a.left(1_000)
    assertFalse(a.entered(1_000 + 60_000))
    assertFalse(a.isOpen(1_000 + 60_001))
    a.unlocked()
    assertTrue(a.isOpen(0 + 1_000 + 70_000))
  }

  @Test fun `lock now locks at once and a clock that runs backwards also locks`() {
    val a = VaultAccess()
    a.unlocked()
    a.lock()
    assertFalse(a.isOpen(1))
    a.unlocked()
    a.left(5_000)
    assertFalse(a.isOpen(4_000))
  }

  @Test fun `leaving twice does not extend the grace`() {
    val a = VaultAccess()
    a.unlocked()
    a.left(0)
    a.left(50_000)
    assertFalse(a.isOpen(60_000))
  }

  @Test fun `leaving while locked does nothing`() {
    val a = VaultAccess()
    a.left(0)
    assertFalse(a.isOpen(1))
  }
}

class VaultMetaTest {
  @Test fun `metadata round trips, including Hindi`() {
    val meta = VaultMeta("आधार कार्ड", "image/jpeg")
    assertEquals(meta, VaultMetaCodec.decode(VaultMetaCodec.encode(meta)))
  }

  @Test fun `unknown versions and garbage are format errors`() {
    val ok = VaultMetaCodec.encode(VaultMeta("a", "b"))
    try { VaultMetaCodec.decode(ok.copyOf().also { it[0] = 7 }); fail() } catch (_: VaultFormatException) { }
    try { VaultMetaCodec.decode(byteArrayOf(1, 0)); fail() } catch (_: VaultFormatException) { }
    try { VaultMetaCodec.decode(ByteArray(0)); fail() } catch (_: VaultFormatException) { }
  }
}

class SecureDeleteTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test fun `contents are overwritten before the file is removed`() {
    val f = tmp.newFile("blob")
    val original = ByteArray(200_000) { (it % 251).toByte() }
    f.writeBytes(original)
    assertTrue(SecureDelete.overwrite(f))
    val after = f.readBytes()
    assertEquals(original.size, after.size)
    assertFalse(original.contentEquals(after))
    // Random fill: not even a run of the original pattern survives.
    var same = 0
    for (i in original.indices) if (original[i] == after[i]) same++
    assertTrue("too many equal bytes: $same", same < original.size / 20)
  }

  @Test fun `overwriteAndDelete removes the file and tolerates a missing one`() {
    val f = tmp.newFile("blob2")
    f.writeBytes(ByteArray(10))
    assertTrue(SecureDelete.overwriteAndDelete(f))
    assertFalse(f.exists())
    assertTrue(SecureDelete.overwriteAndDelete(f))
  }

  @Test fun `an empty file and a directory are handled`() {
    val empty = tmp.newFile("empty")
    assertTrue(SecureDelete.overwriteAndDelete(empty))
    assertFalse(empty.exists())
    assertFalse(SecureDelete.overwrite(tmp.newFolder("dir")))
    assertNull(null)
  }
}
