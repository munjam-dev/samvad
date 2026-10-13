# Samvad Messenger (संवाद) — Stack and Architecture

Last updated: 2026-10-09 (revised: backend-first order)

This document describes two things and keeps them apart:

- **Part A — What exists in the repository today** (verified by inspection).
- **Part B — The target architecture** (planned; nothing in Part B is built yet, and nothing here claims it works).

Exact library versions are intentionally not listed. They are resolved together (AGP, Kotlin, Compose BOM, Hilt, Firebase BoM, and so on) when each implementation plan is written, and then recorded in `android-native/gradle/libs.versions.toml`.

Detailed designs live in `docs/superpowers/specs/`. The first one is `2026-10-09-foundation-auth-design.md`.

---

## Part A — What is in the repository today

### A.1 Overview

| Area | Technology | Location | Status |
|---|---|---|---|
| Mobile app | Flutter / Dart (about 3.8k lines) | `lib/`, `android/`, `ios/`, `web/`, `linux/`, `macos/`, `windows/` | Exists. Build status unverified. |
| App data layer | Supabase (`supabase_flutter`) | `lib/samvad_app.dart`, `lib/screens/chat_list_screen.dart`, `lib/screens/chat_screen.dart`, `supabase/` | Exists. Legacy. |
| Custom backend | Node.js, Express, Socket.io, MongoDB (Mongoose), JWT, bcrypt | `backend/` | Exists. Legacy. |
| Firebase | Only `firebase_core`, `firebase_messaging`, `flutter_local_notifications` in `pubspec.yaml` | none | No Firebase project config in the repo. |
| CI / build services | GitHub Actions, GitLab CI, Bitrise, Codemagic, AppCenter | `.github/workflows/build.yml`, `.gitlab-ci.yml`, `bitrise.yml`, `codemagic.yaml`, `appcenter.json` | Flutter-oriented. |
| Docs | Many ad-hoc APK build guides | repo root `*.md`, `*.bat` | Legacy. |
| New specs and runbooks | Markdown | `docs/` | Added during this work. |

### A.2 Flutter app

- **Screens** (`lib/screens/`): splash, login, chat list, chat, profile, settings.
- **Entry points:** `lib/main.dart`, `lib/main_simple.dart`, `lib/samvad_app.dart`, `lib/zingify_app.dart` (the app was renamed from Zingify to Samvad; the rename is unfinished in the working tree).
- **Notable packages:** `flutter_animate`, `google_fonts`, `shimmer`, `shared_preferences`, `flutter_secure_storage`, `http`, `permission_handler`, `flutter_contacts`, `image_picker`, `file_picker`, `emoji_picker_flutter`, `country_picker`, `supabase_flutter`, `firebase_core`, `firebase_messaging`, `flutter_local_notifications`, `device_info_plus`, `rxdart`, `uuid`.
- **Package IDs in use (inconsistent):** `com.example.samvad_messenger` (Gradle), `com.samvad.messenger` (staged `MainActivity.kt`), `com.example.zingify_mobile_new` (old).
- **Android build:** Gradle Kotlin DSL with the Flutter Gradle plugin; wrapper pinned to Gradle 8.14.

### A.3 Legacy backend (`backend/`)

- `server.js` (about 260 lines): Express + Socket.io + Mongoose + JWT.
- Deployment files: `Dockerfile`, `Procfile`, `heroku.yml`, `healthcheck.js`.
- Not part of the target architecture.

### A.4 Known problems in the current repo

- Credentials are tracked in git (details in `docs/security/incident-leaked-credentials.md`; no values are reproduced).
- 73 uncommitted working-tree changes from a half-finished rename. They are preserved and must not be reset or discarded.
- Only JDK 8 is installed and `JAVA_HOME` is unset, so a modern Android Gradle Plugin cannot run.
- No Firebase configuration, security rules or tests for any of the above.

---

## Part B — Target architecture (planned)

### B.1 Principles

1. **Firebase-first.** Firebase is the primary managed platform. No other primary database (no MongoDB, no Supabase).
2. **Clients talk to Firebase directly** for supported operations; security is enforced by rules, not by UI visibility.
3. **Trusted logic runs on trusted servers.** Cloud Functions for event-driven work; Kotlin + Ktor on Cloud Run only where a Function is not the right tool.
4. **Admin SDK and secrets never reach the app.** No service-account JSON in the app, repo, or build artifacts.
5. **Deny by default.** Firestore, Storage and Realtime Database rules start closed and open per feature, with ownership, membership and field-level checks.
6. **No unverified claims.** End-to-end encryption, call encryption and deployments are not claimed until built and tested.
7. **Build alongside, then replace.** New code lives in new directories next to the Flutter app; legacy code is removed in a separate commit after parity.
8. **Backend first.** The complete, tested backend (rules, Functions, Ktor) is built before the Android app. The Android phase starts only when the owner says so, and must satisfy the client contract in the B1 spec.

