package com.samvad.core.media

/** A snapshot of the device's default network, as the platform reports it. */
data class NetworkInfo(
  /** A network with internet capability is the default. */
  val connected: Boolean = true,
  /** The system has checked that the internet really works on it (false on captive portals). */
  val validated: Boolean = true,
  val metered: Boolean = false,
  /** The platform's downstream bandwidth estimate in kbit/s; 0 or less when unknown. */
  val downstreamKbps: Int = 0,
)

enum class NetworkLevel {
  /** No usable internet. */
  OFFLINE,

  /** Connected, but the estimate says it is slow. */
  SLOW,

  /** Connected on a metered connection (mobile data, or a hotspot marked as metered). */
  METERED,

  /** Connected and nothing to flag. */
  GOOD,
}

/**
 * Turns a network snapshot into what the chat should say about it. The estimate is a hint from the
 * platform, not a measurement, so it only ever triggers a gentle suggestion, never a block.
 */
object NetworkQuality {
  /** Below this the connection is called slow (roughly 2G or a weak 3G). */
  const val SLOW_KBPS = 1_500

  fun classify(info: NetworkInfo): NetworkLevel = when {
    !info.connected || !info.validated -> NetworkLevel.OFFLINE
    info.downstreamKbps in 1 until SLOW_KBPS -> NetworkLevel.SLOW
    info.metered -> NetworkLevel.METERED
    else -> NetworkLevel.GOOD
  }

  fun isOnline(info: NetworkInfo): Boolean = classify(info) != NetworkLevel.OFFLINE

  /** The calm "waiting for network" banner in a chat. */
  fun showOfflineBanner(level: NetworkLevel): Boolean = level == NetworkLevel.OFFLINE

  /**
   * Suggest turning on Data saver once: only on a slow or metered connection, only while it is off, and
   * never again after the person has answered the suggestion.
   */
  fun shouldSuggestDataSaver(level: NetworkLevel, dataSaverOn: Boolean, alreadyAnswered: Boolean): Boolean =
    !dataSaverOn && !alreadyAnswered && (level == NetworkLevel.SLOW || level == NetworkLevel.METERED)
}

/** Progress of a transfer as a fraction, for a determinate progress ring. */
object TransferProgress {
  /** 0..1, or null while the total is not known yet. */
  fun fraction(transferred: Long, total: Long): Float? {
    if (total <= 0L) return null
    return (transferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
  }

  /** Whole percent, 0..100, or null while unknown. */
  fun percent(transferred: Long, total: Long): Int? = fraction(transferred, total)?.let { (it * 100).toInt() }
}
