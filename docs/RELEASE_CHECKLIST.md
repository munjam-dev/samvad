# Release checklist (owner)

Do the steps in order. Nothing here has been done for you: the repository contains no keystore, password or secret,
and release builds are unsigned until you supply signing values.

## 0. Gate: the libsignal (AGPL-3.0) question

**libsignal must not ship until the licence question is answered in writing.** `docs/security/E2EE_DECISION.md`
records that the owner is "not sure" whether the Android source can be published under AGPL-3.0 and that a lawyer
must confirm. Until then no build that goes to the Play Store, a tester group, or any other person may contain it.

Today the app does **not call libsignal anywhere**: the only reference in app code is the Gradle dependency, so it
adds weight and a licence obligation and no feature. To make a release that excludes it:

1. In `android-native/app/build.gradle.kts` delete the line `implementation(libs.libsignal.android)`
   (under the comment "Signal Protocol, AGPL-3.0").
2. Nothing else needs to change: `:core` uses `libs.libsignal.client` only as `compileOnly` and for tests
   (`SignalSpikeTest`), which never ship in the app. If you also want the repository itself free of AGPL code before
   it is shared, delete `core/src/test/kotlin/com/samvad/core/SignalSpikeTest.kt` and the two `libsignal` lines in
   `core/build.gradle.kts`.
3. In `android-native/app/proguard-rules.pro` the `org.signal.libsignal` keep rule is harmless when the library is
   absent; remove it for tidiness.
4. Build `:app:assembleRelease` and check the APK has no `libsignal_jni.so`
   (`unzip -l app-release-unsigned.apk | grep -i signal` must print nothing).
5. Keep the in-app and website wording that messages are **not** end-to-end encrypted. Do not add E2EE claims.

When the lawyer confirms, re-add the dependency, publish the app source under AGPL-3.0 as advised, and only then
build the encrypted-message features listed in the decision record.

## 1. Signing key (Play App Signing)

1. In Play Console create the app (package `com.samvad.messenger`) and enrol in **Play App Signing** (default for new
   apps; Google keeps the app signing key).
2. Create an **upload key** on your own machine, never in the repository:
   `keytool -genkeypair -v -keystore samvad-upload.jks -alias samvad-upload -keyalg RSA -keysize 4096 -validity 10000`
3. Store `samvad-upload.jks` and its passwords in a password manager and an offline backup. Losing the upload key is
   recoverable through Play support; leaking it is serious, so never email or commit it.
4. Give Gradle the values through environment variables or `~/.gradle/gradle.properties` (not the project file):
   `SAMVAD_RELEASE_STORE_FILE`, `SAMVAD_RELEASE_STORE_PASSWORD`, `SAMVAD_RELEASE_KEY_ALIAS`,
   `SAMVAD_RELEASE_KEY_PASSWORD`. When all four are set, `assembleRelease` and `bundleRelease` are signed; when any is
   missing the output is unsigned (this is how CI builds).
5. Register the SHA-1 and SHA-256 of the **Play app signing key** (Play Console, App integrity) and of the upload key
   in the Firebase console (Project settings, Android app), then download a fresh `google-services.json`.
   Phone sign-in and Play Integrity depend on these fingerprints.

## 2. Versioning

1. Edit `versionCode` (integer, must increase on every upload) and `versionName` in `android-native/app/build.gradle.kts`.
   The code is currently 1 / `0.1.0`.
2. Tag the commit (`git tag v0.1.0`) so the Play build can be traced to source.
3. Keep the R8 `mapping.txt` of every uploaded build (`app/build/outputs/mapping/release/`); upload it to Play Console
   (the bundle upload includes it automatically for `bundleRelease`) so crash reports can be read.

## 3. Build

```
cd android-native
./gradlew :core:test :app:lintRelease :app:bundleRelease
```

Upload `app/build/outputs/bundle/release/app-release.aab`. Play builds a per-device APK from it for each ABI.
For a side-load build per ABI use `./gradlew :app:assembleRelease -Psamvad.abiSplits=true` (arm64-v8a and armeabi-v7a).

## 4. App Check: production provider (Play Integrity)

1. Firebase console, App Check, **Apps**, register the release app (`com.samvad.messenger`) with **Play Integrity**.
   Link the Google Cloud project that Play Console uses (Play Console, App integrity, Play Integrity API).
2. Enable the **Play Integrity API** in the Google Cloud project; confirm the SHA-256 of the Play app signing key is
   registered (step 1.5).
3. Release builds already install `PlayIntegrityAppCheckProviderFactory` (`app/src/release/.../AppCheckSetup.kt`).
   Test on a real device from an internal-testing install (a side-loaded APK fails Play Integrity by design).
4. Only after the internal-testing build works, turn **enforcement on** for Firestore, Realtime Database, Storage and
   Cloud Functions, one product at a time, watching the App Check metrics for rejected requests.
