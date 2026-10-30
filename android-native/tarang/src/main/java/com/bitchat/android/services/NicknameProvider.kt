// Samvad Tarang: replaces upstream NicknameProvider (which read the nickname from upstream's DataManager).
// The nickname is chosen by the user in Samvad's Tarang screen and held by the facade.
package com.bitchat.android.services

import android.content.Context

object NicknameProvider {
    @Volatile var nickname: String? = null

    fun getNickname(@Suppress("UNUSED_PARAMETER") context: Context, myPeerID: String): String =
        nickname?.takeIf { it.isNotBlank() } ?: myPeerID
}
