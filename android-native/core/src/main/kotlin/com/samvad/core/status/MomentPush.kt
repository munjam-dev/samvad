package com.samvad.core.status

/** What the device does with a "new moment" push: show it, show it without sound, or drop it. */
enum class MomentPingAction { SHOW, SILENT, DROP }

object MomentPush {
  /**
   * Mute is kept only on this device (Preferences), so the server cannot know it: the app drops the push for a muted
   * author. Quiet hours (also on-device) make the notification silent. A blank [authorId] is a malformed push.
   */
  fun action(authorId: String?, me: String, muted: Set<String>, quiet: Boolean, alertsOn: Boolean = true): MomentPingAction = when {
    !alertsOn -> MomentPingAction.DROP
    authorId.isNullOrBlank() || authorId == me -> MomentPingAction.DROP
    authorId in muted -> MomentPingAction.DROP
    quiet -> MomentPingAction.SILENT
    else -> MomentPingAction.SHOW
  }
}
