// Samvad Tarang: minimal replacement for bitchat-android's AppStateStore (services/AppStateStore.kt upstream).
// Upstream is GPL-3.0 (https://github.com/permissionlesstech/bitchat-android); its store also persists
// conversations, which Tarang does not do. This file is original Samvad code (AGPL-3.0). It only tracks the
// per-transport peer sets the copied mesh code publishes, in memory, for the power manager and gossip.
package com.bitchat.android.services

import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppStateStore {
    private val directByTransport = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _directPeers = MutableStateFlow<Set<String>>(emptySet())
    val directPeers: StateFlow<Set<String>> = _directPeers

    fun setTransportPeers(transport: String, peers: Collection<String>) {}
    fun clearTransportPeers(transport: String) {}

    fun setTransportDirectPeers(transport: String, peers: Collection<String>) {
        directByTransport.value = directByTransport.value + (transport to peers.toSet())
        _directPeers.value = directByTransport.value.values.flatten().toSet()
    }

    fun clearTransportDirectPeers(transport: String) {
        directByTransport.value = directByTransport.value - transport
        _directPeers.value = directByTransport.value.values.flatten().toSet()
    }

    fun getDirectPeers(): Set<String> = _directPeers.value

    /** Tarang's facade tracks delivery state from the mesh delegate callbacks instead. */
    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) {}
}
