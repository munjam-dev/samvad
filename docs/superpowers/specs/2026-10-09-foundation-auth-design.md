# Samvad Native — Sub-project 1: Foundation + Firebase Phone Auth

Date: 2026-10-09
Status: Revision 2, draft for review (revision 1 is commit `f5684fb`)

## 0. What changed in revision 2

- Added an explicit Firebase-first architecture section (§2), including when a Cloud Function or a Ktor service is allowed and where the Admin SDK may run.
- Replaced the single `users/{uid}` document with a private/public split (`users/{uid}`, `profiles/{uid}`) and added `users/{uid}/devices/{deviceId}` for device sessions.
- Added a full auth lifecycle state table with resend limits, expiry, error mapping, logout, "sign out everywhere" and account deletion (§7).
- Added App Check, token/phone-number/device-session protection, logging, backup and analytics-consent rules (§9).
- Added repository conflicts and the exact file list (§4), a JDK/toolchain section (§5), and a prerequisites checklist (§12).
- Stated that this sub-project edits no existing file; it only adds new ones.
- Leaked-credential handling moved to its own runbook: `docs/security/incident-leaked-credentials.md`.

## 1. Context and decomposition

The repository holds a Flutter/Dart app (Supabase data layer) and a Node/Express/MongoDB/Socket.io backend. There is no Firebase configuration. The goal is a production-oriented native Android messenger on a Firebase-first architecture.

That goal covers roughly ten independent subsystems, so it is split into sub-projects, each with its own spec, plan and build cycle:

1. **Foundation + Firebase phone auth** (this spec)
2. Firestore schema, rules and real 1:1 messaging
3. Room cache, offline send queue, multi-device sync
4. FCM notifications and presence/typing (Realtime Database)
5. Groups and media uploads (Cloud Storage)
6. End-to-end encryption foundation and device identity
7. WebRTC voice/video with TURN
8. Stories, communities, scheduled messages, advanced features
9. Security hardening, Performance Monitoring, Remote Config, release documentation

### Decisions already made

- **Coexistence:** the native app is built alongside the Flutter app in new directories. Flutter, Supabase and Node code stay untouched and are removed in a later, separate commit once the native app reaches parity.
- **Firebase:** the owner will create a new Firebase project. Development targets the Emulator Suite first; a live project is not needed to build or run the automated tests.
- **Application ID:** `com.samvad.messenger`.
- **Module layout (hybrid):** start with `:app`, `:core:common`, `:core:designsystem`, `:core:security`, `:feature:authentication`; add other modules in the sub-project that first needs them.

## 2. Firebase-first architecture

| Concern | Service | Used from | Sub-project |
|---|---|---|---|
| Phone OTP and sessions | Firebase Authentication | Android SDK | 1 |
| Primary database (profiles, conversations, messages, groups, memberships) | Cloud Firestore | Android SDK, enforced by security rules | 1 (profiles), 2+ |
| Presence, typing, other transient signals | Realtime Database | Android SDK | 4 |
| User media and documents | Cloud Storage for Firebase | Android SDK, enforced by Storage rules | 5 |
| Push notifications | Firebase Cloud Messaging | Android SDK + trusted sender (Function/Ktor) | 4 |
| Event-driven backend work | Cloud Functions for Firebase | Triggered by Firestore/Auth events, or called as callables | 1 (account callables), 2+ |
| Client access protection | Firebase App Check | Android SDK; enforced on each service | 1 |
| Crash and diagnostics | Crashlytics | Android SDK | 1 |
| Product analytics (consent-gated) | Firebase Analytics | Android SDK | 1 |
| Performance, runtime config | Performance Monitoring, Remote Config | Android SDK | 9 |
| Local development and tests | Firebase Emulator Suite | Developer machine / CI | 1 |

Rules of the architecture:

