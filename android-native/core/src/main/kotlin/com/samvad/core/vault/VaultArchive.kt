package com.samvad.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

/** The backup was made by a newer version of Samvad than this one. */
class VaultBackupTooNewException(val version: Int) : VaultCryptoException("Backup format version $version is newer than this app")

/** One vault row as stored in a backup. [meta] is the sealed title and MIME type, exactly as in the database. */
class ArchivedItem(
  val id: String,
  val type: String,
  val sizeBytes: Long,
  val createdAt: Long,
  val updatedAt: Long,
  val hasThumb: Boolean,
  val meta: ByteArray,
)

/** What the backup says about itself. */
class ArchiveManifest(val createdAtMillis: Long, val appVersion: Int, val itemCount: Int)

/** One already-sealed vault file to include. [length] is its exact size on disk; [open] is called once. */
class ArchiveBlob(val name: String, val length: Long, val open: () -> InputStream)

/** Receives a backup while it is read. On any exception the caller must discard everything it was given. */
interface ArchiveSink {
  fun onDataKey(key: ByteArray)

  fun onManifest(manifest: ArchiveManifest)

  fun onItem(item: ArchivedItem)

  /** The reader writes the (still vault-sealed) bytes of file [name] here and closes the stream. */
  fun openBlob(name: String): OutputStream
}

/** KDF identifiers stored in the archive header. */
enum class ArchiveKdf(val id: Int) {
  RECOVERY_KEY(1),
  PASSPHRASE_PBKDF2(2),
  ;

  companion object {
    fun of(id: Int): ArchiveKdf? = entries.firstOrNull { it.id == id }
  }
}

/**
 * Archive header, version 1 (big-endian), 42 bytes, in the clear but authenticated: it is part of the
 * associated data of every record.
 *
 * ```
 * magic        4 bytes   "SVBK"
 * version      1 byte    1
 * kdf          1 byte    1 = recovery key (HKDF), 2 = passphrase (PBKDF2-HMAC-SHA256)
 * iterations   4 bytes   PBKDF2 iterations, 0 for a recovery key
 * salt        32 bytes   random, per backup setup
 * ```
 */
class ArchiveHeader(val version: Int, val kdf: ArchiveKdf, val iterations: Int, val salt: ByteArray) {
  fun encode(): ByteArray =
    ByteBuffer.allocate(SIZE).put(MAGIC).put(version.toByte()).put(kdf.id.toByte()).putInt(iterations).put(salt).array()

  /** Derives the archive key from what the person typed. Wrong or malformed text gives a key that fails to open anything. */
  fun deriveKey(secret: String): ByteArray = when (kdf) {
    ArchiveKdf.RECOVERY_KEY -> {
      val key = RecoveryCode.parse(secret) ?: throw RecoverySecretFormatException("Not a recovery code")
      try {
        BackupKdf.fromRecoveryKey(key, salt)
      } finally {
        key.fill(0)
      }
    }
    ArchiveKdf.PASSPHRASE_PBKDF2 -> BackupKdf.fromPassphrase(secret, salt, iterations)
  }

  companion object {
    val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val SIZE = 4 + 1 + 1 + 4 + 32

    fun newRecoveryKey(random: SecureRandom = SecureRandom()) =
      ArchiveHeader(VaultArchive.FORMAT_VERSION, ArchiveKdf.RECOVERY_KEY, 0, ByteArray(32).also(random::nextBytes))

    fun newPassphrase(iterations: Int = BackupKdf.PBKDF2_MIN_ITERATIONS, random: SecureRandom = SecureRandom()) =
      ArchiveHeader(VaultArchive.FORMAT_VERSION, ArchiveKdf.PASSPHRASE_PBKDF2, iterations, ByteArray(32).also(random::nextBytes))

    fun decode(bytes: ByteArray): ArchiveHeader {
      if (bytes.size != SIZE) throw VaultFormatException("Not a Samvad backup")
      val buf = ByteBuffer.wrap(bytes)
      val magic = ByteArray(4).also(buf::get)
      if (!magic.contentEquals(MAGIC)) throw VaultFormatException("Not a Samvad backup")
      val version = buf.get().toInt() and 0xFF
      if (version > VaultArchive.FORMAT_VERSION) throw VaultBackupTooNewException(version)
      if (version != VaultArchive.FORMAT_VERSION) throw VaultFormatException("Unsupported backup version $version")
      val kdf = ArchiveKdf.of(buf.get().toInt() and 0xFF) ?: throw VaultFormatException("Unsupported key method")
      val iterations = buf.int
      val salt = ByteArray(32).also(buf::get)
      if (kdf == ArchiveKdf.PASSPHRASE_PBKDF2 && iterations !in BackupKdf.PBKDF2_MIN_ITERATIONS..BackupKdf.PBKDF2_MAX_ITERATIONS) {
        throw VaultFormatException("Unsupported key settings")
      }
      return ArchiveHeader(version, kdf, iterations, salt)
    }
  }
}

