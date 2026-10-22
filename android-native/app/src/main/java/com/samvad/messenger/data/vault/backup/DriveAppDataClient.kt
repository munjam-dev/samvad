package com.samvad.messenger.data.vault.backup

import com.samvad.core.vault.BackupException
import com.samvad.core.vault.BackupFailure
import com.samvad.core.vault.DriveRules
import com.samvad.core.vault.RemoteBackupFile
import com.samvad.core.vault.RemoteQuota
import com.samvad.core.vault.VaultBackupRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Google Drive REST v3, plain HTTPS, only the `appDataFolder` space (the hidden app-data folder). Resumable upload
 * for the archive (which can be large), list, ranged download, delete. No Google client libraries. The access token
 * comes from [DriveAuthorizer] and is only ever sent to googleapis.com in an Authorization header; nothing here logs
 * URLs, headers or bodies.
 */
class DriveAppDataClient(private val token: suspend () -> String) : VaultBackupRemote {
  override suspend fun list(): List<RemoteBackupFile> = withContext(Dispatchers.IO) {
    val result = ArrayList<RemoteBackupFile>()
    var page: String? = null
    do {
      var url = "$API/files?spaces=${DriveRules.SPACE}&pageSize=100&fields=" + enc("nextPageToken,files(id,name,size,modifiedTime)")
      if (page != null) url += "&pageToken=" + enc(page)
      val json = JSONObject(getText(url))
      val files = json.optJSONArray("files")
      for (i in 0 until (files?.length() ?: 0)) result += parseFile(files!!.getJSONObject(i))
      page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
    } while (page != null)
    result
  }

  override suspend fun quota(): RemoteQuota? = withContext(Dispatchers.IO) {
    try {
      val q = JSONObject(getText("$API/about?fields=" + enc("storageQuota(limit,usage)"))).optJSONObject("storageQuota")
      q?.let { RemoteQuota(it.optString("limit").toLongOrNull(), it.optString("usage", "0").toLongOrNull() ?: 0L) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null // Not being able to read the quota must not stop a backup; Drive says no itself if it is full.
    }
  }

  override suspend fun upload(source: File, name: String, onProgress: (Long, Long) -> Unit): RemoteBackupFile =
    withContext(Dispatchers.IO) {
      val total = source.length()
      if (total <= 0) throw BackupException(BackupFailure.FAILED)
      var restarts = 0
      var uploaded: RemoteBackupFile? = null
      while (uploaded == null) {
        val session = startSession(name, total)
        try {
          uploaded = sendChunks(session, source, total, onProgress)
        } catch (e: SessionGone) {
          // The upload address expired: begin again with a new one (twice at most).
          if (++restarts > 2) throw BackupException(BackupFailure.TRANSIENT)
        }
      }
      checkNotNull(uploaded)
    }

  override suspend fun download(file: RemoteBackupFile, target: File, onProgress: (Long, Long) -> Unit) =
    withContext(Dispatchers.IO) {
      target.parentFile?.mkdirs()
      target.delete()
      var attempt = 0
      while (target.length() < file.sizeBytes || file.sizeBytes <= 0) {
        coroutineContext.ensureActive()
        val have = if (target.isFile) target.length() else 0L
        try {
          val conn = open("$API/files/${enc(file.id)}?alt=media", "GET", token())
          try {
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val status = conn.responseCode
            if (status != 200 && status != 206) throw failure(conn, status)
            // A 200 to a ranged request means the server sent everything again.
            val append = status == 206
            FileOutputStream(target, append).use { out ->
              conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var received = if (append) have else 0L
                while (true) {
                  coroutineContext.ensureActive()
                  val n = input.read(buf)
                  if (n < 0) break
                  out.write(buf, 0, n)
                  received += n
                  onProgress(received, file.sizeBytes)
                }
              }
            }
          } finally {
            conn.disconnect()
          }
          if (file.sizeBytes <= 0) break
          if (target.length() < file.sizeBytes) throw IOException("Download ended early")
        } catch (e: BackupException) {
          if (e.failure != BackupFailure.TRANSIENT || ++attempt >= DriveRules.MAX_ATTEMPTS) throw e
          delay(DriveRules.backoffMillis(attempt))
        } catch (e: CancellationException) {
          throw e
        } catch (e: IOException) {
          if (++attempt >= DriveRules.MAX_ATTEMPTS) throw networkError(e)
          delay(DriveRules.backoffMillis(attempt))
        }
      }
      if (target.length() != file.sizeBytes) {
        target.delete()
        throw BackupException(BackupFailure.DAMAGED)
      }
    }

