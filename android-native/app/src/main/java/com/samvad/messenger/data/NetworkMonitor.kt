package com.samvad.messenger.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.samvad.core.media.NetworkInfo
import com.samvad.core.media.NetworkQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The device's default network: whether it is metered, and a live [state] for the chat banners and for
 * pending uploads that wait for a connection. The classification itself is pure and lives in
 * [NetworkQuality]. Uses ConnectivityManager callbacks (ACCESS_NETWORK_STATE).
 */
class NetworkMonitor(context: Context) {
  private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private val _state = MutableStateFlow(read(manager.activeNetwork))

  /** Latest snapshot of the default network. */
  val state: StateFlow<NetworkInfo> = _state

  init {
    // Lives as long as the process, like the container that owns this monitor.
    runCatching {
      manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _state.value = read(network) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _state.value = from(caps) }
        override fun onLost(network: Network) { _state.value = NetworkInfo(connected = false, validated = false) }
      })
    }
  }

  fun isMetered(): Boolean = manager.isActiveNetworkMetered

  val isOnline: Boolean get() = NetworkQuality.isOnline(_state.value)

  /** Returns as soon as the device has a working connection. */
  suspend fun awaitOnline() {
    state.first { NetworkQuality.isOnline(it) }
  }

  private fun read(network: Network?): NetworkInfo {
    val caps = network?.let { manager.getNetworkCapabilities(it) } ?: return NetworkInfo(connected = false, validated = false)
    return from(caps)
  }

  private fun from(caps: NetworkCapabilities) = NetworkInfo(
    connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    downstreamKbps = caps.linkDownstreamBandwidthKbps,
  )
}
