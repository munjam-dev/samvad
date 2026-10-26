package com.samvad.core.e2ee

/** Why an encrypted message cannot be shown on this device. */
enum class LockReason {
  /** The sender's identity key changed and has not been accepted yet. */
  IDENTITY_CHANGED,

  /** Sent before this phone had keys (new phone, reinstall): old encrypted history cannot be recovered. */
  NO_SESSION,

  /** Damaged, or already processed once and not kept. */
  UNREADABLE,

  /** My own message, but this phone no longer has its text (new phone, cleared data). */
  OWN_MESSAGE_LOST,

  /** This build of the app does not include end-to-end encryption. */
  DISABLED,
}

/**
 * What the conversation screen may say about encryption. The honesty rule of the product lives here, in one
 * place and under test: the words "end-to-end encrypted" are available only for a chat the server has marked
 * as encrypted AND only when this build can actually encrypt it.
 */
enum class EncryptionBanner {
  /** Every message in this chat, from its start, is end-to-end encrypted. */
  ENCRYPTED,

  /** Encryption was switched on later: only messages after that moment are protected. */
  ENCRYPTED_FROM_LATER,

  /** Plain chat; both people have keys, so it can be upgraded (new messages only). */
  PLAINTEXT_CAN_UPGRADE,

  /** Plain chat; the other person has no keys (older app or signed out). */
  PLAINTEXT_PEER_WITHOUT_KEYS,

  /** Plain chat; this build of the app has no encryption. */
  PLAINTEXT_FEATURE_OFF,

  /** The chat is marked encrypted but this build cannot read or write it. */
  ENCRYPTED_UNSUPPORTED,

  /** Groups, polls and Moments are not end-to-end encrypted in this version. */
  GROUP_NOT_ENCRYPTED,
}

object E2eePolicy {
  fun banner(
    isGroup: Boolean,
    featureEnabled: Boolean,
    chatIsEncrypted: Boolean,
    encryptedSinceMs: Long?,
    /** null while not known: the upgrade is offered and the answer comes from the server when it is tried */
    peerHasKeys: Boolean?,
  ): EncryptionBanner = when {
    isGroup -> EncryptionBanner.GROUP_NOT_ENCRYPTED
    chatIsEncrypted && !featureEnabled -> EncryptionBanner.ENCRYPTED_UNSUPPORTED
    chatIsEncrypted && encryptedSinceMs != null -> EncryptionBanner.ENCRYPTED_FROM_LATER
    chatIsEncrypted -> EncryptionBanner.ENCRYPTED
    !featureEnabled -> EncryptionBanner.PLAINTEXT_FEATURE_OFF
    peerHasKeys == false -> EncryptionBanner.PLAINTEXT_PEER_WITHOUT_KEYS
    else -> EncryptionBanner.PLAINTEXT_CAN_UPGRADE
  }

  /** Scheduled sending needs the server to write plaintext, which an encrypted chat refuses. */
  fun canSchedule(chatIsEncrypted: Boolean): Boolean = !chatIsEncrypted

  /** Polls and Moment replies carry readable content for the server. */
  fun canSendPoll(chatIsEncrypted: Boolean): Boolean = !chatIsEncrypted

  fun canReplyToMoment(chatIsEncrypted: Boolean): Boolean = !chatIsEncrypted

  /**
   * Edits in an encrypted chat replace the ciphertext, so only a message that was itself encrypted can be edited
   * (a message from before the upgrade is plaintext history and stays as it is).
   */
  fun canEdit(chatIsEncrypted: Boolean, messageEncrypted: Boolean): Boolean = !chatIsEncrypted || messageEncrypted

  /** A new chat is encrypted from its first message only when this build can encrypt and the other person has keys. */
  fun newChatShouldBeEncrypted(featureEnabled: Boolean, isGroup: Boolean, peerHasKeys: Boolean): Boolean =
    featureEnabled && !isGroup && peerHasKeys

  /** Whether a notification may be built from decrypted text: never without the person's preview setting. */
  fun mayDecryptForNotification(previewsAllowed: Boolean, featureEnabled: Boolean, hasCiphertext: Boolean): Boolean =
    featureEnabled && previewsAllowed && hasCiphertext
}
