package com.samvad.messenger.data.e2ee

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.e2ee.AttachmentCrypto
import com.samvad.core.e2ee.AttachmentRef
import com.samvad.core.e2ee.BadBundleException
import com.samvad.core.e2ee.DecryptException
import com.samvad.core.e2ee.EncryptedAttachment
import com.samvad.core.e2ee.Envelope
import com.samvad.core.e2ee.IdentityChangedException
import com.samvad.core.e2ee.LockReason
import com.samvad.core.e2ee.Payload
import com.samvad.core.e2ee.PayloadCodec
import com.samvad.core.e2ee.PreKeyUpload
import com.samvad.core.e2ee.RemoteBundle
import com.samvad.core.e2ee.SafetyNumber
import com.samvad.core.e2ee.SamvadSignalStore
import com.samvad.core.e2ee.SessionRequiredException
import com.samvad.core.e2ee.SignalEngine
import com.samvad.messenger.BuildConfig
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** The key and digest for one encrypted file, learned from a decrypted message. */
class AttachmentKey(val key: ByteArray, val digestHex: String, val fileExtension: String)

/** Storage path of an encrypted file to its key. Filled while messages are opened; used when a file is downloaded. */
class AttachmentKeys {
  private val map = ConcurrentHashMap<String, AttachmentKey>()
  fun put(path: String, key: AttachmentKey) { map[path] = key }
  fun get(path: String): AttachmentKey? = map[path]
  fun clear() = map.clear()
}

/** Where a person stands for starting or continuing an encrypted conversation. */
enum class PeerKeys {
  /** A session exists (or was just made) and sending can go ahead. */
  READY,

  /** The other person has not published keys (older app, signed out). */
  NO_KEYS,

  /** Their identity key changed; sending and reading wait for an explicit decision. */
  IDENTITY_CHANGED,

  /** Network problem, daily limit, or an unreadable bundle. Try again later. */
  UNAVAILABLE,
}

sealed interface Opened {
  data class Ready(val payload: Payload) : Opened
  data class Locked(val reason: LockReason) : Opened
}

/** Thrown when a message cannot be sent encrypted. It is never turned into a plaintext send. */
class E2eeSendException(val peer: PeerKeys) : Exception("cannot send encrypted: $peer")

data class PeerTrust(val hasIdentity: Boolean, val verified: Boolean, val changePending: Boolean)

/** Output of [E2eeService.sealMedia]: the encrypted files and the description that goes into the envelope. */
class SealedMedia(
  val kind: String,
  val name: String,
  val mime: String,
  val width: Int?,
  val height: Int?,
  val durationMs: Long?,
  val waveform: List<Int>,
  val file: EncryptedAttachment,
  val thumb: EncryptedAttachment?,
) {
  fun ref() = EncryptedMediaRef(
    kind, name, mime, width, height, durationMs, waveform,
    file.key, file.digestHex, file.cipherBytes.size.toLong(),
    thumb?.key, thumb?.digestHex, thumb?.cipherBytes?.size?.toLong(),
  )
}

/** Everything the envelope needs to describe an uploaded encrypted attachment: keys, digests, ciphertext sizes, and what the file is. */
class EncryptedMediaRef(
  val kind: String,
  val name: String,
  val mime: String,
  val width: Int?,
  val height: Int?,
  val durationMs: Long?,
  val waveform: List<Int>,
  val fileKey: ByteArray,
  val fileDigest: String,
  val fileSize: Long,
  val thumbKey: ByteArray?,
  val thumbDigest: String?,
  val thumbSize: Long?,
)

/**
 * End-to-end encryption on this phone (docs/security/E2EE_DESIGN.md). Owns the Signal Protocol state, talks to the
 * key-directory callables, and turns payloads into envelopes and back. Everything that touches the protocol state
 * runs under one mutex: libsignal sessions must not be used from two threads at once.
 *
 * Rules this class enforces:
 * - a message is never sent in the clear because encryption failed: failures are thrown ([E2eeSendException]);
 * - a ciphertext is opened once, and the plaintext is kept in [E2eeLocalStore] (sealed), because it cannot be opened twice;
 * - nothing here writes keys, plaintext or ciphertext to a log.
 */
