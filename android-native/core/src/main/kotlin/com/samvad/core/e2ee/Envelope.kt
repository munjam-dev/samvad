package com.samvad.core.e2ee

import java.security.MessageDigest
import java.util.Base64

/**
 * The only thing the server stores for the content of an end-to-end encrypted message: a Signal Protocol
 * ciphertext plus three routing fields. Mirrors the `enc` map checked by firestore.rules.
 *
 * - [type]: "prekey" (the first messages of a session, carry the key-agreement data) or "whisper" (normal).
 * - [ciphertext]: Base64 of the serialized libsignal message.
 * - [registrationId]: the recipient's registration ID that the sender used. The rules compare it with the
 *   recipient's currently published one, so a message addressed to a replaced install (new phone, new keys)
 *   is refused instead of being stored as something nobody can read.
 */
data class Envelope(val v: Int, val type: String, val ciphertext: String, val registrationId: Int) {
  fun toMap(): Map<String, Any> = mapOf("v" to v, "type" to type, "ciphertext" to ciphertext, "registrationId" to registrationId)

  fun cipherBytes(): ByteArray = Base64.getDecoder().decode(ciphertext)

  /** Short fingerprint of the ciphertext, used to notice that an edited message carries a new envelope. */
  fun digest(): String = hex(MessageDigest.getInstance("SHA-256").digest(ciphertext.toByteArray(Charsets.US_ASCII)))

  companion object {
    const val VERSION = 1
    const val TYPE_PREKEY = "prekey"
    const val TYPE_WHISPER = "whisper"

    /** Same cap as firestore.rules: about 24 KB of ciphertext, enough for 4,096 characters of any script. */
    const val MAX_CIPHERTEXT_CHARS = 32_768

    /** libsignal registration IDs are 14 bits (1..16380). */
    const val MAX_REGISTRATION_ID = 16_380

    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /**
     * Reads the `enc` map of a message document. Returns null for anything that does not match the exact shape
     * (extra keys, wrong types, unknown version or type, oversize or non-Base64 ciphertext).
     */
    fun fromMap(map: Map<*, *>?): Envelope? {
      if (map == null || map.size != 4) return null
      val v = (map["v"] as? Number)?.toLong() ?: return null
      val type = map["type"] as? String ?: return null
      val ct = map["ciphertext"] as? String ?: return null
      val reg = (map["registrationId"] as? Number)?.toLong() ?: return null
      if (v != VERSION.toLong()) return null
      if (type != TYPE_PREKEY && type != TYPE_WHISPER) return null
      if (ct.isEmpty() || ct.length > MAX_CIPHERTEXT_CHARS || ct.length % 4 != 0 || !BASE64.matches(ct)) return null
      if (reg < 1 || reg > MAX_REGISTRATION_ID) return null
      return Envelope(VERSION, type, ct, reg.toInt())
    }

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
      for (b in bytes) {
        append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
        append("0123456789abcdef"[b.toInt() and 0xF])
      }
    }
  }
}