- **No other primary datastore.** MongoDB and Supabase are not used by the native app. They remain only as legacy components of the Flutter app until removal.
- **Clients talk to Firebase directly** for supported operations. Firestore reads/writes are not proxied through a custom server unless a specific security or business requirement demands it.
- **Admin SDK only in trusted server environments** (Cloud Functions, and Ktor on Cloud Run when it exists). Never in the Android app, never in a repository, never in a build artifact. Server code uses Application Default Credentials / the runtime's service account; no service-account JSON is created or committed.
- **When a Cloud Function is the right tool:** event-driven or short request/response work tied to Firebase data (Firestore/Auth triggers, notification fan-out, cleanup, account deletion, retries that must be idempotent).
- **When Kotlin + Ktor on Cloud Run is the right tool:** server logic that does not fit a Function, for example long-lived custom WebSocket signaling, TURN credential minting, abuse-sensitive endpoints needing rate limiting and richer middleware, or integrations needing a JVM stack. Ktor verifies Firebase ID tokens (with revocation checking) and App Check tokens on every request. No Ktor service is created in this sub-project; each later sub-project that adds one must justify it against this list.
- **E2EE is not claimed.** Nothing in this sub-project encrypts message content end to end, and no UI, copy, doc or log may imply it. Transport encryption (HTTPS/TLS), Firestore rules and encryption at rest are not E2EE. Sub-project 6 designs and verifies E2EE before any such claim.

## 3. Goals and non-goals

### Goals

- A native Android project that builds with `./gradlew assembleDebug` on JDK 17+.
- Working Firebase phone-number OTP sign-in against the Auth emulator and its test numbers (and against a live project once the owner supplies `google-services.json`).
- First-run profile creation in Firestore under the authenticated UID.
- Device-session record per install; logout; "sign out everywhere"; re-authenticated account deletion.
- App Check wired in (Debug provider in debug; Play Integrity in staging/release).
- Material 3 design system with light, dark and AMOLED themes, dynamic color, localization-ready strings.
- `firebase/` scaffolding with deny-by-default rules, tests, and setup documentation.

### Non-goals (later sub-projects)

Messaging, Room, FCM, presence, groups, media upload, E2EE, calls, stories, Ktor backend, Performance Monitoring, Remote Config.

## 4. Repository conflicts and file changes

### 4.1 Conflicts between the repo and the Firebase architecture

| # | Conflict | Where | Resolution |
|---|---|---|---|
| 1 | Supabase is the app's data layer | `lib/samvad_app.dart`, `lib/screens/chat_list_screen.dart`, `lib/screens/chat_screen.dart`, `pubspec.yaml` (`supabase_flutter`), `supabase/` (functions, migrations) | Left untouched. Removed in the post-parity cleanup commit. Native app never uses it. |
| 2 | Node + MongoDB + Socket.io backend | `backend/` (`server.js`, `package.json` with `mongoose`/`socket.io`, `Dockerfile`, `Procfile`, `heroku.yml`) | Left untouched; not part of the native architecture. Replaced by Firebase + Functions (+ Ktor if justified). Removed post-parity. |
| 3 | Credentials tracked in git | `backend/.env`, `android/app/google-services.json`, `android/app/src/google-services (1).json` (all from commit `b4d56c4`); possible credential-like content in `lib/samvad_app.dart`, `backend/server.js`, `PRODUCTION_SETUP.md` | Separate incident; see `docs/security/incident-leaked-credentials.md`. Not fixed by this spec. |
| 4 | Application ID / package inconsistency | `android/app/build.gradle.kts` uses `com.example.samvad_messenger`; staged `MainActivity.kt` uses `com.samvad.messenger`; legacy `com.example.zingify_mobile_new` | Native project uses `com.samvad.messenger` only. The Flutter shell's package is out of scope and left as is. |
| 5 | Flutter-centric CI | `.github/workflows/build.yml`, `.gitlab-ci.yml`, `bitrise.yml`, `codemagic.yaml`, `appcenter.json` | Not edited. Native CI, if wanted, is a new workflow file. |
| 6 | Mid-rename working tree | 73 uncommitted changes (`zingify` → `samvad`) | Preserved exactly. See §4.3. |
| 7 | Flutter `android/` Gradle project already exists at repo root | `android/` | Native project lives in `android-native/`, separate Gradle root. No shared Gradle files. |
| 8 | No Firebase project config at all | no `firebase.json`, rules, indexes, Functions | Created under `firebase/` (new). |
| 9 | JDK 8 only | `JAVA_HOME` unset; `java` resolves to JDK 8 | See §5. |

