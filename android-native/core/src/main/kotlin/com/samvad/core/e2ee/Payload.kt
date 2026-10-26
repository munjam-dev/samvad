package com.samvad.core.e2ee

import com.samvad.core.chat.ContactCard
import java.util.Base64

/** One encrypted file stored in Storage: where, the AES-256-GCM key, the SHA-256 digest of the stored bytes, and their size. */
data class AttachmentRef(val path: String, val key: String, val digest: String, val size: Long) {
  fun keyBytes(): ByteArray = Base64.getDecoder().decode(key)
}

/**
 * What an end-to-end encrypted message says, before encryption and after decryption. Nothing here is visible to
 * the server: the message document keeps only the [Envelope], the kind and routing fields.
 */
sealed interface Payload {
  /** The message this one replies to (kept inside the envelope, so the server does not learn reply links). */
  val replyTo: String?
  val forwarded: Boolean

  data class Text(val text: String, override val replyTo: String? = null, override val forwarded: Boolean = false) : Payload

  data class Contact(val card: ContactCard, override val replyTo: String? = null, override val forwarded: Boolean = false) : Payload

  /** A photo, voice message, file or video. Display name, type, size and dimensions are inside the envelope. */
  data class Media(
    val kind: String,
    val name: String,
    val mime: String,
    val caption: String = "",
    val file: AttachmentRef,
    val thumb: AttachmentRef? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val waveform: List<Int> = emptyList(),
    override val replyTo: String? = null,
    override val forwarded: Boolean = false,
  ) : Payload
}

/**
 * Payload format, version 1: JSON, padded with a 0x80 marker and zeros to a multiple of 160 bytes (as Signal does)
 * so that short messages do not all differ in length by a few bytes. Decoding is strict: anything unexpected
 * returns null, and the message is shown as unreadable instead of guessed at.
 */
object PayloadCodec {
  const val BLOCK = 160
  const val MAX_PLAINTEXT_BYTES = 24 * 1024
  private val MEDIA_KINDS = setOf("image", "file", "voice", "video")
  private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
  private val HEX64 = Regex("^[0-9a-f]{64}$")

  fun encode(payload: Payload): ByteArray {
    val map = LinkedHashMap<String, Any?>()
    map["v"] = 1
    payload.replyTo?.let { map["reply"] = it }
    if (payload.forwarded) map["fwd"] = true
    when (payload) {
      is Payload.Text -> {
        map["t"] = "text"
        map["text"] = payload.text
      }
      is Payload.Contact -> {
        map["t"] = "contact"
        map["contact"] = linkedMapOf<String, Any?>(
          "name" to payload.card.name,
          "phones" to payload.card.phones,
        ).also { m -> payload.card.organisation?.let { m["org"] = it } }
      }
      is Payload.Media -> {
        map["t"] = "media"
        map["media"] = linkedMapOf<String, Any?>(
          "kind" to payload.kind,
          "name" to payload.name,
          "mime" to payload.mime,
          "file" to ref(payload.file),
        ).also { m ->
          if (payload.caption.isNotEmpty()) m["cap"] = payload.caption
          payload.thumb?.let { m["thumb"] = ref(it) }
          payload.width?.let { m["w"] = it }
          payload.height?.let { m["h"] = it }
          payload.durationMs?.let { m["dur"] = it }
          if (payload.waveform.isNotEmpty()) m["wf"] = payload.waveform
        }
      }
    }
    return pad(MiniJson.write(map).toByteArray(Charsets.UTF_8))
  }

