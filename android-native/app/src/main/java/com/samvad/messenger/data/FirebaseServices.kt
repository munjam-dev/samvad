package com.samvad.messenger.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.samvad.messenger.BuildConfig
import com.samvad.messenger.firebase.installAppCheck

/**
 * Firebase entry points. Debug builds built with -Psamvad.useEmulators=true talk to the Emulator
 * Suite (project demo-samvad) and need no google-services.json. Returns null services when the
 * build has no Firebase configuration at all, so the UI can show a setup screen instead of crashing.
 */
class FirebaseServices private constructor(
  val app: FirebaseApp,
  val auth: FirebaseAuth,
  val db: FirebaseFirestore,
  val rtdb: FirebaseDatabase,
  val functions: FirebaseFunctions,
  val storage: FirebaseStorage,
) {
  companion object {
    private const val DEMO_PROJECT = "demo-samvad"

    /**
     * Not a real key. firebase-functions pulls in firebase-iid, which rejects every callable unless
     * the key has the real shape ("A" + 38 of [A-Za-z0-9_-]) and a sender ID is set.
     */
    private const val DEMO_API_KEY = "A-demo-key-for-local-emulators-only-000"

    fun create(context: Context): FirebaseServices? {
      val existing = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
      val app: FirebaseApp = existing ?: if (BuildConfig.USE_EMULATORS) {
        FirebaseApp.initializeApp(
          context,
          FirebaseOptions.Builder()
            .setProjectId(DEMO_PROJECT)
            .setApplicationId("1:000000000000:android:0000000000000000")
            .setApiKey(DEMO_API_KEY)
            .setGcmSenderId("000000000000")
            .setDatabaseUrl("https://$DEMO_PROJECT-default-rtdb.firebaseio.com")
            .setStorageBucket("$DEMO_PROJECT.appspot.com")
            .build(),
        )
      } else {
        return null
      }

      val auth = FirebaseAuth.getInstance(app)
      val db = FirebaseFirestore.getInstance(app)
      val functions = FirebaseFunctions.getInstance(app, BuildConfig.FUNCTIONS_REGION)
      val rtdb = FirebaseDatabase.getInstance(app)
      val storage = FirebaseStorage.getInstance(app)

      if (BuildConfig.USE_EMULATORS) {
        val host = BuildConfig.EMULATOR_HOST
        auth.useEmulator(host, 9099)
        db.useEmulator(host, 8080)
        functions.useEmulator(host, 5001)
        rtdb.useEmulator(host, 9000)
        storage.useEmulator(host, 9199)
      } else {
        installAppCheck(app)
      }
      return FirebaseServices(app, auth, db, rtdb, functions, storage)
    }
  }
}