### 4.2 Exact files and directories this sub-project adds

All new. No existing file is modified or deleted.

```
android-native/                      (new Gradle root)
  .gitignore
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/*                   (generated by Gradle)
  build-logic/                       (convention plugins)
  app/
    google-services.json.example     (placeholder shape only)
    src/main/AndroidManifest.xml     (allowBackup=false, no cleartext, analytics/crash collection off by default)
    src/main/res/xml/data_extraction_rules.xml
    src/main/kotlin/com/samvad/messenger/...
  core/common/
  core/designsystem/
  core/security/                     (App Check provider setup, device-id store)
  feature/authentication/
  README.md
firebase/
  .gitignore
  firebase.json
  .firebaserc.example
  firestore.rules
  firestore.indexes.json             (empty: no composite indexes required)
  storage.rules                      (deny all)
  database.rules.json                (deny all)
  package.json                       (pins firebase-tools, rules-unit-testing as devDependencies)
  functions/                         (TypeScript: deleteAccount, signOutAllDevices; tests)
  rules-tests/
  README.md                          (setup, environments, deployment)
docs/security/incident-leaked-credentials.md
```

Optional, decided at plan time: `.github/workflows/native-ci.yml` (new file; `build.yml` stays untouched).

### 4.3 Git safety

- The 73 existing uncommitted changes are never reset, stashed, restored, overwritten or deleted by this work.
- Every commit uses explicit paths (`git add -- <path>` and `git commit -- <path>`) so staged-but-unrelated files (e.g. `android/app/src/main/kotlin/com/samvad/messenger/MainActivity.kt`) are not swept in.
- Commit `f5684fb` is kept; spec revisions are new commits, never an amend or rebase.
- Root `.gitignore`, `README.md` and all Flutter files are not edited. Ignore rules for the new trees live in nested `.gitignore` files.
- No git-history rewrite or force-push is performed by this work; the runbook leaves that to the repo owner.

## 5. Toolchain and JDK

**Current state:** `java` is JDK 8 (Temurin 8) and `JAVA_HOME` is unset. Android Studio's bundled JBR is JDK 25. The Flutter project's `android/gradle/wrapper` uses Gradle 8.14.

**Requirement:** the Android Gradle Plugin (8.x and later) needs JDK 17 or newer to run. JDK 8 cannot build the native project. JDK 25 is too new for Gradle 8.14 (which supports running on up to Java 24), so Android Studio's JBR is not a safe CLI build JDK unless the plan selects a Gradle version that supports it.

**Recommendation:** install **Eclipse Temurin 21 (LTS)** and point `JAVA_HOME` and Android Studio's Gradle JDK at it. One LTS JDK then covers Gradle/AGP, the Kotlin compiler, and the Firebase emulators (Firestore/RTDB emulators run on the JVM and recent `firebase-tools` versions require a recent Java). Bytecode target stays Java 17 via the Kotlin/Java toolchain setting, so builds are reproducible on JDK 17 too. Fallback: Temurin 17 if the plan's AGP/Gradle pairing or the pinned `firebase-tools` turns out not to support 21. The final choice is confirmed against the exact AGP, Gradle and `firebase-tools` versions at plan time, because those versions are looked up then, not assumed now.

**Other tools:** Node.js 22 LTS for Cloud Functions and rules tests (the machine has Node 25, a non-LTS release; use Node 22 for functions and keep the functions `engines` field aligned with a supported Cloud Functions runtime). `firebase-tools` is installed as a pinned devDependency in `firebase/package.json`, not globally. Android SDK is already present.

**Installation is not done by this spec.** Installing a JDK changes the machine, so it happens only with the owner's explicit OK at the start of the build phase. It does not touch the repository or the JDK 8 install.

## 6. Layout and build configuration

