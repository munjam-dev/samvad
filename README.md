# Samvad Messenger · संवाद

A calm, private, phone-number messenger for Android, built on Firebase.

| Part | Path | Docs |
|---|---|---|
| Android app (Kotlin, Jetpack Compose) | [`android-native/`](android-native/) | [`android-native/README.md`](android-native/README.md) |
| Backend (Firebase rules, Cloud Functions, Emulator Suite tests) | [`firebase/`](firebase/) | [`firebase/README.md`](firebase/README.md) |
| Architecture, specs, status | [`docs/`](docs/) | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) |

## Quick start

**Building and running the app on your own machine:** follow [`docs/LOCAL_BUILD.md`](docs/LOCAL_BUILD.md)
(local Firebase emulators, Android emulator or USB phone, two-person testing, troubleshooting).

```bash
# Backend tests on the Firebase Emulator Suite (JDK 21, Node 24)
cd firebase && npm install && npm --prefix functions install && npm test

# Android: JVM tests anywhere; the app needs the Android SDK
cd android-native && ./gradlew :core:test
./gradlew :app:installDebug -Psamvad.useEmulators=true   # with `npm run emulators` running in firebase/
```

CI (`.github/workflows/samvad-ci.yml`) runs the backend suite, the log-hygiene check, the `:core` tests and the `:app` build, unit tests and lint, and uploads a debug APK.

## Principles

- Firebase-first; security enforced by rules and trusted functions, never by UI alone.
- Phone numbers are visible only to their owner; no secrets or service-account keys in the repo or app.
- End-to-end encryption is claimed only where it is implemented: one-to-one chats between two people who both have keys. Groups, older chats and calls are not end-to-end encrypted, and the app says so.
- No fake features: unbuilt capabilities are shown as "on the way", never as working.

## Building

Full steps (local Firebase emulators, Android emulator or phone, two-person testing, troubleshooting) are in
[`docs/LOCAL_BUILD.md`](docs/LOCAL_BUILD.md). In short:

- Android app: JDK 17 or newer and the Android SDK; `cd android-native && ./gradlew :app:assembleDebug`.
  Unit tests: `./gradlew :core:test :app:testDebugUnitTest`. Lint: `./gradlew :app:lintDebug`.
- Release builds are not signed and have no `google-services.json` in the repository. End-to-end encryption is on in debug
  builds and off in release unless you pass `-Psamvad.e2ee=true`.
- Backend: `cd firebase && npm install && npm --prefix functions install && npm test` (JDK 21, Node 24).
- Website: `cd website && npm install && npm run build`; check links with `node scripts/check-links.mjs`.

## License

The Android app source is licensed under the **GNU Affero General Public License, version 3** (AGPL-3.0). See
[`LICENSE`](LICENSE). The app uses the Signal Protocol library libsignal (AGPL-3.0), so the app as a whole is published under
the same licence. The server code in `firebase/` is in the same repository and contains no secrets. Third-party
software and its licences are listed in [`NOTICE`](NOTICE). If you run a modified version of the app or server for other
people, the AGPL requires you to offer them your modified source.

Source: https://github.com/munjam-dev/samvad
