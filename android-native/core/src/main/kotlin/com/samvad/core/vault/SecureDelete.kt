package com.samvad.core.vault

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Overwrite, then remove. On flash storage an overwrite cannot be guaranteed to reach the same cells
 * (wear levelling, copy-on-write file systems), so this is a second line of defence: the first is that the
 * file only ever held ciphertext, and forgetting the data key makes every copy unreadable.
 */
object SecureDelete {
  private const val BLOCK = 64 * 1024

  /** Replaces the contents with random bytes and flushes them to disk. Returns false if the file could not be overwritten. */
  fun overwrite(file: File, random: SecureRandom = SecureRandom()): Boolean {
    if (!file.isFile) return false
    return try {
      RandomAccessFile(file, "rw").use { raf ->
        val length = raf.length()
        val block = ByteArray(BLOCK)
        var written = 0L
        raf.seek(0)
        while (written < length) {
          random.nextBytes(block)
          val n = minOf(BLOCK.toLong(), length - written).toInt()
          raf.write(block, 0, n)
          written += n
        }
        raf.fd.sync()
      }
      true
    } catch (_: IOException) {
      false
    }
  }

  /** Overwrites and deletes [file]. Returns true when the file is gone afterwards (also when it never existed). */
  fun overwriteAndDelete(file: File, random: SecureRandom = SecureRandom()): Boolean {
    if (!file.exists()) return true
    overwrite(file, random)
    return file.delete() || !file.exists()
  }
}