- Kotlin DSL throughout; versions only in `libs.versions.toml`. Current stable, mutually compatible versions of AGP, Kotlin, Compose BOM, Hilt, Firebase BoM, Navigation, Lifecycle, Coroutines, DataStore are resolved together at plan time.
- The catalog also declares Room and WorkManager now, but they are added to modules in sub-project 3, where they are first used.
- Android stack for this sub-project: Kotlin, Jetpack Compose + Material 3, AndroidX Core/Lifecycle/ViewModel, Navigation Compose (type-safe), Coroutines + Flow, Hilt, DataStore (theme, country default, local UX counters), Firebase Android SDKs (Auth, Firestore, Functions, App Check, Crashlytics, Analytics).
- Application ID `com.samvad.messenger`. Build types: `debug` (emulator host config via BuildConfig), `staging`, `release`.
- Release signing from environment variables or a gitignored `keystore.properties`. Never committed.
- `google-services.json` is gitignored; `.example` shows the shape with placeholder values only.
- Manifest: `allowBackup="false"`, data-extraction rules exclude all app data, `usesCleartextTraffic="false"` (debug build may allow cleartext to the local emulator host only, via a debug-only network security config).

## 7. Authentication lifecycle

### 7.1 Flow

`PhoneEntry` → `OtpEntry` → `ProfileSetup` (first sign-in only) → Home. Type-safe Navigation Compose routes; a root `AppState` (`Loading`, `SignedOut`, `NeedsProfile`, `SignedIn`) selects the graph. One immutable UI state per screen as `StateFlow` from a Hilt ViewModel; one-shot effects via a separate channel.

### 7.2 Phone input

Country selector, India (+91) default, all international dialing codes. libphonenumber parses, validates and formats (E.164 for the request, international format for display). Invalid numbers never reach Firebase.

### 7.3 Verification state machine

| State | Entered when | Exits to |
|---|---|---|
| `Idle` | screen open | `Sending` on valid submit with consent ticked |
| `Sending` | verification requested | `CodeSent`, `AutoVerified`, or `Failed` |
| `CodeSent(countdown)` | SMS requested successfully | `Verifying` on 6-digit entry; `Sending` on resend after countdown; `Idle` on "change number" |
| `AutoVerified` | SDK instant/auto-retrieval credential | `SigningIn` |
| `Verifying` | user submitted code | `SigningIn` on success; `CodeSent` with `InvalidCode` on wrong code |
| `SigningIn` | credential in hand | `SignedIn`/`NeedsProfile`, or `Failed` |
| `Failed(reason)` | any error | `Idle` or `CodeSent` depending on reason (below) |

### 7.4 Resend limits and abuse controls

- **Authoritative limits are Firebase's:** per-number and per-project SMS quotas, App Check enforcement, and the console's SMS region policy. The client cannot be trusted to enforce these.
- **Client-side UX guards (not security):** 60-second resend countdown; at most 3 resends per verification session; at most 5 verification requests per phone number per hour tracked in DataStore. When a guard trips, the UI says so and offers a wait time.
- A server `TOO_MANY_REQUESTS`/quota response overrides local counters and puts the screen into a back-off state with no automatic retry.
- Live-project prerequisite: enable the SMS region allow-list (start with India and any markets actually served) to reduce SMS-pumping exposure.

### 7.5 Expiry, invalid codes and errors

`AuthError` is a sealed type. Each maps to a localized message and a retry rule. Firebase exception/error-code names are verified against the pinned SDK version when implementing.

| `AuthError` | Typical cause | Behavior |
|---|---|---|
| `InvalidNumber` | rejected phone number | stay on `PhoneEntry`, inline error |
| `InvalidCode` | wrong OTP | stay on `OtpEntry`, clear field, allow retry; after 5 wrong attempts in a session force a fresh code request |
| `CodeExpired` | Firebase reports the verification session/code expired | require a new code request; discard `verificationId` |
| `QuotaExceeded` / `TooManyRequests` | SMS or request quota hit | back-off screen, no auto retry |
| `Network` | no connectivity / timeout | retry button, keep entered values |
| `AppCheckOrCaptcha` | Play Integrity/reCAPTCHA failure or missing activity | explain, offer retry; log a non-PII diagnostic |
| `UserDisabled` | account disabled | sign-out and explain |
| `Unknown` | anything else | generic message; PII-free diagnostic to Crashlytics |

Code validity period is controlled by Firebase and is not hardcoded in the app; the app relies on Firebase's expiry error rather than guessing a TTL.

### 7.6 Session persistence

