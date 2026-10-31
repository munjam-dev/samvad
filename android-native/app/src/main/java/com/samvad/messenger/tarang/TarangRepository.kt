package com.samvad.messenger.tarang

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.NicknameProvider
import com.samvad.core.tarang.TarangAvailability
import com.samvad.core.tarang.TarangDelivery
import com.samvad.core.tarang.TarangMessage
import com.samvad.core.tarang.TarangNickname
import com.samvad.core.tarang.TarangPeer
import com.samvad.core.tarang.TarangReducer
import com.samvad.core.tarang.TarangState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Samvad's facade over the bitchat-android mesh engine (module :tarang). It owns the engine, turns its
 * callbacks into [TarangState] and keeps everything in memory: no message or nickname is stored on disk
 * (only the nickname the person chose), and nothing is sent to a Samvad server.
 *
 * The peer id comes from the engine's own random identity key. It is never derived from the phone number.
 */
class TarangRepository(context: Context) : BluetoothMeshDelegate {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val prefs = appContext.getSharedPreferences("tarang", Context.MODE_PRIVATE)

  private val _state = MutableStateFlow(TarangState(nickname = prefs.getString(KEY_NICKNAME, "").orEmpty()))
  val state: StateFlow<TarangState> = _state.asStateFlow()

  @Volatile private var mesh: BluetoothMeshService? = null
  private var refreshJob: Job? = null
  private var receiverRegistered = false

  /** Which screen is open, so new messages there do not count as unread. */
  @Volatile private var openThread: String? = null
  @Volatile private var roomOpen = false

  /** Private messages waiting for the Noise handshake with a peer to finish. */
  private val pending = HashMap<String, MutableList<Pair<String, String>>>()

  /** Ids of received private messages not yet acknowledged with a read receipt, per peer. */
  private val unreadIncoming = HashMap<String, MutableList<String>>()

