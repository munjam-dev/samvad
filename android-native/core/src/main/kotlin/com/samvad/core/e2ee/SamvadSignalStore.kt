package com.samvad.core.e2ee

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Receives the whole protocol state as one blob after every change. The Android app seals the blob with a key held in
 * the Android Keystore and writes it to no-backup storage; tests keep it in memory. An exception here stops the
 * operation that caused the change (a message is then not acknowledged), so state is never silently lost.
 */
fun interface StatePersister {
  fun persist(snapshot: ByteArray)
}

/**
 * The protocol state of this install: identity key pair, registration ID, pre-keys, sessions, and the trust decisions
 * about other people's identity keys. Implements libsignal's store interfaces on top of plain maps, and serializes
 * itself with [snapshot] / [restore]. One device per account, so every session has device ID 1.
 *
 * Trust model: the first identity key seen for a person is accepted (trust on first use). A different key afterwards
 * is NOT trusted automatically: [isTrustedIdentity] answers false, libsignal refuses to send or decrypt, the change
 * is remembered in [pendingIdentityChange], and only [acceptIdentityChange] (an explicit user action) replaces the
 * trusted key. A replaced key also clears the "verified" mark.
 */
class SamvadSignalStore private constructor(
  private var identity: IdentityKeyPair,
  private var registrationId: Int,
  private val persister: StatePersister,
) : SignalProtocolStore {
  private val lock = Any()
  private val preKeys = LinkedHashMap<Int, ByteArray>()
  private val signedPreKeys = LinkedHashMap<Int, ByteArray>()
  private val kyberPreKeys = LinkedHashMap<Int, ByteArray>()
  private val sessions = LinkedHashMap<String, ByteArray>()
  private val trusted = LinkedHashMap<String, ByteArray>()
  private val verified = LinkedHashSet<String>()
  private val pending = LinkedHashMap<String, ByteArray>()
  private val usedKyber = ArrayDeque<String>()
  private var nextPreKeyId = 1
  private var nextSignedId = 1
  private var nextKyberId = 1
  private var lastRotationMs = 0L

  // ---- identity ------------------------------------------------------------------------------------------------

  override fun getIdentityKeyPair(): IdentityKeyPair = synchronized(lock) { identity }

  override fun getLocalRegistrationId(): Int = synchronized(lock) { registrationId }

  override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange =
    synchronized(lock) {
      val k = key(address)
      val known = trusted[k]
      val bytes = identityKey.serialize()
      when {
        known == null -> {
          trusted[k] = bytes
          changed()
          IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        known.contentEquals(bytes) -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        else -> {
          // libsignal only gets here after isTrustedIdentity said yes, which for a changed key means the person
          // accepted it. Replacing the key also clears "verified".
          trusted[k] = bytes
          verified.remove(k)
          pending.remove(k)
          changed()
          IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
      }
    }

  override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean =
    synchronized(lock) {
      val known = trusted[key(address)] ?: return true
      known.contentEquals(identityKey.serialize())
    }

  override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = synchronized(lock) {
    trusted[key(address)]?.let { IdentityKey(it) }
  }

  /** Remembers that [uid] presented a key different from the trusted one. Nothing is trusted by this call. */
  fun notePendingIdentityChange(uid: String, newKey: IdentityKey) = synchronized(lock) {
    val k = key(address(uid))
    if (trusted.containsKey(k) && !trusted[k]!!.contentEquals(newKey.serialize())) {
      pending[k] = newKey.serialize()
      changed()
    }
  }

  /** The key [uid] is now presenting, when it differs from the one trusted so far; null when nothing is pending. */
  fun pendingIdentityChange(uid: String): IdentityKey? = synchronized(lock) {
    pending[key(address(uid))]?.let { IdentityKey(it) }
  }

  /**
   * The explicit "continue anyway" decision. Trusts the pending key, clears "verified", and drops the old session so
   * that the next message starts a new one with the new key. Returns false when no change is pending.
   */
  fun acceptIdentityChange(uid: String): Boolean = synchronized(lock) {
    val k = key(address(uid))
    val next = pending.remove(k) ?: return false
    trusted[k] = next
    verified.remove(k)
    sessions.remove(k)
    changed()
    true
  }

  fun isVerified(uid: String): Boolean = synchronized(lock) { key(address(uid)) in verified }

  /** Marks the current trusted key of [uid] as compared in person (or by QR code). */
  fun setVerified(uid: String, value: Boolean) = synchronized(lock) {
    val k = key(address(uid))
    if (!trusted.containsKey(k)) return@synchronized
    if (value) verified.add(k) else verified.remove(k)
    changed()
  }

  // ---- one-time pre-keys ----------------------------------------------------------------------------------------

  override fun loadPreKey(preKeyId: Int): PreKeyRecord = synchronized(lock) {
    PreKeyRecord(preKeys[preKeyId] ?: throw InvalidKeyIdException("No such pre-key"))
  }

  override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = synchronized(lock) {
    preKeys[preKeyId] = record.serialize()
    changed()
  }

  override fun containsPreKey(preKeyId: Int): Boolean = synchronized(lock) { preKeys.containsKey(preKeyId) }

  override fun removePreKey(preKeyId: Int) = synchronized(lock) {
    if (preKeys.remove(preKeyId) != null) changed()
  }

  fun oneTimePreKeyCount(): Int = synchronized(lock) { preKeys.size }

  /** Reserves [count] consecutive one-time pre-key IDs. */
  fun reservePreKeyIds(count: Int): Int = synchronized(lock) {
    val first = nextPreKeyId
    nextPreKeyId = if (first + count > MAX_KEY_ID) 1 else first + count
    changed()
    first
  }

  // ---- signed and Kyber pre-keys ---------------------------------------------------------------------------------

  override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = synchronized(lock) {
    SignedPreKeyRecord(signedPreKeys[signedPreKeyId] ?: throw InvalidKeyIdException("No such signed pre-key"))
  }

  override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = synchronized(lock) { signedPreKeys.values.map { SignedPreKeyRecord(it) } }

  override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = synchronized(lock) {
    signedPreKeys[signedPreKeyId] = record.serialize()
    changed()
  }

  override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = synchronized(lock) { signedPreKeys.containsKey(signedPreKeyId) }

  override fun removeSignedPreKey(signedPreKeyId: Int) = synchronized(lock) {
    if (signedPreKeys.remove(signedPreKeyId) != null) changed()
  }

  override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = synchronized(lock) {
    KyberPreKeyRecord(kyberPreKeys[kyberPreKeyId] ?: throw InvalidKeyIdException("No such Kyber pre-key"))
  }

  override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = synchronized(lock) { kyberPreKeys.values.map { KyberPreKeyRecord(it) } }

  override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = synchronized(lock) {
    kyberPreKeys[kyberPreKeyId] = record.serialize()
    changed()
  }

  override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = synchronized(lock) { kyberPreKeys.containsKey(kyberPreKeyId) }

  /**
   * The Kyber pre-key is a "last resort" key: it is reused by many first messages, so it is never deleted here.
   * What is refused is the same key agreement being replayed (same Kyber key, signed pre-key and sender base key).
   */
  override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) = synchronized(lock) {
    val entry = "$kyberPreKeyId:$signedPreKeyId:${Envelope.hex(baseKey.serialize())}"
    if (usedKyber.contains(entry)) throw ReusedBaseKeyException()
    usedKyber.addLast(entry)
    while (usedKyber.size > MAX_USED_KYBER) usedKyber.removeFirst()
    changed()
  }

  fun reserveSignedPreKeyId(): Int = synchronized(lock) {
    val id = nextSignedId
    nextSignedId = if (id + 1 > MAX_KEY_ID) 1 else id + 1
    changed()
    id
  }

  fun reserveKyberPreKeyId(): Int = synchronized(lock) {
    val id = nextKyberId
    nextKyberId = if (id + 1 > MAX_KEY_ID) 1 else id + 1
    changed()
    id
  }

  fun lastRotationMs(): Long = synchronized(lock) { lastRotationMs }

  fun setLastRotationMs(ms: Long) = synchronized(lock) {
    lastRotationMs = ms
    changed()
  }

  /** The IDs of every stored signed pre-key with its creation time, oldest first. */
  fun signedPreKeyAges(): List<Pair<Int, Long>> = synchronized(lock) {
    signedPreKeys.map { (id, bytes) -> id to SignedPreKeyRecord(bytes).timestamp }.sortedBy { it.second }
  }

  fun kyberPreKeyAges(): List<Pair<Int, Long>> = synchronized(lock) {
    kyberPreKeys.map { (id, bytes) -> id to KyberPreKeyRecord(bytes).timestamp }.sortedBy { it.second }
  }

  fun removeKyberPreKey(id: Int) = synchronized(lock) {
    if (kyberPreKeys.remove(id) != null) changed()
  }

  // ---- sessions ----------------------------------------------------------------------------------------------------

  override fun loadSession(address: SignalProtocolAddress): SessionRecord = synchronized(lock) {
    sessions[key(address)]?.let { SessionRecord(it) } ?: SessionRecord()
  }

  override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = synchronized(lock) {
    addresses.map { a -> sessions[key(a)]?.let { SessionRecord(it) } ?: throw NoSessionException("No session for ${a.name}") }
  }

  override fun getSubDeviceSessions(name: String): List<Int> = synchronized(lock) {
    sessions.keys.filter { it.startsWith("$name:") }.map { it.substringAfterLast(':').toInt() }.filter { it != DEVICE_ID }
  }

  override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = synchronized(lock) {
    sessions[key(address)] = record.serialize()
    changed()
  }

  override fun containsSession(address: SignalProtocolAddress): Boolean = synchronized(lock) { sessions.containsKey(key(address)) }

  override fun deleteSession(address: SignalProtocolAddress) = synchronized(lock) {
    if (sessions.remove(key(address)) != null) changed()
  }

  override fun deleteAllSessions(name: String) = synchronized(lock) {
    if (sessions.keys.removeAll { it.startsWith("$name:") }) changed()
  }

  fun hasSessionWith(uid: String): Boolean = containsSession(address(uid))

  // ---- sender keys: groups are not part of v1, so nothing is kept ------------------------------------------------

  override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
    throw UnsupportedOperationException("Group encryption is not part of this version")
  }

  override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? = null

  // ---- persistence ------------------------------------------------------------------------------------------------

  private fun changed() = persister.persist(snapshotLocked())

  /** The complete state as bytes. Contains private keys: only ever write it sealed. */
  fun snapshot(): ByteArray = synchronized(lock) { snapshotLocked() }

  private fun snapshotLocked(): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(FORMAT)
      out.writeBlob(identity.serialize())
      out.writeInt(registrationId)
      out.writeInt(nextPreKeyId)
      out.writeInt(nextSignedId)
      out.writeInt(nextKyberId)
      out.writeLong(lastRotationMs)
      out.writeIntMap(preKeys)
      out.writeIntMap(signedPreKeys)
      out.writeIntMap(kyberPreKeys)
      out.writeStringMap(sessions)
      out.writeStringMap(trusted)
      out.writeStringList(verified.toList())
      out.writeStringMap(pending)
      out.writeStringList(usedKyber.toList())
    }
    return bytes.toByteArray()
  }

  companion object {
    const val DEVICE_ID = 1
    private const val MAGIC = 0x53564531
    private const val FORMAT = 1
    private const val MAX_KEY_ID = 0xFFFFFE
    private const val MAX_USED_KYBER = 5_000
    private const val MAX_BLOB = 4 * 1024 * 1024
    private val random = SecureRandom()

    fun address(uid: String) = SignalProtocolAddress(uid, DEVICE_ID)

    private fun key(a: SignalProtocolAddress) = "${a.name}:${a.deviceId}"

    /** A brand-new identity: fresh key pair and a random registration ID. Persists immediately. */
    fun create(persister: StatePersister): SamvadSignalStore {
      val store = SamvadSignalStore(IdentityKeyPair.generate(), random.nextInt(Envelope.MAX_REGISTRATION_ID) + 1, persister)
      store.changed()
      return store
    }

    /** Re-creates the store from [snapshot]. Throws [IllegalArgumentException] if it is damaged. */
    fun restore(snapshot: ByteArray, persister: StatePersister): SamvadSignalStore {
      try {
        DataInputStream(ByteArrayInputStream(snapshot)).use { inp ->
          require(inp.readInt() == MAGIC) { "not a Samvad protocol state" }
          require(inp.readInt() == FORMAT) { "unknown state version" }
          val store = SamvadSignalStore(IdentityKeyPair(inp.readBlob()), 0, persister)
          store.registrationId = inp.readInt()
          store.nextPreKeyId = inp.readInt()
          store.nextSignedId = inp.readInt()
          store.nextKyberId = inp.readInt()
          store.lastRotationMs = inp.readLong()
          inp.readIntMap(store.preKeys)
          inp.readIntMap(store.signedPreKeys)
          inp.readIntMap(store.kyberPreKeys)
          inp.readStringMap(store.sessions)
          inp.readStringMap(store.trusted)
          store.verified.addAll(inp.readStringList())
          inp.readStringMap(store.pending)
          store.usedKyber.addAll(inp.readStringList())
          return store
        }
      } catch (e: java.io.IOException) {
        throw IllegalArgumentException("damaged protocol state", e)
      } catch (e: org.signal.libsignal.protocol.InvalidKeyException) {
        throw IllegalArgumentException("damaged protocol state", e)
      }
    }

    private fun DataOutputStream.writeBlob(b: ByteArray) {
      writeInt(b.size)
      write(b)
    }

    private fun DataInputStream.readBlob(): ByteArray {
      val n = readInt()
      if (n < 0 || n > MAX_BLOB) throw java.io.IOException("bad length")
      return ByteArray(n).also { readFully(it) }
    }

    private fun DataOutputStream.writeIntMap(m: Map<Int, ByteArray>) {
      writeInt(m.size)
      for ((k, v) in m) { writeInt(k); writeBlob(v) }
    }

    private fun DataInputStream.readIntMap(into: MutableMap<Int, ByteArray>) {
      repeat(readInt().also { if (it < 0 || it > 100_000) throw java.io.IOException("bad count") }) { into[readInt()] = readBlob() }
    }

    private fun DataOutputStream.writeStringMap(m: Map<String, ByteArray>) {
      writeInt(m.size)
      for ((k, v) in m) { writeUTF(k); writeBlob(v) }
    }

    private fun DataInputStream.readStringMap(into: MutableMap<String, ByteArray>) {
      repeat(readInt().also { if (it < 0 || it > 100_000) throw java.io.IOException("bad count") }) { into[readUTF()] = readBlob() }
    }

    private fun DataOutputStream.writeStringList(l: List<String>) {
      writeInt(l.size)
      for (s in l) writeUTF(s)
    }

    private fun DataInputStream.readStringList(): List<String> {
      val n = readInt()
      if (n < 0 || n > 100_000) throw java.io.IOException("bad count")
      return List(n) { readUTF() }
    }
  }
}