  override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
    val conn = open("$API/files/${enc(id)}", "DELETE", token())
    try {
      val status = conn.responseCode
      if (status != 204 && status != 200 && status != 404) throw failure(conn, status)
    } catch (e: IOException) {
      if (e is BackupException) throw e
      throw networkError(e)
    } finally {
      conn.disconnect()
    }
  }

  // ---- resumable upload ----

  private class SessionGone : Exception()

  private sealed interface Probe {
    class At(val offset: Long) : Probe

    class Done(val file: RemoteBackupFile) : Probe
  }

  private suspend fun startSession(name: String, total: Long): String {
    val conn = open("$UPLOAD/files?uploadType=resumable&fields=" + enc("id,name,size,modifiedTime"), "POST", token())
    try {
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
      conn.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
      conn.setRequestProperty("X-Upload-Content-Length", total.toString())
      val body = JSONObject().put("name", name).put("parents", JSONArray().put(DriveRules.SPACE)).toString().toByteArray()
      conn.setFixedLengthStreamingMode(body.size)
      conn.outputStream.use { it.write(body) }
      val status = conn.responseCode
      if (status != 200) throw failure(conn, status)
      return conn.getHeaderField("Location") ?: throw BackupException(BackupFailure.FAILED)
    } catch (e: IOException) {
      if (e is BackupException) throw e
      throw networkError(e)
    } finally {
      conn.disconnect()
    }
  }

  private suspend fun sendChunks(session: String, source: File, total: Long, onProgress: (Long, Long) -> Unit): RemoteBackupFile {
    var offset = 0L
    var attempt = 0
    RandomAccessFile(source, "r").use { raf ->
      while (true) {
        coroutineContext.ensureActive()
        val end = minOf(offset + DriveRules.UPLOAD_CHUNK_BYTES, total)
        try {
          val conn = session(session, "PUT").apply {
            setRequestProperty("Content-Range", DriveRules.contentRange(offset, end, total))
            setFixedLengthStreamingMode(end - offset)
          }
          try {
            raf.seek(offset)
            conn.outputStream.use { out ->
              val buf = ByteArray(64 * 1024)
              var left = end - offset
              var sent = offset
              while (left > 0) {
                coroutineContext.ensureActive()
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("File ended early")
                out.write(buf, 0, n)
                left -= n
                sent += n
                onProgress(sent, total)
              }
            }
            when (val status = conn.responseCode) {
              200, 201 -> return parseFile(JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }))
              308 -> {
                offset = DriveRules.nextOffset(conn.getHeaderField("Range")).coerceIn(0, total)
                attempt = 0
              }
              404, 410 -> throw SessionGone()
              else -> throw failure(conn, status)
            }
          } finally {
            conn.disconnect()
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: SessionGone) {
          throw e
        } catch (e: BackupException) {
          if (e.failure != BackupFailure.TRANSIENT || ++attempt >= DriveRules.MAX_ATTEMPTS) throw e
          delay(DriveRules.backoffMillis(attempt))
          when (val p = probe(session, total)) {
            is Probe.At -> offset = p.offset
            is Probe.Done -> return p.file
          }
        } catch (e: IOException) {
          if (++attempt >= DriveRules.MAX_ATTEMPTS) throw networkError(e)
          delay(DriveRules.backoffMillis(attempt))
          val p = try {
            probe(session, total)
          } catch (_: IOException) {
            Probe.At(offset) // still no connection: the next round tries the same chunk again
          }
          when (p) {
            is Probe.At -> offset = p.offset
            is Probe.Done -> return p.file
          }
        }
      }
    }
    @Suppress("UNREACHABLE_CODE")
    error("unreachable")
  }

  /** Asks Drive how much of the upload it has, or returns the finished file if the last answer was lost. */
  private fun probe(session: String, total: Long): Probe {
    val conn = session(session, "PUT").apply {
      setFixedLengthStreamingMode(0)
      setRequestProperty("Content-Range", DriveRules.statusQueryRange(total))
    }
    try {
      conn.outputStream.close()
      return when (val status = conn.responseCode) {
        308 -> Probe.At(DriveRules.nextOffset(conn.getHeaderField("Range")).coerceIn(0, total))
        200, 201 -> Probe.Done(parseFile(JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })))
        404, 410 -> throw SessionGone()
        else -> throw failure(conn, status)
      }
    } finally {
      conn.disconnect()
    }
  }

  /** The upload address Drive gave us is itself the credential, so no Authorization header is sent to it. */
  private fun session(address: String, method: String): HttpURLConnection {
    require(address.startsWith("https://www.googleapis.com/")) { "Only Google APIs" }
    return (URL(address).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      doOutput = true
    }
  }

  // ---- plumbing ----

  private fun parseFile(f: JSONObject) = RemoteBackupFile(
    f.getString("id"),
    f.optString("name"),
    f.optString("size", "0").toLongOrNull() ?: 0L,
    runCatching { Instant.parse(f.optString("modifiedTime")).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
  )

  private suspend fun getText(url: String): String {
    val conn = open(url, "GET", token())
    try {
      val status = conn.responseCode
      if (status != 200) throw failure(conn, status)
      return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
      if (e is BackupException) throw e
      throw networkError(e)
    } finally {
      conn.disconnect()
    }
  }

  private fun open(url: String, method: String, bearer: String): HttpURLConnection {
    require(url.startsWith("https://www.googleapis.com/")) { "Only Google APIs" }
    return (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = TIMEOUT_MS
      readTimeout = TIMEOUT_MS
      setRequestProperty("Authorization", "Bearer $bearer")
      setRequestProperty("Accept", "application/json")
    }
  }

  private fun failure(conn: HttpURLConnection, status: Int): BackupException {
    val body = runCatching { conn.errorStream?.use { it.readBytes().take(4096).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
    return BackupException(DriveRules.classify(status, body))
  }

  private fun networkError(e: IOException) = BackupException(BackupFailure.NO_NETWORK, e)

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  private companion object {
    const val API = "https://www.googleapis.com/drive/v3"
    const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    const val TIMEOUT_MS = 30_000
  }
}
