package com.samvad.core

import com.samvad.core.vault.AesGcmVaultCrypto
import com.samvad.core.vault.ArchiveBlob
import com.samvad.core.vault.ArchiveHeader
import com.samvad.core.vault.ArchiveManifest
import com.samvad.core.vault.ArchiveSink
import com.samvad.core.vault.ArchivedItem
import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.BackupKdf
import com.samvad.core.vault.BackupPassphrase
import com.samvad.core.vault.DriveRules
import com.samvad.core.vault.RecoveryCode
import com.samvad.core.vault.RecoverySecretFormatException
import com.samvad.core.vault.RemoteBackupFile
import com.samvad.core.vault.RemoteBackups
import com.samvad.core.vault.RemoteQuota
import com.samvad.core.vault.VaultArchive
import com.samvad.core.vault.VaultBackupRemote
import com.samvad.core.vault.VaultBackupTooNewException
import com.samvad.core.vault.VaultContexts
import com.samvad.core.vault.VaultCryptoException
import com.samvad.core.vault.VaultFingerprint
import com.samvad.core.vault.VaultFormatException
import com.samvad.core.vault.VaultReseal
import com.samvad.core.vault.VaultTamperedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Random

class VaultBackupTest {
  private val rnd = Random(7)
  private fun bytes(n: Int) = ByteArray(n).also(rnd::nextBytes)

  private val idA = "11111111-1111-4111-8111-111111111111"
  private val idB = "22222222-2222-4222-8222-222222222222"

  private class Fixture(
    val header: ArchiveHeader,
    val code: String,
    val key: ByteArray,
    val dataKey: ByteArray,
    val items: List<ArchivedItem>,
    val blobData: Map<String, ByteArray>,
  ) {
    val sealedDataKey: ByteArray = VaultArchive.sealDataKey(header, key, dataKey)
    val manifest = ArchiveManifest(1_700_000_000_000L, 7, items.size)
    fun blobs() = blobData.map { (name, data) -> ArchiveBlob(name, data.size.toLong()) { ByteArrayInputStream(data) } }
    fun write(): ByteArray {
      val out = ByteArrayOutputStream()
      VaultArchive.write(out, header, key, sealedDataKey, manifest, items, blobs())
      return out.toByteArray()
    }
  }

  private fun fixture(big: Int = 300): Fixture {
    val recovery = RecoveryCode.generate()
    val header = ArchiveHeader.newRecoveryKey()
    val key = header.deriveKey(RecoveryCode.format(recovery))
    val items = listOf(
      ArchivedItem(idA, "DOCUMENT", 5, 1, 2, true, bytes(40)),
      ArchivedItem(idB, "NOTE", 0, 3, 4, false, bytes(33)),
    )
    val blobs = linkedMapOf(
      "$idA.svl" to bytes(big),
      "$idA.thumb" to bytes(50),
      "$idB.svl" to ByteArray(0),
    )
    return Fixture(header, RecoveryCode.format(recovery), key, bytes(32), items, blobs)
  }

  private class Collector : ArchiveSink {
    var dataKey: ByteArray? = null
    var manifest: ArchiveManifest? = null
    val items = mutableListOf<ArchivedItem>()
    val blobs = linkedMapOf<String, ByteArray>()
    override fun onDataKey(key: ByteArray) { dataKey = key.copyOf() }
    override fun onManifest(manifest: ArchiveManifest) { this.manifest = manifest }
    override fun onItem(item: ArchivedItem) { items += item }
    override fun openBlob(name: String): OutputStream = object : ByteArrayOutputStream() {
      override fun close() { blobs[name] = toByteArray() }
    }
  }