/**
 * The backup archive. A header followed by records; each record is a small frame in the clear and a body
 * sealed with the vault envelope ([AesGcmVaultCrypto], chunked AES-256-GCM) under the archive key:
 *
 * ```
 * kind        1 byte
 * name length 2 bytes
 * name        UTF-8 (blob file name, empty for other kinds)
 * sealed size 8 bytes
 * sealed body
 * ```
 *
 * Records appear in a fixed order: data key (1), manifest (2), items (3), files (4), end (255). The context of
 * every record binds the header, the record's position, kind and name, so records cannot be reordered,
 * dropped, duplicated or renamed; the end record carries the counts, so cutting the archive short at a record
 * boundary is also detected. What is inside the files is the vault's own sealed bytes, so the data key
 * travels in the archive (record 1) and a new phone can re-seal everything under its own key.
 *
 * Failures: wrong secret, tampering and truncation all raise [VaultTamperedException] (they cannot be told
 * apart); a different or newer format raises [VaultFormatException] / [VaultBackupTooNewException].
 */
object VaultArchive {
  const val FORMAT_VERSION = 1
  private const val RECORD_VERSION = 1

  const val KIND_DATA_KEY = 1
  const val KIND_MANIFEST = 2
  const val KIND_ITEM = 3
  const val KIND_BLOB = 4
  const val KIND_END = 255

  /** Largest sealed file accepted: the vault allows 100 MB per file; this leaves room for the two envelopes. */
  const val MAX_BLOB_SEALED = 256L * 1024 * 1024
  const val MAX_SMALL_SEALED = 1L * 1024 * 1024
  const val MAX_ITEMS = 1_000_000
  private const val MAX_NAME = 128
  private const val MAX_META = 64 * 1024

  private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
  private val BLOB_NAME = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(svl|thumb)$")

  /** Seals the vault data key as record 0, so a background backup can reuse it without ever holding the data key. */
  fun sealDataKey(header: ArchiveHeader, archiveKey: ByteArray, dataKey: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
    val out = ByteArrayOutputStream()
    writeRecord(out, header, AesGcmVaultCrypto(archiveKey, random), 0, KIND_DATA_KEY, "", dataKey)
    return out.toByteArray()
  }

  /** Reads the data key back from the bytes made by [sealDataKey], for a check that the cached copy is intact. */
  fun openSealedDataKey(header: ArchiveHeader, archiveKey: ByteArray, sealedRecord: ByteArray): ByteArray {
    val input = ByteArrayInputStream(sealedRecord)
    val (kind, _, body) = readSmall(input, header, AesGcmVaultCrypto(archiveKey), 0)
    if (kind != KIND_DATA_KEY || input.available() != 0) throw VaultTamperedException()
    return body
  }

  /** Exact size in bytes that [write] will produce. */
  fun plannedSize(header: ArchiveHeader, sealedDataKey: ByteArray, manifest: ArchiveManifest, items: List<ArchivedItem>, blobs: List<ArchiveBlob>): Long {
    var total = ArchiveHeader.SIZE.toLong() + sealedDataKey.size
    total += smallRecordSize(encodeManifest(manifest).size)
    for (item in items) total += smallRecordSize(encodeItem(item).size)
    for (blob in blobs) {
      val name = blob.name.toByteArray(Charsets.UTF_8)
      total += 1 + 2 + name.size + 8 + blob.length + AesGcmVaultCrypto.overhead(blob.length)
    }
    return total + smallRecordSize(ENDBODY_SIZE)
  }

