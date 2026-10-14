package com.samvad.core.media

/** When photos in a chat may download by themselves. */
enum class AutoDownload { ALWAYS, WIFI_ONLY, NEVER;

  companion object {
    fun parse(name: String?): AutoDownload = entries.firstOrNull { it.name == name } ?: ALWAYS
  }
}

/**
 * Data saver rules. A photo that is already on the phone always shows. Data saver tightens auto-download to
 * at least "Wi-Fi only" and shrinks what the user uploads; it never loosens a stricter setting.
 */
object DownloadPolicy {
  const val SAVER_EDGE_PX = 1024
  const val SAVER_JPEG_QUALITY = 70

  fun shouldAutoDownload(setting: AutoDownload, dataSaver: Boolean, metered: Boolean, cached: Boolean): Boolean {
    if (cached) return true
    val effective = if (dataSaver && setting == AutoDownload.ALWAYS) AutoDownload.WIFI_ONLY else setting
    return when (effective) {
      AutoDownload.ALWAYS -> true
      AutoDownload.WIFI_ONLY -> !metered
      AutoDownload.NEVER -> false
    }
  }

  fun uploadEdgePx(dataSaver: Boolean): Int = if (dataSaver) SAVER_EDGE_PX else MediaRules.MAX_IMAGE_EDGE

  fun uploadQuality(dataSaver: Boolean): Int = if (dataSaver) SAVER_JPEG_QUALITY else MediaRules.JPEG_QUALITY
}
