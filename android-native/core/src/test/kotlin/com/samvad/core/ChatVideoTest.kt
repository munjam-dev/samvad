package com.samvad.core

import com.samvad.core.media.ChatVideo
import com.samvad.core.media.ChatVideoProblem
import com.samvad.core.media.MediaKind
import com.samvad.core.media.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatVideoTest {
  private val mb = 1024L * 1024

  @Test
  fun `a short small mp4 can be sent`() {
    assertNull(ChatVideo.problem("video/mp4", 5 * mb, 30_000))
    assertNull(ChatVideo.problem("video/mp4", 16 * mb, 60_000))
  }

  @Test
  fun `longer than sixty seconds is refused`() {
    assertEquals(ChatVideoProblem.TOO_LONG, ChatVideo.problem("video/mp4", 5 * mb, 60_001))
  }

  @Test
  fun `over sixteen megabytes is refused`() {
    assertEquals(ChatVideoProblem.TOO_BIG, ChatVideo.problem("video/mp4", 16 * mb + 1, 30_000))
  }

  @Test
  fun `other video types are refused instead of being converted`() {
    assertEquals(ChatVideoProblem.WRONG_TYPE, ChatVideo.problem("video/webm", 1 * mb, 5_000))
    assertEquals(ChatVideoProblem.WRONG_TYPE, ChatVideo.problem("video/quicktime", 1 * mb, 5_000))
    assertEquals(ChatVideoProblem.WRONG_TYPE, ChatVideo.problem(null, 1 * mb, 5_000))
  }

  @Test
  fun `a video with no size or length is unreadable`() {
    assertEquals(ChatVideoProblem.UNREADABLE, ChatVideo.problem("video/mp4", 0, 5_000))
    assertEquals(ChatVideoProblem.UNREADABLE, ChatVideo.problem("video/mp4", 1 * mb, 0))
  }

  @Test
  fun `when both are too much the length is reported first`() {
    assertEquals(ChatVideoProblem.TOO_LONG, ChatVideo.problem("video/mp4", 40 * mb, 120_000))
  }

  @Test
  fun `the poster frame fits inside 480 pixels and keeps its shape`() {
    assertEquals(480 to 270, ChatVideo.thumbSize(1920, 1080))
    assertEquals(270 to 480, ChatVideo.thumbSize(1080, 1920))
    assertEquals(320 to 240, ChatVideo.thumbSize(320, 240))
    assertEquals(480 to 270, ChatVideo.thumbSize(0, 0))
  }

  @Test
  fun `the poster is taken a little into the video`() {
    assertEquals(0L, ChatVideo.posterTimeUs(0))
    assertEquals(500_000L, ChatVideo.posterTimeUs(5_000))
    assertEquals(1_000_000L, ChatVideo.posterTimeUs(60_000))
  }

  @Test
  fun `video is its own media kind with mp4 as the only type`() {
    assertEquals(MediaKind.VIDEO, MediaKind.fromWire("video"))
    assertTrue(MediaRules.isAllowed(MediaKind.VIDEO, "video/mp4"))
    assertFalse(MediaRules.isAllowed(MediaKind.VIDEO, "video/webm"))
    assertFalse(MediaRules.isAllowed(MediaKind.FILE, "video/mp4"))
    assertEquals("0:42", ChatVideo.label(42_000))
    assertEquals(60, ChatVideo.seconds(59_600))
  }
}