### B.2 System diagram

```
                    ┌──────────────────────────────────────────┐
                    │          Android app (Kotlin)            │
                    │  Compose UI → ViewModel → Repository     │
                    │        Room · DataStore · WorkManager    │
                    └───────┬───────────────┬───────────┬──────┘
                            │ Firebase SDKs │           │ HTTPS (only where needed)
        ┌───────────────────┼───────────────┼──┐        │
        ▼                   ▼               ▼  ▼        ▼
  Firebase Auth       Cloud Firestore   Realtime DB   Cloud Storage      Ktor on Cloud Run
  (phone OTP)         (primary data)    (presence,    (media)            (custom business API,
        │                   │            typing)          │               signaling, TURN creds)
        │                   │               │             │                     │
        └──────── App Check on every service ─────────────┘                     │
                            │                                                    │
                    Cloud Functions ◄────── Firestore / Auth triggers            │
                    (event-driven, idempotent)                                   │
                            │                                                    │
                            └──────────── Firebase Admin SDK (trusted only) ─────┘
                                          FCM sender · token verification

  Voice/video:  WebRTC peer media  ⇄  STUN/TURN  ⇄  (SFU for group calls, later)
  Observability: Crashlytics · Analytics (consent-gated) · Performance · Remote Config
  Local dev/test: Firebase Emulator Suite
```

### B.3 Android application stack (later phase, not started)

| Layer | Technology |
|---|---|
| Language / build | Kotlin, Gradle Kotlin DSL, version catalog (`libs.versions.toml`), convention plugins in `build-logic/` |
| UI | Jetpack Compose, Material 3 (light, dark, AMOLED, dynamic color), accessibility and font-scaling support, localization-ready `strings.xml` |
| Architecture | Unidirectional data flow, immutable UI state, ViewModels, repository pattern |
| Async | Kotlin Coroutines and Flow |
| Navigation | Navigation Compose with type-safe `@Serializable` routes |
| Dependency injection | Hilt |
| Local data | Room (offline cache, send queue), DataStore (preferences) |
| Background work | WorkManager (retryable sends, uploads, scheduled tasks) |
| Security | Android Keystore for key protection; backup disabled; no secrets in preferences |
| Media | CameraX, Android Photo Picker |
| Calls | WebRTC |
| Firebase SDKs | Auth, Firestore, Realtime Database, Storage, Messaging, Functions, App Check, Crashlytics, Analytics, Performance, Remote Config |
| Phone parsing | libphonenumber |
| Build types | `debug` (emulators), `staging`, `release` (signing from env or gitignored `keystore.properties`) |
| Application ID | `com.samvad.messenger` |

### B.4 Planned repository layout

```
samvad/
├── android-native/                 LATER  native Android Gradle root (after the backend)
│   ├── app/                        application, DI wiring, root navigation
│   ├── build-logic/                convention plugins
│   ├── gradle/libs.versions.toml   version catalog
│   ├── core/
│   │   ├── common/                 results, dispatchers, utilities
│   │   ├── designsystem/           theme, components
│   │   └── security/               App Check setup, device-id store
│   │   (later: database, datastore, network, notifications, media)
│   └── feature/
│       └── authentication/         phone entry, OTP, profile setup
│       (later: chats, conversation, contacts, groups, communities,
│        stories, calls, settings, privacy)
├── firebase/                       NEXT (B1)  Firebase project definition
│   ├── firebase.json, .firebaserc.example
│   ├── firestore.rules, firestore.indexes.json
│   ├── storage.rules, database.rules.json
│   ├── functions/                  Cloud Functions (TypeScript)
│   ├── rules-tests/                emulator-based rules tests
│   └── README.md                   setup, environments, deployment
├── backend-ktor/                   LATER (B6)  created only when justified
├── docs/
│   ├── ARCHITECTURE.md             this file
│   ├── security/                   incident runbook
│   └── superpowers/specs/          per-sub-project designs
└── (legacy, removed after parity)  lib/, android/, ios/, backend/, supabase/ …
```

Android modules are added in the Android phase, each in the feature that first needs it, to avoid empty shells. The suggested first set is `:app`, `:core:common`, `:core:designsystem`, `:core:security`, `:feature:authentication`.

### B.5 Firebase services and their roles