class E2eeService(
  context: Context,
  private val functions: FirebaseFunctions,
  /** Overridable for tests and for builds that exclude encryption. */
  val enabled: Boolean = BuildConfig.E2EE_ENABLED,
) {
  private val store = E2eeLocalStore(context.applicationContext)
  private val mutex = Mutex()
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()
  private val b64 = Base64.getEncoder()
  private var engine: SignalEngine? = null
  private var engineOwner: String? = null
  @Volatile private var publishedFor: String? = null

  private val _trustVersion = MutableStateFlow(0)

  /** Changes whenever a trust decision or a detected key change happens, so screens re-read [trust]. */
  val trustVersion: StateFlow<Int> = _trustVersion.asStateFlow()

  /** Who has published keys, learned from the key server when starting a chat (a hint for the banner, not a promise). */
  private val peerKeysSeen = ConcurrentHashMap<String, Boolean>()

  fun knownPeerHasKeys(uid: String): Boolean? = peerKeysSeen[uid]

  // ---- engine ----------------------------------------------------------------------------------------------------

  /** Must be called with [mutex] held. */
  private fun engineFor(uid: String): SignalEngine {
    engine?.let { if (engineOwner == uid) return it }
    if (store.owner() != null && store.owner() != uid) store.wipe()
    val saved = store.readState()
    val created = when {
      saved != null -> runCatching { SamvadSignalStore.restore(saved, store::writeState) }.getOrNull()
      else -> null
    } ?: run {
      // First use, or the saved state is gone or unreadable (new phone, cleared data, lost Keystore key): start over.
      // Old encrypted history cannot be recovered; the other person is told our security code changed.
      if (store.hasStateFile()) store.wipe()
      SamvadSignalStore.create(store::writeState)
    }
    store.setOwner(uid)
    return SignalEngine(created).also {
      engine = it
      engineOwner = uid
    }
  }

  // ---- publishing keys -----------------------------------------------------------------------------------------------

  /**
   * Makes sure this install has keys and the key server has the matching public bundle: publishes on first use or
   * after a reinstall, tops up one-time keys, and rotates the signed pre-key every 30 days. Safe to call often.
   * Returns false when encryption is off or the server could not be reached.
   */
  suspend fun ensureReady(uid: String): Boolean {
    if (!enabled) return false
    return try {
      mutex.withLock {
        val e = engineFor(uid)
        val status = call("preKeyStatus", emptyMap<String, Any>())
        val localIdentity = b64.encodeToString(e.store.identityKeyPair.publicKey.serialize())
        val remaining = (status["oneTimeRemaining"] as? Number)?.toInt() ?: 0
        when {
          status["published"] != true || status["identityKey"] != localIdentity -> publish(e.createUpload(SignalEngine.ONE_TIME_BATCH))
          remaining < SignalEngine.REPLENISH_BELOW -> publish(e.createUpload(SignalEngine.ONE_TIME_BATCH))
          e.rotationDue() -> publish(e.rotate())
        }
        publishedFor = uid
      }
      true
    } catch (e: Exception) {
      false
    }
  }

  private suspend fun publish(upload: PreKeyUpload) {
    call("publishPreKeys", upload.toMap())
  }

  fun isReady(uid: String): Boolean = enabled && publishedFor == uid

  // ---- sessions ------------------------------------------------------------------------------------------------------

  private sealed interface Fetched {
    data class Bundle(val bundle: RemoteBundle) : Fetched
    data object NoKeys : Fetched
    data object Unavailable : Fetched
  }

  private suspend fun fetch(other: String): Fetched = try {
    val data = call("fetchPreKeyBundle", mapOf("uid" to other))
    RemoteBundle.fromMap(data)?.let { Fetched.Bundle(it) } ?: Fetched.Unavailable
  } catch (e: FirebaseFunctionsException) {
    if (e.code == FirebaseFunctionsException.Code.NOT_FOUND) Fetched.NoKeys else Fetched.Unavailable
  } catch (e: Exception) {
    Fetched.Unavailable
  }

  /**
   * Makes sure there is a session with [other], fetching their bundle if needed. [rebuild] starts a new session even
   * if one exists (their keys changed, which the server tells us by refusing a send).
   */
  suspend fun ensureSession(me: String, other: String, rebuild: Boolean = false): PeerKeys {
    if (!enabled) return PeerKeys.UNAVAILABLE
    val needsBundle = mutex.withLock {
      val e = engineFor(me)
      when {
        e.store.pendingIdentityChange(other) != null -> return PeerKeys.IDENTITY_CHANGED
        e.hasSession(other) && !rebuild -> false
        else -> true
      }
    }
    if (!needsBundle) return PeerKeys.READY
    val bundle = when (val f = fetch(other)) {
      is Fetched.Bundle -> f.bundle
      Fetched.NoKeys -> {
        peerKeysSeen[other] = false
        return PeerKeys.NO_KEYS
      }
      Fetched.Unavailable -> return PeerKeys.UNAVAILABLE
    }
    peerKeysSeen[other] = true
    return mutex.withLock {
      try {
        engineFor(me).buildSession(other, bundle)
        PeerKeys.READY
      } catch (e: IdentityChangedException) {
        _trustVersion.update { it + 1 }
        PeerKeys.IDENTITY_CHANGED
      } catch (e: BadBundleException) {
        PeerKeys.UNAVAILABLE
      }
    }
  }

  /** Cheap check used when creating a chat: does [other] have keys? Consumes one of their one-time keys, so ask once. */
  suspend fun peerHasKeys(me: String, other: String): Boolean {
    peerKeysSeen[other]?.let { if (!it) return false }
    return ensureSession(me, other) == PeerKeys.READY
  }

  // ---- sending -------------------------------------------------------------------------------------------------------

  /**
   * Encrypts [payload] for [other] and keeps the plaintext of my own message on this phone (a sender cannot decrypt
   * its own ciphertext). Throws [E2eeSendException] when encryption is not possible; the caller must then NOT send.
   */
  suspend fun seal(me: String, other: String, cid: String, mid: String, payload: Payload): Envelope {
    val peer = ensureSession(me, other)
    if (peer != PeerKeys.READY) throw E2eeSendException(peer)
    return withContext(Dispatchers.Default + NonCancellable) {
      mutex.withLock {
        val e = engineFor(me)
        val bytes = PayloadCodec.encode(payload)
        require(bytes.size <= PayloadCodec.MAX_PLAINTEXT_BYTES) { "message too large" }
        val envelope = try {
          e.encrypt(other, bytes)
        } catch (x: IdentityChangedException) {
          _trustVersion.update { it + 1 }
          throw E2eeSendException(PeerKeys.IDENTITY_CHANGED)
        } catch (x: SessionRequiredException) {
          throw E2eeSendException(PeerKeys.UNAVAILABLE)
        }
        store.cacheMessage(cid, mid, envelope.digest(), bytes)
        envelope
      }
    }
  }

  /** Encrypts a prepared attachment: one random key per file, so the thumbnail has its own. */
  fun sealMedia(prepared: com.samvad.messenger.data.PreparedMedia): SealedMedia = SealedMedia(
    kind = prepared.kind.wire,
    name = prepared.displayName,
    mime = prepared.mime,
    width = prepared.width,
    height = prepared.height,
    durationMs = prepared.durationMs,
    waveform = prepared.waveform,
    file = AttachmentCrypto.encrypt(prepared.bytes),
    thumb = prepared.thumbBytes?.let { AttachmentCrypto.encrypt(it) },
  )

  // ---- reading -------------------------------------------------------------------------------------------------------

  /**
   * Opens one stored message. Returns the cached plaintext when this phone has seen the same ciphertext before
   * (own messages, or already decrypted); otherwise decrypts it now and keeps the result. An edited message has a
   * new ciphertext, so its cache entry is replaced. Locked results explain themselves and are retried next time
   * (a message that arrived before the one that sets up the session can be read once that one has been opened).
   */
  suspend fun open(me: String, cid: String, mid: String, senderId: String, enc: Map<*, *>?): Opened {
    if (!enabled) return Opened.Locked(LockReason.DISABLED)
    val envelope = Envelope.fromMap(enc) ?: return Opened.Locked(LockReason.UNREADABLE)
    val digest = envelope.digest()
    val prefix = "conversations/$cid/$mid/"
    return withContext(Dispatchers.Default + NonCancellable) {
      mutex.withLock {
        store.cachedMessage(cid, mid, digest)?.let { cached ->
          PayloadCodec.decode(cached.payload, prefix)?.let { return@withLock Opened.Ready(it) }
        }
        if (senderId == me) return@withLock Opened.Locked(LockReason.OWN_MESSAGE_LOST)
        val e = engineFor(me)
        val hadPendingChange = e.store.pendingIdentityChange(senderId) != null
        try {
          val bytes = e.decrypt(senderId, envelope)
          val payload = PayloadCodec.decode(bytes, prefix) ?: return@withLock Opened.Locked(LockReason.UNREADABLE)
          store.cacheMessage(cid, mid, digest, bytes)
          Opened.Ready(payload)
        } catch (x: IdentityChangedException) {
          // Only a NEW change is announced; re-trying a held message must not set off another round of updates.
          if (!hadPendingChange) _trustVersion.update { it + 1 }
          Opened.Locked(LockReason.IDENTITY_CHANGED)
        } catch (x: DecryptException) {
          Opened.Locked(
            when (x.reason) {
              DecryptException.Reason.NO_SESSION -> LockReason.NO_SESSION
              else -> LockReason.UNREADABLE
            },
          )
        }
      }
    }
  }

  /** The chat-list line for a message already decrypted on this phone, or null. Never decrypts. */
  fun cachedPreview(cid: String, mid: String): String? {
    val cached = store.latestCachedMessage(cid, mid) ?: return null
    return PayloadCodec.decode(cached.payload)?.let { PayloadCodec.previewOf(it) }
  }

  /** Decrypts a push that carried its ciphertext, for a notification preview. Null when it cannot be read. */
  suspend fun previewFromPush(me: String, cid: String, mid: String, senderId: String, type: String, cipher: String, reg: String): String? {
    val env = mapOf("v" to 1L, "type" to type, "ciphertext" to cipher, "registrationId" to (reg.toLongOrNull() ?: return null))
    val opened = open(me, cid, mid, senderId, env)
    return (opened as? Opened.Ready)?.let { PayloadCodec.previewOf(it.payload) }
  }

  fun forgetMessage(cid: String, mid: String) = store.forgetMessage(cid, mid)

  /** Drops the decrypted copy of messages the server confirmed are gone (disappearing or deleted for everyone). */
  fun pruneChat(cid: String, keep: Set<String>, sinceTs: Long) {
    val settled = System.currentTimeMillis() - 60_000L
    runCatching { store.pruneChat(cid, keep, sinceTs - 60_000L, settled) }
  }

  // ---- trust and safety numbers -----------------------------------------------------------------------------------

  suspend fun trust(me: String, other: String): PeerTrust = mutex.withLock {
    val e = engineFor(me)
    PeerTrust(
      hasIdentity = e.store.getIdentity(SamvadSignalStore.address(other)) != null,
      verified = e.store.isVerified(other),
      changePending = e.store.pendingIdentityChange(other) != null,
    )
  }

  /** The safety number for the chat; with [forPendingKey] the number of the NEW key that is waiting for a decision. */
  suspend fun safetyNumber(me: String, other: String, forPendingKey: Boolean = false): SafetyNumber? = mutex.withLock {
    engineFor(me).safetyNumber(me, other, forPendingKey)
  }

  suspend fun setVerified(me: String, other: String, verified: Boolean) {
    mutex.withLock { engineFor(me).store.setVerified(other, verified) }
    _trustVersion.update { it + 1 }
  }

  /** "Continue anyway" after a security code change. Clears "verified" and the old session. */
  suspend fun acceptIdentityChange(me: String, other: String): Boolean {
    val ok = mutex.withLock { engineFor(me).store.acceptIdentityChange(other) }
    _trustVersion.update { it + 1 }
    return ok
  }

  // ---- chat upgrade ----------------------------------------------------------------------------------------------

  /** Asks the server to switch an existing direct chat to encrypted. Only new messages are protected. */
  suspend fun upgradeConversation(cid: String): Boolean = try {
    call("upgradeConversationE2ee", mapOf("conversationId" to cid))
    true
  } catch (e: Exception) {
    false
  }

  // ---- local sealing for pending uploads ---------------------------------------------------------------------------

  fun sealLocal(plain: ByteArray, context: String): ByteArray = store.seal(plain, context)

  fun openLocal(sealed: ByteArray, context: String): ByteArray = store.open(sealed, context)

  // ---- sign-out ------------------------------------------------------------------------------------------------------

  /**
   * Sign-out: tells the key server to forget this account's public keys (best effort, needs the session), then erases
   * every key, session and decrypted message from this phone. Old encrypted history is NOT recoverable afterwards.
   */
  suspend fun unpublishAndWipe() {
    if (enabled && publishedFor != null) runCatching { call("deletePreKeys", emptyMap<String, Any>()) }
    wipe()
  }

  /** Erases local encryption state only (used when the session is already gone). Waits for running operations. */
  suspend fun wipe() {
    withContext(Dispatchers.IO + NonCancellable) { mutex.withLock { wipeNow() } }
  }

  private fun wipeNow() {
    engine = null
    engineOwner = null
    publishedFor = null
    peerKeysSeen.clear()
    store.wipe()
    _trustVersion.update { it + 1 }
  }

  private suspend fun call(name: String, data: Map<String, Any?>): Map<*, *> =
    functions.getHttpsCallable(name, limitedUse).call(data).await().getData() as? Map<*, *> ?: emptyMap<Any, Any>()

  /** Convenience for [AttachmentRef] users. */
  fun registerKey(keys: AttachmentKeys, ref: AttachmentRef, extension: String) {
    keys.put(ref.path, AttachmentKey(ref.keyBytes(), ref.digest, extension))
  }
}