  /**
   * [pathPrefix] is the Storage folder this message's files must live in ("conversations/{cid}/{mid}/"), so a
   * sender cannot make a receiving phone fetch some other file it is allowed to read.
   */
  fun decode(padded: ByteArray, pathPrefix: String? = null): Payload? {
    return try {
    val json = String(unpad(padded) ?: return null, Charsets.UTF_8)
    val map = MiniJson.parse(json) as? Map<*, *> ?: return null
    if ((map["v"] as? Number)?.toLong() != 1L) return null
    val reply = (map["reply"] as? String)?.also { if (!UUID.matches(it)) return null }
    val fwd = map["fwd"] == true
    when (map["t"]) {
      "text" -> (map["text"] as? String)?.let { Payload.Text(it, reply, fwd) }
      "contact" -> {
        val c = map["contact"] as? Map<*, *> ?: return null
        val name = c["name"] as? String ?: return null
        val phones = (c["phones"] as? List<*>)?.map { it as? String ?: return null } ?: return null
        Payload.Contact(ContactCard(name, phones, c["org"] as? String), reply, fwd)
      }
      "media" -> media(map["media"] as? Map<*, *> ?: return null, reply, fwd, pathPrefix)
      else -> null
    }
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun media(m: Map<*, *>, reply: String?, fwd: Boolean, prefix: String?): Payload.Media? {
    val kind = m["kind"] as? String ?: return null
    if (kind !in MEDIA_KINDS) return null
    val file = readRef(m["file"] as? Map<*, *> ?: return null, prefix) ?: return null
    val thumb = (m["thumb"] as? Map<*, *>)?.let { readRef(it, prefix) ?: return null }
    return Payload.Media(
      kind = kind,
      name = m["name"] as? String ?: return null,
      mime = m["mime"] as? String ?: return null,
      caption = m["cap"] as? String ?: "",
      file = file,
      thumb = thumb,
      width = (m["w"] as? Number)?.toInt(),
      height = (m["h"] as? Number)?.toInt(),
      durationMs = (m["dur"] as? Number)?.toLong(),
      waveform = (m["wf"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
      replyTo = reply,
      forwarded = fwd,
    )
  }

  private fun ref(r: AttachmentRef): Map<String, Any> =
    linkedMapOf("path" to r.path, "key" to r.key, "digest" to r.digest, "size" to r.size)

  private fun readRef(m: Map<*, *>, prefix: String?): AttachmentRef? {
    val path = m["path"] as? String ?: return null
    val key = m["key"] as? String ?: return null
    val digest = m["digest"] as? String ?: return null
    val size = (m["size"] as? Number)?.toLong() ?: return null
    if (!HEX64.matches(digest) || size <= 0) return null
    val keyBytes = try { Base64.getDecoder().decode(key) } catch (e: IllegalArgumentException) { return null }
    if (keyBytes.size != AttachmentCrypto.KEY_BYTES) return null
    if (prefix != null && (!path.startsWith(prefix) || path.contains("..") || path.length > 300)) return null
    return AttachmentRef(path, key, digest, size)
  }

  fun pad(data: ByteArray): ByteArray {
    val total = ((data.size + 1 + BLOCK - 1) / BLOCK) * BLOCK
    val out = ByteArray(total)
    System.arraycopy(data, 0, out, 0, data.size)
    out[data.size] = 0x80.toByte()
    return out
  }

  fun unpad(padded: ByteArray): ByteArray? {
    var i = padded.size - 1
    while (i >= 0 && padded[i] == 0.toByte()) i--
    if (i < 0 || padded[i] != 0x80.toByte()) return null
    return padded.copyOf(i)
  }

  /**
   * Chat-list line for a decrypted message. Same wording as the server's preview of ordinary messages; only ever
   * built on the device, from the decrypted payload.
   */
  fun previewOf(p: Payload, maxLength: Int = 120): String {
    val line = when (p) {
      is Payload.Text -> p.text.trim()
      is Payload.Contact -> "👤 ${p.card.name}"
      is Payload.Media -> {
        val label = when (p.kind) {
          "image" -> "📷 Photo"
          "voice" -> "🎤 Voice message"
          "video" -> "🎬 Video"
          else -> "📄 ${p.name}"
        }
        if (p.caption.isNotBlank() && p.kind != "voice") "$label · ${p.caption.trim()}" else label
      }
    }
    return line.take(maxLength)
  }
}
