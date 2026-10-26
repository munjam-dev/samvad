package com.samvad.core

import com.samvad.core.e2ee.E2eePolicy
import com.samvad.core.e2ee.EncryptionBanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eePolicyTest {
  private fun banner(
    group: Boolean = false, feature: Boolean = true, encrypted: Boolean = false, since: Long? = null, peer: Boolean? = true,
  ) = E2eePolicy.banner(group, feature, encrypted, since, peer)

  @Test fun `an encrypted chat says so only when this build really encrypts it`() {
    assertEquals(EncryptionBanner.ENCRYPTED, banner(encrypted = true))
    assertEquals(EncryptionBanner.ENCRYPTED_FROM_LATER, banner(encrypted = true, since = 5L))
    assertEquals(EncryptionBanner.ENCRYPTED_UNSUPPORTED, banner(encrypted = true, feature = false))
  }

  @Test fun `a plain chat never claims to be encrypted`() {
    val claims = setOf(EncryptionBanner.ENCRYPTED, EncryptionBanner.ENCRYPTED_FROM_LATER)
    for (feature in listOf(true, false)) for (peer in listOf(true, false, null)) for (since in listOf(null, 1L)) {
      assertFalse(banner(feature = feature, peer = peer, since = since, encrypted = false) in claims)
    }
    assertFalse(banner(group = true, encrypted = true) in claims)
  }

  @Test fun `a plain chat says why it is plain and what can be done`() {
    assertEquals(EncryptionBanner.PLAINTEXT_CAN_UPGRADE, banner(peer = true))
    assertEquals(EncryptionBanner.PLAINTEXT_PEER_WITHOUT_KEYS, banner(peer = false))
    assertEquals("unknown: offer the upgrade, the server answers", EncryptionBanner.PLAINTEXT_CAN_UPGRADE, banner(peer = null))
    assertEquals(EncryptionBanner.PLAINTEXT_FEATURE_OFF, banner(feature = false))
    assertEquals(EncryptionBanner.GROUP_NOT_ENCRYPTED, banner(group = true))
  }

  @Test fun `features that need the server to read content are off in encrypted chats`() {
    assertFalse(E2eePolicy.canSchedule(true))
    assertFalse(E2eePolicy.canSendPoll(true))
    assertFalse(E2eePolicy.canReplyToMoment(true))
    assertTrue(E2eePolicy.canSchedule(false))
    assertTrue(E2eePolicy.canSendPoll(false))
    assertTrue(E2eePolicy.canReplyToMoment(false))
  }

  @Test fun `only encrypted messages can be edited in an encrypted chat`() {
    assertTrue(E2eePolicy.canEdit(chatIsEncrypted = true, messageEncrypted = true))
    assertFalse(E2eePolicy.canEdit(chatIsEncrypted = true, messageEncrypted = false))
    assertTrue(E2eePolicy.canEdit(chatIsEncrypted = false, messageEncrypted = false))
  }

  @Test fun `new chats are encrypted only between two people on a build with encryption`() {
    assertTrue(E2eePolicy.newChatShouldBeEncrypted(featureEnabled = true, isGroup = false, peerHasKeys = true))
    assertFalse(E2eePolicy.newChatShouldBeEncrypted(featureEnabled = false, isGroup = false, peerHasKeys = true))
    assertFalse(E2eePolicy.newChatShouldBeEncrypted(featureEnabled = true, isGroup = true, peerHasKeys = true))
    assertFalse(E2eePolicy.newChatShouldBeEncrypted(featureEnabled = true, isGroup = false, peerHasKeys = false))
  }

  @Test fun `an encrypted last message with no local preview is not shown as deleted`() {
    val enc = com.samvad.core.chat.LastMessage("1", "bob", "", "text", 10L, encrypted = true)
    val gone = com.samvad.core.chat.LastMessage("1", "bob", "", "text", 10L)
    fun row(last: com.samvad.core.chat.LastMessage) = com.samvad.core.chat.ChatList.build(
      "me", listOf(com.samvad.core.chat.Conversation("bob_me", listOf("bob", "me"), last, 10L)), emptyMap(),
      mapOf("bob" to com.samvad.core.chat.Profile("bob", "Bob")), com.samvad.core.chat.ChatFilter.ALL, 20L,
    ).single().preview
    assertEquals(com.samvad.core.chat.ChatList.ENCRYPTED_MESSAGE, row(enc))
    assertEquals(com.samvad.core.chat.ChatList.DELETED_MESSAGE, row(gone))
  }

  @Test fun `a notification is only built from decrypted text when previews are allowed`() {
    assertTrue(E2eePolicy.mayDecryptForNotification(previewsAllowed = true, featureEnabled = true, hasCiphertext = true))
    assertFalse(E2eePolicy.mayDecryptForNotification(previewsAllowed = false, featureEnabled = true, hasCiphertext = true))
    assertFalse(E2eePolicy.mayDecryptForNotification(previewsAllowed = true, featureEnabled = false, hasCiphertext = true))
    assertFalse(E2eePolicy.mayDecryptForNotification(previewsAllowed = true, featureEnabled = true, hasCiphertext = false))
  }
}
