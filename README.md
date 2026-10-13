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
- No end-to-end encryption is claimed until one is implemented and verified.
- No fake features: unbuilt capabilities are shown as "on the way", never as working.
