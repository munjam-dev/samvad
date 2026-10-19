package com.samvad.core.media

/** Why an upload attempt failed, as far as retrying is concerned. */
enum class FailureKind {
  /** The phone had no connection. Waiting for the network is not counted as a failed attempt. */
  OFFLINE,

  /** A passing problem such as a timeout or a server hiccup. Worth retrying with a growing delay. */
  TRANSIENT,

  /** Retrying cannot help (not allowed, file gone, over the limit). The person has to act. */
  PERMANENT,
}

enum class RetryStatus {
  /** Will be retried by itself at [RetryState.nextAttemptAtMs]. */
  ACTIVE,

  /** Gave up retrying by itself; waits for the person to tap Retry or Cancel. */
  FAILED,
}

/** The persisted retry state of one pending upload. */
data class RetryState(
  val attempts: Int = 0,
  val nextAttemptAtMs: Long = 0L,
  val status: RetryStatus = RetryStatus.ACTIVE,
)

/** What to do with a stored pending upload when the app starts or the network returns. */
sealed interface ResumeAction {
  /** Start (or continue) the upload now. */
  data object RunNow : ResumeAction

  /** Wait until [atMs], then run. */
  data class WaitUntil(val atMs: Long) : ResumeAction

  /** Show it as failed and let the person decide. */
  data object NeedsPerson : ResumeAction
}

/**
 * Exponential back-off and give-up rules for pending uploads. Pure: time and randomness are passed in so
 * the rules can be tested. Nothing here promises delivery; after [MAX_AUTO_ATTEMPTS] failed tries, or a
 * permanent failure, the upload is shown as failed and waits for the person.
 */
object UploadRetry {
  const val BASE_DELAY_MS = 2_000L
  const val MAX_DELAY_MS = 5 * 60_000L
  const val MAX_AUTO_ATTEMPTS = 8

  /** An upload older than this is not resumed by itself: a photo appearing a day late would confuse people. */
  const val MAX_AUTO_AGE_MS = 24L * 60 * 60 * 1000

  /** Share of the delay that random jitter may add, so many uploads do not retry in the same instant. */
  const val JITTER_SHARE = 0.25

  /**
   * Delay before the retry that follows failed attempt number [attempt] (1 for the first failure):
   * 2 s, 4 s, 8 s … capped at 5 minutes. [jitter] is a random number in 0 until 1, adding up to 25%.
   */
  fun delayMs(attempt: Int, jitter: Double = 0.0): Long {
    require(attempt >= 1)
    val exponent = (attempt - 1).coerceAtMost(20)
    val base = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    val extra = (base * JITTER_SHARE * jitter.coerceIn(0.0, 0.999999)).toLong()
    return (base + extra).coerceAtMost(MAX_DELAY_MS)
  }

  /** The state after an attempt failed at [nowMs]. */
  fun onFailure(state: RetryState, kind: FailureKind, nowMs: Long, jitter: Double = 0.0): RetryState = when (kind) {
    FailureKind.OFFLINE -> state.copy(nextAttemptAtMs = nowMs, status = RetryStatus.ACTIVE)
    FailureKind.PERMANENT -> state.copy(attempts = state.attempts + 1, status = RetryStatus.FAILED)
    FailureKind.TRANSIENT -> {
      val attempts = state.attempts + 1
      if (attempts >= MAX_AUTO_ATTEMPTS) {
        state.copy(attempts = attempts, status = RetryStatus.FAILED)
      } else {
        RetryState(attempts, nowMs + delayMs(attempts, jitter), RetryStatus.ACTIVE)
      }
    }
  }

  /** The person tapped Retry: start again from a clean slate, right away. */
  fun manualRetry(nowMs: Long): RetryState = RetryState(0, nowMs, RetryStatus.ACTIVE)

  fun isExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs > MAX_AUTO_AGE_MS

  /** Decides what to do with a stored upload on app start. */
  fun resume(state: RetryState, createdAtMs: Long, nowMs: Long): ResumeAction = when {
    state.status == RetryStatus.FAILED -> ResumeAction.NeedsPerson
    isExpired(createdAtMs, nowMs) -> ResumeAction.NeedsPerson
    state.nextAttemptAtMs > nowMs -> ResumeAction.WaitUntil(state.nextAttemptAtMs)
    else -> ResumeAction.RunNow
  }
}