`verificationId` and the resend token live in `SavedStateHandle` so they survive process death, and are never written to disk, logs, analytics or crash reports. The reCAPTCHA browser fallback must return to the same verification session.

### 7.7 Account creation and consent

On first sign-in with no profile, `ProfileSetup` writes `users/{uid}` and `profiles/{uid}` in a single batched write. The document ID is always the authenticated UID, never a client-supplied value. Terms of Service and Privacy Policy links plus an explicit consent checkbox appear at `PhoneEntry`; the accepted version and server timestamp are stored on `users/{uid}`. Policy text is supplied by the owner; this sub-project ships placeholder pages clearly marked as such.

### 7.8 Logout, sign-out everywhere, deletion

- **Logout (this device):** delete the device-session doc, call `FirebaseAuth.signOut()`, clear local session state and the DataStore UX counters.
- **Sign out everywhere:** callable Function `signOutAllDevices` calls Admin `revokeRefreshTokens(uid)` and stamps `users/{uid}.tokensRevokedAt`. Existing ID tokens remain valid until they expire (up to about an hour) unless the caller checks revocation; Firestore rules therefore cannot assume instant revocation, and Ktor/Functions use `checkRevoked`. This limit is documented, not hidden.
- **Delete account:** requires a fresh sign-in (re-OTP). Callable `deleteAccount` rejects unauthenticated calls and calls whose `auth_time` is older than 5 minutes, then deletes `users/{uid}` (including the `devices` subcollection via recursive delete), `profiles/{uid}`, and the Auth user. It is idempotent: a retry after partial failure completes the remaining steps. Data owned by later sub-projects (messages, media) adds its own deletion steps in those specs.
- Both callables enforce App Check (`enforceAppCheck: true`).

## 8. Data model, rules and indexes

### 8.1 Collections

**`users/{uid}`** — private to the owner.

| Field | Type | Constraints |
|---|---|---|
| `phoneE164` | string | on create must equal `request.auth.token.phone_number`; immutable |
| `consent` | map `{version:int, acceptedAt:timestamp}` | `acceptedAt == request.time`; version may only increase |
| `createdAt` | timestamp | `== request.time` on create; immutable |
| `tokensRevokedAt` | timestamp or absent | server-only (Admin SDK); clients cannot write |

**`profiles/{uid}`** — the only user data other signed-in users can read, one document at a time.

| Field | Type | Constraints |
|---|---|---|
| `displayName` | string | 1–64 chars |
| `about` | string | ≤ 140 chars |
| `photoUrl` | null | must be null in this sub-project; sub-project 5 restricts it to the app's own Storage paths |
| `updatedAt` | timestamp | `== request.time` |

**`users/{uid}/devices/{deviceId}`** — one doc per install, private to the owner. `deviceId` is a random UUID generated at first launch and stored in DataStore; no hardware identifiers.

| Field | Type | Constraints |
|---|---|---|
| `platform` | string | `"android"` |
| `appVersion` | string | ≤ 32 chars |
| `osVersion` | string | ≤ 32 chars |
| `createdAt` | timestamp | immutable |
| `lastSeenAt` | timestamp | `== request.time` on update |
| `fcmToken` | string or null | owner-writable; populated from sub-project 4 |
| `revokedAt` | timestamp or absent | server-only |

### 8.2 Security rules (summary of required behavior)

- Default deny: `match /{document=**} { allow read, write: if false; }` and nothing broader.
- Every allow requires `request.auth != null` and `request.auth.token.firebase.sign_in_provider == 'phone'`. Anonymous and other providers are disabled in the console and rejected in rules.
- `users/{uid}`: owner-only `get`; `list` denied; create/update only when `request.auth.uid == uid`, field allow-list (`hasOnly`), type/length checks and the immutability rules above; client delete denied (deletion goes through `deleteAccount`).
- `profiles/{uid}`: signed-in users may `get` (not `list`); owner-only create/update with field allow-list; client delete denied.
- `users/{uid}/devices/{deviceId}`: owner-only read/create/update/delete; clients cannot set `revokedAt`.
- Contact discovery (finding other users by phone number) is not enabled. Phone numbers are only readable by their owner and by trusted server code. Discovery is designed in sub-project 2 as a rate-limited server-mediated path (for example a callable that accepts hashed numbers), not by opening `users` for query.
- Storage rules: deny all. Realtime Database rules: deny all. Both are placeholders until sub-projects 5 and 4. Note for sub-project 4: RTDB rules cannot read Firestore, so conversation membership checks for presence/typing need a mirrored membership structure or a Function-mediated write; that design is made there.

