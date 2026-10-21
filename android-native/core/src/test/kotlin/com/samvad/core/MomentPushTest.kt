package com.samvad.core

import com.samvad.core.status.MomentPingAction
import com.samvad.core.status.MomentPush
import com.samvad.core.status.StatusItem
import com.samvad.core.status.StatusRules
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class MomentPushTest {
  @Test fun showsForAnAuthorIHaveNotMuted() =
    assertEquals(MomentPingAction.SHOW, MomentPush.action("anil", "me", emptySet(), quiet = false))

  @Test fun isSilentInQuietHours() =
    assertEquals(MomentPingAction.SILENT, MomentPush.action("anil", "me", emptySet(), quiet = true))

  @Test fun dropsAMutedAuthorEvenOutsideQuietHours() {
    assertEquals(MomentPingAction.DROP, MomentPush.action("anil", "me", setOf("anil"), quiet = false))
    assertEquals(MomentPingAction.DROP, MomentPush.action("anil", "me", setOf("anil"), quiet = true))
  }

  @Test fun dropsMalformedOrOwnPushes() {
    assertEquals(MomentPingAction.DROP, MomentPush.action(null, "me", emptySet(), quiet = false))
    assertEquals(MomentPingAction.DROP, MomentPush.action(" ", "me", emptySet(), quiet = false))
    assertEquals(MomentPingAction.DROP, MomentPush.action("me", "me", emptySet(), quiet = false))
  }

  @Test fun voiceRepliesAreLimitedToThirtySeconds() {
    assertTrue(StatusRules.voiceReplyOk(1_000))
    assertTrue(StatusRules.voiceReplyOk(30_000))
    assertFalse(StatusRules.voiceReplyOk(30_001))
    assertFalse(StatusRules.voiceReplyOk(0))
  }

  @Test fun repliesAreAllowedOnlyWhenNotTurnedOffAndNotOnMyOwn() {
    val item = StatusItem("s", "anil", "text", "hi", null, null, 0L, null)
    assertTrue(StatusRules.canReply(item, "me"))
    assertFalse(StatusRules.canReply(item.copy(repliesOff = true), "me"))
    assertFalse(StatusRules.canReply(item, "anil"))
  }
}
