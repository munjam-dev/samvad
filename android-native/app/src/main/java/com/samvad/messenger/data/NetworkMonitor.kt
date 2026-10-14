package com.samvad.messenger.data

import android.content.Context
import android.net.ConnectivityManager

/** Whether the current connection is metered (mobile data, or a hotspot the system marks as metered). */
class NetworkMonitor(context: Context) {
  private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  fun isMetered(): Boolean = manager.isActiveNetworkMetered
}
