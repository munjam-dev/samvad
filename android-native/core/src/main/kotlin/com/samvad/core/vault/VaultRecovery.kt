package com.samvad.core.vault

import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The text the person typed is not a recovery code or passphrase this backup can use. Treated as "wrong secret". */
class RecoverySecretFormatException(message: String) : VaultCryptoException(message)

/**
 * The recovery code: 256 random bits written as 52 characters of Crockford base32 (digits and letters
 * without I, L, O, U) shown in 13 groups of 4. It is the only thing, apart from the encrypted archive,
 * needed to open a backup on a new phone. It is never stored on the phone.
 */
object RecoveryCode {
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  const val KEY_BYTES = 32
  const val CHARS = 52
  const val GROUP = 4
  const val GROUPS = CHARS / GROUP

  /** Fresh random key. The caller wipes it after use. */
  fun generate(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

  /** The key as 52 characters without separators. */
  fun encode(key: ByteArray): String {
    require(key.size == KEY_BYTES) { "A recovery key is $KEY_BYTES bytes" }
    val sb = StringBuilder(CHARS)
    var buffer = 0
    var bits = 0
    for (b in key) {
      buffer = (buffer shl 8) or (b.toInt() and 0xFF)
      bits += 8
      while (bits >= 5) {
        sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
        bits -= 5
      }
      buffer = buffer and ((1 shl bits) - 1)
    }
    if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
    return sb.toString()
  }

  /** The 13 groups of 4 characters, in order. */
  fun groups(key: ByteArray): List<String> = encode(key).chunked(GROUP)

  /** Display form: groups separated by a space. */
  fun format(key: ByteArray): String = groups(key).joinToString(" ")

  /** Removes spaces and dashes, upper-cases, and maps the look-alikes I and L to 1 and O to 0. */
  fun normalize(text: String): String = buildString {
    for (ch in text) {
      if (ch.isWhitespace() || ch == '-' || ch == '‐' || ch == '‑' || ch == '–') continue
      append(
        when (val up = ch.uppercaseChar()) {
          'I', 'L' -> '1'
          'O' -> '0'
          else -> up
        },
      )
    }
  }

  /** @return the 32-byte key, or null when [text] is not a valid code. */
  fun parse(text: String): ByteArray? {
    val clean = normalize(text)
    if (clean.length != CHARS) return null
    val out = ByteArray(KEY_BYTES)
    var buffer = 0
    var bits = 0
    var index = 0
    for (ch in clean) {
      val v = ALPHABET.indexOf(ch)
      if (v < 0) return null
      buffer = (buffer shl 5) or v
      bits += 5
      if (bits >= 8) {
        if (index >= KEY_BYTES) return null
        out[index++] = ((buffer shr (bits - 8)) and 0xFF).toByte()
        bits -= 8
        buffer = buffer and ((1 shl bits) - 1)
      }
    }
    // The last character carries 4 spare bits that must be zero in a code made by [encode].
    if (index != KEY_BYTES || buffer != 0) return null
    return out
  }

  /** Picks [count] different group positions (0-based, ascending) to ask the person to type back. */
  fun challenge(count: Int = 3, random: SecureRandom = SecureRandom()): List<Int> {
    require(count in 1..GROUPS)
    val picked = sortedSetOf<Int>()
    while (picked.size < count) picked.add(random.nextInt(GROUPS))
    return picked.toList()
  }

  /** True when every typed group (keyed by 0-based position) matches the code, ignoring case and look-alikes. */
  fun checkGroups(key: ByteArray, typed: Map<Int, String>): Boolean {
    if (typed.isEmpty()) return false
    val groups = groups(key)
    return typed.all { (position, text) ->
      position in groups.indices && normalize(text) == groups[position]
    }
  }
}

/** The passphrase alternative to the recovery code. */
object BackupPassphrase {
  const val MIN_LENGTH = 12
  const val MAX_LENGTH = 256

  /** Unicode compatibility form, so the same typed text always gives the same key. */
  fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

  fun isAcceptable(text: String): Boolean {
    val n = normalize(text)
    val length = n.codePointCount(0, n.length)
    return length in MIN_LENGTH..MAX_LENGTH && n.isNotBlank()
  }
}

/** Key derivation for the backup archive. */
object BackupKdf {
  /** OWASP guidance for PBKDF2-HMAC-SHA256. Argon2 is not available without a native dependency. */
  const val PBKDF2_MIN_ITERATIONS = 600_000
  const val PBKDF2_MAX_ITERATIONS = 10_000_000
  private val INFO = "samvad-vault-backup-v1".toByteArray(Charsets.US_ASCII)

  /** HKDF-SHA256 of a high-entropy recovery key. No slow hash is needed for 256 random bits. */
  fun fromRecoveryKey(key: ByteArray, salt: ByteArray): ByteArray {
    require(key.size == RecoveryCode.KEY_BYTES)
    val prk = Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(salt, "HmacSHA256"))
      doFinal(key)
    }
    val okm = Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(prk, "HmacSHA256"))
      update(INFO)
      update(1)
      doFinal()
    }
    prk.fill(0)
    return okm
  }

  fun fromPassphrase(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
    require(iterations in PBKDF2_MIN_ITERATIONS..PBKDF2_MAX_ITERATIONS) { "Unsupported iteration count" }
    val spec = PBEKeySpec(BackupPassphrase.normalize(passphrase).toCharArray(), salt, iterations, 256)
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }
}