  /**
   * Writes the whole archive to [out] (not closed). Each file is read exactly once. If a file turns out
   * to have a different length than declared (it changed during the backup) this throws and the output must be discarded.
   */
  fun write(
    out: OutputStream,
    header: ArchiveHeader,
    archiveKey: ByteArray,
    sealedDataKey: ByteArray,
    manifest: ArchiveManifest,
    items: List<ArchivedItem>,
    blobs: List<ArchiveBlob>,
    random: SecureRandom = SecureRandom(),
  ) {
    require(manifest.itemCount == items.size) { "Manifest does not match the items" }
    val ids = items.map { it.id }.toSet()
    for (b in blobs) require(BLOB_NAME.matchEntire(b.name)?.groupValues?.get(1) in ids) { "File without an item" }
    val crypto = AesGcmVaultCrypto(archiveKey, random)
    out.write(header.encode())
    out.write(sealedDataKey)
    var index = 1
    writeRecord(out, header, crypto, index++, KIND_MANIFEST, "", encodeManifest(manifest))
    for (item in items) writeRecord(out, header, crypto, index++, KIND_ITEM, "", encodeItem(item))
    for (blob in blobs) {
      val name = blob.name.toByteArray(Charsets.UTF_8)
      writeFrame(out, KIND_BLOB, name, blob.length + AesGcmVaultCrypto.overhead(blob.length))
      blob.open().use { source ->
        val counted = ExactInput(source, blob.length)
        crypto.encrypt(counted, out, context(header, index++, KIND_BLOB, name))
        counted.verifyEnd()
      }
    }
    val end = ByteBuffer.allocate(ENDBODY_SIZE).putInt(items.size).putInt(blobs.size).array()
    writeRecord(out, header, crypto, index, KIND_END, "", end)
    out.flush()
  }

  /**
   * Reads and fully verifies an archive. [secret] is the recovery code or passphrase as typed. Everything is
   * authenticated before [sink] is told the archive is complete (this returns); a failure part way means the sink
   * holds partial data that must be discarded.
   */
  fun read(input: InputStream, secret: String, sink: ArchiveSink): ArchiveManifest {
    val headerBytes = ByteArray(ArchiveHeader.SIZE)
    if (readFully(input, headerBytes) != headerBytes.size) throw VaultFormatException("Not a Samvad backup")
    val header = ArchiveHeader.decode(headerBytes)
    val key = header.deriveKey(secret)
    try {
      return readBody(input, header, key, sink)
    } finally {
      key.fill(0)
    }
  }

  /** Same as [read] when the archive key is already known. */
  fun readWithKey(input: InputStream, archiveKey: ByteArray, sink: ArchiveSink): ArchiveManifest {
    val headerBytes = ByteArray(ArchiveHeader.SIZE)
    if (readFully(input, headerBytes) != headerBytes.size) throw VaultFormatException("Not a Samvad backup")
    return readBody(input, ArchiveHeader.decode(headerBytes), archiveKey, sink)
  }

