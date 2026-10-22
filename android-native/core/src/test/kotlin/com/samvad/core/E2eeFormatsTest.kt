package com.samvad.core

import com.samvad.core.chat.ContactCard
import com.samvad.core.e2ee.AttachmentCrypto
import com.samvad.core.e2ee.AttachmentException
import com.samvad.core.e2ee.AttachmentRef
import com.samvad.core.e2ee.Envelope
import com.samvad.core.e2ee.Payload
import com.samvad.core.e2ee.PayloadCodec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class E2eeFormatsTest {
  private val reply = "7b2d1f0a-1111-4222-8333-944455556666"
  private val prefix = "conversations/anil_bina/7b2d1f0a-1111-4222-8333-000000000001/"

  private fun ref(name: String = "blob.bin", key: ByteArray = ByteArray(32) { it.toByte() }) =
    AttachmentRef(prefix + name, Base64.getEncoder().encodeToString(key), "a".repeat(64), 1234)

  // ---- envelope ----------------------------------------------------------------------------------------------------------

  @Test fun `an envelope round trips through its map form`() {
    val e = Envelope(1, Envelope.TYPE_PREKEY, Base64.getEncoder().encodeToString(ByteArray(40) { it.toByte() }), 4321)
    assertEquals(e, Envelope.fromMap(e.toMap()))
    assertEquals(e.digest(), Envelope.fromMap(e.toMap())!!.digest())
    assertEquals(64, e.digest().length)
    assertArrayEquals(ByteArray(40) { it.toByte() }, e.cipherBytes())
  }

  @Test fun `a malformed envelope is refused`() {
    val ok = Envelope(1, Envelope.TYPE_WHISPER, "AAAA", 5).toMap()
    assertNotNull(Envelope.fromMap(ok))
    assertNull(Envelope.fromMap(null))
    assertNull(Envelope.fromMap(ok + ("extra" to 1)))
    assertNull(Envelope.fromMap(ok - "v"))
    assertNull(Envelope.fromMap(ok + ("v" to 2)))
    assertNull(Envelope.fromMap(ok + ("type" to "plain")))
    assertNull(Envelope.fromMap(ok + ("ciphertext" to "")))
    assertNull(Envelope.fromMap(ok + ("ciphertext" to "not base64!")))
    assertNull(Envelope.fromMap(ok + ("ciphertext" to "AAA")))
    assertNull(Envelope.fromMap(ok + ("ciphertext" to "A".repeat(Envelope.MAX_CIPHERTEXT_CHARS + 4))))
    assertNull(Envelope.fromMap(ok + ("registrationId" to 0)))
    assertNull(Envelope.fromMap(ok + ("registrationId" to 16381)))
    assertNull(Envelope.fromMap(ok + ("registrationId" to "5")))
  }

  // ---- payload -----------------------------------------------------------------------------------------------------------

  @Test fun `a text payload round trips with every kind of character`() {
    val text = "Hello \"quoted\" \\ back\nslash and new line\t tab नमस्ते తెలుగు தமிழ் 😀 \u0001"
    val p = Payload.Text(text, replyTo = reply, forwarded = true)
    val bytes = PayloadCodec.encode(p)
    assertEquals(0, bytes.size % PayloadCodec.BLOCK)
    assertEquals(p, PayloadCodec.decode(bytes))
  }

  @Test fun `short messages have the same padded length`() {
    val a = PayloadCodec.encode(Payload.Text("hi"))
    val b = PayloadCodec.encode(Payload.Text("a somewhat longer line, but still short"))
    assertEquals(a.size, b.size)
    assertTrue(PayloadCodec.encode(Payload.Text("x".repeat(500))).size > a.size)
  }

  @Test fun `contact and media payloads round trip`() {
    val contact = Payload.Contact(ContactCard("Asha", listOf("+919999900001", "+919999900002"), "Samvad"), reply)
    assertEquals(contact, PayloadCodec.decode(PayloadCodec.encode(contact)))

    val media = Payload.Media(
      kind = "video", name = "clip.mp4", mime = "video/mp4", caption = "look", file = ref(), thumb = ref("thumb.bin"),
      width = 640, height = 360, durationMs = 12_000, waveform = listOf(1, 2, 3), replyTo = reply,
    )
    assertEquals(media, PayloadCodec.decode(PayloadCodec.encode(media), prefix))
    val voice = Payload.Media(kind = "voice", name = "voice.m4a", mime = "audio/mp4", file = ref(), durationMs = 5_000, waveform = (0..30).toList())
    assertEquals(voice, PayloadCodec.decode(PayloadCodec.encode(voice), prefix))
  }

  @Test fun `a media payload pointing outside its own message folder is refused`() {
    val bytes = PayloadCodec.encode(Payload.Media(kind = "image", name = "p.jpg", mime = "image/jpeg", file = ref()))
    assertNotNull(PayloadCodec.decode(bytes, prefix))
    assertNull(PayloadCodec.decode(bytes, "conversations/other_chat/7b2d1f0a-1111-4222-8333-000000000001/"))
    val sneaky = PayloadCodec.encode(Payload.Media(kind = "image", name = "p", mime = "image/jpeg", file = ref().copy(path = prefix + "../x")))
    assertNull(PayloadCodec.decode(sneaky, prefix))
    val shortKey = PayloadCodec.encode(Payload.Media(kind = "image", name = "p", mime = "image/jpeg", file = ref(key = ByteArray(16))))
    assertNull(PayloadCodec.decode(shortKey, prefix))
  }

  @Test fun `garbage and wrong versions do not decode`() {
    assertNull(PayloadCodec.decode(ByteArray(0)))
    assertNull(PayloadCodec.decode(ByteArray(160)))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("not json".toByteArray())))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("""{"v":2,"t":"text","text":"x"}""".toByteArray())))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("""{"v":1,"t":"poll"}""".toByteArray())))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("""{"v":1,"t":"text"}""".toByteArray())))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("""{"v":1,"t":"text","text":"x","reply":"not-a-uuid"}""".toByteArray())))
    assertNull(PayloadCodec.decode(PayloadCodec.pad("[".repeat(100).toByteArray())))
  }

  @Test fun `previews are built from the decrypted payload only`() {
    assertEquals("hello", PayloadCodec.previewOf(Payload.Text(" hello ")))
    assertEquals(120, PayloadCodec.previewOf(Payload.Text("x".repeat(500))).length)
    assertTrue(PayloadCodec.previewOf(Payload.Contact(ContactCard("Asha", listOf("+919999900001")))).contains("Asha"))
    assertTrue(PayloadCodec.previewOf(Payload.Media("image", "p.jpg", "image/jpeg", "sunset", ref())).contains("sunset"))
    assertFalse(PayloadCodec.previewOf(Payload.Media("voice", "v.m4a", "audio/mp4", "ignored", ref())).contains("ignored"))
  }

  // ---- attachments ---------------------------------------------------------------------------------------------------------

  @Test fun `an attachment round trips and the stored file hides the content`() {
    val plain = "A secret photo, repeated. ".repeat(200).toByteArray()
    val enc = AttachmentCrypto.encrypt(plain)
    assertEquals(plain.size + AttachmentCrypto.OVERHEAD, enc.cipherBytes.size)
    assertEquals(32, enc.key.size)
    assertEquals(AttachmentCrypto.digestOf(enc.cipherBytes), enc.digestHex)
    assertFalse(String(enc.cipherBytes, Charsets.ISO_8859_1).contains("secret photo"))
    assertArrayEquals(plain, AttachmentCrypto.decrypt(enc.cipherBytes, enc.key, enc.digestHex))
  }

  @Test fun `every file gets its own key and IV`() {
    val a = AttachmentCrypto.encrypt(ByteArray(100))
    val b = AttachmentCrypto.encrypt(ByteArray(100))
    assertFalse(a.key.contentEquals(b.key))
    assertFalse(a.cipherBytes.contentEquals(b.cipherBytes))
    assertNotEquals(a.digestHex, b.digestHex)
  }

  @Test fun `a tampered attachment is refused`() {
    val enc = AttachmentCrypto.encrypt(ByteArray(1000) { it.toByte() })
    for (index in listOf(0, 11, 12, 500, enc.cipherBytes.size - 1)) {
      val bad = enc.cipherBytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
      try {
        AttachmentCrypto.decrypt(bad, enc.key, enc.digestHex)
        fail("flip at $index must be caught")
      } catch (e: AttachmentException) {
        assertEquals("digest mismatch", e.message)
      }
    }
    // Even when the attacker also fixes the digest, the authentication tag still fails.
    val bad = enc.cipherBytes.copyOf().also { it[500] = (it[500].toInt() xor 1).toByte() }
    try {
      AttachmentCrypto.decrypt(bad, enc.key, AttachmentCrypto.digestOf(bad))
      fail()
    } catch (e: AttachmentException) {
      assertEquals("authentication failed", e.message)
    }
    try {
      AttachmentCrypto.decrypt(enc.cipherBytes.copyOf(enc.cipherBytes.size - 1), enc.key, enc.digestHex)
      fail("truncation")
    } catch (e: AttachmentException) {
      // expected
    }
    try {
      AttachmentCrypto.decrypt(enc.cipherBytes, enc.key, "zz")
      fail()
    } catch (e: AttachmentException) {
      assertEquals("bad digest", e.message)
    }
  }

  @Test fun `the wrong key is refused`() {
    val enc = AttachmentCrypto.encrypt("hello".toByteArray())
    val other = AttachmentCrypto.encrypt("hello".toByteArray())
    try {
      AttachmentCrypto.decrypt(enc.cipherBytes, other.key, enc.digestHex)
      fail()
    } catch (e: AttachmentException) {
      assertEquals("authentication failed", e.message)
    }
    try {
      AttachmentCrypto.decrypt(enc.cipherBytes, ByteArray(16), enc.digestHex)
      fail()
    } catch (e: AttachmentException) {
      assertEquals("bad key", e.message)
    }
  }

  @Test fun `an empty file can be encrypted and a tiny stored file is refused`() {
    val enc = AttachmentCrypto.encrypt(ByteArray(0))
    assertEquals(0, AttachmentCrypto.decrypt(enc.cipherBytes, enc.key, enc.digestHex).size)
    try {
      AttachmentCrypto.decrypt(ByteArray(5), enc.key, AttachmentCrypto.digestOf(ByteArray(5)))
      fail()
    } catch (e: AttachmentException) {
      assertEquals("too short", e.message)
    }
  }
}
