package com.samvad.messenger.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.community.Community
import com.samvad.core.community.LinkedGroup
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class CommunityException(message: String) : Exception(message) {
  class CommunityFull : CommunityException("community-full")
  class GroupsFull : CommunityException("community-groups-full")
  class GroupFull : CommunityException("group-full")
  class AdminLimit : CommunityException("admin-limit")
  class LastAdmin : CommunityException("last-admin")
  class AlreadyLinked : CommunityException("already-linked")
  class InvalidCode : CommunityException("invalid-code")
  class NotAllowed : CommunityException("not-allowed")
  class NotFound : CommunityException("not-found")
  class LimitReached : CommunityException("limit")
  class Failed(cause: Throwable) : CommunityException("failed") {
    init { initCause(cause) }
  }
}

data class CommunityPreview(val name: String, val description: String, val memberCount: Int, val groupCount: Int)

/**
 * Communities (Phase 6). Reads are live Firestore listeners (the rules let members read their
 * communities); every change goes through a callable, because the rules never let clients write them.
 */
class CommunityRepository(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  /** The communities I belong to. Order is decided by the caller (core CommunityRules.sortCommunities). */
  fun mine(me: String): Flow<Snapshot<List<Community>>> = callbackFlow {
    val reg = db.collection("communities")
      .whereArrayContains("members", me)
      .limit(100)
      .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
        if (err != null) {
          close(err)
          return@addSnapshotListener
        }
        if (snap != null) trySend(Snapshot(snap.documents.mapNotNull(::toCommunity), snap.metadata.isFromCache))
      }
    awaitClose { reg.remove() }
  }

  /** One community, live; null once it is gone or I am no longer a member. */
  fun community(id: String): Flow<Community?> = callbackFlow {
    val reg = db.document("communities/$id").addSnapshotListener { snap, err ->
      if (err != null) {
        trySend(null)
        return@addSnapshotListener
      }
      trySend(snap?.takeIf { it.exists() }?.let(::toCommunity))
    }
    awaitClose { reg.remove() }
  }

  suspend fun create(name: String, description: String, groups: List<String>): Pair<String, String> {
    val data = call("createCommunity", mapOf("name" to name, "description" to description, "groups" to groups))
    return (data["id"] as String) to (data["announcementsCid"] as String)
  }

  suspend fun updateInfo(id: String, name: String? = null, description: String? = null) {
    call("updateCommunityInfo", buildMap { put("id", id); name?.let { put("name", it) }; description?.let { put("description", it) } })
  }

  suspend fun setPhoto(id: String, jpeg: ByteArray?) {
    val encoded: Any? = jpeg?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    call("setCommunityPhoto", mapOf("id" to id, "jpegBase64" to encoded))
  }

  suspend fun linkGroup(id: String, cid: String) { call("linkCommunityGroup", mapOf("id" to id, "cid" to cid)) }

  suspend fun unlinkGroup(id: String, cid: String) { call("unlinkCommunityGroup", mapOf("id" to id, "cid" to cid)) }

  suspend fun setAdmin(id: String, uid: String, admin: Boolean) {
    call("setCommunityAdmin", mapOf("id" to id, "uid" to uid, "admin" to admin))
  }

  suspend fun removeMember(id: String, uid: String) { call("removeCommunityMember", mapOf("id" to id, "uid" to uid)) }

  suspend fun addMembers(id: String, uids: List<String>): List<String> =
    (call("addCommunityMembers", mapOf("id" to id, "uids" to uids))["added"] as? List<*>)?.filterIsInstance<String>().orEmpty()

  suspend fun leave(id: String) { call("leaveCommunity", mapOf("id" to id)) }

  suspend fun delete(id: String) { call("deleteCommunity", mapOf("id" to id)) }

  /** The current invite code; [regenerate] replaces it so the old one stops working. */
  suspend fun invite(id: String, regenerate: Boolean = false): String =
    call("createCommunityInvite", mapOf("id" to id, "regenerate" to regenerate))["code"] as String

  suspend fun revokeInvite(id: String) { call("revokeCommunityInvite", mapOf("id" to id)) }

  suspend fun preview(code: String): CommunityPreview {
    val d = call("previewCommunityInvite", mapOf("code" to code))
    return CommunityPreview(
      d["name"] as? String ?: "",
      d["description"] as? String ?: "",
      (d["memberCount"] as? Number)?.toInt() ?: 0,
      (d["groupCount"] as? Number)?.toInt() ?: 0,
    )
  }

  suspend fun join(code: String): String = call("joinCommunityWithCode", mapOf("code" to code))["id"] as String

  /** A community member joins one of its linked groups; returns the group's conversation ID. */
  suspend fun joinGroup(id: String, cid: String): String = call("joinCommunityGroup", mapOf("id" to id, "cid" to cid))["cid"] as String

  private fun toCommunity(d: DocumentSnapshot): Community? {
    val ann = d.getString("announcementsCid") ?: return null
    fun strings(field: String) = (d.get(field) as? List<*>)?.filterIsInstance<String>().orEmpty()
    val groups = (d.get("groups") as? List<*>).orEmpty().mapNotNull { raw ->
      val m = raw as? Map<*, *> ?: return@mapNotNull null
      LinkedGroup(m["cid"] as? String ?: return@mapNotNull null, m["name"] as? String ?: "", m["linkedBy"] as? String ?: "")
    }
    return Community(
      id = d.id,
      name = d.getString("name").orEmpty(),
      description = d.getString("description").orEmpty(),
      photoPath = d.getString("photoUrl"),
      members = strings("members"),
      admins = strings("admins"),
      announcementsCid = ann,
      groups = groups,
      createdBy = d.getString("createdBy").orEmpty(),
    )
  }

  private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
    try {
      @Suppress("UNCHECKED_CAST")
      return (functions.getHttpsCallable(name, limitedUse).call(data).await().data as? Map<String, Any?>).orEmpty()
    } catch (e: FirebaseFunctionsException) {
      throw when {
        e.message == "community-full" -> CommunityException.CommunityFull()
        e.message == "community-groups-full" -> CommunityException.GroupsFull()
        e.message == "group-full" -> CommunityException.GroupFull()
        e.message == "admin-limit" -> CommunityException.AdminLimit()
        e.message == "last-admin" -> CommunityException.LastAdmin()
        e.message == "already-linked" -> CommunityException.AlreadyLinked()
        e.message == "invalid-code" -> CommunityException.InvalidCode()
        e.code == FirebaseFunctionsException.Code.NOT_FOUND -> CommunityException.NotFound()
        e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED -> CommunityException.NotAllowed()
        e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> CommunityException.LimitReached()
        else -> CommunityException.Failed(e)
      }
    } catch (e: Exception) {
      throw CommunityException.Failed(e)
    }
  }
}
