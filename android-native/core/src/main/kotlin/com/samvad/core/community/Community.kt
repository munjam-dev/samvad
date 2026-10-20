package com.samvad.core.community

/**
 * Communities (Phase 6). Pure rules shared by the data layer and the UI. The server enforces every one
 * of these again; the client uses them to show only the actions that can work, and to validate input
 * before a round trip.
 */

/** A group linked into a community. The community keeps its own copy of the name. */
data class LinkedGroup(val cid: String, val name: String, val linkedBy: String)

data class Community(
  val id: String,
  val name: String,
  val description: String,
  /** Storage path (communityPhotos/{id}/…), readable by members only. */
  val photoPath: String?,
  val members: List<String>,
  val admins: List<String>,
  /** The conversation (type community_announcements) that holds the announcements. */
  val announcementsCid: String,
  val groups: List<LinkedGroup>,
  val createdBy: String = "",
)

enum class CommunityRole { ADMIN, MEMBER }

object CommunityRules {
  const val MAX_NAME = 60
  const val MAX_DESCRIPTION = 300
  const val MAX_MEMBERS = 1000
  const val MAX_GROUPS = 10
  const val MAX_ADMINS = 10
  const val INVITE_LENGTH = 12

  /** Same alphabet as group invite codes: no 0, 1, I or O. */
  const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

  fun validName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

  /** The description to send (trimmed), or null when it is too long. Empty is fine. */
  fun validDescription(raw: String): String? = raw.trim().takeIf { raw.length <= MAX_DESCRIPTION }

  fun roleOf(community: Community, uid: String): CommunityRole? = when {
    uid !in community.members -> null
    uid in community.admins -> CommunityRole.ADMIN
    else -> CommunityRole.MEMBER
  }

  fun isMember(community: Community, uid: String): Boolean = uid in community.members

  fun isAdmin(community: Community, uid: String): Boolean = roleOf(community, uid) == CommunityRole.ADMIN

  /** Only admins write announcements; everyone in the community reads them. */
  fun canPostAnnouncement(community: Community, uid: String): Boolean = isAdmin(community, uid)

  /** Editing, inviting, linking groups and managing members are admin actions. */
  fun canManage(community: Community, uid: String): Boolean = isAdmin(community, uid)

  fun isFull(community: Community): Boolean = community.members.size >= MAX_MEMBERS

  fun canLinkMore(community: Community): Boolean = community.groups.size < MAX_GROUPS

  /** How many more groups can still be linked. */
  fun groupSlotsLeft(linked: Int): Int = (MAX_GROUPS - linked).coerceAtLeast(0)

  /** The actions an admin sees for another member, in the order shown. */
  fun memberActions(community: Community, actor: String, target: String): List<MemberAction> {
    if (!canManage(community, actor) || actor == target || target !in community.members) return emptyList()
    val promote = when {
      target in community.admins -> MemberAction.DEMOTE
      community.admins.size < MAX_ADMINS -> MemberAction.PROMOTE
      else -> null
    }
    return listOfNotNull(promote, MemberAction.REMOVE)
  }

  /** Admins first, then by name; the person looking comes first of all. */
  fun sortMembers(rows: List<MemberRow>): List<MemberRow> =
    rows.sortedWith(compareByDescending<MemberRow> { it.me }.thenByDescending { it.role == CommunityRole.ADMIN }.thenBy { it.name.lowercase() })

  /** Communities with something unread first, then the most recent announcement, then by name. */
  fun sortCommunities(rows: List<CommunityRow>): List<CommunityRow> =
    rows.sortedWith(
      compareByDescending<CommunityRow> { it.unread > 0 }
        .thenByDescending { it.lastAnnouncementAt }
        .thenBy { it.name.lowercase() },
    )

  /** Keeps only letters and digits of the allowed alphabet, upper-cased, so pasted "ab-cd ef…" works. */
  fun normalizeInvite(raw: String): String =
    raw.uppercase().filter { it in INVITE_ALPHABET }.take(INVITE_LENGTH)

  fun isInviteComplete(raw: String): Boolean = normalizeInvite(raw).length == INVITE_LENGTH

  /** "ABCD EFGH JKLM" for reading aloud and typing. */
  fun displayInvite(code: String): String = code.chunked(4).joinToString(" ")

  /** What a linked-group card offers a community member. */
  fun groupAction(joined: Boolean): GroupAction = if (joined) GroupAction.OPEN else GroupAction.JOIN

  /** Whether the actor may add [candidate] groups: it must be one they admin, not yet linked, within the cap. */
  fun linkableGroups(
    community: Community,
    myAdminGroups: List<LinkedGroup>,
    alreadyElsewhere: Set<String> = emptySet(),
  ): List<LinkedGroup> =
    myAdminGroups.filter { g -> community.groups.none { it.cid == g.cid } && g.cid !in alreadyElsewhere }
      .sortedBy { it.name.lowercase() }
}

enum class MemberAction { PROMOTE, DEMOTE, REMOVE }

enum class GroupAction { JOIN, OPEN }

data class MemberRow(val uid: String, val name: String, val role: CommunityRole, val me: Boolean)

/** A line of the "My communities" list. */
data class CommunityRow(
  val id: String,
  val name: String,
  val memberCount: Int,
  val unread: Int,
  /** Epoch millis of the newest announcement, 0 when there is none. */
  val lastAnnouncementAt: Long,
  val photoPath: String?,
)

/** The three calm steps of creating (or editing) a community. */
enum class CreateStep {
  NAME_AND_PHOTO, DESCRIPTION, GROUPS;

  fun next(): CreateStep? = entries.getOrNull(ordinal + 1)
  fun previous(): CreateStep? = entries.getOrNull(ordinal - 1)
  val number: Int get() = ordinal + 1
}

object CreateFlow {
  val TOTAL: Int = CreateStep.entries.size

  /** Whether the person can move on from [step] with what they have entered. */
  fun canContinue(step: CreateStep, name: String, description: String): Boolean = when (step) {
    CreateStep.NAME_AND_PHOTO -> CommunityRules.validName(name) != null
    CreateStep.DESCRIPTION -> CommunityRules.validDescription(description) != null
    CreateStep.GROUPS -> true
  }

  /** Groups that can be chosen: the ones the person admins that are not already in a community. */
  fun selectable(myAdminGroups: List<LinkedGroup>, inACommunity: Set<String>): List<LinkedGroup> =
    myAdminGroups.filter { it.cid !in inACommunity }.sortedBy { it.name.lowercase() }

  /** Adds or removes [cid] from the chosen groups, never past the cap. */
  fun toggle(selected: List<String>, cid: String): List<String> = when {
    cid in selected -> selected - cid
    selected.size >= CommunityRules.MAX_GROUPS -> selected
    else -> selected + cid
  }

  /** What an edit changes about the linked groups: to link and to unlink. */
  fun linkDiff(current: List<String>, wanted: List<String>): Pair<List<String>, List<String>> =
    (wanted - current.toSet()) to (current - wanted.toSet())
}

/** Unread label for the list: nothing for 0, "99+" over 99. */
object UnreadLabel {
  fun of(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
  }
}
