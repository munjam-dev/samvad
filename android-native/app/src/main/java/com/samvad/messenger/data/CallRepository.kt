package com.samvad.messenger.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.call.CallKind
import com.samvad.core.call.CallRecord
import com.samvad.core.call.CallStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Why a call or meeting request failed, in terms the UI can explain. */
sealed class CallException(message: String) : Exception(message) {
  /** The backend has no LiveKit project yet ("Calling isn't set up yet"). */
  class NotConfigured : CallException("calls-not-configured")
  class CalleeBusy : CallException("callee-busy")
  class AlreadyInCall : CallException("already-in-call")
  class NotAllowed : CallException("not-allowed")
  class Ended : CallException("call-ended")
  class MeetingNotOpen : CallException("meeting-not-open")
  class MeetingEnded : CallException("meeting-ended")
  class MeetingFull : CallException("meeting-full")
  class LimitReached : CallException("limit")
  class Failed(cause: Throwable) : CallException("failed") { init { initCause(cause) } }
}

data class StartedCall(val callId: String, val roomName: String)

/** Everything needed to join a LiveKit room. The token is valid for 10 minutes. */
data class JoinInfo(val url: String, val token: String, val roomName: String)

data class MeetingInfo(
  val id: String,
  val groupId: String,
  val hostUid: String,
  val title: String,
  val kind: CallKind,
  val startsAtMs: Long,
  val ended: Boolean,
)

/**
 * Calls and group meetings (Phase 7). Ringing, answering and token minting all go through callables;
 * the rules let clients only read their own calls and their groups' meetings.
 */
class CallRepository(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun start(conversationId: String, kind: CallKind): StartedCall {
    val r = call("startCall", mapOf("conversationId" to conversationId, "kind" to kind.wire))
    return StartedCall(r["callId"] as String, r["roomName"] as? String ?: "")
  }

  suspend fun answer(callId: String) { call("answerCall", mapOf("callId" to callId)) }
  suspend fun decline(callId: String) { call("declineCall", mapOf("callId" to callId)) }
  suspend fun end(callId: String) { call("endCall", mapOf("callId" to callId)) }
  suspend fun token(callId: String): JoinInfo = join(call("getCallToken", mapOf("callId" to callId)))

  suspend fun scheduleMeeting(groupId: String, title: String, startsAtMs: Long, kind: CallKind): String =
    call("scheduleMeeting", mapOf("groupId" to groupId, "title" to title, "startsAt" to startsAtMs, "kind" to kind.wire))["meetingId"] as String

  suspend fun meetingToken(meetingId: String): JoinInfo = join(call("getMeetingToken", mapOf("meetingId" to meetingId)))
  suspend fun endMeeting(meetingId: String) { call("endMeeting", mapOf("meetingId" to meetingId)) }

  /** My most recent calls, newest first. */
  fun history(me: String): Flow<List<CallRecord>> = callbackFlow {
    val reg = db.collection("calls")
      .whereArrayContains("participants", me)
      .orderBy("createdAt", Query.Direction.DESCENDING)
      .limit(100)
      .addSnapshotListener { snap, err ->
        if (err != null) { close(err); return@addSnapshotListener }
        if (snap != null) trySend(snap.documents.mapNotNull(::toRecord))
      }
    awaitClose { reg.remove() }
  }

  fun call(callId: String): Flow<CallRecord?> = callbackFlow {
    val reg = db.document("calls/$callId").addSnapshotListener { snap, err ->
      if (err != null) { close(err); return@addSnapshotListener }
      trySend(snap?.let(::toRecord))
    }
    awaitClose { reg.remove() }
  }

  fun meeting(meetingId: String): Flow<MeetingInfo?> = callbackFlow {
    val reg = db.document("meetings/$meetingId").addSnapshotListener { snap, err ->
      if (err != null) { close(err); return@addSnapshotListener }
      trySend(snap?.let(::toMeeting))
    }
    awaitClose { reg.remove() }
  }

  private fun DocumentSnapshot.millis(field: String): Long? = (get(field) as? Timestamp)?.toDate()?.time

  private fun toRecord(d: DocumentSnapshot): CallRecord? {
    if (!d.exists()) return null
    val created = d.millis("createdAt") ?: return null
    return CallRecord(
      id = d.id,
      callerUid = d.getString("callerUid") ?: return null,
      participants = (d.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty(),
      kind = CallKind.parse(d.getString("kind")),
      status = CallStatus.parse(d.getString("status")),
      createdAtMs = created,
      answeredAtMs = d.millis("answeredAt"),
      endedAtMs = d.millis("endedAt"),
      expiresAtMs = d.millis("expiresAt") ?: created,
    )
  }

  private fun toMeeting(d: DocumentSnapshot): MeetingInfo? {
    if (!d.exists()) return null
    return MeetingInfo(
      id = d.id,
      groupId = d.getString("groupId") ?: return null,
      hostUid = d.getString("hostUid") ?: "",
      title = d.getString("title").orEmpty(),
      kind = CallKind.parse(d.getString("kind")),
      startsAtMs = d.millis("startsAt") ?: return null,
      ended = d.getString("status") == "ended",
    )
  }

  private fun join(r: Map<String, Any?>) = JoinInfo(r["url"] as String, r["token"] as String, r["roomName"] as? String ?: "")

  private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
    try {
      @Suppress("UNCHECKED_CAST")
      return (functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<String, Any?>).orEmpty()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.message == "calls-not-configured" -> CallException.NotConfigured()
        e.message == "callee-busy" -> CallException.CalleeBusy()
        e.message == "already-in-call" -> CallException.AlreadyInCall()
        e.message == "call-ended" -> CallException.Ended()
        e.message == "meeting-not-open" -> CallException.MeetingNotOpen()
        e.message == "meeting-ended" -> CallException.MeetingEnded()
        e.message == "meeting-full" -> CallException.MeetingFull()
        e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED || e.code == FirebaseFunctionsException.Code.NOT_FOUND -> CallException.NotAllowed()
        e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> CallException.LimitReached()
        else -> CallException.Failed(e)
      }
    } catch (e: Exception) {
      throw CallException.Failed(e)
    }
  }
}

val CallKind.wire: String get() = if (this == CallKind.VIDEO) "video" else "audio"
