package com.samvad.core

import com.samvad.core.e2ee.DecryptException
import com.samvad.core.e2ee.Envelope
import com.samvad.core.e2ee.IdentityChangedException
import com.samvad.core.e2ee.OneTimeKeyDto
import com.samvad.core.e2ee.PreKeyUpload
import com.samvad.core.e2ee.RemoteBundle
import com.samvad.core.e2ee.SamvadSignalStore
import com.samvad.core.e2ee.SessionRequiredException
import com.samvad.core.e2ee.SignalEngine
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real libsignal, no mocks: the same engine the app runs, with an in-memory stand-in for the key server. */
class E2eeEngineTest {
  /** What the backend does with pre-keys: keep the latest bundle per person and hand out each one-time key once. */
  private class FakeKeyServer {
    private val bundles = HashMap<String, PreKeyUpload>()
    private val oneTime = HashMap<String, ArrayDeque<OneTimeKeyDto>>()
    var fetches = 0

    fun publish(uid: String, upload: PreKeyUpload) {
      val replaced = bundles[uid]?.identityKey != upload.identityKey
      bundles[uid] = upload
      if (replaced) oneTime[uid] = ArrayDeque()
      oneTime.getOrPut(uid) { ArrayDeque() }.addAll(upload.oneTimePreKeys)
    }

    fun fetch(uid: String): RemoteBundle? {
      fetches++
      val b = bundles[uid] ?: return null
      val otk = oneTime[uid]?.removeFirstOrNull()
      return RemoteBundle(b.registrationId, b.identityKey, b.signedPreKey, b.kyberPreKey, otk)
    }

    fun remaining(uid: String) = oneTime[uid]?.size ?: 0
  }

  private class Party(val uid: String, val server: FakeKeyServer, oneTimeKeys: Int = 100, clock: () -> Long = System::currentTimeMillis) {
    var snapshot: ByteArray = ByteArray(0)
    val store = SamvadSignalStore.create { snapshot = it }
    val engine = SignalEngine(store, clock)

    init { server.publish(uid, engine.createUpload(oneTimeKeys)) }

    fun connect(other: String) = engine.buildSession(other, server.fetch(other)!!)

    fun say(to: String, text: String): Envelope = engine.encrypt(to, text.toByteArray())

    fun hear(from: String, env: Envelope): String = String(engine.decrypt(from, env))
  }

  @Test fun `a session is set up from a published bundle and talks both ways`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    alice.connect("bob")

    val first = alice.say("bob", "hello bob")
    assertEquals(Envelope.TYPE_PREKEY, first.type)
    assertEquals(bob.store.localRegistrationId, first.registrationId)
    assertFalse(first.ciphertext.contains("hello"))
    assertEquals("hello bob", bob.hear("alice", first))

    val reply = bob.say("alice", "hi alice")
    assertEquals(Envelope.TYPE_WHISPER, reply.type)
    assertEquals("hi alice", alice.hear("bob", reply))

