package com.samvad.messenger.ui.newchat

import android.content.Context
import android.content.Intent

/** Public download page used in invites. */
const val INVITE_DOWNLOAD_URL = "https://samvad-messenger.web.app/download"

/** Opens the system share sheet with the localized invite text and the download link. */
fun shareInvite(context: Context, inviteText: String) {
  context.startActivity(
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText), null),
  )
}