| Service | Role | Arrives in |
|---|---|---|
| Authentication | Phone OTP sign-in, sessions, token revocation | B1 |
| Cloud Firestore | Profiles, conversations, messages, groups, memberships, message metadata | B1 (profiles), B2+ |
| Realtime Database | Presence, typing, other transient signals | B5 |
| Cloud Storage | Media and documents | B4 |
| Cloud Messaging (FCM) | Push notifications, generic text for encrypted chats | B5 |
| Cloud Functions | Account deletion, sign-out-everywhere, notification fan-out, cleanup, scheduled work | B1, B2+ |
| App Check | Play Integrity in staging/release, Debug provider in debug; enforced per service | B1 |
| Crashlytics, Analytics (consent-gated) | Crash reports and events, no PII | Android phase |
| Performance, Remote Config | Monitoring, non-secret runtime config | B9 / Android phase |
| Emulator Suite | Local dev and automated tests | B1 |
| App Distribution, Test Lab | Tester builds and device-lab tests | B9 / Android phase |

### B.6 Backend split: Functions vs Ktor

| Use a Cloud Function when | Use Kotlin + Ktor on Cloud Run when |
|---|---|
| Triggered by Firestore, Auth or schedule events | Custom bidirectional WebSocket signaling is genuinely needed |
| Short request/response tied to Firebase data (callables) | TURN credential minting or abuse-sensitive endpoints need rich middleware and rate limiting |
| Notification fan-out, cleanup, retries that must be idempotent | Logic does not fit the Functions runtime or needs a JVM stack |

Ktor, when it exists: structured routing, Kotlin serialization, Firebase ID-token verification with revocation checks, App Check token verification, request validation, rate limiting, structured logging, health checks, metrics, graceful shutdown, unit and integration tests, container image deployed to Cloud Run, secrets from Secret Manager. Firestore reads and writes are **not** proxied through Ktor without a specific security or business reason.

### B.7 Data model (current design)

Only B1 collections are specified so far; later sub-projects define their own schemas, rules and indexes before implementing each feature.

| Path | Purpose | Access |
|---|---|---|
| `users/{uid}` | Private account data: `phoneE164`, `consent`, `createdAt`, `tokensRevokedAt` | Owner `get` only; field allow-list; client delete denied |
| `profiles/{uid}` | Public profile: `displayName`, `about`, `photoUrl` (null until B4), `updatedAt` | Signed-in users `get` (no `list`); owner write |
| `users/{uid}/devices/{deviceId}` | Per-install session record: platform, versions, `lastSeenAt`, `fcmToken`, `revokedAt` | Owner only; `revokedAt` server-only |
| Everything else | — | Denied by default |

Planned (not yet designed): `conversations`, `messages`, `groups`, `memberships`, reactions, pinned messages, stories, communities, scheduled messages. Realtime Database will hold presence and typing; because its rules cannot read Firestore, membership checks there need a mirrored structure or Function-mediated writes (decided in B5).

### B.8 Security model

- **Identity:** the document ID and every ownership check come from `request.auth.uid`, never from client-supplied IDs. Only phone-provider sign-ins are accepted by rules.
- **Authorization:** Firestore and Storage rules check ownership, conversation membership, group role and permitted field changes. Admin-only fields (`revokedAt`, `tokensRevokedAt`) are writable only by server code.
- **Abuse resistance:** App Check on every service, SMS region allow-list, Firebase quotas, budget alerts, rate limits on OTP-adjacent and discovery-style operations.
- **Phone numbers:** stored only in the owner's private document and the Auth record; never in public profiles, logs, analytics or crash keys. Contact discovery will be server-mediated and rate-limited.
- **Tokens and sessions:** managed by the Firebase SDK in app-private storage; never copied elsewhere; backup disabled; servers verify with revocation checks. Revocation is per user ("sign out everywhere"); per-device revocation needs the device-identity design in B8.
- **Secrets:** `google-services.json`, keystores and `.env` files are gitignored; Admin credentials are never created as files; server code uses runtime service accounts and Secret Manager.
- **Encryption:** transport uses TLS, storage is encrypted at rest by Google. **End-to-end encryption is not implemented and not claimed.** It is designed, built and reviewed in B8 (server side) and the Android phase (client), with a well-reviewed protocol library, Keystore-protected keys, per-device identity, media encrypted before upload, and generic notification text. No "encrypted" badge appears until then.
- **Calls:** no call UI reports a connection without a real WebRTC session; no call-encryption claim until validated.

### B.9 Roadmap (backend-first sub-projects)

Each is a separate spec, plan and build cycle with its own approval gate, in this order. The Android app follows the backend and starts only when the owner says so.