### 8.3 Indexes

All reads in this sub-project are single-document gets by UID, so **no composite indexes are required**; `firestore.indexes.json` is committed empty and the emulator tests assert that no query in scope needs one. Indexes for message ordering and conversation lists are defined in sub-project 2 together with the queries that need them.

## 9. Security review

| Asset | Risk | Control in this sub-project |
|---|---|---|
| Phone number (PII) | exposure to other users or logs | stored only in `users/{uid}` (owner-only) and the Auth record; never in `profiles`, logs, analytics, Crashlytics keys, or deep links; shown masked in UI after entry; no contact discovery yet |
| Profile data | tampering, enumeration | owner-only writes with field allow-list; `get`-only for others; no `list`; UIDs are not guessable; `photoUrl` forced null to prevent tracking-pixel URLs |
| Firebase ID / refresh tokens | theft, replay | managed by the Firebase SDK in app-private storage; never copied to DataStore, logs or analytics; backup disabled; server checks revocation; `signOutAllDevices` available |
| Device sessions | stale or hijacked sessions | per-install record, owner-only, `revokedAt` server-only. **Limit:** Firebase Auth revokes refresh tokens per user, not per device, so true per-device revocation needs a device-bound session design in sub-project 6; until then "sign out everywhere" is the revocation tool |
| Verification session (`verificationId`, resend token) | leakage | `SavedStateHandle` only; never persisted or logged |
| SMS pumping / quota abuse | cost and lockout | App Check, SMS region allow-list, Firebase quotas, billing alerts and budget caps (see §12); client guards only improve UX |
| Admin credentials | compromise | not in the app; server code uses runtime service accounts; no service-account JSON is created or committed |
| Client abuse (scripted/modified clients) | unauthorized API use | App Check |
| Analytics / crash data | PII leakage, consent | Analytics and Crashlytics collection disabled by default in the manifest and enabled only after consent; no phone numbers, OTPs, tokens or message text in events, custom keys or logs; user can opt out in settings |
| Local data | extraction via backup/ADB | `allowBackup=false`, data-extraction rules exclude everything, no secrets stored in DataStore |

**App Check:** Play Integrity provider for staging/release; Debug provider for debug builds, with debug tokens registered per developer and never committed. Roll out in "monitor" mode on the live project, then enforce for Firestore, Storage, Realtime Database and callable Functions (`enforceAppCheck`); enforcement for Authentication is enabled where the console supports it for the project's configuration. The Emulator Suite does not enforce App Check, so enforcement can only be verified on a live staging project and is listed as such in §11.

**No E2EE claim:** no badge, string, README sentence or log message refers to end-to-end encryption in this sub-project.

## 10. UI and accessibility

- Material 3 theme with light, dark and AMOLED variants; dynamic color on Android 12+ with a brand fallback palette; type scale that renders Devanagari correctly.
- All user-visible strings in `strings.xml`; no sentence concatenation; plurals where needed; a path for Indian-language translations.
- 48dp minimum touch targets, content descriptions on non-text controls, layouts verified at 200% font scale, sensible TalkBack focus order on the OTP field.
- Home is a real empty state ("no conversations yet"). No hardcoded sample conversations in release code; any sample data lives only in debug fixtures.
- Clear loading, offline and error-with-retry states on every auth screen.
- Settings (minimal): theme, analytics opt-out, sign out, sign out everywhere, delete account, legal links.

## 11. Testing

### Automated (must pass for acceptance)

