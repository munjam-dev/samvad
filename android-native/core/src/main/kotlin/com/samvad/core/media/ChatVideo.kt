package com.samvad.core.media

import kotlin.math.roundToInt

/** Why a chosen video cannot be sent in a chat. Nothing is shrunk or cut for the person: they are told and choose. */
enum class ChatVideoProblem { UNREADABLE, WRONG_TYPE, TOO_LONG, TOO_BIG }

/**
 * Videos in chats. Mirrors firestore.rules and storage.rules (MP4, at most 16 MB, at most 60 seconds, a small
 * JPEG poster frame). The server enforces all of it; these checks only give an instant, plain explanation.
 */
object ChatVideo {
  const val MAX_BYTES = MediaRules.MAX_BYTES
  const val MAX_DURATION_MS = 60_000L
  const val THUMB_MAX_EDGE = 480
  const val THUMB_QUALITY = 78
  /** Storage allows a poster frame of at most this size. */
  const val THUMB_MAX_BYTES = 256 * 1024
  const val FILE_NAME = "video.mp4"
  const val THUMB_NAME = "thumb.jpg"

  /** The first problem with a chosen video, or null when it can be sent. Order: type, readable, length, size. */
  fun problem(mime: String?, sizeBytes: Long, durationMs: Long): ChatVideoProblem? = when {
    mime != MediaRules.VIDEO_MIME -> ChatVideoProblem.WRONG_TYPE
    sizeBytes <= 0L || durationMs <= 0L -> ChatVideoProblem.UNREADABLE
    durationMs > MAX_DURATION_MS -> ChatVideoProblem.TOO_LONG
    sizeBytes > MAX_BYTES -> ChatVideoProblem.TOO_BIG
    else -> null
  }

  /** Size of the poster frame: fits inside [THUMB_MAX_EDGE] on its long edge, never larger than the video. */
  fun thumbSize(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return THUMB_MAX_EDGE to (THUMB_MAX_EDGE * 9 / 16)
    return MediaRules.fitWithin(width, height, THUMB_MAX_EDGE)
  }

  /** Time of the frame used as the poster: a little in, so it is not a black first frame. Microseconds. */
  fun posterTimeUs(durationMs: Long): Long = (durationMs / 10).coerceIn(0L, 1_000L) * 1000L

  /** "0:42" or "1:00". */
  fun label(durationMs: Long): String = MediaRules.formatDuration(durationMs)

  /** Whole seconds, for the "x seconds" in the too-long message. */
  fun seconds(durationMs: Long): Int = (durationMs / 1000.0).roundToInt()
}