| # | Sub-project | Main content |
|---|---|---|
| B1 | Firebase foundation + auth backend | `firebase/` scaffold, Emulator Suite, `users`/`profiles`/`devices` rules, `deleteAccount` and `signOutAllDevices` callables, App Check config, tests, setup docs, client contract |
| B2 | 1:1 messaging | Conversations/messages schema, receipts, replies, edits/deletes, reactions, pagination indexes, rules, FCM fan-out, tests |
| B3 | Groups and safety | Memberships, roles, invites, moderation, block/report/mute, rate limits |
| B4 | Media | Storage rules, upload metadata, cleanup/retention functions |
| B5 | Presence and notifications | RTDB presence/typing rules, FCM token lifecycle, notification preferences |
| B6 | Ktor service on Cloud Run | Contact discovery (hashed numbers), rate-limited abuse-sensitive endpoints, TURN credentials and call authorization, container, health/metrics, tests |
| B7 | Stories, communities, scheduled messages, call-signaling data | |
| B8 | E2EE server side | Key-bundle directory and device registry, after the protocol is chosen |
| B9 | Hardening and deployment | Monitoring, quotas, abuse/load tests, Cloud Run and Secret Manager deployment docs |
| - | Android app (later) | Compose UI, Room/WorkManager offline sync, WebRTC calls, E2EE client; built against the tested backend |

### B.10 Testing strategy

| Level | Tooling |
|---|---|
| Unit (Android phase) | JUnit, Turbine, fakes: ViewModels, repositories, domain logic |
| Rules and authorization | Firebase Emulator Suite with `@firebase/rules-unit-testing`, including negative (unauthorized read/write) cases |
| Functions | Emulator-backed tests for callables and triggers, idempotency |
| Backend (if Ktor exists) | Ktor test application: routes, auth, authorization |
| Instrumented UI (Android phase) | Compose tests against the Auth emulator and test phone numbers |
| Later | Offline/reconnect/duplicate-message tests, multi-device sync tests, WebRTC permission/connection tests, release build validation |
| Backend gate (B1) | One documented command from `firebase/` runs all rules, function and end-to-end emulator tests |
| Android build gate (later) | `./gradlew assembleDebug testDebugUnitTest lintDebug` |

Things the emulator cannot prove and that need a live Firebase project: real SMS delivery, Play Integrity, App Check enforcement, deployed rules/functions, billing alerts, Crashlytics reporting.

### B.11 Environments

| Environment | Purpose | Notes |
|---|---|---|
| Local / emulator | Daily development and tests | Tests and (later) debug builds point at the Emulator Suite; Auth test phone numbers |
| `samvad-dev` | Shared development | Debug App Check tokens; test numbers allowed |
| `samvad-staging` | Pre-release verification | Play Integrity, App Check enforced, real SMS in limited regions |
| `samvad-prod` | Production | Release signing, budget alerts, SMS region allow-list |

Project names are suggestions. Each environment has its own alias in `.firebaserc` (from `.firebaserc.example`) and, in the Android phase, its own gitignored `google-services.json`.

### B.12 Toolchain

| Tool | Requirement |
|---|---|
| JDK | Needed now by the Firebase emulators (they run on the JVM) and later by Ktor and the Android build. Required: Eclipse Temurin 21 LTS (`firebase-tools` 15.33.0 refuses Java below 21). The installed JDK 8 is too old. Android Studio's bundled JDK 25 is too new for Gradle 8.14, so it is not used as the Gradle build JDK. |
| Node.js | 24 LTS (24.12 or newer) for Cloud Functions (`nodejs24`), rules tests and vitest (the machine has Node 25.8.1, which vitest 5 does not support) |
| `firebase-tools` | Pinned devDependency in `firebase/package.json`, not global |
| Language | TypeScript for Cloud Functions (Functions supports Node.js and Python); Kotlin for Ktor (B6) and Android |
| Gradle / Android SDK | Android phase only; SDK already present under `ANDROID_HOME` |

---

## Current status summary

| Item | State |
|---|---|
| Order of work | B1 backend, then A1 Android foundation, then each later B/A pair (owner decision 2026-10-13) |
| B1 design spec (revision 3.1, backend-only) | Approved as rev 3; §0.1 amendments await review with the B1 plan |
| B1 implementation plan | Written (`docs/superpowers/plans/2026-10-09-b1-firebase-foundation.md`), awaiting review |
| Android design system + client spec (rev 4) | Written (`docs/superpowers/specs/2026-10-13-android-design-system-client.md`), awaiting your approval |
| Backend code (`firebase/`) | None written |
| Native Android code (`android-native/`) | None written; A1 plan follows rev 4 approval |
| Firebase project | Not created (owner action) |
| Leaked credentials | Open incident; runbook written; rotation not performed |
| JDK 21 and Node 24 LTS | Not installed |
