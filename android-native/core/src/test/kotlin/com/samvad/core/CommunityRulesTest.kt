package com.samvad.core

import com.samvad.core.chat.SystemInfo
import com.samvad.core.chat.SystemText
import com.samvad.core.community.Community
import com.samvad.core.community.CommunityRole
import com.samvad.core.community.CommunityRow
import com.samvad.core.community.CommunityRules
import com.samvad.core.community.CreateFlow
import com.samvad.core.community.CreateStep
import com.samvad.core.community.GroupAction
import com.samvad.core.community.LinkedGroup
import com.samvad.core.community.MemberAction
import com.samvad.core.community.MemberRow
import com.samvad.core.community.UnreadLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityRulesTest {
  private fun community(
    members: List<String> = listOf("asha", "bhim", "chitra"),
    admins: List<String> = listOf("asha"),
    groups: List<LinkedGroup> = emptyList(),
  ) = Community("c1", "Lane 4", "", null, members, admins, "ann1", groups)

  private fun groups(n: Int) = (1..n).map { LinkedGroup("g$it", "Group $it", "asha") }

  @Test
  fun `names are trimmed and bounded to 60 characters`() {
    assertEquals("Lane 4", CommunityRules.validName("  Lane 4  "))
    assertNull(CommunityRules.validName("   "))
    assertNull(CommunityRules.validName(""))
    assertEquals(60, CommunityRules.validName("x".repeat(60))!!.length)
    assertNull(CommunityRules.validName("x".repeat(61)))
  }

  @Test
  fun `descriptions may be empty but never longer than 300 characters`() {
    assertEquals("", CommunityRules.validDescription(""))
    assertEquals("About us", CommunityRules.validDescription(" About us "))
    assertEquals(300, CommunityRules.validDescription("d".repeat(300))!!.length)
    assertNull(CommunityRules.validDescription("d".repeat(301)))
  }

  @Test
  fun `roles come from the admin and member lists, and outsiders have none`() {
    val c = community()
    assertEquals(CommunityRole.ADMIN, CommunityRules.roleOf(c, "asha"))
    assertEquals(CommunityRole.MEMBER, CommunityRules.roleOf(c, "bhim"))
    assertNull(CommunityRules.roleOf(c, "outsider"))
    // An admin entry without membership is not a role.
    assertNull(CommunityRules.roleOf(community(admins = listOf("ghost")), "ghost"))
  }

  @Test
  fun `only admins post announcements and manage the community`() {
    val c = community()
    assertTrue(CommunityRules.canPostAnnouncement(c, "asha"))
    assertFalse(CommunityRules.canPostAnnouncement(c, "bhim"))
    assertFalse(CommunityRules.canPostAnnouncement(c, "outsider"))
    assertTrue(CommunityRules.canManage(c, "asha"))
    assertFalse(CommunityRules.canManage(c, "bhim"))
    assertTrue(CommunityRules.isMember(c, "bhim"))
    assertFalse(CommunityRules.isMember(c, "outsider"))
  }

  @Test
  fun `the member cap is 1000 and the group cap is 10`() {
    assertFalse(CommunityRules.isFull(community(members = (1..999).map { "u$it" })))
    assertTrue(CommunityRules.isFull(community(members = (1..1000).map { "u$it" })))
    assertTrue(CommunityRules.canLinkMore(community(groups = groups(9))))
    assertFalse(CommunityRules.canLinkMore(community(groups = groups(10))))
    assertEquals(3, CommunityRules.groupSlotsLeft(7))
    assertEquals(0, CommunityRules.groupSlotsLeft(12))
  }

  @Test
  fun `admins get promote or demote and remove for others, never for themselves`() {
    val c = community(admins = listOf("asha", "bhim"))
    assertEquals(listOf(MemberAction.DEMOTE, MemberAction.REMOVE), CommunityRules.memberActions(c, "asha", "bhim"))
    assertEquals(listOf(MemberAction.PROMOTE, MemberAction.REMOVE), CommunityRules.memberActions(c, "asha", "chitra"))
    assertEquals(emptyList<MemberAction>(), CommunityRules.memberActions(c, "asha", "asha"))
    assertEquals(emptyList<MemberAction>(), CommunityRules.memberActions(c, "chitra", "bhim"))
    assertEquals(emptyList<MemberAction>(), CommunityRules.memberActions(c, "asha", "outsider"))
  }

  @Test
  fun `promotion is hidden once there are ten admins`() {
    val admins = (1..10).map { "a$it" }
    val c = community(members = admins + "bhim", admins = admins)
    assertEquals(listOf(MemberAction.REMOVE), CommunityRules.memberActions(c, "a1", "bhim"))
  }

  @Test
  fun `members list you first, then admins, then by name ignoring case`() {
    val rows = listOf(
      MemberRow("u1", "zoya", CommunityRole.MEMBER, false),
      MemberRow("u2", "Bhim", CommunityRole.MEMBER, false),
      MemberRow("u3", "meera", CommunityRole.ADMIN, false),
      MemberRow("u4", "Asha", CommunityRole.ADMIN, false),
      MemberRow("me", "Neel", CommunityRole.MEMBER, true),
    )
    assertEquals(listOf("me", "u4", "u3", "u2", "u1"), CommunityRules.sortMembers(rows).map { it.uid })
  }

  @Test
  fun `communities with unread announcements come first, then the newest, then by name`() {
    fun row(id: String, name: String, unread: Int, at: Long) = CommunityRow(id, name, 5, unread, at, null)
    val sorted = CommunityRules.sortCommunities(
      listOf(
        row("old", "Alpha", 0, 100),
        row("new", "Beta", 0, 900),
        row("unreadOld", "Gamma", 2, 50),
        row("unreadNew", "Delta", 1, 700),
        row("tieB", "bravo", 0, 0),
        row("tieA", "Alpha two", 0, 0),
      ),
    )
    assertEquals(listOf("unreadNew", "unreadOld", "new", "old", "tieA", "tieB"), sorted.map { it.id })
  }

  @Test
  fun `invite codes accept pasted text with spaces, dashes and lower case`() {
    assertEquals("ABCDEFGHJKLM", CommunityRules.normalizeInvite("abcd-efgh jklm"))
    assertTrue(CommunityRules.isInviteComplete(" abcd efgh jklm "))
    assertFalse(CommunityRules.isInviteComplete("ABCD EFGH"))
    // 0, 1, I and O are not in the alphabet, so they are dropped rather than guessed.
    assertEquals("ABCDEFGHJKL", CommunityRules.normalizeInvite("ABCD0EFGH1JKLI"))
    assertEquals("ABCD EFGH JKLM", CommunityRules.displayInvite("ABCDEFGHJKLM"))
  }

  @Test
  fun `a linked group offers join until you are in it, then open`() {
    assertEquals(GroupAction.JOIN, CommunityRules.groupAction(false))
    assertEquals(GroupAction.OPEN, CommunityRules.groupAction(true))
  }

  @Test
  fun `only groups you admin that are not yet linked can be added`() {
    val c = community(groups = listOf(LinkedGroup("g1", "Cricket", "asha")))
    val mine = listOf(LinkedGroup("g1", "Cricket", "asha"), LinkedGroup("g3", "book club", "asha"), LinkedGroup("g2", "Aarti", "asha"), LinkedGroup("g4", "Taken", "asha"))
    assertEquals(listOf("g2", "g3"), CommunityRules.linkableGroups(c, mine, alreadyElsewhere = setOf("g4")).map { it.cid })
  }

  @Test
  fun `creating takes three steps and each has its own gate`() {
    assertEquals(3, CreateFlow.TOTAL)
    assertEquals(CreateStep.DESCRIPTION, CreateStep.NAME_AND_PHOTO.next())
    assertEquals(CreateStep.GROUPS, CreateStep.DESCRIPTION.next())
    assertNull(CreateStep.GROUPS.next())
    assertNull(CreateStep.NAME_AND_PHOTO.previous())
    assertEquals(2, CreateStep.DESCRIPTION.number)
    assertFalse(CreateFlow.canContinue(CreateStep.NAME_AND_PHOTO, "  ", ""))
    assertTrue(CreateFlow.canContinue(CreateStep.NAME_AND_PHOTO, "Lane 4", ""))
    assertTrue(CreateFlow.canContinue(CreateStep.DESCRIPTION, "Lane 4", ""))
    assertFalse(CreateFlow.canContinue(CreateStep.DESCRIPTION, "Lane 4", "d".repeat(301)))
    assertTrue(CreateFlow.canContinue(CreateStep.GROUPS, "", ""))
  }

  @Test
  fun `choosing groups never passes ten and skips groups already in a community`() {
    val mine = listOf(LinkedGroup("g2", "b", "u"), LinkedGroup("g1", "A", "u"), LinkedGroup("g3", "c", "u"))
    assertEquals(listOf("g1", "g2"), CreateFlow.selectable(mine, setOf("g3")).map { it.cid })
    assertEquals(listOf("g1"), CreateFlow.toggle(emptyList(), "g1"))
    assertEquals(emptyList<String>(), CreateFlow.toggle(listOf("g1"), "g1"))
    val ten = (1..10).map { "g$it" }
    assertEquals(ten, CreateFlow.toggle(ten, "g11"))
  }

  @Test
  fun `an edit links the new groups and unlinks the removed ones`() {
    val (link, unlink) = CreateFlow.linkDiff(current = listOf("g1", "g2"), wanted = listOf("g2", "g3"))
    assertEquals(listOf("g3"), link)
    assertEquals(listOf("g1"), unlink)
    assertEquals(emptyList<String>() to emptyList<String>(), CreateFlow.linkDiff(listOf("g1"), listOf("g1")))
  }

  @Test
  fun `unread labels hide zero and cap at 99 plus`() {
    assertNull(UnreadLabel.of(0))
    assertNull(UnreadLabel.of(-3))
    assertEquals("7", UnreadLabel.of(7))
    assertEquals("99", UnreadLabel.of(99))
    assertEquals("99+", UnreadLabel.of(100))
  }

  @Test
  fun `joining a group from a community reads naturally in the chat`() {
    val line = SystemText.describe(SystemInfo("joinedFromCommunity"), actor = "meera", me = "me") { "Meera" }
    assertEquals("Meera joined from the community", line)
    assertEquals("You joined from the community", SystemText.describe(SystemInfo("joinedFromCommunity"), actor = "me", me = "me") { "x" })
  }
}
