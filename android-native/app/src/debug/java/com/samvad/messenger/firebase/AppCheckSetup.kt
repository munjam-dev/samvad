package com.samvad.messenger.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds use the App Check debug provider; register the logged token in the console. */
fun installAppCheck(app: FirebaseApp) {
  FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