  private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
    try {
      block()
      fail("Expected ${T::class.simpleName}")
    } catch (e: Throwable) {
      if (e !is T) throw e
    }
  }

  // ---- recovery code ----

  @Test fun `recovery code round trips and has 13 groups of 4`() {
    repeat(20) {
      val key = RecoveryCode.generate()
      val text = RecoveryCode.format(key)
      assertEquals(13, text.split(" ").size)
      assertTrue(text.split(" ").all { it.length == 4 })
      assertArrayEquals(key, RecoveryCode.parse(text))
    }
  }

  @Test fun `recovery code parsing ignores case, spaces, dashes and look-alike letters`() {
    val key = RecoveryCode.generate()
    val plain = RecoveryCode.encode(key)
    assertArrayEquals(key, RecoveryCode.parse(plain.lowercase()))
    assertArrayEquals(key, RecoveryCode.parse(RecoveryCode.groups(key).joinToString("-")))
    assertArrayEquals(key, RecoveryCode.parse("  " + RecoveryCode.format(key).replace('1', 'l').replace('0', 'O') + " "))
  }

  @Test fun `bad recovery codes are refused`() {
    val good = RecoveryCode.encode(RecoveryCode.generate())
    assertNull(RecoveryCode.parse(""))
    assertNull(RecoveryCode.parse(good.dropLast(1)))
    assertNull(RecoveryCode.parse(good + "A"))
    assertNull(RecoveryCode.parse(good.dropLast(1) + "U"))
    assertNull(RecoveryCode.parse(good.dropLast(1) + "!"))
    // The last character holds 4 spare bits that must be zero.
    assertNull(RecoveryCode.parse(good.dropLast(1) + "Z"))
    assertNull(RecoveryCode.parse("0".repeat(51) + "1"))
  }

  @Test fun `challenge picks distinct sorted groups and checking is exact`() {
    val key = RecoveryCode.generate()
    repeat(50) {
      val c = RecoveryCode.challenge(3)
      assertEquals(3, c.size)
      assertEquals(c.sorted(), c)
      assertEquals(3, c.toSet().size)
      assertTrue(c.all { it in 0 until RecoveryCode.GROUPS })
    }
    val groups = RecoveryCode.groups(key)
    assertTrue(RecoveryCode.checkGroups(key, mapOf(0 to groups[0].lowercase(), 5 to " ${groups[5]} ", 12 to groups[12])))
    assertFalse(RecoveryCode.checkGroups(key, mapOf(0 to groups[1])))
    assertFalse(RecoveryCode.checkGroups(key, emptyMap()))
    assertFalse(RecoveryCode.checkGroups(key, mapOf(13 to groups[0])))
  }

  @Test fun `passphrase needs 12 characters`() {
    assertFalse(BackupPassphrase.isAcceptable("short one"))
    assertFalse(BackupPassphrase.isAcceptable("            "))
    assertTrue(BackupPassphrase.isAcceptable("correct horse battery"))
    assertTrue(BackupPassphrase.isAcceptable("सुरक्षित पासवर्ड वाक्य"))
  }

  // ---- archive ----

  @Test fun `archive round trip returns everything and planned size is exact`() {
    val f = fixture(big = 200_000)
    val archive = f.write()
    assertEquals(VaultArchive.plannedSize(f.header, f.sealedDataKey, f.manifest, f.items, f.blobs()), archive.size.toLong())
    val sink = Collector()
    val manifest = VaultArchive.read(ByteArrayInputStream(archive), f.code, sink)
    assertEquals(2, manifest.itemCount)
    assertEquals(7, manifest.appVersion)
    assertArrayEquals(f.dataKey, sink.dataKey)
    assertEquals(listOf(idA, idB), sink.items.map { it.id })
    assertEquals("DOCUMENT", sink.items[0].type)
    assertTrue(sink.items[0].hasThumb)
    assertArrayEquals(f.items[0].meta, sink.items[0].meta)
    assertEquals(f.blobData.keys.toList(), sink.blobs.keys.toList())
    for ((name, data) in f.blobData) assertArrayEquals(data, sink.blobs[name])
  }

  @Test fun `empty vault archive round trips`() {
    val header = ArchiveHeader.newRecoveryKey()
    val code = RecoveryCode.format(RecoveryCode.generate())
    val key = header.deriveKey(code)
    val sealed = VaultArchive.sealDataKey(header, key, bytes(32))
    val manifest = ArchiveManifest(1, 1, 0)
    val out = ByteArrayOutputStream()
    VaultArchive.write(out, header, key, sealed, manifest, emptyList(), emptyList())
    val sink = Collector()
    VaultArchive.read(ByteArrayInputStream(out.toByteArray()), code, sink)
    assertTrue(sink.items.isEmpty())
  }

  @Test fun `cached sealed data key opens and is reused across archives`() {
    val f = fixture()
    assertArrayEquals(f.dataKey, VaultArchive.openSealedDataKey(f.header, f.key, f.sealedDataKey))
    val wrong = ByteArray(32) { 9 }
    assertFails<VaultTamperedException> { VaultArchive.openSealedDataKey(f.header, wrong, f.sealedDataKey) }
    val a = f.write()
    val b = f.write()
    assertNotEquals(a.toList(), b.toList()) // fresh salts every time
    for (archive in listOf(a, b)) {
      val sink = Collector()
      VaultArchive.read(ByteArrayInputStream(archive), f.code, sink)
      assertArrayEquals(f.dataKey, sink.dataKey)
    }
  }

  @Test fun `wrong recovery code fails the same way as tampering`() {
    val f = fixture()
    val archive = f.write()
    val other = RecoveryCode.format(RecoveryCode.generate())
    assertFails<VaultTamperedException> { VaultArchive.read(ByteArrayInputStream(archive), other, Collector()) }
    assertFails<RecoverySecretFormatException> { VaultArchive.read(ByteArrayInputStream(archive), "not a code", Collector()) }
    assertFails<RecoverySecretFormatException> { VaultArchive.read(ByteArrayInputStream(archive), "", Collector()) }
  }

  @Test fun `every single flipped byte is detected`() {
    val f = fixture(big = 100)
    val archive = f.write()
    for (i in archive.indices) {
      val copy = archive.copyOf()
      copy[i] = (copy[i].toInt() xor 0x01).toByte()
      try {
        VaultArchive.read(ByteArrayInputStream(copy), f.code, Collector())
        fail("Flipped byte at $i was not detected")
      } catch (e: VaultCryptoException) {
        // expected
      }
    }
  }

  @Test fun `every truncation is detected, including at record boundaries`() {
    val f = fixture(big = 100)
    val archive = f.write()
    for (n in archive.indices) {
      try {
        VaultArchive.read(ByteArrayInputStream(archive.copyOf(n)), f.code, Collector())
        fail("Archive cut to $n bytes was accepted")
      } catch (e: VaultCryptoException) {
        // expected
      }
    }
  }

  @Test fun `data after the end record is refused`() {
    val f = fixture()
    assertFails<VaultTamperedException> {
      VaultArchive.read(ByteArrayInputStream(f.write() + byteArrayOf(0)), f.code, Collector())
    }
  }

  @Test fun `a dropped middle record is detected`() {
    // Rebuild a valid-looking archive without the second item record by cutting its frame out.
    val f = fixture()
    val archive = f.write()
    val itemStart = ArchiveHeader.SIZE + f.sealedDataKey.size + manifestRecordSize(archive, f)
    val firstItemLen = frameSize(archive, itemStart)
    val dropped = archive.copyOfRange(0, itemStart) + archive.copyOfRange(itemStart + firstItemLen, archive.size)
    assertFails<VaultCryptoException> { VaultArchive.read(ByteArrayInputStream(dropped), f.code, Collector()) }
  }

  private fun manifestRecordSize(archive: ByteArray, f: Fixture) = frameSize(archive, ArchiveHeader.SIZE + f.sealedDataKey.size)

  private fun frameSize(archive: ByteArray, at: Int): Int {
    val nameLen = ((archive[at + 1].toInt() and 0xFF) shl 8) or (archive[at + 2].toInt() and 0xFF)
    val sealed = ByteBuffer.wrap(archive, at + 3 + nameLen, 8).long
    return (3 + nameLen + 8 + sealed).toInt()
  }

  @Test fun `swapping two records is detected`() {
    val f = fixture()
    val archive = f.write()
    val start = ArchiveHeader.SIZE + f.sealedDataKey.size + manifestRecordSize(archive, f)
    val len1 = frameSize(archive, start)
    val len2 = frameSize(archive, start + len1)
    val swapped = archive.copyOfRange(0, start) +
      archive.copyOfRange(start + len1, start + len1 + len2) +
      archive.copyOfRange(start, start + len1) +
      archive.copyOfRange(start + len1 + len2, archive.size)
    assertFails<VaultCryptoException> { VaultArchive.read(ByteArrayInputStream(swapped), f.code, Collector()) }
  }

  @Test fun `unknown and newer versions are refused`() {
    val f = fixture()
    val archive = f.write()
    val newer = archive.copyOf().also { it[4] = 2 }
    try {
      VaultArchive.read(ByteArrayInputStream(newer), f.code, Collector())
      fail()
    } catch (e: VaultBackupTooNewException) {
      assertEquals(2, e.version)
    }
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(archive.copyOf().also { it[4] = 0 }), f.code, Collector()) }
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(archive.copyOf().also { it[0] = 'X'.code.toByte() }), f.code, Collector()) }
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(archive.copyOf().also { it[5] = 9 }), f.code, Collector()) }
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(ByteArray(0)), f.code, Collector()) }
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream("hello, this is not a backup file at all".toByteArray()), f.code, Collector()) }
  }

  @Test fun `absurd sizes are refused without allocating`() {
    val f = fixture()
    val archive = f.write()
    val at = ArchiveHeader.SIZE + f.sealedDataKey.size // the manifest frame
    val huge = archive.copyOf()
    ByteBuffer.wrap(huge, at + 3, 8).putLong(Long.MAX_VALUE)
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(huge), f.code, Collector()) }
    val negative = archive.copyOf()
    ByteBuffer.wrap(negative, at + 3, 8).putLong(-1)
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(negative), f.code, Collector()) }
    val longName = archive.copyOf()
    longName[at + 1] = 0x7F
    assertFails<VaultCryptoException> { VaultArchive.read(ByteArrayInputStream(longName), f.code, Collector()) }
    // The first record (data key) has its own frame at the start of the body.
    val first = archive.copyOf()
    ByteBuffer.wrap(first, ArchiveHeader.SIZE + 3, 8).putLong(Int.MAX_VALUE.toLong() + 5)
    assertFails<VaultFormatException> { VaultArchive.read(ByteArrayInputStream(first), f.code, Collector()) }
  }

  @Test fun `a file that changes length while it is written aborts the backup`() {
    val f = fixture()
    val shorter = f.blobData.entries.first()
    val blobs = listOf(ArchiveBlob(shorter.key, shorter.value.size.toLong() + 10) { ByteArrayInputStream(shorter.value) })
    assertFails<VaultCryptoException> {
      VaultArchive.write(ByteArrayOutputStream(), f.header, f.key, f.sealedDataKey, f.manifest, f.items, blobs)
    }
    val longer = listOf(ArchiveBlob(shorter.key, shorter.value.size.toLong() - 10) { ByteArrayInputStream(shorter.value) })
    assertFails<VaultCryptoException> {
      VaultArchive.write(ByteArrayOutputStream(), f.header, f.key, f.sealedDataKey, f.manifest, f.items, longer)
    }
  }

  @Test fun `file names that could escape the folder are refused when writing`() {
    val f = fixture()
    for (name in listOf("../evil.svl", "$idA.svl/../x", "$idA.exe", "/etc/passwd", "other.svl")) {
      assertFails<IllegalArgumentException> {
        VaultArchive.write(ByteArrayOutputStream(), f.header, f.key, f.sealedDataKey, f.manifest, f.items, listOf(ArchiveBlob(name, 1) { ByteArrayInputStream(byteArrayOf(1)) }))
      }
    }
  }

  @Test fun `a failing sink aborts the read`() {
    val f = fixture()
    val sink = object : ArchiveSink by Collector() {
      override fun openBlob(name: String): OutputStream = throw java.io.IOException("disk full")
    }
    assertFails<java.io.IOException> { VaultArchive.read(ByteArrayInputStream(f.write()), f.code, sink) }
  }

  @Test fun `passphrase archives round trip and reject a wrong passphrase`() {
    val header = ArchiveHeader.newPassphrase()
    assertEquals(BackupKdf.PBKDF2_MIN_ITERATIONS, header.iterations)
    val pass = "correct horse battery staple"
    val key = header.deriveKey(pass)
    val sealed = VaultArchive.sealDataKey(header, key, bytes(32))
    val out = ByteArrayOutputStream()
    VaultArchive.write(out, header, key, sealed, ArchiveManifest(1, 1, 0), emptyList(), emptyList())
    VaultArchive.read(ByteArrayInputStream(out.toByteArray()), pass, Collector())
    assertFails<VaultTamperedException> {
      VaultArchive.read(ByteArrayInputStream(out.toByteArray()), "correct horse battery stapl", Collector())
    }
  }

  @Test fun `weak or absurd iteration counts in a header are refused`() {
    val h = ArchiveHeader.newPassphrase().encode()
    ByteBuffer.wrap(h, 6, 4).putInt(1000)
    assertFails<VaultFormatException> { ArchiveHeader.decode(h) }
    ByteBuffer.wrap(h, 6, 4).putInt(Int.MAX_VALUE)
    assertFails<VaultFormatException> { ArchiveHeader.decode(h) }
  }

  // ---- re-sealing ----

  @Test fun `reseal moves data between keys`() {
    val a = AesGcmVaultCrypto(ByteArray(32) { 1 }, chunkSize = 64)
    val b = AesGcmVaultCrypto(ByteArray(32) { 2 }, chunkSize = 64)
    val plain = bytes(1000)
    val sealedA = a.seal(plain, VaultContexts.file(idA))
    val out = ByteArrayOutputStream()
    VaultReseal.reseal(a, VaultContexts.file(idA), b, VaultContexts.file(idA), ByteArrayInputStream(sealedA), out)
    assertArrayEquals(plain, b.open(out.toByteArray(), VaultContexts.file(idA)))
  }

  @Test fun `reseal reports a damaged source`() {
    val a = AesGcmVaultCrypto(ByteArray(32) { 1 }, chunkSize = 64)
    val b = AesGcmVaultCrypto(ByteArray(32) { 2 }, chunkSize = 64)
    val sealed = a.seal(bytes(1000), VaultContexts.file(idA))
    sealed[sealed.size - 3] = (sealed[sealed.size - 3].toInt() xor 1).toByte()
    assertFails<VaultTamperedException> {
      VaultReseal.reseal(a, VaultContexts.file(idA), b, VaultContexts.file(idA), ByteArrayInputStream(sealed), ByteArrayOutputStream())
    }
    // Wrong source key.
    val good = a.seal(bytes(500), VaultContexts.file(idA))
    assertFails<VaultTamperedException> {
      VaultReseal.reseal(b, VaultContexts.file(idA), a, VaultContexts.file(idA), ByteArrayInputStream(good), ByteArrayOutputStream())
    }
  }

  @Test fun `reseal stops when the output fails`() {
    val a = AesGcmVaultCrypto(ByteArray(32) { 1 }, chunkSize = 64)
    val b = AesGcmVaultCrypto(ByteArray(32) { 2 }, chunkSize = 64)
    val sealed = a.seal(bytes(100_000), VaultContexts.file(idA))
    val broken = object : OutputStream() {
      override fun write(b: Int) = throw java.io.IOException("disk full")
    }
    assertFails<java.io.IOException> {
      VaultReseal.reseal(a, VaultContexts.file(idA), b, VaultContexts.file(idA), ByteArrayInputStream(sealed), broken)
    }
  }

  // ---- Drive rules ----

  @Test fun `drive errors map to what the person is told`() {
    assertEquals(BackupFailure.AUTH_REVOKED, DriveRules.classify(401, null))
    assertEquals(BackupFailure.QUOTA_FULL, DriveRules.classify(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}"""))
    assertEquals(BackupFailure.TRANSIENT, DriveRules.classify(403, """{"reason":"userRateLimitExceeded"}"""))
    assertEquals(BackupFailure.NOT_CONFIGURED, DriveRules.classify(403, """{"reason":"accessNotConfigured"}"""))
    assertEquals(BackupFailure.AUTH_REVOKED, DriveRules.classify(403, """{"reason":"insufficientPermissions"}"""))
    assertEquals(BackupFailure.TRANSIENT, DriveRules.classify(503, null))
    assertEquals(BackupFailure.TRANSIENT, DriveRules.classify(429, null))
    assertEquals(BackupFailure.NO_BACKUP, DriveRules.classify(404, null))
    assertEquals(BackupFailure.FAILED, DriveRules.classify(400, "bad"))
  }

  @Test fun `resumable upload arithmetic`() {
    assertEquals("bytes 0-99/250", DriveRules.contentRange(0, 100, 250))
    assertEquals("bytes 200-249/250", DriveRules.contentRange(200, 250, 250))
    assertEquals("bytes */250", DriveRules.statusQueryRange(250))
    assertEquals(0L, DriveRules.nextOffset(null))
    assertEquals(0L, DriveRules.nextOffset(""))
    assertEquals(100L, DriveRules.nextOffset("bytes=0-99"))
    assertEquals(0L, DriveRules.nextOffset("garbage"))
    assertEquals(0, DriveRules.UPLOAD_CHUNK_BYTES % (256 * 1024))
    assertTrue(DriveRules.backoffMillis(1) < DriveRules.backoffMillis(3))
    assertEquals(60_000L, DriveRules.backoffMillis(20))
    assertFails<IllegalArgumentException> { DriveRules.contentRange(5, 5, 10) }
  }

  @Test fun `the only scope is the hidden app data folder`() {
    assertEquals("https://www.googleapis.com/auth/drive.appdata", DriveRules.SCOPE_APPDATA)
  }

  @Test fun `quota check`() {
    assertTrue(RemoteQuota(null, 5).hasRoomFor(Long.MAX_VALUE / 2))
    assertTrue(RemoteQuota(100, 50).hasRoomFor(50))
    assertFalse(RemoteQuota(100, 50).hasRoomFor(51))
  }

  // ---- fingerprint and remote replace ----

  @Test fun `fingerprint changes only when the vault changes`() {
    val f = fixture()
    val a = VaultFingerprint.of(f.items)
    assertEquals(a, VaultFingerprint.of(f.items.reversed()))
    val changed = listOf(f.items[0], ArchivedItem(idB, "NOTE", 0, 3, 99, false, ByteArray(1)))
    assertNotEquals(a, VaultFingerprint.of(changed))
    assertNotEquals(a, VaultFingerprint.of(f.items.take(1)))
  }

  private class FakeRemote : VaultBackupRemote {
    val files = mutableListOf<RemoteBackupFile>()
    var failUpload: BackupException? = null
    var failDelete = false
    private var next = 1
    override suspend fun list() = files.toList()
    override suspend fun upload(source: File, name: String, onProgress: (Long, Long) -> Unit): RemoteBackupFile {
      failUpload?.let { throw it }
      return RemoteBackupFile("f${next++}", name, source.length(), next * 1000L).also { files += it }
    }
    override suspend fun download(file: RemoteBackupFile, target: File, onProgress: (Long, Long) -> Unit) = Unit
    override suspend fun delete(id: String) {
      if (failDelete) throw BackupException(BackupFailure.TRANSIENT)
      files.removeAll { it.id == id }
    }
    override suspend fun quota(): RemoteQuota? = null
  }

  @Test fun `replace keeps the old backup when the upload fails`() = runBlocking {
    val remote = FakeRemote()
    val file = File.createTempFile("svbk", ".tmp").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }
    RemoteBackups.replace(remote, file) { _, _ -> }
    assertEquals(1, remote.files.size)
    remote.failUpload = BackupException(BackupFailure.QUOTA_FULL)
    try {
      RemoteBackups.replace(remote, file) { _, _ -> }
      fail()
    } catch (e: BackupException) {
      assertEquals(BackupFailure.QUOTA_FULL, e.failure)
    }
    assertEquals(1, remote.files.size)
  }

  @Test fun `replace removes older copies, and a failed clean-up is not a failed backup`() = runBlocking {
    val remote = FakeRemote()
    val file = File.createTempFile("svbk", ".tmp").also { it.writeBytes(ByteArray(10)); it.deleteOnExit() }
    RemoteBackups.replace(remote, file) { _, _ -> }
    val second = RemoteBackups.replace(remote, file) { _, _ -> }
    assertEquals(listOf(second.id), remote.files.map { it.id })
    remote.failDelete = true
    val third = RemoteBackups.replace(remote, file) { _, _ -> }
    assertEquals(2, remote.files.size)
    assertEquals(third.id, RemoteBackups.latest(remote)?.id)
    remote.failDelete = false
    assertEquals(2, RemoteBackups.deleteAll(remote))
    assertTrue(remote.files.isEmpty())
    assertNull(RemoteBackups.latest(remote))
  }

  @Test fun `generated recovery codes are not repeated`() {
    val seen = HashSet<String>()
    repeat(200) { assertTrue(seen.add(RecoveryCode.encode(RecoveryCode.generate(SecureRandom())))) }
  }
}
