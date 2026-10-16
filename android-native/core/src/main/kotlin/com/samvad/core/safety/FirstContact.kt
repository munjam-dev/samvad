package com.samvad.core.safety

/**
 * When to show the "new contact" banner at the top of a chat. Uses chat metadata only (who sent messages and whether
 * the person is in my saved contacts), never message content.
 */
object FirstContact {
  /**
   * Show the banner for a direct chat when all of these hold:
   *  - the other person is not in my saved contacts, and I can tell (contacts access is on, so "not saved" is known);
   *  - they have written to me;
   *  - I have never written in this chat, judged only when the whole history is loaded ([allLoaded]);
   *  - I have not dismissed it for this chat, and I have not blocked them.
   * [dismissed] is null while the stored choice is still loading, which keeps the banner hidden rather than flashing.
   */
  fun shouldShow(
    isGroup: Boolean,
    contactsKnown: Boolean,
    isSaved: Boolean,
    incomingCount: Int,
    outgoingCount: Int,
    allLoaded: Boolean,
    dismissed: Boolean?,
    blockedByMe: Boolean,
  ): Boolean =
    !isGroup &&
      contactsKnown &&
      !isSaved &&
      incomingCount > 0 &&
      outgoingCount == 0 &&
      allLoaded &&
      dismissed == false &&
      !blockedByMe
}
