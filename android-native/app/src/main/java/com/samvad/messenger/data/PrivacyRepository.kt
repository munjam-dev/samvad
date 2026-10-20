package com.samvad.messenger.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.samvad.core.privacy.PrivacySettings
import com.samvad.core.privacy.Visibility
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Phase 3 privacy settings: who sees "About", the profile photo and group-add permission
 * (users/{uid}/settings/privacy in Firestore), who can find the account by phone number, and
 * who sees "last seen" (privacy/{uid} in the Realtime Database, next to the older hideLastSeen
 * switch it replaces). The server (rules and callables) enforces all of this; this repository only
 * reads and writes the settings.
 */
class PrivacyRepository(private val db: FirebaseFirestore, private val rtdb: FirebaseDatabase) {
  private fun settingsRef(uid: String) = db.document("users/$uid/settings/privacy")

  private fun firestorePart(uid: String): Flow<PrivacySettings> = callbackFlow {
    val reg = settingsRef(uid).addSnapshotListener { snap, _ ->
      val d = snap?.takeIf { it.exists() }
      trySend(
        PrivacySettings(
          aboutVisibility = Visibility.parse(d?.getString("aboutVisibility")),
          photoVisibility = Visibility.parse(d?.getString("photoVisibility")),
          groupAdds = Visibility.parse(d?.getString("groupAdds")),
          phoneDiscovery = d?.getBoolean("phoneDiscovery") ?: true,
        ),
      )
    }
    awaitClose { reg.remove() }
  }

  /** The RTDB side: the tri-state setting, with the legacy boolean switch still honoured. */
  private fun lastSeenPart(uid: String): Flow<Visibility> = valueFlow("privacy/$uid") { s ->
    val hideLegacy = s.child("hideLastSeen").getValue(Boolean::class.java) == true
    if (hideLegacy) Visibility.NOBODY else Visibility.parse(s.child("lastSeen").getValue(String::class.java))
  }.map { it ?: Visibility.EVERYONE }

  /** The signed-in person's own settings, live. Unset parts default to Everyone / on, the open defaults. */
  fun settings(uid: String): Flow<PrivacySettings> =
    combine(firestorePart(uid), lastSeenPart(uid)) { base, lastSeen -> base.copy(lastSeen = lastSeen) }

  suspend fun setAboutVisibility(uid: String, value: Visibility) = patch(uid, "aboutVisibility", value.wire)
  suspend fun setPhotoVisibility(uid: String, value: Visibility) = patch(uid, "photoVisibility", value.wire)
  suspend fun setGroupAdds(uid: String, value: Visibility) = patch(uid, "groupAdds", value.wire)
  suspend fun setPhoneDiscovery(uid: String, enabled: Boolean) = patch(uid, "phoneDiscovery", enabled)

  private suspend fun patch(uid: String, field: String, value: Any) {
    settingsRef(uid).set(mapOf(field to value, "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge()).await()
  }

  /** Also clears the legacy switch, so the two controls never disagree. */
  suspend fun setLastSeenVisibility(uid: String, value: Visibility) {
    rtdb.getReference("privacy/$uid/lastSeen").setValue(value.wire).await()
    rtdb.getReference("privacy/$uid/hideLastSeen").setValue(if (value == Visibility.NOBODY) true else null).await()
  }

  private fun <T> valueFlow(path: String, map: (DataSnapshot) -> T?): Flow<T?> = callbackFlow {
    val ref = rtdb.getReference(path)
    val listener = object : ValueEventListener {
      override fun onDataChange(snapshot: DataSnapshot) {
        trySend(if (snapshot.exists()) map(snapshot) else null)
      }
      override fun onCancelled(error: DatabaseError) {
        trySend(null)
      }
    }
    ref.addValueEventListener(listener)
    awaitClose { ref.removeEventListener(listener) }
  }
}
