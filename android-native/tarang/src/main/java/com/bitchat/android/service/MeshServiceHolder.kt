package com.bitchat.android.service

// Samvad: trimmed to the shared-gossip part; the service/unified-mesh holder is not used by Tarang.
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    @Volatile
    var sharedGossipSyncManager: GossipSyncManager? = null
        private set

    private val activeGossipOwners = mutableSetOf<String>()

    @Synchronized
    fun setGossipManager(
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket
    ) {
        val previous = sharedGossipSyncManager
        if (previous !== mgr) {
            try { previous?.stop() } catch (_: Exception) { }
        }
        sharedGossipSyncManager = mgr
        mgr.delegate = TransportGossipDelegate(signer)
        if (activeGossipOwners.isNotEmpty()) {
            mgr.start()
        }
    }

    @Synchronized
    fun startSharedGossip(owner: String) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) {
            sharedGossipSyncManager?.start()
        }
    }

    @Synchronized
    fun stopSharedGossip(owner: String) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) {
            sharedGossipSyncManager?.stop()
        }
    }

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            return signer(packet)
        }
    }

    @Synchronized
    fun clear() {
        try { sharedGossipSyncManager?.clear() } catch (_: Exception) { }
        try { sharedGossipSyncManager?.stop() } catch (_: Exception) { }
        sharedGossipSyncManager = null
        activeGossipOwners.clear()
    }
}
