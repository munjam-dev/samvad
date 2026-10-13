package com.samvad.core

import com.samvad.core.chat.ChatExport
import com.samvad.core.chat.DeliveryState
import com.samvad.core.chat.ExportLabels
import com.samvad.core.chat.MemberState
import com.samvad.core.chat.Message
import com.samvad.core.chat.MessageRules
import com.samvad.core.media.Media
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatExportTest {
  private val zone = ZoneId.of("Asia/Kolkata")
  private val labels = ExportLabels(
    header = "Samvad chat with Asha",
    deleted = "This message was deleted",
    photo = "Photo",
    voice = "Voice message",
    file = "File",
    edited = "(edited)",
    unknownSender = "Deleted account",
  )
  // 2026-10-13 14:05 IST
  private val t0 = 1_791_880_500_000L

  @Test
  fun `formats one line per message in time order with media described`() {
    val messages = listOf(
      Message("3", "asha", "see you", t0 + 120_000, editedAt = t0 + 130_000),
      Message("1", "me", "hi\nthere", t0),
      Message("2", "asha", "", t0 + 60_000, kind = "image", media = Media("p", "x.jpg", 10, "image/jpeg")),
      Message("4", "me", "", t0 + 180_000, deleted = true),
      Message("5", "me", "notes", t0 + 240_000, kind = "file", media = Media("p", "Trip.pdf", 10, "application/pdf")),
      Message("6", "gone", "", t0 + 300_000, kind = "voice", media = Media("p", "v.m4a", 10, "audio/mp4", durationMs = 1000)),
    )
    val out = ChatExport.format(messages, mapOf("me" to "You", "asha" to "Asha"), labels, zone)
    assertEquals(
      listOf(
        "Samvad chat with Asha",
        "",
        "2026-10-13 14:05 - You: hi",
        "there",
        "2026-10-13 14:06 - Asha: <Photo>",
        "2026-10-13 14:07 - Asha: see you (edited)",
        "2026-10-13 14:08 - You: This message was deleted",
        "2026-10-13 14:09 - You: <File: Trip.pdf> notes",
        "2026-10-13 14:10 - Deleted account: <Voice message>",
        "",
      ),
      out.split("\n"),
    )
  }

  @Test
  fun `file names are safe and never empty`() {
    assertEquals("Samvad - Asha Rao.txt", ChatExport.fileName("Asha Rao"))
    assertEquals("Samvad - a b.txt", ChatExport.fileName("a/../b"))
    assertEquals("Samvad - chat.txt", ChatExport.fileName("///"))
    assertEquals("Samvad - आशा.txt", ChatExport.fileName("आशा"))
    assertEquals("Samvad - தமிழ் நண்பர்.txt", ChatExport.fileName("தமிழ் நண்பர்"))
  }

  @Test
  fun `with read receipts off, my messages show delivered at most`() {
    val m = Message("1", "me", "hi", 1000)
    val other = MemberState("asha", lastReadAt = 2000, lastDeliveredAt = 2000)
    assertEquals(DeliveryState.READ, MessageRules.deliveryState(m, other))
    assertEquals(DeliveryState.DELIVERED, MessageRules.deliveryState(m, other, showRead = false))
  }
}
