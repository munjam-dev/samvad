package com.samvad.core.e2ee

import java.util.Base64
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.fingerprint.ScannableFingerprint
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** A signed public key (the signed pre-key or the Kyber pre-key) as published: ID, Base64 key, Base64 signature. */
data class SignedKeyDto(val keyId: Int, val publicKey: String, val signature: String) {
  fun toMap(): Map<String, Any> = mapOf("keyId" to keyId, "publicKey" to publicKey, "signature" to signature)

  companion object {
    fun fromMap(m: Map<*, *>?): SignedKeyDto? {
      if (m == null) return null
      return SignedKeyDto(
        (m["keyId"] as? Number)?.toInt() ?: return null,
        m["publicKey"] as? String ?: return null,
        m["signature"] as? String ?: return null,
      )
    }
  }
}

data class OneTimeKeyDto(val keyId: Int, val publicKey: String) {
  fun toMap(): Map<String, Any> = mapOf("keyId" to keyId, "publicKey" to publicKey)

  companion object {
    fun fromMap(m: Map<*, *>?): OneTimeKeyDto? {
      if (m == null) return null
      return OneTimeKeyDto((m["keyId"] as? Number)?.toInt() ?: return null, m["publicKey"] as? String ?: return null)
    }
  }
}

/** Public keys sent to the server by `publishPreKeys`. Private keys never leave the store. */
data class PreKeyUpload(
  val registrationId: Int,
  val identityKey: String,
  val signedPreKey: SignedKeyDto,
  val kyberPreKey: SignedKeyDto,
  val oneTimePreKeys: List<OneTimeKeyDto>,
) {
  fun toMap(): Map<String, Any> = mapOf(
    "registrationId" to registrationId,
    "identityKey" to identityKey,
    "signedPreKey" to signedPreKey.toMap(),
    "kyberPreKey" to kyberPreKey.toMap(),
    "oneTimePreKeys" to oneTimePreKeys.map { it.toMap() },
  )
}

/** What `fetchPreKeyBundle` returns: one person's public keys, with at most one one-time pre-key. */
data class RemoteBundle(
  val registrationId: Int,
  val identityKey: String,
  val signedPreKey: SignedKeyDto,
  val kyberPreKey: SignedKeyDto,
  val oneTimePreKey: OneTimeKeyDto?,
) {
  companion object {
    fun fromMap(m: Map<*, *>?): RemoteBundle? {
      if (m == null) return null
      return RemoteBundle(
        (m["registrationId"] as? Number)?.toInt() ?: return null,
        m["identityKey"] as? String ?: return null,
        SignedKeyDto.fromMap(m["signedPreKey"] as? Map<*, *>) ?: return null,
        SignedKeyDto.fromMap(m["kyberPreKey"] as? Map<*, *>) ?: return null,
        (m["oneTimePreKey"] as? Map<*, *>)?.let { OneTimeKeyDto.fromMap(it) },
      )
    }
  }
}

/** The person's identity key is not the one trusted so far. Nothing is sent or read until it is accepted. */
class IdentityChangedException(val uid: String) : Exception("identity key changed")

/** No session with this person yet: fetch their pre-key bundle and call [SignalEngine.buildSession]. */
class SessionRequiredException(val uid: String) : Exception("no session")

class BadBundleException(cause: Throwable? = null) : Exception("bad pre-key bundle", cause)

class DecryptException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
  enum class Reason {
    /** The same message was already processed. */
    DUPLICATE,

    /** A follow-up message arrived for a session this install does not have (new phone, lost keys). */
    NO_SESSION,

    /** Damaged, not addressed to this install's keys, or not a valid message. */
    INVALID,
  }
}

/** The 60 digits people compare, and the bytes behind the QR code. */
class SafetyNumber internal constructor(val digits: String, private val scannable: ScannableFingerprint) {
  /** 12 groups of 5 digits, for reading aloud. */
  fun grouped(): List<String> = digits.chunked(5)

  /** Content of the QR code. */
  fun qrBytes(): ByteArray = scannable.serialized

  /** True when [scanned] (from the other person's QR code) describes the same two identity keys. */
  fun matchesScan(scanned: ByteArray): Boolean = try {
    scannable.compareTo(scanned)
  } catch (e: Exception) {
    false
  }
}

/**
 * Signal Protocol operations for this install, on top of [SamvadSignalStore]: key generation, session setup from a
 * pre-key bundle (PQXDH: X25519 plus Kyber-1024), encrypt and decrypt with the Double Ratchet, signed pre-key
 * rotation and safety numbers. Network and storage stay outside; this class is pure and runs in JVM unit tests.
 * Calls are not thread-safe against each other: callers serialize them (the app holds one mutex).
 */