- **Unit tests** (JUnit, Turbine, fakes): auth state machine, resend countdown and local counters, error mapping, phone validation/formatting, `AppState` selection, consent gating of analytics.
- **Firestore rules tests** (`@firebase/rules-unit-testing`, Firestore emulator), including negative cases:
  - unauthenticated and non-phone-provider requests denied for every path;
  - user A cannot read or write `users/B`, `users/B/devices/*`, or write `profiles/B`;
  - user A can `get` but not `list` `profiles`;
  - `users/{uid}` rejects: mismatched `phoneE164`, changed `phoneE164`/`createdAt`, unknown fields, wrong types, oversized strings, client-set `tokensRevokedAt`/`revokedAt`, client delete;
  - `profiles` rejects non-null `photoUrl`;
  - any unnamed collection is denied;
  - no query in scope requires a composite index.
- **Storage and RTDB rules tests:** deny-all verified with the Storage/Database emulators.
- **Function tests:** `deleteAccount` and `signOutAllDevices` reject unauthenticated calls; `deleteAccount` rejects a stale `auth_time`, removes `users/{uid}` incl. devices, `profiles/{uid}` and the Auth user, and is idempotent on retry.
- **Instrumented test:** Compose test of the OTP flow against the Auth emulator and a Firebase test phone number.
- **Build gate:** `./gradlew assembleDebug testDebugUnitTest lintDebug` passes.

### Acceptance criteria

1. The build gate passes on JDK 17+.
2. Rules and function tests pass on the Emulator Suite.
3. On an emulator/device pointed at the Auth emulator, a test phone number can sign in, create a profile, log out, sign out everywhere, and delete the account.
4. `firebase/README.md` and `android-native/README.md` document setup, environments (dev/staging/prod), rules/function deployment and App Check.
5. No secret or Admin credential is tracked in any new file.
6. The 73 pre-existing uncommitted changes are byte-for-byte unchanged and commit `f5684fb` is still in history.

### Not verified without the owner's live Firebase project (will be reported as "setup still required")

Real SMS delivery, Play Integrity attestation, reCAPTCHA fallback on a physical device, App Check enforcement, SMS region policy, deployed rules/functions, billing alerts, Crashlytics reporting.

## 12. Firebase configuration prerequisites (owner actions)

1. Create the Firebase project(s): `samvad-dev`, `samvad-staging`, `samvad-prod` (names are suggestions); link a billing account (Blaze is required for Cloud Functions and recommended for production phone auth).
2. Register the Android app `com.samvad.messenger` per environment with debug and release SHA-1/SHA-256 fingerprints; download `google-services.json` locally into `android-native/app/` (gitignored).
3. Authentication: enable Phone provider; disable Anonymous and any provider not in use; add test phone numbers for non-production; set the SMS region allow-list; add authorized domains only as needed.
4. Create Firestore (production mode), Realtime Database and Storage buckets with default-deny rules; choose regions deliberately, because they cannot be changed later.
5. App Check: register Play Integrity (requires the app on Play, at least an internal test track) and Debug tokens; start in monitor mode, then enforce.
6. Restrict the project's API keys by package name + SHA-1 and by API.
7. Set budget alerts and usage quotas for SMS, Firestore and Functions; enable Cloud Monitoring alerts on auth and quota anomalies.
8. Deploy rules and functions with the pinned `firebase-tools` per environment alias (`.firebaserc` from the example file).
9. Cloud Run, Secret Manager and Ktor configuration are deferred to the first sub-project that introduces a Ktor service.
10. Provide Terms of Service and Privacy Policy text and URLs.
11. Treat the leaked legacy credentials as a separate incident (runbook linked in §4.1).

## 13. Risks

- **JDK/AGP mismatch:** blocked until a compatible JDK is installed and selected (§5).
- **Version compatibility:** Compose, Hilt, Kotlin (KSP), Firebase BoM and AGP are resolved together; the first milestone is an empty app that builds and runs before any feature work.
- **Emulator vs. reality:** the Auth emulator accepts test numbers and skips SMS, Play Integrity and App Check, so passing emulator tests does not prove production behavior (§11).
- **Token revocation latency:** ID tokens outlive revocation for up to about an hour (§7.8).
- **Per-device revocation is not available in this sub-project** (§9).
- **Legacy credentials already exposed:** rotation by the owner is urgent and independent of this work.
- **Unpublished app:** Play Integrity needs a Play-distributed build, so App Check enforcement can slip until an internal track exists.
