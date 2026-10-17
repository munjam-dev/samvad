package com.samvad.core

import com.samvad.core.vault.VaultFilter
import com.samvad.core.vault.VaultItem
import com.samvad.core.vault.VaultItemType
import com.samvad.core.vault.VaultLimits
import com.samvad.core.vault.VaultSearch
import com.samvad.core.vault.VaultTitles
import com.samvad.core.vault.VaultTypes
import com.samvad.core.vault.VaultViewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultItemsTest {
  @Test fun `type detection uses the MIME type first`() {
    assertEquals(VaultItemType.PHOTO, VaultTypes.detect("image/jpeg", "scan.pdf"))
    assertEquals(VaultItemType.DOCUMENT, VaultTypes.detect("application/pdf", "photo.jpg"))
    assertEquals(VaultItemType.PHOTO, VaultTypes.detect("IMAGE/PNG; charset=binary", null))
  }

  @Test fun `type detection falls back to the extension when the MIME type is missing or generic`() {
    assertEquals(VaultItemType.PHOTO, VaultTypes.detect(null, "IMG_0001.JPG"))
    assertEquals(VaultItemType.PHOTO, VaultTypes.detect("application/octet-stream", "pic.heic"))
    assertEquals(VaultItemType.DOCUMENT, VaultTypes.detect("", "report.pdf"))
    assertEquals(VaultItemType.DOCUMENT, VaultTypes.detect(null, "noextension"))
    assertEquals(VaultItemType.DOCUMENT, VaultTypes.detect(null, null))
    assertEquals("application/pdf", VaultTypes.canonicalMime("*/*", "a.PDF"))
    assertEquals("application/octet-stream", VaultTypes.canonicalMime(null, "archive.unknownext"))
  }

  @Test fun `extensions are read from the last dot only`() {
    assertEquals("pdf", VaultTypes.extensionOf("a.b.PDF"))
    assertEquals("", VaultTypes.extensionOf(".hidden"))
    assertEquals("", VaultTypes.extensionOf("trailing."))
    assertEquals("jpg", VaultTypes.extensionOf("C:\\dir.v2\\pic.jpg"))
    assertEquals("", VaultTypes.extensionOf("dir.v2/file"))
  }

  @Test fun `an ID copy is only ever chosen by the person`() {
    assertEquals(VaultItemType.ID_COPY, VaultTypes.forImport("image/jpeg", "x.jpg", asIdCopy = true))
    assertEquals(VaultItemType.ID_COPY, VaultTypes.forImport("application/pdf", "x.pdf", asIdCopy = true))
    assertEquals(VaultItemType.PHOTO, VaultTypes.forImport("image/jpeg", "x.jpg", asIdCopy = false))
    assertEquals(VaultItemType.DOCUMENT, VaultTypes.detect("text/plain", "n.txt"))
  }

  @Test fun `view kind follows type and MIME`() {
    assertEquals(VaultViewKind.TEXT, VaultTypes.viewKind(VaultItemType.NOTE, VaultTypes.MIME_NOTE))
    assertEquals(VaultViewKind.IMAGE, VaultTypes.viewKind(VaultItemType.PHOTO, "image/png"))
    assertEquals(VaultViewKind.IMAGE, VaultTypes.viewKind(VaultItemType.ID_COPY, "image/jpeg"))
    assertEquals(VaultViewKind.EXTERNAL, VaultTypes.viewKind(VaultItemType.ID_COPY, "application/pdf"))
    assertEquals(VaultViewKind.EXTERNAL, VaultTypes.viewKind(VaultItemType.DOCUMENT, "application/zip"))
  }

  @Test fun `only known-safe types are handed to another app`() {
    assertTrue(VaultTypes.canOpenInAnotherApp("application/pdf"))
    assertTrue(VaultTypes.canOpenInAnotherApp("image/heic"))
    assertTrue(VaultTypes.canOpenInAnotherApp("video/mp4"))
    assertFalse(VaultTypes.canOpenInAnotherApp("application/vnd.android.package-archive"))
    assertFalse(VaultTypes.canOpenInAnotherApp("text/html"))
    assertFalse(VaultTypes.canOpenInAnotherApp("application/x-sh"))
    assertFalse(VaultTypes.canOpenInAnotherApp("application/octet-stream"))
  }

  @Test fun `titles lose control, direction and zero-width characters`() {
    assertEquals("Passport", VaultTitles.sanitize("  Pass\u0000port\u200B  ", "x"))
    // Right-to-left override can disguise a file extension.
    assertEquals("invoice exe.pdf", VaultTitles.sanitize("invoice \u202Eexe.pdf", "x"))
    assertEquals("a b c", VaultTitles.sanitize("a\n\tb\r\n  c", "x"))
    assertEquals("a b", VaultTitles.sanitize("a/b", "x"))
    assertEquals("a b", VaultTitles.sanitize("a\\b", "x"))
    assertEquals("x", VaultTitles.sanitize("\u200B \u202E", "x"))
  }

  @Test fun `titles keep Hindi text and joiners`() {
    val hindi = "आधार कार्ड"
    assertEquals(hindi, VaultTitles.sanitize(hindi, "x"))
    val withJoiner = "क्\u200Dष"
    assertEquals(withJoiner, VaultTitles.sanitize(withJoiner, "x"))
  }

  @Test fun `titles are limited in length without splitting a character`() {
    val long = "a".repeat(500)
    assertEquals(VaultLimits.MAX_TITLE_CHARS, VaultTitles.sanitize(long, "x").length)
    val emoji = "\uD83D\uDE00".repeat(200) // surrogate pairs
    val cut = VaultTitles.sanitize(emoji, "x")
    assertEquals(VaultLimits.MAX_TITLE_CHARS, cut.codePointCount(0, cut.length))
  }

  @Test fun `empty titles use the fallback, and the fallback is cleaned too`() {
    assertEquals("Untitled note", VaultTitles.sanitize("   ", "Untitled note"))
    assertEquals("Untitled note", VaultTitles.sanitize(null, "Untitled note"))
    assertEquals("Untitled", VaultTitles.sanitize("", "\u200B"))
  }

  @Test fun `default titles come from the file name without the extension`() {
    assertEquals("Passport scan", VaultTitles.fromFileName("Passport scan.pdf", "Document"))
    assertEquals("archive.tar", VaultTitles.fromFileName("archive.tar.gz", "Document"))
    assertEquals("Document", VaultTitles.fromFileName(null, "Document"))
    assertEquals(".pdf", VaultTitles.fromFileName(".pdf", "Document"))
    assertEquals("photo", VaultTitles.fromFileName("../../photo.jpg", "Document"))
  }

  @Test fun `export names are safe and carry the right extension`() {
    assertEquals("Passport scan.pdf", VaultTitles.exportFileName("Passport scan", "application/pdf"))
    assertEquals("a_b.jpg", VaultTitles.exportFileName("a:b", "image/jpeg"))
    assertEquals("आधार कार्ड.txt", VaultTitles.exportFileName("आधार कार्ड", "text/plain"))
    assertEquals("vault-item", VaultTitles.exportFileName("...", "application/x-unknown"))
    assertEquals("note", VaultTitles.exportFileName("note", "application/x-unknown"))
    assertFalse(VaultTitles.exportFileName("../../etc/passwd", "text/plain").contains('/'))
  }

  private fun item(id: String, title: String, type: VaultItemType, updated: Long) =
    VaultItem(id, title, type, "application/pdf", 10, 0, updated)

  private val items = listOf(
    item("1", "Passport scan", VaultItemType.ID_COPY, 100),
    item("2", "Rent agreement 2026", VaultItemType.DOCUMENT, 300),
    item("3", "Holiday photo", VaultItemType.PHOTO, 200),
    item("4", "Wi-Fi codes", VaultItemType.NOTE, 400),
    item("5", "Café receipt", VaultItemType.DOCUMENT, 50),
  )

  @Test fun `no filter lists everything, newest change first`() {
    assertEquals(listOf("4", "2", "3", "1", "5"), VaultSearch.apply(items, VaultFilter()).map { it.id })
  }

  @Test fun `search matches every word, ignoring case and accents`() {
    assertEquals(listOf("1"), VaultSearch.apply(items, VaultFilter("PASSPORT")).map { it.id })
    assertEquals(listOf("2"), VaultSearch.apply(items, VaultFilter("agreement  rent")).map { it.id })
    assertEquals(listOf("5"), VaultSearch.apply(items, VaultFilter("cafe")).map { it.id })
    assertEquals(listOf("5"), VaultSearch.apply(items, VaultFilter("CAFÉ")).map { it.id })
    assertEquals(emptyList<String>(), VaultSearch.apply(items, VaultFilter("rent passport")).map { it.id })
    assertEquals(listOf("2"), VaultSearch.apply(items, VaultFilter("2026")).map { it.id })
  }

  @Test fun `search matches Hindi titles`() {
    val hindi = items + item("6", "आधार कार्ड", VaultItemType.ID_COPY, 10)
    assertEquals(listOf("6"), VaultSearch.apply(hindi, VaultFilter("आधार")).map { it.id })
    assertEquals(listOf("6"), VaultSearch.apply(hindi, VaultFilter("कार्ड")).map { it.id })
  }

  @Test fun `type chips combine with search and with each other`() {
    assertEquals(listOf("2", "5"), VaultSearch.apply(items, VaultFilter(types = setOf(VaultItemType.DOCUMENT))).map { it.id })
    assertEquals(listOf("2", "1", "5"), VaultSearch.apply(items, VaultFilter(types = setOf(VaultItemType.DOCUMENT, VaultItemType.ID_COPY), query = "r")).map { it.id })
    assertEquals(emptyList<String>(), VaultSearch.apply(items, VaultFilter("passport", setOf(VaultItemType.PHOTO))).map { it.id })
  }

  @Test fun `toggle adds and removes a type`() {
    val on = VaultFilter().toggle(VaultItemType.NOTE)
    assertEquals(setOf(VaultItemType.NOTE), on.types)
    assertTrue(on.isActive)
    val off = on.toggle(VaultItemType.NOTE)
    assertFalse(off.isActive)
  }

  @Test fun `counts per type`() {
    val counts = VaultSearch.counts(items)
    assertEquals(2, counts[VaultItemType.DOCUMENT])
    assertEquals(1, counts[VaultItemType.NOTE])
    assertEquals(1, counts[VaultItemType.PHOTO])
    assertEquals(1, counts[VaultItemType.ID_COPY])
  }
}