class SignalEngine(val store: SamvadSignalStore, private val clock: () -> Long = System::currentTimeMillis) {
  private val b64 = Base64.getEncoder()
  private val unb64 = Base64.getDecoder()

  fun hasSession(uid: String): Boolean = store.hasSessionWith(uid)

  // ---- keys to publish -----------------------------------------------------------------------------------------------

  /** Makes sure a signed pre-key and a Kyber pre-key exist, then creates [oneTimeCount] new one-time pre-keys. */
  fun createUpload(oneTimeCount: Int = ONE_TIME_BATCH): PreKeyUpload {
    require(oneTimeCount in 0..ONE_TIME_BATCH)
    if (store.loadSignedPreKeys().isEmpty() || store.loadKyberPreKeys().isEmpty()) generateSignedAndKyber()
    val first = if (oneTimeCount > 0) store.reservePreKeyIds(oneTimeCount) else 0
    val oneTime = (0 until oneTimeCount).map { i ->
      val pair = ECKeyPair.generate()
      store.storePreKey(first + i, PreKeyRecord(first + i, pair))
      OneTimeKeyDto(first + i, b64.encodeToString(pair.publicKey.serialize()))
    }
    return currentUpload(oneTime)
  }

  /** The public part of the current identity and signed keys, with no one-time keys (re-publishing after a rotation). */
  fun currentUpload(oneTime: List<OneTimeKeyDto> = emptyList()): PreKeyUpload {
    val signed = store.loadSignedPreKeys().maxByOrNull { it.timestamp } ?: error("no signed pre-key")
    val kyber = store.loadKyberPreKeys().maxByOrNull { it.timestamp } ?: error("no Kyber pre-key")
    return PreKeyUpload(
      registrationId = store.localRegistrationId,
      identityKey = b64.encodeToString(store.identityKeyPair.publicKey.serialize()),
      signedPreKey = SignedKeyDto(signed.id, b64.encodeToString(signed.keyPair.publicKey.serialize()), b64.encodeToString(signed.signature)),
      kyberPreKey = SignedKeyDto(kyber.id, b64.encodeToString(kyber.keyPair.publicKey.serialize()), b64.encodeToString(kyber.signature)),
      oneTimePreKeys = oneTime,
    )
  }

