package com.samvad.core.tarang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TarangTest {
  @Test fun nicknameIsTrimmedAndCollapsed() {
    assertEquals(TarangNickname.Result.Valid("Asha Rao"), TarangNickname.validate("  Asha    Rao "))
  }

  @Test fun nicknameLengthLimits() {
    assertEquals(TarangNickname.Result.TooShort, TarangNickname.validate(" a "))
    assertEquals(TarangNickname.Result.TooLong, TarangNickname.validate("x".repeat(25)))
    assertTrue(TarangNickname.validate("x".repeat(24)) is TarangNickname.Result.Valid)
  }

  @Test fun nicknameAcceptsIndianScripts() {
    assertTrue(TarangNickname.validate("తరంగ్") is TarangNickname.Result.Valid)
    assertTrue(TarangNickname.validate("तरंग मित्र") is TarangNickname.Result.Valid)
    assertTrue(TarangNickname.validate("தரங்") is TarangNickname.Result.Valid)
  }

  @Test fun nicknameRejectsMarkersAndControlText() {
    assertEquals(TarangNickname.Result.InvalidCharacters, TarangNickname.validate("@asha"))
    assertEquals(TarangNickname.Result.InvalidCharacters, TarangNickname.validate("#room"))
    assertEquals(TarangNickname.Result.InvalidCharacters, TarangNickname.validate("ab\u0000cd"))
    assertEquals(TarangNickname.Result.InvalidCharacters, TarangNickname.validate("ab‮cd"))
  }

  @Test fun defaultNicknameUsesPeerId() {
    assertEquals("tarang-a1b2", TarangNickname.defaultFor("A1B2C3D4E5F60718"))
    assertEquals("tarang-user", TarangNickname.defaultFor(""))
  }

  private fun msg(id: String, peer: String = "p1", mine: Boolean = false, t: Long = 1, d: TarangDelivery? = null) =
    TarangMessage(id, peer, "n", "hi", t, mine, d)

  @Test fun publicMessagesDeduplicateAndCountUnread() {
    var s = TarangState(availability = TarangAvailability.RUNNING)
    s = TarangReducer.withPublic(s, msg("a"), roomOpen = false)
    s = TarangReducer.withPublic(s, msg("a"), roomOpen = false)
    s = TarangReducer.withPublic(s, msg("b", mine = true, t = 2), roomOpen = false)
    assertEquals(2, s.publicMessages.size)
    assertEquals(1, s.publicUnread)
    s = TarangReducer.markPublicRead(s)
    assertEquals(0, s.publicUnread)
  }

  @Test fun openThreadDoesNotAddUnread() {
    var s = TarangState()
    s = TarangReducer.withPrivate(s, msg("a"), threadOpen = true)
    assertEquals(0, TarangReducer.totalUnread(s))
    s = TarangReducer.withPrivate(s, msg("b", t = 2), threadOpen = false)
    assertEquals(1, TarangReducer.totalUnread(s))
    s = TarangReducer.markThreadRead(s, "p1")
    assertEquals(0, TarangReducer.totalUnread(s))
  }

  @Test fun threadsAreCapped() {
    var s = TarangState()
    for (i in 1..(TarangReducer.MAX_PER_THREAD + 20)) s = TarangReducer.withPrivate(s, msg("m$i", t = i.toLong()), true)
    assertEquals(TarangReducer.MAX_PER_THREAD, s.threads.getValue("p1").size)
    assertEquals("m${TarangReducer.MAX_PER_THREAD + 20}", s.threads.getValue("p1").last().id)
  }

  @Test fun deliveryOnlyMovesForward() {
    var s = TarangReducer.withPrivate(TarangState(), msg("a", mine = true, d = TarangDelivery.SENDING), true)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.READ)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.DELIVERED)
    assertEquals(TarangDelivery.READ, s.threads.getValue("p1").single().delivery)
    val same = TarangReducer.withDelivery(s, "a", TarangDelivery.SENT)
    assertSame(s, same)
  }

  @Test fun failedCanBeRetriedButNotAfterDelivered() {
    var s = TarangReducer.withPrivate(TarangState(), msg("a", mine = true, d = TarangDelivery.SENDING), true)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.FAILED)
    assertEquals(TarangDelivery.FAILED, s.threads.getValue("p1").single().delivery)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.SENDING)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.DELIVERED)
    s = TarangReducer.withDelivery(s, "a", TarangDelivery.FAILED)
    assertEquals(TarangDelivery.DELIVERED, s.threads.getValue("p1").single().delivery)
  }

  @Test fun peersSortDirectFirstThenName() {
    val s = TarangReducer.withPeers(
      TarangState(),
      listOf(TarangPeer("3", "zed", null, false, false), TarangPeer("2", "bea", -60, true, true), TarangPeer("1", "Amy", -70, true, false)),
    )
    assertEquals(listOf("Amy", "bea", "zed"), s.peers.map { it.nickname })
  }

  @Test fun clearingKeepsNicknameOnly() {
    var s = TarangState(myPeerId = "me", nickname = "Asha", availability = TarangAvailability.RUNNING)
    s = TarangReducer.withPublic(s, msg("a"), false)
    val c = TarangReducer.cleared(s, TarangAvailability.OFF)
    assertEquals("Asha", c.nickname)
    assertEquals("me", c.myPeerId)
    assertTrue(c.publicMessages.isEmpty() && c.threads.isEmpty() && c.peers.isEmpty())
    assertTrue(!TarangReducer.canSend(c))
  }
}