5. Keep a short App Check token TTL appropriate to your quota (the default is fine).

## 5. Remove debug tokens and debug-only access

1. Firebase console, App Check, **Apps**, Samvad (debug), Manage debug tokens: **delete every debug token** you added
   during development (`docs/OWNER_SETUP.md`). Do this before enforcement goes live for the public.
2. Confirm release has no debug provider: `firebase-appcheck-debug` is `debugImplementation` only, and the release
   `AppCheckSetup.kt` uses Play Integrity. `src/debug/res/xml/network_security_config.xml` (cleartext for emulators)
   exists only in the debug source set.
3. Make sure no emulator flag is on: release forces `USE_EMULATORS = false`.
4. Remove or rotate any test phone numbers in Firebase Authentication, Sign-in method, Phone.
5. Confirm `google-services.json` is the production project's and is not committed.

## 6. Play Console declarations

- **Data safety:** fill the form from `docs/PLAY_DATA_SAFETY.md` (answers and the reasoning for each data type).
- **Privacy policy URL** and **Terms**: the hosted pages (`firebase/hosting`) must be live at the URLs in `BuildConfig`
  (`PRIVACY_URL`, `TERMS_URL`) before submission. The policy must name LiveKit as the call-media processor
  (`docs/CALLING_SETUP.md`, data safety section).
- **Calls:** complete the LiveKit Cloud project and secrets per `docs/CALLING_SETUP.md` and test two real devices.
  Declare the foreground-service types and permissions the app uses (microphone, camera, full-screen intent for
  incoming calls, notifications) in the **Permissions declaration** and **Foreground service** forms, with a short
  video of an incoming call if Play asks.
- **App access:** Play reviewers need to sign in with a phone number. Provide a test number with a fixed code
  (Firebase Authentication test number) in the review instructions, then delete it afterwards.
- **Content rating** questionnaire (user-generated content, chat), **Target audience** (not for children),
  **User-generated content policy** (report, block and appeal are in the app: say so), **Ads:** none.
- **Government/financial/health apps:** not applicable.
- **Account deletion:** the app has in-app deletion (`deleteAccount`); give the Play Console the deletion URL
  (a hosted page describing the steps) as the Data deletion section requires.

## 7. Store listing assets

- App name, short description (80 characters), full description (4,000), in English, Hindi, Telugu and Tamil.
- App icon 512x512 PNG; feature graphic 1024x500.
- Phone screenshots (at least 2, 16:9 or 9:16, 320 to 3840 px), ideally 4 to 8 per language; add 7-inch and 10-inch
  tablet screenshots if you want the listing to show as tablet-ready.
- Privacy policy URL, support email, website.
- Contact details and a category (Communication).
- Release notes for the first release in each language.

## 8. Backend before launch

1. Deploy rules, indexes and functions from a clean checkout: `firebase deploy --only firestore,database,storage,functions`
   (done by you, not by CI).
2. Set the LiveKit secrets (`docs/CALLING_SETUP.md`).
3. Create the budget alerts and monitoring policies in `docs/COST_AUDIT.md`, section 6.
4. Turn on Firestore TTL policies listed in `firestore.indexes.json` and check they show "Serving".

## 9. What to test on real devices before each release

Use the internal-testing track install (not a side-load) on at least: a low-end Android 8 or 9 phone (minSdk is 26),
a mid-range Android 13 to 15 phone, and one tablet or foldable.

- **Release-build smoke (R8 / shrinking):** cold start, phone sign-in, profile setup, send and receive a text, an image,
  a voice note, a video and a document; open Vault (biometric prompt); start a 1:1 voice call and a video call, join a
  meeting; receive a push with the app closed and open the chat from it; change language to Hindi, Telugu and Tamil.
  These paths use reflection or native code (Room, Firebase, LiveKit/WebRTC/JNA, Play Integrity) that R8 could break.
- **Both ABIs:** run at least once on a 32-bit ARM device (armeabi-v7a) and a 64-bit device (arm64-v8a).
- **Calls:** two devices on different networks; incoming call on a locked screen; Bluetooth headset route; end call
  from the notification; call while another call is active; poor network.
- **Permissions:** deny and re-grant camera, microphone, notifications, contacts, and media access; check each screen's
  fallback copy.
- **Accessibility:** TalkBack walk-through and 200% font scale on the screens listed in `docs/A11Y_AUDIT.md`.
- **Process death and offline:** background the app for an hour; kill it mid-upload; airplane mode while sending.
- **Instrumented tests:** `./gradlew :app:connectedDebugAndroidTest` on an emulator (the smoke tests in
  `app/src/androidTest` compile in CI but are not yet run there).
- **App Check:** confirm that Firestore and Functions calls succeed from the Play-installed build with enforcement on,
  and fail from a rooted or modified build.
- **Battery and size:** install size on device (see the sizes in the release notes of this branch), and a day of
  standby battery drain.
