package com.samvad.core.security

/** How long the app may stay in the background before it asks to be unlocked again. */
enum class LockTimeout(val millis: Long) {
  IMMEDIATELY(0L),
  ONE_MINUTE(60_000L),
  THIRTY_MINUTES(30 * 60_000L),
}

object AppLockPolicy {
  /**
   * Leaving for a picker or viewer the app itself opened (photo picker, file viewer) gets at least
   * this long, so attaching a photo with "Immediately" does not demand an unlock on return.
   */
  const val EXTERNAL_GRACE_MS = 60_000L

  /**
   * Whether coming back to the foreground must lock. Times come from a monotonic clock
   * (elapsedRealtime), so changing the phone's clock cannot skip the lock.
   *
   * @param backgroundedAt when the app last went to the background, or null if it has not since unlocking.
   * @param leftForExternal whether that background was caused by an activity the app launched itself.
   */
  fun shouldLock(
    enabled: Boolean,
    timeout: LockTimeout,
    backgroundedAt: Long?,
    now: Long,
    leftForExternal: Boolean = false,
  ): Boolean {
    if (!enabled || backgroundedAt == null) return false
    val elapsed = now - backgroundedAt
    if (elapsed < 0) return true
    val limit = if (leftForExternal) maxOf(timeout.millis, EXTERNAL_GRACE_MS) else timeout.millis
    return elapsed >= limit
  }
}
