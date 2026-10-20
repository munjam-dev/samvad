package com.samvad.messenger.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import kotlinx.coroutines.tasks.await

sealed class GroupException(message: String) : Exception(message) {
  class Full : GroupException("group-full")
  class NoEligibleMembers : GroupException("no-eligible-members")
  class InvalidCode : GroupException("invalid-code")
  class LastAdmin : GroupException("last-admin")
  class NotAllowed : GroupException("not-allowed")
  class LimitReached : GroupException("limit")
  /** Pinned messages: three are already pinned. */
  class PinLimit : GroupException("pin-limit")
  class EventInPast : GroupException("event-in-past")
  class EventTooFar : GroupException("event-too-far")
  class EventGone : GroupException("event-gone")
  class TooManyEvents : GroupException("too-many-events")
  class TooManyRequests : GroupException("too-many-requests")
  class NoRequest : GroupException("no-request")
  class Failed(cause: Throwable) : GroupException("failed") {
    init { initCause(cause) }
  }
}

data class InvitePreview(val name: String, val memberCount: Int, val approval: Boolean = false, val pending: Boolean = false)

/** The server's answer to joining when a code requires approval, or to deciding a request. */
enum class JoinOutcome { JOINED, PENDING, APPROVED, DECLINED, UNAVAILABLE }

/**
 * Group management (Phase 2). Every change goes through a callable: the rules never let clients write
 * a group's member list, admins, name or photo.
 */
class GroupRepository(private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun create(name: String, members: List<String>): String =
    call("createGroup", mapOf("name" to name, "members" to members))["cid"] as String

  suspend fun addMembers(cid: String, uids: List<String>): List<String> =
    (call("addGroupMembers", mapOf("cid" to cid, "uids" to uids))["added"] as? List<*>)?.filterIsInstance<String>().orEmpty()

  suspend fun removeMember(cid: String, uid: String) { call("removeGroupMember", mapOf("cid" to cid, "uid" to uid)) }

  suspend fun leave(cid: String) { call("leaveGroup", mapOf("cid" to cid)) }

  suspend fun setAdmin(cid: String, uid: String, admin: Boolean) {
    call("setGroupAdmin", mapOf("cid" to cid, "uid" to uid, "admin" to admin))
  }

  suspend fun updateInfo(cid: String, name: String? = null, description: String? = null) {
    call("updateGroupInfo", buildMap { put("cid", cid); name?.let { put("name", it) }; description?.let { put("description", it) } })
  }

  suspend fun setPhoto(cid: String, jpeg: ByteArray?) {
    val encoded: Any? = jpeg?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    call("setGroupPhoto", mapOf("cid" to cid, "jpegBase64" to encoded))
  }

  suspend fun createInvite(cid: String): String = call("createGroupInvite", mapOf("cid" to cid))["code"] as String

  suspend fun revokeInvite(cid: String) { call("revokeGroupInvite", mapOf("cid" to cid)) }

  suspend fun previewInvite(code: String): InvitePreview {
    val data = call("previewGroupInvite", mapOf("code" to code))
    return InvitePreview(
      data["name"] as? String ?: "",
      (data["memberCount"] as? Number)?.toInt() ?: 0,
      data["approval"] == true,
      data["pending"] == true,
    )
  }

  /** Phase 6: announcement mode, member approval and who may create events (admins only; the server checks). */
  suspend fun setSettings(
    cid: String,
    onlyAdminsSend: Boolean? = null,
    approveMembers: Boolean? = null,
    eventsByMembers: Boolean? = null,
  ) {
    call(
      "setGroupSettings",
      buildMap {
        put("cid", cid)
        onlyAdminsSend?.let { put("onlyAdminsSend", it) }
        approveMembers?.let { put("approveMembers", it) }
        eventsByMembers?.let { put("eventsByMembers", it) }
      },
    )
  }

  /** An admin approves or declines a pending join request. */
  suspend fun decideJoinRequest(cid: String, uid: String, approve: Boolean): JoinOutcome {
    val data = call("decideGroupJoinRequest", mapOf("cid" to cid, "uid" to uid, "approve" to approve))
    return when (data["status"] as? String) {
      "approved" -> JoinOutcome.APPROVED
      "unavailable" -> JoinOutcome.UNAVAILABLE
      else -> JoinOutcome.DECLINED
    }
  }

  /** Creates a group event; returns its id. The server checks admin rights / the group's setting. */
  suspend fun createEvent(cid: String, title: String, description: String, place: String, startsAtMs: Long): String =
    call(
      "createGroupEvent",
      mapOf("cid" to cid, "title" to title, "description" to description, "place" to place, "startsAt" to startsAtMs),
    )["eventId"] as String

  /** [answer] null withdraws my RSVP. */
  suspend fun rsvpEvent(cid: String, eventId: String, answer: com.samvad.core.chat.Rsvp?) {
    call("rsvpGroupEvent", mapOf("cid" to cid, "eid" to eventId, "answer" to answer?.wire))
  }

  suspend fun cancelEvent(cid: String, eventId: String) {
    call("cancelGroupEvent", mapOf("cid" to cid, "eid" to eventId))
  }

  /** Disappearing messages: 0 turns it off. Direct chats: either member; groups: admins only (the server decides). */
  suspend fun setDisappearingTimer(cid: String, seconds: Long) {
    call("setDisappearingTimer", mapOf("conversationId" to cid, "seconds" to seconds))
  }

  /** Pins or unpins one message. Direct chats: either person; groups: admins only (the server decides). */
  suspend fun setPinnedMessage(cid: String, messageId: String, pinned: Boolean) {
    call("setPinnedMessage", mapOf("conversationId" to cid, "messageId" to messageId, "pinned" to pinned))
  }

  suspend fun join(code: String): Pair<String, JoinOutcome> {
    val data = call("joinGroupWithCode", mapOf("code" to code))
    val cid = data["cid"] as String
    val outcome = if (data["status"] == "pending") JoinOutcome.PENDING else JoinOutcome.JOINED
    return cid to outcome
  }

  private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
    try {
      @Suppress("UNCHECKED_CAST")
      return (functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<String, Any?>).orEmpty()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.message == "group-full" -> GroupException.Full()
        e.message == "no-eligible-members" -> GroupException.NoEligibleMembers()
        e.message == "invalid-code" || e.code == FirebaseFunctionsException.Code.NOT_FOUND -> GroupException.InvalidCode()
        e.message == "pin-limit" -> GroupException.PinLimit()
        e.message == "last-admin" -> GroupException.LastAdmin()
        e.message == "event-in-past" -> GroupException.EventInPast()
        e.message == "event-too-far" -> GroupException.EventTooFar()
        e.message == "event-cancelled" || e.message == "event-over" -> GroupException.EventGone()
        e.message == "too-many-events" -> GroupException.TooManyEvents()
        e.message == "too-many-requests" -> GroupException.TooManyRequests()
        e.message == "no-request" -> GroupException.NoRequest()
        e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED -> GroupException.NotAllowed()
        e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> GroupException.LimitReached()
        else -> GroupException.Failed(e)
      }
    } catch (e: Exception) {
      throw GroupException.Failed(e)
    }
  }
}
