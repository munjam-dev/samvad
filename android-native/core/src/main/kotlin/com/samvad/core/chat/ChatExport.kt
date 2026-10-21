package com.samvad.core.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Words used in an exported chat, from the app's string resources. */
data class ExportLabels(
  val header: String,
  val deleted: String,
  /** "Photo", "Voice message", "File": shown as <Photo>, <Voice message>, <File: name.pdf>. */
  val photo: String,
  val voice: String,
  val file: String,
  val edited: String,
  val unknownSender: String,
  /** "Video" and "Contact": shown as <Video>, <Contact: Asha Rao, +91 98765 43210>. */
  val video: String = "Video",
  val contact: String = "Contact",
)

/**
 * Plain-text chat export (Phase 1.6), one line per message like "2026-10-13 14:05 - Asha: hello".
 * Media is described, never embedded. Pure, so it is tested on the JVM.
 */
object ChatExport {
  /** Upper bound on exported messages: each one is a Firestore read. */
  const val MAX_MESSAGES = 5000

  private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  fun format(messages: List<Message>, names: Map<String, String>, labels: ExportLabels, zone: ZoneId): String =
    buildString {
      appendLine(labels.header)
      appendLine()
      for (m in messages.sortedBy { it.createdAt }) {
        val time = stamp.format(Instant.ofEpochMilli(m.createdAt).atZone(zone))
        val who = names[m.senderId] ?: labels.unknownSender
        append(time).append(" - ").append(who).append(": ").appendLine(body(m, labels))
      }
    }

  private fun body(m: Message, labels: ExportLabels): String {
    if (m.deleted) return labels.deleted
    val media = when (m.kind) {
      "image" -> "<${labels.photo}>"
      "voice" -> "<${labels.voice}>"
      "file" -> "<${labels.file}: ${m.media?.name.orEmpty()}>"
      "video" -> "<${labels.video}>"
      "contact" -> m.contact?.let { c -> "<${labels.contact}: ${(listOf(c.name) + c.phones).joinToString(", ")}>" } ?: "<${labels.contact}>"
      else -> null
    }
    val text = listOfNotNull(media, m.text.takeIf { it.isNotBlank() }).joinToString(" ")
    return if (m.editedAt != null) "$text ${labels.edited}" else text
  }

  private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

  /**
   * A file name that is safe on every platform: letters, digits, spaces, '-' and '_' only, plus
   * combining marks so Indic names keep their vowel signs (आशा, not आश).
   */
  fun fileName(title: String): String {
    val keep = { c: Char -> c.isLetterOrDigit() || c.category in MARKS || c == ' ' || c == '-' || c == '_' }
    val clean = title.map { if (keep(it)) it else ' ' }
      .joinToString("").trim().replace(Regex("\\s+"), " ").take(60)
    return "Samvad - ${clean.ifEmpty { "chat" }}.txt"
  }
}
