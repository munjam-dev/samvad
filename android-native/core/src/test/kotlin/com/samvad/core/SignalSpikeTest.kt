package com.samvad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Spike: proves the Signal protocol library runs in this build and that two parties can establish a session
 * (PQXDH with a one-time pre-key) and exchange messages both ways. Not product code.
 */
class SignalSpikeTest {
  private class Party(val name: String, val registrationId: Int) {
    val identity: IdentityKeyPair = IdentityKeyPair.generate()
    val store = InMemorySignalProtocolStore(identity, registrationId)
    val address = SignalProtocolAddress(name, 1)
  }

  /** Bob publishes a bundle (identity, signed pre-key, one-time pre-key, Kyber pre-key) and keeps the private halves. */
  private fun publishBundle(bob: Party): PreKeyBundle {
    val preKey = ECKeyPair.generate()
    val signed = ECKeyPair.generate()
    val signedSig = bob.identity.privateKey.calculateSignature(signed.publicKey.serialize())
    val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
    val kyberSig = bob.identity.privateKey.calculateSignature(kyber.publicKey.serialize())
    val now = System.currentTimeMillis()

    bob.store.storePreKey(1, PreKeyRecord(1, preKey))
    bob.store.storeSignedPreKey(2, SignedPreKeyRecord(2, now, signed, signedSig))
    bob.store.storeKyberPreKey(3, KyberPreKeyRecord(3, now, kyber, kyberSig))

    return PreKeyBundle(
      bob.registrationId, 1,
      1, preKey.publicKey,
      2, signed.publicKey, signedSig,
      bob.identity.publicKey,
      3, kyber.publicKey, kyberSig,
    )
  }

  @Test fun `two parties establish a session and talk both ways`() {
    val alice = Party("alice", 1111)
    val bob = Party("bob", 2222)

    SessionBuilder(alice.store, bob.address).process(publishBundle(bob))

    val aliceToBob = SessionCipher(alice.store, bob.address)
    val bobFromAlice = SessionCipher(bob.store, alice.address)

    val first = aliceToBob.encrypt("hello bob".toByteArray())
    assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
    assertFalse("ciphertext must not contain the plaintext", String(first.serialize()).contains("hello bob"))
    assertEquals("hello bob", String(bobFromAlice.decrypt(PreKeySignalMessage(first.serialize()))))

    val reply = SessionCipher(bob.store, alice.address).encrypt("hello alice".toByteArray())
    assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
    assertEquals("hello alice", String(aliceToBob.decrypt(SignalMessage(reply.serialize()))))

    val second = aliceToBob.encrypt("again".toByteArray())
    assertEquals(CiphertextMessage.WHISPER_TYPE, second.type)
    assertEquals("again", String(bobFromAlice.decrypt(SignalMessage(second.serialize()))))
  }

  @Test fun `a message cannot be decrypted by a third party`() {
    val alice = Party("alice", 1)
    val bob = Party("bob", 2)
    val eve = Party("eve", 3)
    SessionBuilder(alice.store, bob.address).process(publishBundle(bob))
    val cipher = SessionCipher(alice.store, bob.address).encrypt("secret".toByteArray())

    var failed = false
    try {
      SessionCipher(eve.store, alice.address).decrypt(PreKeySignalMessage(cipher.serialize()))
    } catch (e: Exception) {
      failed = true
    }
    assertTrue("a party without the keys must not be able to read it", failed)
  }

  @Test fun `each message uses a fresh ciphertext`() {
    val alice = Party("alice", 1)
    val bob = Party("bob", 2)
    SessionBuilder(alice.store, bob.address).process(publishBundle(bob))
    val cipher = SessionCipher(alice.store, bob.address)
    val a = cipher.encrypt("same".toByteArray()).serialize()
    val b = cipher.encrypt("same".toByteArray()).serialize()
    assertFalse(a.contentEquals(b))
  }

  /** Reads a message by its type: until the other side replies, follow-ups are still pre-key messages. */
  private fun open(cipher: SessionCipher, m: CiphertextMessage): String = String(
    if (m.type == CiphertextMessage.PREKEY_TYPE) cipher.decrypt(PreKeySignalMessage(m.serialize()))
    else cipher.decrypt(SignalMessage(m.serialize())),
  )

  @Test fun `messages decrypt even when delivered out of order`() {
    val alice = Party("alice", 1)
    val bob = Party("bob", 2)
    SessionBuilder(alice.store, bob.address).process(publishBundle(bob))
    val toBob = SessionCipher(alice.store, bob.address)
    val fromAlice = SessionCipher(bob.store, alice.address)

    val m1 = toBob.encrypt("one".toByteArray())
    val m2 = toBob.encrypt("two".toByteArray())
    val m3 = toBob.encrypt("three".toByteArray())
    assertEquals("one", open(fromAlice, m1))
    assertEquals("three", open(fromAlice, m3))
    assertEquals("two", open(fromAlice, m2))
  }

  @Test fun `a replayed message is refused`() {
    val alice = Party("alice", 1)
    val bob = Party("bob", 2)
    SessionBuilder(alice.store, bob.address).process(publishBundle(bob))
    val m = SessionCipher(alice.store, bob.address).encrypt("once".toByteArray())
    val fromAlice = SessionCipher(bob.store, alice.address)
    assertEquals("once", open(fromAlice, m))
    var refused = false
    try { open(fromAlice, m) } catch (e: Exception) { refused = true }
    assertTrue("a message must not be accepted twice", refused)
  }
}
