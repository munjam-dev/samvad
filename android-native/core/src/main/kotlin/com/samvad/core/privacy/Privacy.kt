package com.samvad.core.privacy

/**
 * Phase 3: who may see a profile part, or add the person to a group. Mirrors the server
 * (functions/src/lib/privacy.ts) and the rules exactly, so the three names are shared end to end.
 */
enum class Visibility {
  EVERYONE,
  /** People who share a direct chat with the owner: the owner started it, or has sent a message in it. */
  PARTNERS,
  NOBODY;

  companion object {
    fun parse(raw: String?): Visibility = entries.firstOrNull { it.wire == raw } ?: EVERYONE
  }

  /** The exact string stored in Firestore/RTDB and sent to the callables; never change these. */
  val wire: String get() = when (this) {
    EVERYONE -> "everyone"
    PARTNERS -> "partners"
    NOBODY -> "nobody"
  }
}

/**
 * All of a person's Phase 3 privacy settings. [lastSeen] is kept separately in the Realtime Database
 * (the presence rules cannot read Firestore), the rest in users/{uid}/settings/privacy.
 */
data class PrivacySettings(
  val lastSeen: Visibility = Visibility.EVERYONE,
  val aboutVisibility: Visibility = Visibility.EVERYONE,
  val photoVisibility: Visibility = Visibility.EVERYONE,
  val groupAdds: Visibility = Visibility.EVERYONE,
  val phoneDiscovery: Boolean = true,
)

object PrivacyRules {
  /** Whether [reader] may see something of [owner]'s under [setting], given whether they are chat partners. */
  fun mayShow(setting: Visibility, isPartner: Boolean, isSelf: Boolean): Boolean = when {
    isSelf -> true
    setting == Visibility.EVERYONE -> true
    setting == Visibility.PARTNERS -> isPartner
    else -> false
  }
}
