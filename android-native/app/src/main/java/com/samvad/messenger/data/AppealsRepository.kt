package com.samvad.messenger.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.samvad.core.privacy.Appeal
import com.samvad.core.privacy.AppealKind
import com.samvad.core.privacy.AppealStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed interface SubmitAppealResult {
  data object Submitted : SubmitAppealResult
  data object TooManyToday : SubmitAppealResult
  data object Invalid : SubmitAppealResult
  data object Failed : SubmitAppealResult
}

/** Phase 3 appeals (functions/src/appeals.ts). appeals/{id} is written only by the server; a person reads only their own. */
class AppealsRepository(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
  private val limitedUse = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()

  suspend fun submit(uid: String, kind: AppealKind, message: String): SubmitAppealResult = try {
    functions.getHttpsCallable("submitAppeal", limitedUse).call(mapOf("kind" to kind.wire, "message" to message)).await()
    SubmitAppealResult.Submitted
  } catch (e: FirebaseFunctionsException) {
    when (e.code) {
      FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> SubmitAppealResult.TooManyToday
      FirebaseFunctionsException.Code.INVALID_ARGUMENT -> SubmitAppealResult.Invalid
      else -> SubmitAppealResult.Failed
    }
  } catch (e: Exception) {
    SubmitAppealResult.Failed
  }

  fun myAppeals(uid: String): Flow<List<Appeal>> = callbackFlow {
    val reg = db.collection("appeals").whereEqualTo("uid", uid).orderBy("createdAt", Query.Direction.DESCENDING)
      .addSnapshotListener { snap, _ ->
        trySend(
          snap?.documents.orEmpty().mapNotNull { d ->
            val kindWire = d.getString("kind") ?: return@mapNotNull null
            val kind = AppealKind.entries.firstOrNull { it.wire == kindWire } ?: AppealKind.OTHER
            Appeal(
              id = d.id,
              kind = kind,
              message = d.getString("message") ?: "",
              status = AppealStatus.parse(d.getString("status")),
              createdAtMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            )
          },
        )
      }
    awaitClose { reg.remove() }
  }
}