  private fun readBody(input: InputStream, header: ArchiveHeader, archiveKey: ByteArray, sink: ArchiveSink): ArchiveManifest {
    val crypto = AesGcmVaultCrypto(archiveKey)
    var index = 0
    val first = readSmall(input, header, crypto, index++)
    if (first.kind != KIND_DATA_KEY || first.body.size != AesGcmVaultCrypto.KEY_BYTES) throw VaultTamperedException()
    sink.onDataKey(first.body)
    first.body.fill(0)

    val second = readSmall(input, header, crypto, index++)
    if (second.kind != KIND_MANIFEST) throw VaultTamperedException()
    val manifest = decodeManifest(second.body)
    sink.onManifest(manifest)

    val itemIds = HashSet<String>()
    val seenBlobs = HashSet<String>()
    var blobCount = 0
    var phase = KIND_ITEM
    while (true) {
      val kind = input.read()
      if (kind < 0) throw VaultTamperedException() // cut short before the end record
      val nameLength = readU16(input)
      if (nameLength > MAX_NAME) throw VaultFormatException("Backup is damaged")
      val nameBytes = ByteArray(nameLength)
      if (readFully(input, nameBytes) != nameLength) throw VaultTamperedException()
      val sealedLength = readU64(input)
      val ctx = context(header, index++, kind, nameBytes)
      when (kind) {
        KIND_ITEM -> {
          if (phase != KIND_ITEM || nameLength != 0) throw VaultTamperedException()
          val body = openSmall(input, crypto, sealedLength, ctx)
          val item = decodeItem(body)
          if (!itemIds.add(item.id) || itemIds.size > MAX_ITEMS) throw VaultTamperedException()
          sink.onItem(item)
        }
        KIND_BLOB -> {
          if (phase > KIND_BLOB) throw VaultTamperedException()
          phase = KIND_BLOB
          val name = String(nameBytes, Charsets.UTF_8)
          val match = BLOB_NAME.matchEntire(name) ?: throw VaultFormatException("Backup is damaged")
          if (match.groupValues[1] !in itemIds || !seenBlobs.add(name)) throw VaultTamperedException()
          if (sealedLength !in 0..MAX_BLOB_SEALED) throw VaultFormatException("Backup is damaged")
          val bounded = BoundedInput(input, sealedLength)
          val out = sink.openBlob(name)
          try {
            crypto.decrypt(bounded, out, ctx)
          } finally {
            out.close()
          }
          if (bounded.remaining != 0L) throw VaultTamperedException()
          blobCount++
        }
        KIND_END -> {
          if (nameLength != 0) throw VaultTamperedException()
          val body = openSmall(input, crypto, sealedLength, ctx)
          if (body.size != ENDBODY_SIZE) throw VaultTamperedException()
          val buf = ByteBuffer.wrap(body)
          if (buf.int != itemIds.size || buf.int != blobCount || manifest.itemCount != itemIds.size) throw VaultTamperedException()
          if (input.read() >= 0) throw VaultTamperedException() // data after the end
          return manifest
        }
        else -> throw VaultFormatException("Backup is damaged")
      }
    }
  }

  // ---- records ----

  private class Small(val kind: Int, val name: String, val body: ByteArray) {
    operator fun component1() = kind
    operator fun component2() = name
    operator fun component3() = body
  }

  private fun readSmall(input: InputStream, header: ArchiveHeader, crypto: AesGcmVaultCrypto, index: Int): Small {
    val kind = input.read()
    if (kind < 0) throw VaultTamperedException()
    val nameLength = readU16(input)
    if (nameLength != 0) throw VaultTamperedException()
    val sealedLength = readU64(input)
    val body = openSmall(input, crypto, sealedLength, context(header, index, kind, ByteArray(0)))
    return Small(kind, "", body)
  }

  private fun openSmall(input: InputStream, crypto: AesGcmVaultCrypto, sealedLength: Long, ctx: ByteArray): ByteArray {
    if (sealedLength !in 0..MAX_SMALL_SEALED) throw VaultFormatException("Backup is damaged")
    val sealed = ByteArray(sealedLength.toInt())
    if (readFully(input, sealed) != sealed.size) throw VaultTamperedException()
    return crypto.open(sealed, ctx)
  }

  private fun writeRecord(out: OutputStream, header: ArchiveHeader, crypto: AesGcmVaultCrypto, index: Int, kind: Int, name: String, body: ByteArray) {
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    val sealed = crypto.seal(body, context(header, index, kind, nameBytes))
    writeFrame(out, kind, nameBytes, sealed.size.toLong())
    out.write(sealed)
  }

  private fun writeFrame(out: OutputStream, kind: Int, name: ByteArray, sealedLength: Long) {
    require(name.size <= MAX_NAME)
    out.write(ByteBuffer.allocate(1 + 2 + name.size + 8).put(kind.toByte()).putShort(name.size.toShort()).put(name).putLong(sealedLength).array())
  }

  private fun smallRecordSize(bodySize: Int): Long =
    1L + 2 + 8 + bodySize + AesGcmVaultCrypto.overhead(bodySize.toLong())

  private fun context(header: ArchiveHeader, index: Int, kind: Int, name: ByteArray): ByteArray =
    ByteBuffer.allocate(8 + ArchiveHeader.SIZE + 4 + 1 + 2 + name.size)
      .put("svb:rec:".toByteArray(Charsets.US_ASCII)).put(header.encode())
      .putInt(index).put(kind.toByte()).putShort(name.size.toShort()).put(name).array()

  private const val ENDBODY_SIZE = 8

  // ---- bodies ----