  private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
      val s = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
      if (s == BluetoothAdapter.STATE_OFF && mesh != null) stopInternal(TarangAvailability.BLUETOOTH_OFF)
      else refreshAvailability()
    }
  }

  init {
    refreshAvailability()
  }

  // ---------- availability and permissions ----------

  fun hasBluetoothLe(): Boolean = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter() != null

  private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

  fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
  }

  /** Re-reads Bluetooth and permission state. Call when the screen resumes. */
  fun refreshAvailability() {
    _state.update { s ->
      val a = when {
        !hasBluetoothLe() -> TarangAvailability.UNSUPPORTED
        mesh != null -> TarangAvailability.RUNNING
        adapter()?.isEnabled != true -> TarangAvailability.BLUETOOTH_OFF
        !hasRequiredPermissions() -> TarangAvailability.NEEDS_PERMISSION
        else -> TarangAvailability.OFF
      }
      if (a == s.availability) s else s.copy(availability = a)
    }
  }

  // ---------- start and stop ----------

  /** Starts the mesh. Call only after the person turned Tarang on and the permissions are granted. */
  @Synchronized
  fun start() {
    refreshAvailability()
    if (mesh != null || _state.value.availability != TarangAvailability.OFF) return
    try {
      val service = BluetoothMeshService(appContext)
      val nick = _state.value.nickname.ifBlank { TarangNickname.defaultFor(service.myPeerID) }
      NicknameProvider.nickname = nick
      service.delegate = this
      service.onNoiseSessionEstablished = ::onSessionEstablished
      mesh = service
      service.startServices()
      _state.update { it.copy(availability = TarangAvailability.RUNNING, myPeerId = service.myPeerID, nickname = nick) }
      registerReceiver()
      startRefreshing()
      TarangService.start(appContext)
    } catch (e: Exception) {
      Log.e(TAG, "Could not start Tarang: ${e.javaClass.simpleName}")
      mesh = null
      refreshAvailability()
    }
  }

  /** The person pressed "Stop Tarang". Everything held in memory is forgotten. */
  fun stop() = stopInternal(TarangAvailability.OFF)

  @Synchronized
  private fun stopInternal(next: TarangAvailability) {
    val service = mesh
    mesh = null
    refreshJob?.cancel()
    refreshJob = null
    unregisterReceiver()
    try { service?.delegate = null; service?.stopServices() } catch (e: Exception) { Log.w(TAG, "stop: ${e.javaClass.simpleName}") }
    pending.clear()
    unreadIncoming.clear()
    _state.update { TarangReducer.cleared(it, next) }
    TarangService.stop(appContext)
    if (next == TarangAvailability.OFF) refreshAvailability()
  }

  /** Forgets the random nearby identity (the Noise key and peer id). A new one is created on the next start. */
  fun resetIdentity() {
    val service = mesh
    try { service?.clearAllInternalData() } catch (_: Exception) { }
    try { service?.clearAllEncryptionData() } catch (_: Exception) { }
    stopInternal(TarangAvailability.OFF)
    // The engine only wipes keys through a live service; make sure a stopped one is wiped too.
    try {
      val temp = BluetoothMeshService(appContext)
      temp.clearAllEncryptionData()
    } catch (_: Exception) { }
    _state.update { it.copy(myPeerId = "") }
  }

  private fun registerReceiver() {
    if (receiverRegistered) return
    ContextCompat.registerReceiver(appContext, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
  }

  private fun unregisterReceiver() {
    if (!receiverRegistered) return
    try { appContext.unregisterReceiver(bluetoothReceiver) } catch (_: Exception) { }
    receiverRegistered = false
  }

  // ---------- nickname ----------

  fun setNickname(raw: String): TarangNickname.Result {
    val result = TarangNickname.validate(raw)
    if (result is TarangNickname.Result.Valid) {
      prefs.edit().putString(KEY_NICKNAME, result.nickname).apply()
      NicknameProvider.nickname = result.nickname
      _state.update { it.copy(nickname = result.nickname) }
      // Tell nearby people the new name straight away.
      try { mesh?.sendBroadcastAnnounce() } catch (_: Exception) { }
    }
    return result
  }

  // ---------- sending ----------

  fun sendPublic(text: String) {
    val service = mesh ?: return
    val body = text.trim()
    if (body.isEmpty()) return
    val s = _state.value
    val id = UUID.randomUUID().toString()
    _state.update { TarangReducer.withPublic(it, TarangMessage(id, "", s.nickname, body, System.currentTimeMillis(), true), true) }
    service.sendMessage(body)
  }

  fun sendPrivate(peerId: String, text: String) {
    val service = mesh ?: return
    val body = text.trim()
    if (body.isEmpty()) return
    val id = UUID.randomUUID().toString()
    val s = _state.value
    _state.update {
      TarangReducer.withPrivate(it, TarangMessage(id, peerId, s.nickname, body, System.currentTimeMillis(), true, TarangDelivery.SENDING), true)
    }
    deliverPrivate(service, peerId, id, body)
  }

  fun retry(peerId: String, messageId: String) {
    val service = mesh ?: return
    val m = _state.value.threads[peerId]?.firstOrNull { it.id == messageId && it.mine } ?: return
    _state.update { TarangReducer.withDelivery(it, messageId, TarangDelivery.SENDING) }
    deliverPrivate(service, peerId, messageId, m.text)
  }

  private fun deliverPrivate(service: BluetoothMeshService, peerId: String, id: String, body: String) {
    val nick = service.getPeerNicknames()[peerId] ?: peerId
    if (service.hasEstablishedSession(peerId)) {
      service.sendPrivateMessage(body, peerId, nick, id)
      _state.update { TarangReducer.withDelivery(it, id, TarangDelivery.SENT) }
    } else {
      synchronized(pending) { pending.getOrPut(peerId) { mutableListOf() }.add(id to body) }
      service.initiateNoiseHandshake(peerId)
      scope.launch {
        delay(HANDSHAKE_TIMEOUT_MS)
        val stillWaiting = synchronized(pending) { pending[peerId]?.removeAll { it.first == id } == true }
        if (stillWaiting) _state.update { TarangReducer.withDelivery(it, id, TarangDelivery.FAILED) }
      }
    }
  }

  private fun onSessionEstablished(peerId: String) {
    val service = mesh ?: return
    val queued = synchronized(pending) { pending.remove(peerId).orEmpty() }
    val nick = service.getPeerNicknames()[peerId] ?: peerId
    for ((id, body) in queued) {
      service.sendPrivateMessage(body, peerId, nick, id)
      _state.update { TarangReducer.withDelivery(it, id, TarangDelivery.SENT) }
    }
    refreshPeers()
  }

  // ---------- which screen is open ----------

  fun setRoomOpen(open: Boolean) {
    roomOpen = open
    if (open) _state.update { TarangReducer.markPublicRead(it) }
  }

  fun setThreadOpen(peerId: String?) {
    openThread = peerId
    if (peerId == null) return
    _state.update { TarangReducer.markThreadRead(it, peerId) }
    val service = mesh ?: return
    // Start the handshake early, so the first message goes out without waiting.
    if (!service.hasEstablishedSession(peerId)) service.initiateNoiseHandshake(peerId)
    val ids = synchronized(unreadIncoming) { unreadIncoming.remove(peerId).orEmpty() }
    val me = _state.value.nickname
    if (service.hasEstablishedSession(peerId)) ids.forEach { service.sendReadReceipt(it, peerId, me) }
  }

  // ---------- peers ----------

  private fun startRefreshing() {
    refreshJob?.cancel()
    refreshJob = scope.launch {
      while (isActive) {
        refreshPeers()
        delay(REFRESH_MS)
      }
    }
  }

  private fun refreshPeers() {
    val service = mesh ?: return
    try {
      val names = service.getPeerNicknames()
      val rssi = service.getPeerRSSI()
      val me = service.myPeerID
      val peers = names.filterKeys { it != me }.map { (id, name) ->
        val info = service.getPeerInfo(id)
        TarangPeer(id, name, rssi[id], info?.isDirectConnection == true, service.hasEstablishedSession(id))
      }
      _state.update { TarangReducer.withPeers(it, peers) }
    } catch (e: Exception) {
      Log.w(TAG, "refreshPeers: ${e.javaClass.simpleName}")
    }
  }

  // ---------- mesh callbacks (background threads) ----------

  override fun didReceiveMessage(message: BitchatMessage) {
    if (message.sender == "system") return
    val mine = message.senderPeerID == _state.value.myPeerId
    if (mine) return
    val text = message.content
    if (text.isBlank()) return
    val ts = message.timestamp.time
    if (message.isPrivate) {
      val peer = message.senderPeerID ?: return
      val open = openThread == peer
      val m = TarangMessage(message.id, peer, message.sender, text, ts, false)
      _state.update { TarangReducer.withPrivate(it, m, open) }
      if (open) {
        val service = mesh
        if (service != null) try { service.sendReadReceipt(message.id, peer, _state.value.nickname) } catch (_: Exception) { }
      } else {
        synchronized(unreadIncoming) { unreadIncoming.getOrPut(peer) { mutableListOf() }.add(message.id) }
      }
    } else if (message.channel == null) {
      val m = TarangMessage(message.id, message.senderPeerID.orEmpty(), message.sender, text, ts, false)
      _state.update { TarangReducer.withPublic(it, m, roomOpen) }
    }
  }

  override fun didUpdatePeerList(peers: List<String>) = refreshPeers()
  override fun didReceiveChannelLeave(channel: String, fromPeer: String) {}
  override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
  override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}

  override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
    _state.update { TarangReducer.withDelivery(it, messageID, TarangDelivery.DELIVERED) }
  }

  override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
    _state.update { TarangReducer.withDelivery(it, messageID, TarangDelivery.READ) }
  }

  override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
  override fun getNickname(): String? = _state.value.nickname.ifBlank { null }
  override fun isFavorite(peerID: String): Boolean = false

  companion object {
    private const val TAG = "Tarang"
    private const val KEY_NICKNAME = "nickname"
    private const val REFRESH_MS = 4_000L
    private const val HANDSHAKE_TIMEOUT_MS = 20_000L

    /** Runtime permissions to ask for, in one batch, when the person opens Tarang. */
    fun requiredPermissions(): List<String> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
      } else {
        // Before Android 12 a Bluetooth scan needs the location permission. Samvad collects no location data.
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
      }

    /** Asked together with the required ones; Tarang works if it is denied. */
    fun optionalPermissions(): List<String> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
  }
}
