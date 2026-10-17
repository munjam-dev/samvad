package com.samvad.messenger.data.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.samvad.core.media.MediaRules
import com.samvad.core.vault.AesGcmVaultCrypto
import com.samvad.core.vault.SecureDelete
import com.samvad.core.vault.VaultAccess
import com.samvad.core.vault.VaultContexts
import com.samvad.core.vault.VaultCryptoException
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultItemType
import com.samvad.core.vault.VaultKeyUnavailableException
import com.samvad.core.vault.VaultKeyring
import com.samvad.core.vault.VaultLimits
import com.samvad.core.vault.VaultMeta
import com.samvad.core.vault.VaultMetaCodec
import com.samvad.core.vault.VaultTitles
import com.samvad.core.vault.VaultTypes
import com.samvad.messenger.data.ImageCodec
import com.samvad.messenger.security.AppLock
import com.samvad.messenger.security.LockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

enum class VaultState { LOCKED, UNLOCKED, KEY_LOST }

/** Why a vault operation failed. Never carries file names or content. */
class VaultException(val reason: Reason) : Exception(reason.name) {
  enum class Reason { LOCKED, TOO_LARGE, EMPTY, UNREADABLE, NOT_FOUND, FAILED }
}

/** A one-time decrypted copy for another app to read. Give it back to [VaultRepository.release] when viewing ends. */
class VaultViewCopy internal constructor(val uri: Uri, val mime: String, internal val dir: File)

