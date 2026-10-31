// Samvad Tarang: minimal replacement for bitchat-android's debug-settings singleton.
// Upstream (GPL-3.0, https://github.com/permissionlesstech/bitchat-android) keeps a large debug UI state here.
// Samvad ships no debug panel, so every switch is fixed at its upstream default and logging is a no-op.
// This file is original Samvad code (AGPL-3.0); it only mirrors the API the copied mesh code calls.
package com.bitchat.android.ui.debug

import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DebugScanResult(
    val deviceName: String?,
    val deviceAddress: String,
    val rssi: Int,
    val peerID: String?
)

class DebugSettingsManager private constructor() {
    val bleEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    val gattServerEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    val gattClientEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    val packetRelayEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    // Upstream defaults.
    val maxConnectionsOverall: StateFlow<Int> = MutableStateFlow(8)
    val maxServerConnections: StateFlow<Int> = MutableStateFlow(8)
    val maxClientConnections: StateFlow<Int> = MutableStateFlow(8)

    fun setNicknameResolver(resolver: (String) -> String?) {}
    fun addScanResult(result: DebugScanResult) {}
    fun logIncomingPacket(peerID: String, nickname: String?, messageType: String, routeDevice: String?) {}
    fun logIncoming(
        packet: BitchatPacket,
        fromPeerID: String,
        fromNickname: String?,
        fromDeviceAddress: String?,
        myPeerID: String
    ) {}
    fun logPeerConnection(peerID: String, nickname: String, deviceAddress: String, isInbound: Boolean) {}
    fun logOutgoing(
        packetType: String,
        toPeerID: String?,
        toNickname: String?,
        toDeviceAddress: String?,
        previousHopPeerID: String?,
        packetVersion: UByte,
        routeInfo: String?
    ) {}
    fun logPacketRelayDetailed(
        packetType: String,
        senderPeerID: String?,
        senderNickname: String?,
        fromPeerID: String?,
        fromNickname: String?,
        fromDeviceAddress: String?,
        toPeerID: String?,
        toNickname: String?,
        toDeviceAddress: String?,
        ttl: UByte,
        isRelay: Boolean,
        packetVersion: UByte,
        routeInfo: String?
    ) {}

    companion object {
        private val instance = DebugSettingsManager()
        fun getInstance(): DebugSettingsManager = instance
    }
}

object DebugPreferenceManager {
    fun getBleEnabled(default: Boolean): Boolean = default
    fun getSeenPacketCapacity(default: Int): Int = default
    fun getGcsMaxFilterBytes(default: Int): Int = default
    fun getGcsFprPercent(default: Double): Double = default
}