  private fun encodeManifest(m: ArchiveManifest): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeByte(RECORD_VERSION)
      it.writeLong(m.createdAtMillis)
      it.writeInt(m.appVersion)
      it.writeInt(m.itemCount)
    }
    return bytes.toByteArray()
  }

  private fun decodeManifest(body: ByteArray): ArchiveManifest = decoding {
    val data = DataInputStream(ByteArrayInputStream(body))
    checkRecordVersion(data.readUnsignedByte())
    val created = data.readLong()
    val app = data.readInt()
    val count = data.readInt()
    if (count !in 0..MAX_ITEMS) throw VaultFormatException("Backup is damaged")
    ArchiveManifest(created, app, count)
  }

  private fun encodeItem(item: ArchivedItem): ByteArray {
    require(ID.matches(item.id) && item.type.length <= 32 && item.meta.size <= MAX_META) { "Item cannot be archived" }
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeByte(RECORD_VERSION)
      it.writeUTF(item.id)
      it.writeUTF(item.type)
      it.writeLong(item.sizeBytes)
      it.writeLong(item.createdAt)
      it.writeLong(item.updatedAt)
      it.writeBoolean(item.hasThumb)
      it.writeInt(item.meta.size)
      it.write(item.meta)
    }
    return bytes.toByteArray()
  }

  private fun decodeItem(body: ByteArray): ArchivedItem = decoding {
    val data = DataInputStream(ByteArrayInputStream(body))
    checkRecordVersion(data.readUnsignedByte())
    val id = data.readUTF()
    val type = data.readUTF()
    val size = data.readLong()
    val created = data.readLong()
    val updated = data.readLong()
    val hasThumb = data.readBoolean()
    val metaSize = data.readInt()
    if (!ID.matches(id) || type.length > 32 || metaSize !in 0..MAX_META || size < 0) throw VaultFormatException("Backup is damaged")
    val meta = ByteArray(metaSize)
    data.readFully(meta)
    ArchivedItem(id, type, size, created, updated, hasThumb, meta)
  }

  private fun checkRecordVersion(v: Int) {
    if (v > RECORD_VERSION) throw VaultBackupTooNewException(v)
    if (v != RECORD_VERSION) throw VaultFormatException("Backup is damaged")
  }

  private inline fun <T> decoding(block: () -> T): T = try {
    block()
  } catch (e: EOFException) {
    throw VaultFormatException("Backup is damaged")
  } catch (e: IOException) {
    if (e is VaultCryptoException) throw e
    throw VaultFormatException("Backup is damaged")
  }

  // ---- stream helpers ----

  private fun readU16(input: InputStream): Int {
    val a = input.read()
    val b = input.read()
    if (a < 0 || b < 0) throw VaultTamperedException()
    return (a shl 8) or b
  }

  private fun readU64(input: InputStream): Long {
    var v = 0L
    repeat(8) {
      val b = input.read()
      if (b < 0) throw VaultTamperedException()
      v = (v shl 8) or b.toLong()
    }
    return v
  }

  private fun readFully(input: InputStream, buf: ByteArray): Int {
    var read = 0
    while (read < buf.size) {
      val n = input.read(buf, read, buf.size - read)
      if (n < 0) break
      read += n
    }
    return read
  }

  /** Hands out at most [limit] bytes of the underlying stream. */
  private class BoundedInput(private val src: InputStream, limit: Long) : InputStream() {
    var remaining = limit
      private set

    override fun read(): Int {
      if (remaining <= 0) return -1
      val b = src.read()
      if (b >= 0) remaining--
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (remaining <= 0) return -1
      val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
      if (n > 0) remaining -= n
      return n
    }
  }

  /** Reads exactly [length] bytes; reading less or more than declared is an error. */
  private class ExactInput(private val src: InputStream, private val length: Long) : InputStream() {
    private var count = 0L

    override fun read(): Int {
      if (count >= length) return -1
      val b = src.read()
      if (b >= 0) count++
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (count >= length) return -1
      val n = src.read(b, off, minOf(len.toLong(), length - count).toInt())
      if (n > 0) count += n
      return n
    }

    fun verifyEnd() {
      if (count != length || src.read() >= 0) throw VaultCryptoException("A file changed while it was being backed up")
    }
  }
}
