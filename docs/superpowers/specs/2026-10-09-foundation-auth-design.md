# Samvad Native — Sub-project 1: Foundation + Firebase Phone Auth

Date: 2026-10-09
Status: Draft for review

## 1. Context and decomposition

The repository currently holds a Flutter/Dart app (Supabase data layer) and a Node/Express/MongoDB/Socket.io backend. There is no Firebase configuration (no `firebase.json`, rules, indexes or Functions). The goal is a production-oriented native Android messenger (Kotlin, Compose, Material 3, Hilt, Room, WorkManager, DataStore, Firebase, WebRTC; Ktor on Cloud Run where trusted server logic is required).

That goal covers roughly ten independent subsystems, so it is split into sub-projects. Each gets its own spec, plan and build cycle:

1. **Foundation + Firebase phone auth** (this spec)
2. Firestore schema, rules and real 1:1 messaging
3. Room cache, offline send queue, multi-device sync
4. FCM notifications and presence
5. Groups and media uploads
6. End-to-end encryption foundation and device identity
7. WebRTC voice/video with TURN
8. Stories, communities, scheduled messages, advanced features
9. Security hardening, monitoring, release documentation

Ktor on Cloud Run and Cloud Functions are introduced only in the sub-project that first needs trusted server logic. In this sub-project, the only server-side piece is the account-deletion callable function.

### Decisions already made

- **Coexistence:** the native app is built alongside the existing Flutter app in new directories. Flutter, Supabase and Node code are left untouched and removed in a later, separate commit once the native app reaches parity.
- **Firebase:** a new Firebase project will be created by the owner. All development targets the Emulator Suite first. No live project is needed to build or run the tests.
- **Application ID:** `com.samvad.messenger`.
- **Module layout (hybrid):** start with `:app`, `:core:common`, `:core:designsystem`, `:core:security` and `:feature:authentication`. Add other modules in the sub-project that first needs them.

## 2. Findings that affect this work

- Only JDK 8 is installed; the Android Gradle Plugin requires JDK 17+. The Android SDK is present under `ANDROID_HOME`.
- `backend/.env` (MongoDB URI, JWT secret) and two `google-services.json` files are tracked in git and the remote is on GitHub. These credentials are exposed and must be rotated independently of this work. The native project must never track equivalents.
- The working tree has uncommitted changes in about 73 files (a half-finished rename from `zingify` to `samvad`). This work must not commit or revert them; commits include only explicit paths.
- Current build status of the Flutter app is unverified.

## 3. Goals and non-goals

### Goals

- A native Android project that builds with `./gradlew assembleDebug`.
- Working Firebase phone-number OTP sign-in (against the Auth emulator and its test numbers; against a live project once the owner supplies config).
- First-run profile creation stored in Firestore under the authenticated UID.
- Logout and re-authenticated account deletion.
- Material 3 design system with light, dark and AMOLED themes, dynamic color, localization-ready strings.
- Firebase project scaffolding (`firebase/`) with deny-by-default rules for the collections in scope, and rules tests.
- Setup documentation for Firebase and the environments.

### Non-goals (later sub-projects)

Messaging, Room, FCM, presence, groups, media, E2EE, calls, stories, Ktor backend. No E2EE badge or encryption claim appears anywhere in this sub-project.

## 4. Layout and build configuration

```
android-native/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml        # version catalog
  build-logic/                      # convention plugins
  app/
  core/common/
  core/designsystem/
  core/security/
  feature/authentication/
firebase/
  firebase.json
  firestore.rules
  firestore.indexes.json
  storage.rules                     # deny-all placeholder
  database.rules.json               # deny-all placeholder
  functions/                        # account deletion callable
  rules-tests/                      # emulator-based rules tests
docs/
```

- Kotlin DSL throughout; versions only in `libs.versions.toml`. Current stable, mutually compatible versions are looked up at implementation time, not assumed.
- Application ID `com.samvad.messenger`. Build types: `debug` (points at emulators via a build config flag), `staging`, `release`.
- Release signing is read from environment variables or a gitignored `keystore.properties`. Never committed.
- `google-services.json` is gitignored; `google-services.json.example` documents the shape.
- Toolchain: install or select JDK 17+ and verify with `./gradlew --version` before any code. Installing a JDK needs the owner's approval.
- `.gitignore` additions for the native project cover `local.properties`, `keystore.properties`, `google-services.json`, `serviceAccount*.json`, `.env*`, Gradle build output.

## 5. Authentication design

### Screens and navigation

`PhoneEntry` → `OtpEntry` → `ProfileSetup` → Home. Type-safe Navigation Compose routes (`@Serializable`). A root `AppState` (`Loading`, `SignedOut`, `NeedsProfile`, `SignedIn`) selects the active graph.

### State

Each screen has one immutable UI state exposed as `StateFlow` from a Hilt ViewModel. Events flow up as intents; one-shot effects (navigation, snackbars) use a separate channel.

### Phone input

- Country selector, India (+91) selected by default, all international dialing codes available.
- libphonenumber for parsing, validation and E.164 / international display formatting.
- The number is validated before any verification request is sent.

### Verification

`AuthRepository` wraps `PhoneAuthProvider.verifyPhoneNumber` and exposes a `Flow<VerificationEvent>`:

