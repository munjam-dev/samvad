package com.samvad.core.safety

/** Why a contact is reported. [wire] is the value the reportContact callable accepts. */
enum class ReportReason(val wire: String) {
  SPAM("spam"),
  ABUSE("abuse"),
  HARASSMENT("harassment"),
  IMPERSONATION("impersonation"),
  OTHER("other"),
  ;

  companion object {
    fun fromWire(value: String): ReportReason? = entries.firstOrNull { it.wire == value }
  }
}
