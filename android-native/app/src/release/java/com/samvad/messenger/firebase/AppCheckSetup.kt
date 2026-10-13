package com.samvad.messenger.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds attest with Play Integrity. */
fun installAppCheck(app: FirebaseApp) {
  FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}
