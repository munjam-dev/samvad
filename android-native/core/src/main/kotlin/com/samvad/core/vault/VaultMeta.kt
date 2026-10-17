package com.samvad.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** What is sealed in the database next to each item: the title and the MIME type. */
data class VaultMeta(val title: String, val mime: String)

object VaultMetaCodec {
  private const val VERSION = 1

  fun encode(meta: VaultMeta): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeByte(VERSION)
      it.writeUTF(meta.title)
      it.writeUTF(meta.mime)
    }
    return bytes.toByteArray()
  }

  /** @throws VaultFormatException when the bytes are not metadata this version understands. */
  fun decode(bytes: ByteArray): VaultMeta {
    try {
      DataInputStream(ByteArrayInputStream(bytes)).use {
        val version = it.readUnsignedByte()
        if (version != VERSION) throw VaultFormatException("Unsupported metadata version $version")
        return VaultMeta(it.readUTF(), it.readUTF())
      }
    } catch (e: IOException) {
      if (e is VaultFormatException) throw e
      throw VaultFormatException("Unreadable metadata")
    }
  }
}
