package com.samvad.core.tarang

/**
 * Pure logic for Tarang (nearby chat over Bluetooth). No Android types, so it is unit tested on the JVM.
 * Messages live in memory only: nothing here is persisted or sent to a server.
 */

/** Nickname rules. The nickname is shown to everyone nearby, so it stays short, printable and free of control text. */
object TarangNickname {
  const val MAX_LENGTH = 24
  const val MIN_LENGTH = 2

  sealed interface Result {
    data class Valid(val nickname: String) : Result
    data object TooShort : Result
    data object TooLong : Result
    data object InvalidCharacters : Result
  }

  /** Trims, collapses inner whitespace runs, then checks length and characters. */
  fun validate(raw: String): Result {
    val cleaned = raw.trim().replace(Regex("\\s+"), " ")
    val length = cleaned.codePointCount(0, cleaned.length)
    if (length < MIN_LENGTH) return Result.TooShort
    if (length > MAX_LENGTH) return Result.TooLong
    // Letters, digits, marks (Indian scripts need combining marks), spaces and a few separators. No '@' or '#'
    // (they act as mention and channel markers on the wire) and no control or format characters.
    val ok = cleaned.all { c ->
      Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-' || c == '.' || isMark(c)
    }
    return if (ok) Result.Valid(cleaned) else Result.InvalidCharacters
  }

  private fun isMark(c: Char): Boolean {
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() ||
      type == Character.COMBINING_SPACING_MARK.toInt() ||
      type == Character.ENCLOSING_MARK.toInt()
  }

  /** A friendly default derived from the random peer id, for example "tarang-a1b2". */
  fun defaultFor(peerId: String): String = "tarang-" + peerId.lowercase().filter { it.isLetterOrDigit() }.take(4).ifEmpty { "user" }
}

enum class TarangDelivery { SENDING, SENT, DELIVERED, READ, FAILED }

data class TarangMessage(
  val id: String,
  val peerId: String,
  val senderName: String,
  val text: String,
  val timestampMs: Long,
  val mine: Boolean,
  val delivery: TarangDelivery? = null,
)

data class TarangPeer(
  val peerId: String,
  val nickname: String,
  /** Signal strength in dBm when the phone is a direct Bluetooth neighbour, otherwise null. */
  val rssi: Int?,
  val direct: Boolean,
  val encrypted: Boolean,
)

enum class TarangAvailability { UNSUPPORTED, BLUETOOTH_OFF, NEEDS_PERMISSION, OFF, RUNNING }

data class TarangState(
  val availability: TarangAvailability = TarangAvailability.OFF,
  val myPeerId: String = "",
  val nickname: String = "",
  val peers: List<TarangPeer> = emptyList(),
  val publicMessages: List<TarangMessage> = emptyList(),
  /** Private threads keyed by the other person's peer id. */
  val threads: Map<String, List<TarangMessage>> = emptyMap(),
  val unread: Map<String, Int> = emptyMap(),
  val publicUnread: Int = 0,
) {
  val peerCount: Int get() = peers.size
}

/** State transitions. Every function returns a new state; the lists are capped so memory stays small. */
object TarangReducer {
  const val MAX_PER_THREAD = 300

  private fun List<TarangMessage>.capped(): List<TarangMessage> = if (size > MAX_PER_THREAD) takeLast(MAX_PER_THREAD) else this

  fun withPeers(state: TarangState, peers: List<TarangPeer>): TarangState =
    state.copy(peers = peers.sortedWith(compareByDescending<TarangPeer> { it.direct }.thenBy { it.nickname.lowercase() }))

  /** Adds a public-room message once (the mesh can deliver the same packet twice). */
  fun withPublic(state: TarangState, message: TarangMessage, roomOpen: Boolean): TarangState {
    if (state.publicMessages.any { it.id == message.id }) return state
    val list = (state.publicMessages + message).sortedBy { it.timestampMs }.capped()
    return state.copy(publicMessages = list, publicUnread = if (message.mine || roomOpen) state.publicUnread else state.publicUnread + 1)
  }

  fun withPrivate(state: TarangState, message: TarangMessage, threadOpen: Boolean): TarangState {
    val current = state.threads[message.peerId].orEmpty()
    if (current.any { it.id == message.id }) return state
    val list = (current + message).sortedBy { it.timestampMs }.capped()
    val unread = if (message.mine || threadOpen) state.unread else state.unread + (message.peerId to ((state.unread[message.peerId] ?: 0) + 1))
    return state.copy(threads = state.threads + (message.peerId to list), unread = unread)
  }

  /** Delivery only moves forward: a late "delivered" never overwrites "read", and "failed" is final until resent. */
  fun withDelivery(state: TarangState, messageId: String, to: TarangDelivery): TarangState {
    var changed = false
    val threads = state.threads.mapValues { (_, list) ->
      list.map { m ->
        if (m.id == messageId && m.mine && advances(m.delivery, to)) {
          changed = true
          m.copy(delivery = to)
        } else m
      }
    }
    return if (changed) state.copy(threads = threads) else state
  }

  private fun advances(from: TarangDelivery?, to: TarangDelivery): Boolean {
    if (from == null) return true
    if (from == to) return false
    if (to == TarangDelivery.FAILED) return from == TarangDelivery.SENDING
    if (from == TarangDelivery.FAILED) return to == TarangDelivery.SENDING
    return to.ordinal > from.ordinal
  }

  fun markThreadRead(state: TarangState, peerId: String): TarangState =
    if ((state.unread[peerId] ?: 0) == 0) state else state.copy(unread = state.unread - peerId)

  fun markPublicRead(state: TarangState): TarangState = if (state.publicUnread == 0) state else state.copy(publicUnread = 0)

  /** Stopping Tarang forgets everything held in memory, but keeps the nickname the person chose. */
  fun cleared(state: TarangState, availability: TarangAvailability): TarangState =
    TarangState(availability = availability, myPeerId = state.myPeerId, nickname = state.nickname)

  /** Whether a send box should accept a message right now. */
  fun canSend(state: TarangState): Boolean = state.availability == TarangAvailability.RUNNING

  /** The total shown on the Explore card. */
  fun totalUnread(state: TarangState): Int = state.publicUnread + state.unread.values.sum()
}
