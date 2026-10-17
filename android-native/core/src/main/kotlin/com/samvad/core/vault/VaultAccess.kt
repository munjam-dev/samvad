package com.samvad.core.vault

/**
 * When the vault may stay open without asking the person to authenticate again.
 *
 * The vault opens only after fingerprint, face or screen lock. It then stays open while the person is in
 * it. After leaving it (back, another screen, the app in the background) it stays open for [graceMs] so that
 * a quick trip to the photo picker or a viewer does not ask again; after that, authentication is needed.
 * "Lock now" closes it at once. Times come from a monotonic clock, so changing the phone's clock changes nothing.
 * All calls happen on the main thread.
 */
class VaultAccess(private val graceMs: Long = GRACE_MS) {
  private var open = false
  private var leftAt: Long? = null

  /** True when the vault may be shown at [now] without asking. Locks it first when the grace period has run out. */
  fun isOpen(now: Long): Boolean {
    if (!open) return false
    val left = leftAt
    if (left != null) {
      val away = now - left
      if (away < 0 || away >= graceMs) {
        lock()
        return false
      }
    }
    return true
  }

  /** Authentication succeeded. */
  fun unlocked() {
    open = true
    leftAt = null
  }

  /** The person came back to the vault after [left]; it is open again only when [isOpen] says so. */
  fun entered(now: Long): Boolean {
    val ok = isOpen(now)
    if (ok) leftAt = null
    return ok
  }

  /** The person left the vault screen or the app. Starts the grace period. */
  fun left(now: Long) {
    if (open && leftAt == null) leftAt = now
  }

  fun lock() {
    open = false
    leftAt = null
  }

  companion object {
    const val GRACE_MS = 60_000L
  }
}
