package com.samvad.messenger.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.safety.ReportReason
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed class ReportException(message: String) : Exception(message) {
  class LimitReached : ReportException("limit")
  class Failed(cause: Throwable) : ReportException("failed") {
    init { initCause(cause) }
  }
}

/** One message the person chose to attach to a report from an end-to-end encrypted chat. */
data class ReportEvidence(val messageId: String, val kind: String, val text: String)

/**
 * Block list and reports (Phase 1.3). Blocking is enforced by Firestore and RTDB rules; this class
 * only writes the owner's own list. Reports go through the reportContact callable.
 */
class SafetyRepository(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  /** UIDs [me] has blocked. Local writes show up at once (Firestore latency compensation). */
  fun blocked(me: String): Flow<Set<String>> = callbackFlow {
    val reg = db.collection("users/$me/blocked").addSnapshotListener { snap, err ->
      if (err != null) return@addSnapshotListener
      if (snap != null) trySend(snap.documents.map { it.id }.toSet())
    }
    awaitClose { reg.remove() }
  }

  suspend fun block(me: String, other: String) {
    db.document("users/$me/blocked/$other").set(mapOf("createdAt" to FieldValue.serverTimestamp())).await()
  }

  suspend fun unblock(me: String, other: String) {
    db.document("users/$me/blocked/$other").delete().await()
  }

  /**
   * Reports the other member of [conversationId]. Reuse [requestId] when retrying the same report:
   * the server stores it once.
   */
  suspend fun report(
    requestId: String,
    conversationId: String,
    reason: ReportReason,
    alsoBlock: Boolean,
    /** Encrypted chats only: messages this phone decrypted, attached after the person agreed. Empty otherwise. */
    evidence: List<ReportEvidence> = emptyList(),
  ) {
    try {
      functions.getHttpsCallable("reportContact", limitedUse).call(
        buildMap<String, Any> {
          put("requestId", requestId)
          put("conversationId", conversationId)
          put("reason", reason.wire)
          put("block", alsoBlock)
          if (evidence.isNotEmpty()) {
            put("evidenceConsent", true)
            put("evidence", evidence.map { mapOf("messageId" to it.messageId, "kind" to it.kind, "text" to it.text) })
          }
        },
      ).await()
    } catch (e: FirebaseFunctionsException) {
      throw if (e.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) ReportException.LimitReached() else ReportException.Failed(e)
    } catch (e: Exception) {
      throw ReportException.Failed(e)
    }
  }
}
