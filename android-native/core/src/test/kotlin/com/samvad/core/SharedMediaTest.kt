package com.samvad.core

import com.samvad.core.chat.Message
import com.samvad.core.chat.SharedMediaBuilder
import com.samvad.core.media.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaTest {
  private fun media(name: String, mime: String) = Media(path = "conversations/c/m/$name", name = name, size = 100, mime = mime)
  private fun msg(id: String, at: Long, kind: String = "text", text: String = "", media: Media? = null, deleted: Boolean = false, expires: Long? = null, pending: Boolean = false) =
    Message(id = id, senderId = "asha", text = text, createdAt = at, kind = kind, media = media, deleted = deleted, expiresAt = expires, pending = pending)

  @Test
  fun `sorts photos, documents and links newest first`() {
    val messages = listOf(
      msg("p1", 10, "image", media = media("a.jpg", "image/jpeg")),
      msg("d1", 20, "file", media = media("notes.pdf", "application/pdf")),
      msg("p2", 30, "image", media = media("b.jpg", "image/jpeg")),
      msg("l1", 40, text = "see https://example.com/page and www.docs.example.org/x."),
      msg("t1", 50, text = "no links here"),
      msg("v1", 60, "voice", media = media("v.m4a", "audio/mp4")),
    )
    val shared = SharedMediaBuilder.build(messages, nowMs = 1_000)
    assertEquals(listOf("p2", "p1"), shared.photos.map { it.messageId })
    assertEquals(listOf("d1"), shared.documents.map { it.messageId })
    assertEquals(listOf("https://example.com/page", "https://www.docs.example.org/x"), shared.links.map { it.url })
    assertEquals(listOf("example.com", "docs.example.org"), shared.links.map { it.host })
    assertFalse(shared.isEmpty)
  }

  @Test
  fun `deleted, expired, pending and system messages are left out`() {
    val messages = listOf(
      msg("gone", 1, "image", media = media("a.jpg", "image/jpeg"), deleted = true),
      msg("old", 2, "image", media = media("b.jpg", "image/jpeg"), expires = 500),
      msg("wait", 3, "file", media = media("c.pdf", "application/pdf"), pending = true),
      msg("sys", 4, "system", text = "https://example.com"),
      msg("ok", 5, "image", media = media("d.jpg", "image/jpeg"), expires = 5_000),
    )
    val shared = SharedMediaBuilder.build(messages, nowMs = 1_000)
    assertEquals(listOf("ok"), shared.photos.map { it.messageId })
    assertTrue(shared.documents.isEmpty())
    assertTrue(shared.links.isEmpty())
  }

  @Test
  fun `videos go with photos and are marked`() {
    val shared = SharedMediaBuilder.build(
      listOf(msg("v", 1, "video", media = media("c.mp4", "video/mp4")), msg("f", 2, "file", media = media("clip.mp4", "video/mp4"))),
      nowMs = 0,
    )
    assertEquals(listOf("f", "v"), shared.photos.map { it.messageId })
    assertTrue(shared.photos.all { it.video })
    assertTrue(shared.documents.isEmpty())
  }

  @Test
  fun `link finding trims punctuation, keeps brackets in urls, ignores plain words and repeats`() {
    assertEquals(listOf("https://example.com/a"), SharedMediaBuilder.links("Look (https://example.com/a)."))
    assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), SharedMediaBuilder.links("https://en.wikipedia.org/wiki/Foo_(bar)"))
    assertEquals(listOf("http://a.in/x?y=1"), SharedMediaBuilder.links("a http://a.in/x?y=1, http://a.in/x?y=1"))
    assertTrue(SharedMediaBuilder.links("just words. and more").isEmpty())
    assertTrue(SharedMediaBuilder.links("http://localhost").isEmpty())
    assertTrue(SharedMediaBuilder.links("").isEmpty())
  }

  @Test
  fun `host drops scheme, credentials, port, path and www`() {
    assertEquals("example.com", SharedMediaBuilder.host("https://user:pw@www.Example.com:8080/a/b?c#d"))
    assertEquals("a.in", SharedMediaBuilder.host("http://a.in"))
  }

  @Test
  fun `a caption link on a photo counts as a link too`() {
    val shared = SharedMediaBuilder.build(
      listOf(msg("p", 1, "image", text = "from https://example.com", media = media("a.jpg", "image/jpeg"))),
      nowMs = 0,
    )
    assertEquals(1, shared.photos.size)
    assertEquals(1, shared.links.size)
  }
}
