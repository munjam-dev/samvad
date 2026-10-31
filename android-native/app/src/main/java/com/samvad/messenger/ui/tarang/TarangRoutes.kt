package com.samvad.messenger.ui.tarang

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samvad.core.tarang.TarangAvailability
import com.samvad.messenger.R
import com.samvad.messenger.tarang.TarangRepository

/** The nearby public room: everyone in Bluetooth range (and relayed range) can read it. */
@Composable
fun TarangRoomScreen(repository: TarangRepository, onBack: () -> Unit) {
  val state = repository.state.collectAsStateWithLifecycle().value
  DisposableEffect(Unit) {
    repository.setRoomOpen(true)
    onDispose { repository.setRoomOpen(false) }
  }
  val running = state.availability == TarangAvailability.RUNNING
  TarangChatScreen(
    title = stringResource(R.string.tarang_room_title),
    subtitle = stringResource(R.string.tarang_room_sub),
    avatarSeed = "room",
    isRoom = true,
    messages = state.publicMessages,
    canSend = running,
    banner = null,
    emptyTitle = stringResource(R.string.tarang_room_empty_title),
    emptyBody = stringResource(R.string.tarang_room_empty_body),
    onSend = repository::sendPublic,
    onRetry = {},
    onBack = onBack,
  )
}

/** A private, Noise-encrypted chat with one nearby person. */
@Composable
fun TarangPrivateChatScreen(repository: TarangRepository, peerId: String, onBack: () -> Unit) {
  val state = repository.state.collectAsStateWithLifecycle().value
  DisposableEffect(peerId) {
    repository.setThreadOpen(peerId)
    onDispose { repository.setThreadOpen(null) }
  }
  val peer = state.peers.firstOrNull { it.peerId == peerId }
  val thread = state.threads[peerId].orEmpty()
  // Keep the name after the person leaves range, using their last message.
  val name = peer?.nickname ?: thread.lastOrNull { !it.mine }?.senderName ?: peerId.take(8)
  val running = state.availability == TarangAvailability.RUNNING
  // Reading a message while the screen is open marks it read.
  LaunchedEffect(thread.size) { if (thread.isNotEmpty()) repository.setThreadOpen(peerId) }
  TarangChatScreen(
    title = name,
    subtitle = stringResource(if (peer?.encrypted == true) R.string.tarang_chat_private_sub else R.string.tarang_chat_connecting),
    avatarSeed = peerId,
    isRoom = false,
    messages = thread,
    canSend = running && peer != null,
    banner = if (running && peer == null) stringResource(R.string.tarang_chat_out_of_range, name) else null,
    emptyTitle = stringResource(R.string.tarang_chat_empty_title),
    emptyBody = stringResource(R.string.tarang_chat_empty_body, name),
    onSend = { repository.sendPrivate(peerId, it) },
    onRetry = { repository.retry(peerId, it) },
    onBack = onBack,
  )
}
