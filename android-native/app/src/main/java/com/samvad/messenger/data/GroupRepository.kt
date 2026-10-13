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
  class Failed(cause: Throwable) : GroupException("failed") {
    init { initCause(cause) }
  }
}

data class InvitePreview(val name: String, val memberCount: Int)

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
    return InvitePreview(data["name"] as? String ?: "", (data["memberCount"] as? Number)?.toInt() ?: 0)
  }

  suspend fun join(code: String): String = call("joinGroupWithCode", mapOf("code" to code))["cid"] as String

  private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
    try {
      @Suppress("UNCHECKED_CAST")
      return (functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<String, Any?>).orEmpty()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.message == "group-full" -> GroupException.Full()
        e.message == "no-eligible-members" -> GroupException.NoEligibleMembers()
        e.message == "invalid-code" || e.code == FirebaseFunctionsException.Code.NOT_FOUND -> GroupException.InvalidCode()
        e.message == "last-admin" -> GroupException.LastAdmin()
        e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED -> GroupException.NotAllowed()
        e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> GroupException.LimitReached()
        else -> GroupException.Failed(e)
      }
    } catch (e: Exception) {
      throw GroupException.Failed(e)
    }
  }
}