/**
 * The Personal Vault: encrypted files and sealed metadata in app-private, no-backup storage. Local only:
 * nothing here uses the network, and nothing is written to logs, notifications or shared storage.
 *
 * Layout under `noBackupFilesDir/vault/`: `vault.db` (Room), `datakey.bin` (the data key wrapped by the
 * Keystore), `blobs/<id>.svl` (file or note, sealed) and `blobs/<id>.thumb` (small preview, sealed).
 * Decrypted data exists only in memory, except the one-time copy for another app in `cacheDir/vault_view/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultRepository(
  context: Context,
  appLock: AppLock,
  private val scope: CoroutineScope,
  private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
  private val app = context.applicationContext
  private val root = File(app.noBackupFilesDir, "vault")
  private val blobDir = File(root, "blobs")
  private val viewDir = File(app.cacheDir, VIEW_DIR)
  private val wrapper = AndroidKeystoreKeyWrapper(app)
  private val keyring = VaultKeyring(wrapper, FileWrappedKeyStore(File(root, "datakey.bin")))
  private val access = VaultAccess()
  private val ioLock = Mutex()
  private val db: VaultDatabase by lazy { VaultDatabase.open(app, root) }
  private val thumbs = object : LruCache<String, Bitmap>(24) {}
  private var leaveJob: Job? = null
  /** Vault screens currently on screen (the list and a viewer can overlap during a transition). */
  private var visible = 0

  @Volatile private var crypto: AesGcmVaultCrypto? = null
  private val _state = MutableStateFlow(VaultState.LOCKED)
  val state: StateFlow<VaultState> = _state.asStateFlow()

  /** Decrypted descriptions of every item, newest change first. Null while the vault is locked or still being read. */
  val items: Flow<List<VaultItem>?> = _state.flatMapLatest { s ->
    if (s != VaultState.UNLOCKED) flowOf(null)
    else db.dao().observeAll().map { rows -> rows.mapNotNull(::toItem) }.flowOn(Dispatchers.Default)
  }

  init {
    scope.launch(Dispatchers.IO) { sweepViewCopies() }
    // The app lock closing the app closes the vault too.
    scope.launch { appLock.state.collect { if (it == LockState.LOCKED) lockNow() } }
  }

  fun protection(): VaultKeyProtection = wrapper.protection()

  // ---- access ----

  /**
   * The vault screen came to the front. True when the vault is still open from a moment ago (inside the
   * one minute grace); otherwise the key is dropped and the person must authenticate. Main thread.
   */
  fun enter(): Boolean {
    leaveJob?.cancel()
    visible++
    if (_state.value == VaultState.KEY_LOST) return false
    val open = access.entered(clock()) && crypto != null
    if (open) _state.value = VaultState.UNLOCKED else lockNow()
    return open
  }

  /**
   * A vault screen went away or the app went to the background. When no vault screen is left on screen the
   * grace minute starts. Every [enter] is paired with one [leave]. Main thread.
   */
  fun leave() {
    visible = maxOf(0, visible - 1)
    if (visible > 0) return
    access.left(clock())
    leaveJob?.cancel()
    // Do not keep the key in memory for a person who is not there: drop it when the grace has run out.
    leaveJob = scope.launch {
      delay(VaultAccess.GRACE_MS + 250)
      if (!access.isOpen(clock())) lockNow()
    }
  }

  /** Closes the vault at once: wipes the key from memory, forgets cached previews, deletes any one-time copies. Main thread. */
  fun lockNow() {
    leaveJob?.cancel()
    access.lock()
    crypto?.wipe()
    crypto = null
    thumbs.evictAll()
    scope.launch(Dispatchers.IO) { sweepViewCopies() }
    if (_state.value != VaultState.KEY_LOST) _state.value = VaultState.LOCKED
  }

  /**
   * Call after the person authenticated (BiometricGate). Loads the data key into memory, creating it on first
   * use. If the vault has items but the key is gone, the state becomes [VaultState.KEY_LOST]. Main thread.
   */
  suspend fun unlock(): VaultState {
    leaveJob?.cancel()
    val key = withContext(Dispatchers.IO) {
      val hasItems = db.dao().all().isNotEmpty()
      if (!keyring.exists() && hasItems) null
      else try {
        keyring.load(createIfMissing = true)
      } catch (_: VaultKeyUnavailableException) {
        null
      } catch (_: IOException) {
        null
      }
    }
    if (key == null) {
      _state.value = VaultState.KEY_LOST
      return VaultState.KEY_LOST
    }
    crypto?.wipe()
    crypto = AesGcmVaultCrypto(key)
    key.fill(0)
    access.unlocked()
    _state.value = VaultState.UNLOCKED
    scope.launch(Dispatchers.IO) { reconcile() }
    return VaultState.UNLOCKED
  }

  private fun requireCrypto(): AesGcmVaultCrypto = crypto ?: throw VaultException(VaultException.Reason.LOCKED)

  // ---- reading ----

  suspend fun item(id: String): VaultItem? = withContext(Dispatchers.IO) {
    val row = db.dao().find(safeId(id)) ?: return@withContext null
    toItem(row)
  }

  /** The image decoded upright and sampled to about [maxEdgePx]. Null if it cannot be decoded. */
  suspend fun loadImage(id: String, maxEdgePx: Int): Bitmap? = withContext(Dispatchers.IO) {
    val c = requireCrypto()
    val file = blob(id)
    if (!file.isFile) throw VaultException(VaultException.Reason.NOT_FOUND)
    if (file.length() > VaultLimits.MAX_INLINE_IMAGE_BYTES + AesGcmVaultCrypto.overhead(VaultLimits.MAX_INLINE_IMAGE_BYTES)) {
      throw VaultException(VaultException.Reason.TOO_LARGE)
    }
    val plain = ByteArrayOutputStream(file.length().toInt())
    try {
      file.inputStream().buffered().use { c.decrypt(it, plain, VaultContexts.file(id)) }
    } catch (_: VaultCryptoException) {
      throw VaultException(VaultException.Reason.UNREADABLE)
    }
    decodeUpright(plain.toByteArray(), maxEdgePx)
  }

  /** The small preview made at import time, or null when the item has none. Cached in memory until the vault locks. */
  suspend fun loadThumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
    thumbs.get(id)?.let { return@withContext it }
    val c = crypto ?: return@withContext null
    val file = thumb(id)
    if (!file.isFile) return@withContext null
    val bitmap = try {
      decodeUpright(c.open(file.readBytes(), VaultContexts.thumbnail(id)), VaultLimits.THUMBNAIL_EDGE_PX)
    } catch (_: IOException) {
      null
    }
    if (bitmap != null && crypto != null) thumbs.put(id, bitmap)
    bitmap
  }

  suspend fun readNote(id: String): String = withContext(Dispatchers.IO) {
    val c = requireCrypto()
    val file = blob(id)
    if (!file.isFile) throw VaultException(VaultException.Reason.NOT_FOUND)
    val out = ByteArrayOutputStream()
    try {
      file.inputStream().buffered().use { c.decrypt(it, out, VaultContexts.file(id)) }
    } catch (_: VaultCryptoException) {
      throw VaultException(VaultException.Reason.UNREADABLE)
    }
    String(out.toByteArray(), Charsets.UTF_8)
  }

  // ---- writing ----

  /** Copies the picked file into the vault, encrypted as it is read. [fallbackTitle] is used when the file has no usable name. */
  suspend fun import(uri: Uri, asIdCopy: Boolean, fallbackTitle: String): VaultItem = withContext(Dispatchers.IO) {
    ioLock.withLock {
      val c = requireCrypto()
      val (name, size) = describe(uri)
      if (size != null && size > VaultLimits.MAX_IMPORT_BYTES) throw VaultException(VaultException.Reason.TOO_LARGE)
      if (size == 0L) throw VaultException(VaultException.Reason.EMPTY)
      val mime = VaultTypes.canonicalMime(runCatching { app.contentResolver.getType(uri) }.getOrNull(), name)
      val type = VaultTypes.forImport(mime, name, asIdCopy)
      val id = UUID.randomUUID().toString()
      blobDir.mkdirs()
      val part = File(blobDir, "$id.part")
      var thumbFile: File? = null
      try {
        val input = app.contentResolver.openInputStream(uri) ?: throw VaultException(VaultException.Reason.UNREADABLE)
        val counted = CountingInput(input, VaultLimits.MAX_IMPORT_BYTES)
        try {
          counted.use { writeSealed(c, it, part, VaultContexts.file(id)) }
        } catch (_: TooLarge) {
          throw VaultException(VaultException.Reason.TOO_LARGE)
        }
        if (counted.count == 0L) throw VaultException(VaultException.Reason.EMPTY)
        if (!part.renameTo(blob(id))) throw IOException("rename failed")
        var hasThumb = false
        if (VaultTypes.isImage(mime)) {
          val bytes = makeThumbnail(uri)
          if (bytes != null) {
            thumbFile = thumb(id)
            writeAtomically(thumbFile, c.seal(bytes, VaultContexts.thumbnail(id)))
            hasThumb = true
          }
        }
        val now = System.currentTimeMillis()
        val title = VaultTitles.fromFileName(name, fallbackTitle)
        val entity = VaultItemEntity(
          id, type.name, counted.count, now, now, hasThumb,
          c.seal(VaultMetaCodec.encode(VaultMeta(title, mime)), VaultContexts.meta(id)),
        )
        db.dao().put(entity)
        VaultItem(id, title, type, mime, counted.count, now, now, hasThumb)
      } catch (e: Throwable) {
        SecureDelete.overwriteAndDelete(part)
        SecureDelete.overwriteAndDelete(blob(id))
        thumbFile?.let { SecureDelete.overwriteAndDelete(it) }
        throw when (e) {
          is VaultException, is kotlinx.coroutines.CancellationException -> e
          else -> VaultException(VaultException.Reason.FAILED)
        }
      }
    }
  }

  /** Creates a note ([id] null) or replaces the text of an existing one. */
  suspend fun saveNote(id: String?, title: String, body: String, fallbackTitle: String): VaultItem = withContext(Dispatchers.IO) {
    ioLock.withLock {
      val c = requireCrypto()
      if (body.length > VaultLimits.MAX_NOTE_CHARS) throw VaultException(VaultException.Reason.TOO_LARGE)
      val existing = id?.let { db.dao().find(safeId(it)) ?: throw VaultException(VaultException.Reason.NOT_FOUND) }
      val noteId = existing?.id ?: UUID.randomUUID().toString()
      val bytes = body.toByteArray(Charsets.UTF_8)
      val cleanTitle = VaultTitles.sanitize(title, fallbackTitle)
      blobDir.mkdirs()
      try {
        writeAtomically(blob(noteId), c.seal(bytes, VaultContexts.file(noteId)))
        val now = System.currentTimeMillis()
        val created = existing?.createdAt ?: now
        db.dao().put(
          VaultItemEntity(
            noteId, VaultItemType.NOTE.name, bytes.size.toLong(), created, now, false,
            c.seal(VaultMetaCodec.encode(VaultMeta(cleanTitle, VaultTypes.MIME_NOTE)), VaultContexts.meta(noteId)),
          ),
        )
        VaultItem(noteId, cleanTitle, VaultItemType.NOTE, VaultTypes.MIME_NOTE, bytes.size.toLong(), created, now)
      } catch (e: IOException) {
        if (existing == null) SecureDelete.overwriteAndDelete(blob(noteId))
        throw VaultException(VaultException.Reason.FAILED)
      }
    }
  }

  suspend fun rename(id: String, title: String, fallbackTitle: String) = withContext(Dispatchers.IO) {
    ioLock.withLock {
      val c = requireCrypto()
      val row = db.dao().find(safeId(id)) ?: throw VaultException(VaultException.Reason.NOT_FOUND)
      try {
        val meta = VaultMetaCodec.decode(c.open(row.meta, VaultContexts.meta(id)))
        val renamed = meta.copy(title = VaultTitles.sanitize(title, fallbackTitle))
        db.dao().put(
          VaultItemEntity(
            row.id, row.type, row.sizeBytes, row.createdAt, System.currentTimeMillis(), row.hasThumb,
            c.seal(VaultMetaCodec.encode(renamed), VaultContexts.meta(id)),
          ),
        )
      } catch (_: IOException) {
        throw VaultException(VaultException.Reason.FAILED)
      }
    }
  }

  /** Removes the item from the list, then overwrites and deletes its files. */
  suspend fun delete(id: String) = withContext(Dispatchers.IO) {
    ioLock.withLock {
      safeId(id)
      db.dao().remove(id)
      SecureDelete.overwriteAndDelete(blob(id))
      SecureDelete.overwriteAndDelete(thumb(id))
      thumbs.remove(id)
    }
  }

  /**
   * Erases the whole vault, including the keys. Used when the person asks for it, and as the only way
   * forward after [VaultState.KEY_LOST] (the items can no longer be read). Leaves the vault locked and empty.
   */
  suspend fun eraseAll() = withContext(Dispatchers.IO) {
    ioLock.withLock {
      blobDir.listFiles()?.forEach { SecureDelete.overwriteAndDelete(it) }
      db.dao().removeAll()
      keyring.destroy()
      wrapper.deleteKey()
    }
    withContext(Dispatchers.Main.immediate) {
      access.lock()
      crypto?.wipe()
      crypto = null
      thumbs.evictAll()
      _state.value = VaultState.LOCKED
    }
    sweepViewCopies()
  }

  // ---- getting data out ----

  /**
   * Decrypts one item into a private cache folder and returns a content URI for another app to read. Nothing else
   * can open it: the URI is not exported and the read grant is given only to the app that is started with it.
   * Call [release] as soon as viewing ends.
   */
  suspend fun decryptForViewing(id: String): VaultViewCopy = withContext(Dispatchers.IO) {
    val c = requireCrypto()
    val row = db.dao().find(safeId(id)) ?: throw VaultException(VaultException.Reason.NOT_FOUND)
    val meta = try {
      VaultMetaCodec.decode(c.open(row.meta, VaultContexts.meta(id)))
    } catch (_: IOException) {
      throw VaultException(VaultException.Reason.UNREADABLE)
    }
    if (!VaultTypes.canOpenInAnotherApp(meta.mime)) throw VaultException(VaultException.Reason.FAILED)
    val dir = File(viewDir, UUID.randomUUID().toString()).also { it.mkdirs() }
    val out = File(dir, VaultTitles.exportFileName(meta.title, meta.mime))
    try {
      blob(id).inputStream().buffered().use { input ->
        FileOutputStream(out).use { fos ->
          val buffered = fos.buffered()
          c.decrypt(input, buffered, VaultContexts.file(id))
          buffered.flush()
        }
      }
    } catch (e: IOException) {
      deleteTree(dir)
      throw VaultException(if (e is VaultCryptoException) VaultException.Reason.UNREADABLE else VaultException.Reason.FAILED)
    }
    VaultViewCopy(FileProvider.getUriForFile(app, app.packageName + ".files", out), meta.mime, dir)
  }

  /** Revokes the read grant and overwrites and deletes the one-time copy. Safe to call twice. */
  fun release(copy: VaultViewCopy) {
    runCatching { app.revokeUriPermission(copy.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    deleteTree(copy.dir)
  }

  /** Decrypts straight into the document the person chose; no plaintext file is created by Samvad. */
  suspend fun exportTo(id: String, destination: Uri) = withContext(Dispatchers.IO) {
    val c = requireCrypto()
    val file = blob(id)
    if (!file.isFile) throw VaultException(VaultException.Reason.NOT_FOUND)
    try {
      val out = app.contentResolver.openOutputStream(destination, "wt") ?: throw VaultException(VaultException.Reason.FAILED)
      out.buffered().use { o -> file.inputStream().buffered().use { c.decrypt(it, o, VaultContexts.file(id)) } }
    } catch (e: Exception) {
      // Do not leave a half-written plaintext document behind.
      runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }
      throw if (e is VaultException) e else VaultException(VaultException.Reason.FAILED)
    }
  }

  /** Suggested file name for [exportTo]. */
  fun exportName(item: VaultItem): String = VaultTitles.exportFileName(item.title, item.mime)

  // ---- internals ----

  private fun toItem(row: VaultItemEntity): VaultItem? {
    val c = crypto ?: return null
    return try {
      val meta = VaultMetaCodec.decode(c.open(row.meta, VaultContexts.meta(row.id)))
      VaultItem(
        row.id, meta.title, VaultItemType.valueOf(row.type), meta.mime, row.sizeBytes, row.createdAt, row.updatedAt, row.hasThumb,
      )
    } catch (_: IOException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun describe(uri: Uri): Pair<String?, Long?> {
    var name: String? = null
    var size: Long? = null
    runCatching {
      app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          if (!cursor.isNull(0)) name = cursor.getString(0)
          if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
      }
    }
    return name to size
  }

  private fun writeSealed(c: AesGcmVaultCrypto, input: InputStream, target: File, context: ByteArray) {
    FileOutputStream(target).use { fos ->
      val buffered = fos.buffered()
      c.encrypt(input.buffered(), buffered, context)
      buffered.flush()
      fos.fd.sync()
    }
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, target.name + ".part")
    FileOutputStream(tmp).use { it.write(bytes); it.flush(); it.fd.sync() }
    if (!tmp.renameTo(target)) {
      SecureDelete.overwriteAndDelete(tmp)
      throw IOException("rename failed")
    }
  }

  private fun makeThumbnail(uri: Uri): ByteArray? = runCatching {
    val decoded = ImageCodec.decodeUpright(app.contentResolver, uri, VaultLimits.THUMBNAIL_EDGE_PX) ?: return null
    val edge = maxOf(decoded.width, decoded.height)
    val scaled = if (edge > VaultLimits.THUMBNAIL_EDGE_PX) {
      val f = VaultLimits.THUMBNAIL_EDGE_PX.toFloat() / edge
      Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * f).toInt()), maxOf(1, (decoded.height * f).toInt()), true)
    } else decoded
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    out.toByteArray()
  }.getOrNull()

  private fun decodeUpright(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = MediaRules.sampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val orientation = runCatching {
      ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = ImageCodec.orientationMatrix(orientation) ?: return decoded
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (upright !== decoded) decoded.recycle()
    return upright
  }

  /** Deletes encrypted files that no item refers to (an import that was cut short by a crash). */
  private suspend fun reconcile() = ioLock.withLock {
    val known = db.dao().all().map { it.id }.toSet()
    blobDir.listFiles()?.forEach { f ->
      val id = f.name.substringBefore('.')
      if (f.name.endsWith(".part") || id !in known) SecureDelete.overwriteAndDelete(f)
    }
  }

  private fun sweepViewCopies() {
    viewDir.listFiles()?.forEach { deleteTree(it) }
  }

  private fun deleteTree(file: File) {
    if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
    SecureDelete.overwriteAndDelete(file)
  }

  private fun safeId(id: String): String {
    if (!ID_PATTERN.matches(id)) throw VaultException(VaultException.Reason.NOT_FOUND)
    return id
  }

  private fun blob(id: String) = File(blobDir, safeId(id) + ".svl")

  private fun thumb(id: String) = File(blobDir, safeId(id) + ".thumb")

  private class TooLarge : IOException()

  /** Counts what passes through and refuses to go past [limit], whatever the provider claimed about the size. */
  private class CountingInput(private val src: InputStream, private val limit: Long) : InputStream() {
    var count = 0L
      private set

    override fun read(): Int {
      val b = src.read()
      if (b >= 0 && ++count > limit) throw TooLarge()
      return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      val n = src.read(b, off, len)
      if (n > 0) {
        count += n
        if (count > limit) throw TooLarge()
      }
      return n
    }

    override fun close() = src.close()
  }

  companion object {
    const val VIEW_DIR = "vault_view"
    private val ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
  }
}
