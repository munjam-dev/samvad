package com.samvad.core.vault

import java.text.Normalizer
import java.util.Locale

/** What a vault item is, for the filter chips and the icon. [ID_COPY] is chosen by the person, never guessed. */
enum class VaultItemType { PHOTO, DOCUMENT, NOTE, ID_COPY }

/** How the viewer shows an item. */
enum class VaultViewKind { IMAGE, TEXT, EXTERNAL }

/** Decrypted description of one item. Only ever held in memory. */
data class VaultItem(
  val id: String,
  val title: String,
  val type: VaultItemType,
  val mime: String,
  val sizeBytes: Long,
  val createdAt: Long,
  val updatedAt: Long,
  val hasThumbnail: Boolean = false,
)

object VaultTypes {
  const val MIME_NOTE = "text/plain"
  const val MIME_FALLBACK = "application/octet-stream"

  private val extensionMime = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
    "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
    "pdf" to "application/pdf",
    "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "zip" to "application/zip",
  )

  /** The file extension without the dot, lower case; empty when there is none. */
  fun extensionOf(fileName: String?): String {
    val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return ""
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase(Locale.ROOT)
  }

  /**
   * The MIME type to store. A specific type reported by the system wins; a missing or generic one
   * ("application/octet-stream") falls back to the file extension. Parameters (";charset=...") are dropped.
   */
  fun canonicalMime(reported: String?, fileName: String?): String {
    val clean = reported?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val generic = clean.isEmpty() || clean == MIME_FALLBACK || clean == "*/*" || !clean.contains('/')
    if (!generic) return clean
    return extensionMime[extensionOf(fileName)] ?: MIME_FALLBACK
  }

  fun isImage(mime: String): Boolean = mime.startsWith("image/")

  /** Photo or document, from what the file is. A picked "ID copy" is decided by the caller, see [forImport]. */
  fun detect(mime: String?, fileName: String?): VaultItemType =
    if (isImage(canonicalMime(mime, fileName))) VaultItemType.PHOTO else VaultItemType.DOCUMENT

  /** The type for an imported file: an ID copy when the person said so, otherwise detected. */
  fun forImport(mime: String?, fileName: String?, asIdCopy: Boolean): VaultItemType =
    if (asIdCopy) VaultItemType.ID_COPY else detect(mime, fileName)

  /** Images are drawn in the app; notes are text; everything else goes to another app through a one-time copy. */
  fun viewKind(type: VaultItemType, mime: String): VaultViewKind = when {
    type == VaultItemType.NOTE -> VaultViewKind.TEXT
    isImage(mime) -> VaultViewKind.IMAGE
    else -> VaultViewKind.EXTERNAL
  }

  private val openableExact = setOf(
    "application/pdf", "text/plain", "text/csv", "text/markdown", "application/msword", "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  )

  /**
   * Whether a one-time copy may be handed to another app. An allow-list on purpose: an installer package,
   * a script or a web page imported as a "document" is never opened from here, it can only be exported.
   */
  fun canOpenInAnotherApp(mime: String): Boolean =
    mime in openableExact || mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")

  /** Extension to use when exporting or sharing, derived from the MIME type; empty if unknown. */
  fun extensionFor(mime: String): String =
    extensionMime.entries.firstOrNull { it.value == mime && it.key != "jpeg" }?.key.orEmpty()
}

object VaultLimits {
  /** Each import is held in memory only in 64 KiB pieces, but the person's phone is the limit; 100 MB is generous for IDs and scans. */
  const val MAX_IMPORT_BYTES = 100L * 1024 * 1024
  const val MAX_NOTE_CHARS = 100_000
  const val MAX_TITLE_CHARS = 80
  /** Largest image the in-app viewer decrypts into memory. Bigger ones open in another app. */
  const val MAX_INLINE_IMAGE_BYTES = 40L * 1024 * 1024
  const val THUMBNAIL_EDGE_PX = 256
}

object VaultTitles {
  /**
   * A title that is safe to show and to use as a suggested file name: no control, line-break, zero-width or
   * direction-override characters (the last can make "gpj.exe" look like "exe.jpg"), no path separators,
   * single spaces, at most [VaultLimits.MAX_TITLE_CHARS] characters. Falls back to [fallback] when nothing is left.
   */
  fun sanitize(raw: String?, fallback: String): String {
    val cleaned = clean(raw.orEmpty())
    return if (cleaned.isEmpty()) clean(fallback).ifEmpty { "Untitled" } else cleaned
  }

  /** The default title for an imported file: its name without the extension. */
  fun fromFileName(fileName: String?, fallback: String): String {
    val base = fileName?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
    val ext = VaultTypes.extensionOf(base)
    val withoutExt = if (ext.isEmpty()) base else base.dropLast(ext.length + 1)
    return sanitize(withoutExt, fallback)
  }

  /** A file name for an export: [title] plus the extension for [mime]. Letters (any script), marks, digits and `. _ ( ) -` are kept; other characters become underscores. */
  fun exportFileName(title: String, mime: String): String {
    val stem = clean(title).replace(Regex("""[^\p{L}\p{M}\p{N} ._()-]"""), "_").trim('.', ' ').ifEmpty { "vault-item" }
    val ext = VaultTypes.extensionFor(mime)
    val shortStem = stem.take(VaultLimits.MAX_TITLE_CHARS)
    return if (ext.isEmpty()) shortStem else "$shortStem.$ext"
  }

  private fun clean(raw: String): String {
    val sb = StringBuilder()
    var lastSpace = true
    var count = 0
    var i = 0
    val text = Normalizer.normalize(raw, Normalizer.Form.NFC)
    while (i < text.length && count < VaultLimits.MAX_TITLE_CHARS) {
      val cp = text.codePointAt(i)
      i += Character.charCount(cp)
      val ch = when {
        isInvisible(cp) -> continue
        cp == '/'.code || cp == '\\'.code -> ' '.code
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> ' '.code
        else -> cp
      }
      if (ch == ' '.code) {
        if (lastSpace) continue
        lastSpace = true
      } else {
        lastSpace = false
      }
      sb.appendCodePoint(ch)
      count++
    }
    return sb.toString().trim()
  }

  /**
   * Characters that draw nothing or reorder text. Zero-width joiner and non-joiner are kept: Hindi and
   * other Indic scripts need them to spell some words.
   */
  private fun isInvisible(cp: Int): Boolean {
    if (Character.isISOControl(cp)) return !Character.isWhitespace(cp)
    if (cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C) return true
    if (cp == 0x200B || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD) return true
    val type = Character.getType(cp)
    return type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt() || type == Character.UNASSIGNED.toInt()
  }
}