- `CodeSent(verificationId, resendToken)`
- `AutoVerified(credential)`
- `Failed(reason)`

`reason` is a sealed type: `InvalidNumber`, `QuotaExceeded`, `Network`, `AppCheckOrCaptcha`, `InvalidCode`, `CodeExpired`, `Unknown`. Each maps to a user-facing, localized message and a retry rule.

- Resend: 60-second countdown using the resend token. The resend button is disabled until the countdown ends.
- Code expiry handled explicitly: after expiry the user must request a new code.
- `verificationId` and the resend token are held in `SavedStateHandle` so they survive process death. They are never logged.
- Play Integrity / reCAPTCHA fallback is handled by the Firebase SDK; the UI must handle the fallback browser hop and return.
- Release builds contain no OTP bypass. Debug builds use Firebase test phone numbers on the Auth emulator.

### Account lifecycle

- **Create:** after first successful sign-in with no profile, `ProfileSetup` writes `users/{uid}`. The document ID is always `request.auth.uid`; the client never supplies another user's ID.
- **Logout:** `FirebaseAuth.signOut()` and clear local session state.
- **Delete:** the client re-authenticates (fresh OTP), then calls a callable Cloud Function that deletes the auth user and `users/{uid}`. The function is idempotent. It verifies `request.auth` and that the sign-in is recent.
- **Consent:** Terms of Service / Privacy Policy links and an explicit consent checkbox at `PhoneEntry`; consent timestamp and version stored on the profile.
- **Permissions:** none are requested in this sub-project. Notification, contacts, camera, mic and media permissions are requested by the feature that needs them, with a rationale.

### Firestore: `users/{uid}`

| Field | Type | Rule |
|---|---|---|
| `displayName` | string, 1–64 chars | owner-writable |
| `about` | string, ≤ 140 | owner-writable |
| `photoUrl` | string or null | owner-writable |
| `phoneE164` | string | set on create from `request.auth.token.phone_number`; immutable |
| `consent` | map `{version, acceptedAt}` | set on create; update only with a newer version |
| `createdAt` | timestamp | server timestamp; immutable |

### Rules for this sub-project

- Default deny for everything not named.
- `users/{uid}`: create/update only if `request.auth.uid == uid`; field allow-list and type/length checks; `phoneE164` must equal the token's phone number and cannot change; delete denied to clients (deletion goes through the callable function).
- Read: owner only for now. Cross-user profile lookup (contacts discovery) is designed in sub-project 2 and will go through a rate-limited server path, not open reads.
- Storage and Realtime Database rules are deny-all placeholders until the sub-projects that use them.

## 6. Design system and UI

- Material 3 theme with light, dark and AMOLED variants; dynamic color on Android 12+, with a brand fallback palette.
- Type scale that renders Devanagari correctly.
- All user-visible strings in `strings.xml`; no concatenated sentences; plural resources where needed.
- Accessibility: 48dp minimum touch targets, content descriptions on non-text controls, layouts tested at 200% font scale, TalkBack-friendly focus order on the OTP field.
- Home is a real empty state ("no conversations yet") with no hardcoded sample conversations. Any sample data lives only in debug fixtures.
- Clear loading, offline and error-with-retry states on every auth screen.

## 7. Testing and acceptance

### Automated

- **Unit tests** (JUnit + Turbine + fakes): `AuthViewModel` state machine, resend countdown, error mapping, phone validation/formatting, `AppState` selection.
- **Rules tests** (`@firebase/rules-unit-testing` on the Firestore emulator): unauthenticated read/write denied; user can create and update only their own profile; cannot write another UID; cannot change `phoneE164` or `createdAt`; unknown fields rejected; client delete denied; default-deny on unnamed collections.
- **Function tests:** account-deletion callable rejects unauthenticated calls and stale sign-ins; deletion is idempotent.
- **Instrumented test:** Compose test of the OTP flow against the Auth emulator and a test phone number.
- **Build gate:** `./gradlew assembleDebug testDebugUnitTest lintDebug` passes.

### Acceptance (sub-project 1 is done when)

1. The gate above passes on a machine with JDK 17+.
2. Rules and function tests pass on the Emulator Suite.
3. On an emulator or device pointed at the Auth emulator, a test phone number can sign in, create a profile, log out and delete the account.
4. `firebase/README.md` documents project creation, app registration, phone-auth enablement, adding `google-services.json`, running emulators, deploying rules, and dev/staging/prod environments.
5. No secret is tracked in the new directories.

### Explicitly not verified without the owner's live Firebase project

Real SMS delivery, Play Integrity attestation, reCAPTCHA fallback on a real device, App Check enforcement, and deployed rules/functions. The final report will list these as "setup still required" and will not claim them as working.

## 8. Risks

- **JDK/AGP mismatch:** blocked until JDK 17+ is available.
- **Version compatibility:** Compose, Hilt, Firebase BoM and AGP versions must be resolved together; mitigated by the version catalog and a build-first milestone.
- **Phone-auth abuse:** SMS pumping and quota exhaustion are handled by App Check and per-number limits; full hardening is sub-project 9. Until then the live project should have SMS region restrictions set (documented in the README).
- **Exposed legacy credentials:** rotation is the owner's responsibility and is called out in the README.