    val next = alice.say("bob", "ratchet moves on")
    assertEquals(Envelope.TYPE_WHISPER, next.type)
    assertEquals("ratchet moves on", bob.hear("alice", next))
    assertTrue(Envelope.fromMap(next.toMap()) == next)
  }

  @Test fun `messages that arrive out of order are all read, and a replay is refused`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    alice.connect("bob")
    val m = (1..5).map { alice.say("bob", "message $it") }

    assertEquals("message 1", bob.hear("alice", m[0]))
    assertEquals("message 4", bob.hear("alice", m[3]))
    assertEquals("message 3", bob.hear("alice", m[2]))
    assertEquals("message 5", bob.hear("alice", m[4]))
    assertEquals("message 2", bob.hear("alice", m[1]))

    try {
      bob.hear("alice", m[2])
      fail("a replayed message must not open twice")
    } catch (e: DecryptException) {
      assertEquals(DecryptException.Reason.DUPLICATE, e.reason)
    }
  }

  @Test fun `many one-time keys are used up and a session still starts without one`() {
    val server = FakeKeyServer()
    val bob = Party("bob", server, oneTimeKeys = 2)
    val senders = (1..4).map { Party("s$it", server) }
    val used = HashSet<Int>()
    senders.forEach { s ->
      val bundle = server.fetch("bob")!!
      bundle.oneTimePreKey?.let { assertTrue("a one-time key is handed out once", used.add(it.keyId)) }
      s.engine.buildSession("bob", bundle)
      assertEquals("hi from ${s.uid}", bob.hear(s.uid, s.say("bob", "hi from ${s.uid}")))
    }
    assertEquals(2, used.size)
    assertEquals(0, server.remaining("bob"))
    // Two of the four sessions had no one-time key, yet all four were readable (checked above).
    assertEquals(0, bob.store.oneTimePreKeyCount())
  }

  @Test fun `a new identity key is not trusted automatically when sending`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bobOld = Party("bob", server)
    alice.connect("bob")
    assertEquals("x", bobOld.hear("alice", alice.say("bob", "x")))

    // Bob gets a new phone: new keys, same account.
    val bobNew = Party("bob", server)
    try {
      alice.connect("bob")
      fail("the changed key must stop the session")
    } catch (e: IdentityChangedException) {
      assertEquals("bob", e.uid)
    }
    assertNotNull(alice.store.pendingIdentityChange("bob"))
    try {
      alice.say("bob", "still sending to the old key?")
      fail("sending must be blocked until the change is accepted")
    } catch (e: IdentityChangedException) {
      // expected
    }

    assertTrue(alice.store.acceptIdentityChange("bob"))
    assertNull(alice.store.pendingIdentityChange("bob"))
    alice.connect("bob")
    assertEquals("to the new phone", bobNew.hear("alice", alice.say("bob", "to the new phone")))
  }

  @Test fun `a first message from a changed identity key is held until accepted`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bobOld = Party("bob", server)
    alice.connect("bob")
    assertEquals("one", bobOld.hear("alice", alice.say("bob", "one")))
    val back = bobOld.say("alice", "two")
    assertEquals("two", alice.hear("bob", back))
    alice.store.setVerified("bob", true)
    assertTrue(alice.store.isVerified("bob"))

    val bobNew = Party("bob", server)
    bobNew.connect("alice")
    val fromNew = bobNew.say("alice", "it is me, new phone")
    try {
      alice.hear("bob", fromNew)
      fail("must not open a message from an unaccepted identity")
    } catch (e: IdentityChangedException) {
      // expected
    }
    assertNotNull(alice.store.pendingIdentityChange("bob"))
    assertTrue("still marked verified until the person decides", alice.store.isVerified("bob"))

    assertTrue(alice.store.acceptIdentityChange("bob"))
    assertFalse("a replaced key is never 'verified'", alice.store.isVerified("bob"))
    assertEquals("it is me, new phone", alice.hear("bob", fromNew))
  }

  @Test fun `an unknown sender with no session cannot be answered and a follow-up without a session is refused`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    try {
      alice.say("bob", "no session yet")
      fail()
    } catch (e: SessionRequiredException) {
      assertEquals("bob", e.uid)
    }
    alice.connect("bob")
    bob.hear("alice", alice.say("bob", "first"))
    val reply = bob.say("alice", "reply")
    alice.hear("bob", reply)
    val follow = alice.say("bob", "follow-up")
    assertEquals(Envelope.TYPE_WHISPER, follow.type)
    // A phone that has lost its keys cannot read it.
    val fresh = Party("bob", server)
    try {
      fresh.hear("alice", follow)
      fail()
    } catch (e: DecryptException) {
      assertTrue(e.reason == DecryptException.Reason.NO_SESSION || e.reason == DecryptException.Reason.INVALID)
    }
  }

  @Test fun `a third person cannot read a message`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    val eve = Party("eve", server)
    alice.connect("bob")
    val env = alice.say("bob", "private")
    try {
      eve.hear("alice", env)
      fail()
    } catch (e: DecryptException) {
      // expected
    }
    assertEquals("private", bob.hear("alice", env))
  }

  @Test fun `state survives a restart and the conversation continues`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    alice.connect("bob")
    bob.hear("alice", alice.say("bob", "before restart"))
    alice.hear("bob", bob.say("alice", "ack"))
    alice.store.setVerified("bob", true)

    var saved = ByteArray(0)
    val restored = SamvadSignalStore.restore(alice.snapshot) { saved = it }
    val engine = SignalEngine(restored)
    assertEquals(alice.store.localRegistrationId, restored.localRegistrationId)
    assertEquals(alice.store.oneTimePreKeyCount(), restored.oneTimePreKeyCount())
    assertTrue(restored.isVerified("bob"))
    assertTrue(restored.hasSessionWith("bob"))

    val env = engine.encrypt("bob", "after restart".toByteArray())
    assertEquals("after restart", bob.hear("alice", env))
    assertTrue("changes are persisted", saved.isNotEmpty())
  }

  @Test fun `a damaged state is rejected`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    try {
      SamvadSignalStore.restore(alice.snapshot.copyOf(20)) { }
      fail()
    } catch (e: IllegalArgumentException) {
      // expected
    }
    try {
      SamvadSignalStore.restore(ByteArray(64)) { }
      fail()
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }

  @Test fun `signed and Kyber keys are rotated after thirty days and old ones expire`() {
    var now = 1_800_000_000_000L
    val server = FakeKeyServer()
    val alice = Party("alice", server, clock = { now })
    assertFalse(alice.engine.rotationDue())
    val first = alice.engine.currentUpload()
    now += SignalEngine.ROTATION_MS + 1
    assertTrue(alice.engine.rotationDue())
    val rotated = alice.engine.rotate()
    assertNotEquals(first.signedPreKey.keyId, rotated.signedPreKey.keyId)
    assertNotEquals(first.kyberPreKey.keyId, rotated.kyberPreKey.keyId)
    assertEquals("identity key never rotates", first.identityKey, rotated.identityKey)
    assertFalse(alice.engine.rotationDue())
    assertEquals("the previous pair is kept for the grace period", 2, alice.store.loadSignedPreKeys().size)
    now += SignalEngine.ROTATION_MS + 1
    alice.engine.rotate()
    assertEquals("the oldest pair is deleted once it is past rotation plus grace", 2, alice.store.loadSignedPreKeys().size)
    assertEquals(2, alice.store.loadKyberPreKeys().size)
    assertFalse(alice.store.signedPreKeyAges().any { it.first == first.signedPreKey.keyId })
  }

  @Test fun `one-time pre-key replenishment creates distinct ids`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server, oneTimeKeys = 10)
    val more = alice.engine.createUpload(100)
    assertEquals(100, more.oneTimePreKeys.size)
    assertEquals(110, alice.store.oneTimePreKeyCount())
    assertEquals(100, more.oneTimePreKeys.map { it.keyId }.toSet().size)
    assertTrue(more.oneTimePreKeys.none { it.keyId in 1..10 })
  }

  @Test fun `published key sizes are the ones the server checks`() {
    val u = Party("alice", FakeKeyServer(), 1).engine.currentUpload(emptyList())
    val b = Base64.getDecoder()
    assertEquals(33, b.decode(u.identityKey).size)
    assertEquals(33, b.decode(u.signedPreKey.publicKey).size)
    assertEquals(64, b.decode(u.signedPreKey.signature).size)
    assertEquals(1569, b.decode(u.kyberPreKey.publicKey).size)
    assertEquals(64, b.decode(u.kyberPreKey.signature).size)
  }

  @Test fun `safety numbers are 60 digits, the same on both phones, and change with the key`() {
    val server = FakeKeyServer()
    val alice = Party("alice", server)
    val bob = Party("bob", server)
    alice.connect("bob")
    bob.hear("alice", alice.say("bob", "hi"))

    val a = alice.engine.safetyNumber("alice", "bob")!!
    val b = bob.engine.safetyNumber("bob", "alice")!!
    assertEquals(60, a.digits.length)
    assertTrue(a.digits.all { it.isDigit() })
    assertEquals(12, a.grouped().size)
    assertEquals(a.digits, b.digits)
    assertTrue(a.matchesScan(b.qrBytes()))

    val carol = Party("carol", server)
    alice.connect("carol")
    assertNotEquals(a.digits, alice.engine.safetyNumber("alice", "carol")!!.digits)
    assertFalse(a.matchesScan(alice.engine.safetyNumber("alice", "carol")!!.qrBytes()))
    assertFalse(a.matchesScan(byteArrayOf(1, 2, 3)))
    assertNull(carol.engine.safetyNumber("carol", "alice"))
  }
}