  private fun generateSignedAndKyber() {
    val now = clock()
    val signedId = store.reserveSignedPreKeyId()
    val signedPair = ECKeyPair.generate()
    val signedSig = store.identityKeyPair.privateKey.calculateSignature(signedPair.publicKey.serialize())
    store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, now, signedPair, signedSig))

    val kyberId = store.reserveKyberPreKeyId()
    val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    val kyberSig = store.identityKeyPair.privateKey.calculateSignature(kyberPair.publicKey.serialize())
    store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, now, kyberPair, kyberSig))
    store.setLastRotationMs(now)
  }

  /** True when the signed pre-key and the Kyber pre-key are older than [ROTATION_MS]. */
  fun rotationDue(): Boolean = clock() - store.lastRotationMs() > ROTATION_MS

  /**
   * Replaces the signed pre-key and the Kyber pre-key. The previous ones stay for [GRACE_MS] so first messages that
   * were already on their way still open, and are deleted once they are older than [ROTATION_MS] plus [GRACE_MS]. Returns what to publish.
   */
  fun rotate(): PreKeyUpload {
    generateSignedAndKyber()
    val now = clock()
    val newestSigned = store.signedPreKeyAges().lastOrNull()?.first
    store.signedPreKeyAges().filter { (id, ts) -> id != newestSigned && now - ts > ROTATION_MS + GRACE_MS }.forEach { store.removeSignedPreKey(it.first) }
    val newestKyber = store.kyberPreKeyAges().lastOrNull()?.first
    store.kyberPreKeyAges().filter { (id, ts) -> id != newestKyber && now - ts > ROTATION_MS + GRACE_MS }.forEach { store.removeKyberPreKey(it.first) }
    return currentUpload()
  }

  // ---- sessions ------------------------------------------------------------------------------------------------------

  /**
   * Starts a session with [uid] from the bundle the server handed out. libsignal verifies both signatures with the
   * bundle's identity key. If that identity key is not the one trusted before, nothing is trusted: the change is
   * remembered and [IdentityChangedException] is thrown.
   */
  fun buildSession(uid: String, bundle: RemoteBundle) {
    val prepared = try {
      val one = bundle.oneTimePreKey
      PreKeyBundle(
        bundle.registrationId, SamvadSignalStore.DEVICE_ID,
        one?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID, one?.let { ECPublicKey(unb64.decode(it.publicKey)) },
        bundle.signedPreKey.keyId, ECPublicKey(unb64.decode(bundle.signedPreKey.publicKey)), unb64.decode(bundle.signedPreKey.signature),
        IdentityKey(unb64.decode(bundle.identityKey)),
        bundle.kyberPreKey.keyId, KEMPublicKey(unb64.decode(bundle.kyberPreKey.publicKey)), unb64.decode(bundle.kyberPreKey.signature),
      )
    } catch (e: Exception) {
      throw BadBundleException(e)
    }
    try {
      SessionBuilder(store, SamvadSignalStore.address(uid)).process(prepared)
    } catch (e: UntrustedIdentityException) {
      store.notePendingIdentityChange(uid, prepared.identityKey)
      throw IdentityChangedException(uid)
    } catch (e: org.signal.libsignal.protocol.InvalidKeyException) {
      // Includes a bad signature on the signed pre-key or the Kyber pre-key.
      throw BadBundleException(e)
    }
  }

  fun encrypt(uid: String, plaintext: ByteArray): Envelope {
    if (store.pendingIdentityChange(uid) != null) throw IdentityChangedException(uid)
    if (!hasSession(uid)) throw SessionRequiredException(uid)
    val cipher = SessionCipher(store, SamvadSignalStore.address(uid))
    val message = try {
      cipher.encrypt(plaintext)
    } catch (e: UntrustedIdentityException) {
      throw IdentityChangedException(uid)
    } catch (e: NoSessionException) {
      throw SessionRequiredException(uid)
    }
    val type = when (message.type) {
      CiphertextMessage.PREKEY_TYPE -> Envelope.TYPE_PREKEY
      CiphertextMessage.WHISPER_TYPE -> Envelope.TYPE_WHISPER
      else -> error("unexpected message type")
    }
    val registrationId = cipher.remoteRegistrationId
    check(registrationId in 1..Envelope.MAX_REGISTRATION_ID) { "bad registration id" }
    return Envelope(Envelope.VERSION, type, b64.encodeToString(message.serialize()), registrationId)
  }

  /**
   * Decrypts one envelope from [uid]. Each ciphertext can be opened once (replays are refused), so the caller keeps
   * the plaintext. A first message from a changed identity key throws [IdentityChangedException] and is NOT opened.
   */
  fun decrypt(uid: String, envelope: Envelope): ByteArray {
    val cipher = SessionCipher(store, SamvadSignalStore.address(uid))
    val bytes = try { envelope.cipherBytes() } catch (e: IllegalArgumentException) { throw DecryptException(DecryptException.Reason.INVALID, e) }
    try {
      return if (envelope.type == Envelope.TYPE_PREKEY) {
        val message = PreKeySignalMessage(bytes)
        try {
          cipher.decrypt(message)
        } catch (e: UntrustedIdentityException) {
          store.notePendingIdentityChange(uid, message.identityKey)
          throw IdentityChangedException(uid)
        }
      } else {
        cipher.decrypt(SignalMessage(bytes))
      }
    } catch (e: IdentityChangedException) {
      throw e
    } catch (e: UntrustedIdentityException) {
      throw IdentityChangedException(uid)
    } catch (e: DuplicateMessageException) {
      throw DecryptException(DecryptException.Reason.DUPLICATE, e)
    } catch (e: NoSessionException) {
      throw DecryptException(DecryptException.Reason.NO_SESSION, e)
    } catch (e: Exception) {
      throw DecryptException(DecryptException.Reason.INVALID, e)
    }
  }

  // ---- trust -----------------------------------------------------------------------------------------------------------

  /** The safety number for the chat between [myUid] and [theirUid]; null until their identity key is known. */
  fun safetyNumber(myUid: String, theirUid: String, forPendingKey: Boolean = false): SafetyNumber? {
    val theirs = (if (forPendingKey) store.pendingIdentityChange(theirUid) else null)
      ?: store.getIdentity(SamvadSignalStore.address(theirUid)) ?: return null
    val fp = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
      FINGERPRINT_VERSION,
      myUid.toByteArray(Charsets.UTF_8), store.identityKeyPair.publicKey,
      theirUid.toByteArray(Charsets.UTF_8), theirs,
    )
    return SafetyNumber(fp.displayableFingerprint.displayText, fp.scannableFingerprint)
  }

  companion object {
    const val ONE_TIME_BATCH = 100
    const val REPLENISH_BELOW = 25
    const val ROTATION_MS = 30L * 24 * 60 * 60 * 1000
    const val GRACE_MS = 30L * 24 * 60 * 60 * 1000
    private const val FINGERPRINT_ITERATIONS = 5200
    private const val FINGERPRINT_VERSION = 2
  }
}
