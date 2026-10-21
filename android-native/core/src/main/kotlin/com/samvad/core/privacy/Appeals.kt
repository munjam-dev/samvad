package com.samvad.core.privacy

enum class AppealKind {
  REPORT_DECISION,
  ACCOUNT_RESTRICTION,
  OTHER;

  val wire: String get() = when (this) {
    REPORT_DECISION -> "report_decision"
    ACCOUNT_RESTRICTION -> "account_restriction"
    OTHER -> "other"
  }
}

enum class AppealStatus {
  OPEN, GRANTED, DENIED;

  companion object {
    fun parse(raw: String?): AppealStatus = when (raw) {
      "granted" -> GRANTED
      "denied" -> DENIED
      else -> OPEN
    }
  }
}

data class Appeal(val id: String, val kind: AppealKind, val message: String, val status: AppealStatus, val createdAtMs: Long)

/** Mirrors the server (functions/src/appeals.ts), so the form validates before spending the call. */
object Appeals {
  const val MIN_MESSAGE = 10
  const val MAX_MESSAGE = 1000
  const val DAILY_LIMIT = 3

  fun messageProblem(message: String): Boolean = message.trim().length !in MIN_MESSAGE..MAX_MESSAGE
}
