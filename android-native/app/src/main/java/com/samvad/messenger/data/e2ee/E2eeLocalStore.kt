package com.samvad.messenger.data.e2ee

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.samvad.core.vault.AesGcmVaultCrypto
import com.samvad.core.vault.VaultKeyring
import com.samvad.messenger.data.vault.AndroidKeystoreKeyWrapper
import com.samvad.messenger.data.vault.FileWrappedKeyStore
import java.io.File

/**
 * Everything the encryption feature keeps on the phone, all in app-private no-backup storage (Android never copies
 * it to the cloud or to a new phone; the backup rules exclude it as well):
 *
 * - `state.sealed`: the Signal Protocol state (identity key pair, pre-keys, sessions, trust decisions).
 * - `plain.db`: what this phone decrypted (or sent), one sealed row per message. Needed because a Signal ciphertext
 *   can be opened only once. This is also the source for chat-list previews and the on-device search index.
 * - `datakey.bin`: a random 256-bit data key, wrapped by an Android Keystore key (hardware-backed where the phone
 *   has it). The same approach as the Personal Vault, with its own Keystore alias.
 *
 * Nothing here is ever logged or uploaded. [wipe] removes all of it, including the Keystore key.
 */
class E2eeLocalStore(private val context: Context) {
  private val root = File(context.noBackupFilesDir, "e2ee").apply { mkdirs() }
  private val wrapper = AndroidKeystoreKeyWrapper(context.applicationContext, ALIAS)
  private val keyring = VaultKeyring(wrapper, FileWrappedKeyStore(File(root, "datakey.bin")))
  private val lock = Any()
  private var crypto: AesGcmVaultCrypto? = null
  private var helper: PlainDb? = null

  private fun crypto(): AesGcmVaultCrypto = synchronized(lock) {
    crypto ?: run {
      val key = keyring.load(createIfMissing = true)
      AesGcmVaultCrypto(key).also {
        key.fill(0)
        crypto = it
      }
    }
  }

  // ---- owner ---------------------------------------------------------------------------------------------------------

  /** The account the stored keys belong to; a different account signing in starts from nothing. */
  fun owner(): String? = runCatching { File(root, "owner").takeIf { it.isFile }?.readText() }.getOrNull()

  fun setOwner(uid: String) {
    File(root, "owner").writeText(uid)
  }

  // ---- protocol state ----------------------------------------------------------------------------------------------

  /** The saved protocol state, or null when there is none or it cannot be opened (Keystore key lost, file damaged). */
  fun readState(): ByteArray? {
    val file = File(root, "state.sealed")
    if (!file.isFile) return null
    return runCatching { crypto().open(file.readBytes(), STATE_CONTEXT) }.getOrNull()
  }

  /** Whether a state file exists, even if it can no longer be opened. */
  fun hasStateFile(): Boolean = File(root, "state.sealed").isFile

  fun writeState(snapshot: ByteArray) {
    val sealed = crypto().seal(snapshot, STATE_CONTEXT)
    val file = File(root, "state.sealed")
    val tmp = File(root, "state.sealed.tmp")
    tmp.outputStream().use { out ->
      out.write(sealed)
      out.flush()
      out.fd.sync()
    }
    if (!tmp.renameTo(file)) {
      file.delete()
      if (!tmp.renameTo(file)) throw java.io.IOException("Could not save encryption state")
    }
  }

  // ---- decrypted messages ----------------------------------------------------------------------------------------

  class CachedMessage(val digest: String, val payload: ByteArray)

  /** The decrypted payload of this exact ciphertext (an edit has a different digest than the original). */
  fun cachedMessage(cid: String, mid: String, digest: String): CachedMessage? {
    val db = plain().readableDatabase
    db.rawQuery("SELECT blob FROM plain WHERE cid = ? AND mid = ? AND digest = ?", arrayOf(cid, mid, digest)).use { c ->
      if (!c.moveToFirst()) return null
      val payload = runCatching { crypto().open(c.getBlob(0), rowContext(cid, mid, digest)) }.getOrNull() ?: return null
      return CachedMessage(digest, payload)
    }
  }

  /** The newest decrypted payload of a message (after edits), for previews. */
  fun latestCachedMessage(cid: String, mid: String): CachedMessage? {
    val db = plain().readableDatabase
    db.rawQuery("SELECT digest, blob FROM plain WHERE cid = ? AND mid = ? ORDER BY ts DESC LIMIT 1", arrayOf(cid, mid)).use { c ->
      if (!c.moveToFirst()) return null
      val digest = c.getString(0)
      val payload = runCatching { crypto().open(c.getBlob(1), rowContext(cid, mid, digest)) }.getOrNull() ?: return null
      return CachedMessage(digest, payload)
    }
  }

  fun cacheMessage(cid: String, mid: String, digest: String, payload: ByteArray) {
    val values = ContentValues().apply {
      put("cid", cid)
      put("mid", mid)
      put("digest", digest)
      put("blob", crypto().seal(payload, rowContext(cid, mid, digest)))
      put("ts", System.currentTimeMillis())
    }
    val db = plain().writableDatabase
    db.insertWithOnConflict("plain", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    // An edited message keeps its last few versions (the screen may still show the one before the edit landed).
    db.execSQL(
      "DELETE FROM plain WHERE cid = ? AND mid = ? AND digest NOT IN (SELECT digest FROM plain WHERE cid = ? AND mid = ? ORDER BY ts DESC LIMIT 3)",
      arrayOf(cid, mid, cid, mid),
    )
  }

  fun forgetMessage(cid: String, mid: String) {
    plain().writableDatabase.delete("plain", "cid = ? AND mid = ?", arrayOf(cid, mid))
  }

  /**
   * Removes decrypted messages the server no longer has (deleted for everyone, or expired by the disappearing timer).
   * Only rows cached at or after [sinceTs] (the oldest message currently loaded) and before [olderThanTs] are
   * considered, so a message that arrived a moment ago is never mistaken for a missing one.
   */
  fun pruneChat(cid: String, keep: Set<String>, sinceTs: Long, olderThanTs: Long): Int {
    val db = plain().writableDatabase
    val gone = db.rawQuery(
      "SELECT DISTINCT mid FROM plain WHERE cid = ? AND ts >= ? AND ts < ?",
      arrayOf(cid, sinceTs.toString(), olderThanTs.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.filter { it !in keep }
    gone.forEach { db.delete("plain", "cid = ? AND mid = ?", arrayOf(cid, it)) }
    return gone.size
  }

  fun forgetChat(cid: String) {
    plain().writableDatabase.delete("plain", "cid = ?", arrayOf(cid))
  }

  // ---- sealing for other local files (pending uploads) ---------------------------------------------------------------

  fun seal(plain: ByteArray, context: String): ByteArray = crypto().seal(plain, context.toByteArray(Charsets.UTF_8))

  fun open(sealed: ByteArray, context: String): ByteArray = crypto().open(sealed, context.toByteArray(Charsets.UTF_8))

  // ---- erase ---------------------------------------------------------------------------------------------------------

  /** Deletes the protocol state, every decrypted message, the data key and the Keystore key. Safe to call twice. */
  fun wipe() {
    synchronized(lock) {
      runCatching { helper?.close() }
      helper = null
      crypto?.wipe()
      crypto = null
    }
    runCatching { context.deleteDatabase(File(root, "plain.db").path) }
    root.listFiles()?.forEach { runCatching { it.delete() } }
    wrapper.deleteKey()
  }

  private fun plain(): PlainDb = synchronized(lock) { helper ?: PlainDb(context, File(root, "plain.db").path).also { helper = it } }

  private fun rowContext(cid: String, mid: String, digest: String) = "e2ee:plain:$cid:$mid:$digest".toByteArray(Charsets.UTF_8)

  private class PlainDb(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL("CREATE TABLE plain (cid TEXT NOT NULL, mid TEXT NOT NULL, digest TEXT NOT NULL, blob BLOB NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (cid, mid, digest))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
  }

  companion object {
    const val ALIAS = "samvad_e2ee_wrap_v1"
    private val STATE_CONTEXT = "e2ee:state:v1".toByteArray(Charsets.US_ASCII)
  }
}
