# Samvad Backend — B1: Firebase Foundation + Authentication Backend

Date: 2026-10-09
Status: Revision 3.1, approved by the owner as revision 3; plan-time amendments in §0.1 await review with the plan (revision 1 = `f5684fb`, revision 2 = `73605c1`, revision 3 = `c482629`)

## 0. What changed in revision 3

The owner decided to build a complete, production-ready **backend first** and the Android frontend later (on the owner's instruction). This revision:

- Narrows the spec to the backend only: the `firebase/` project (rules, indexes, Cloud Functions, Emulator Suite tests, setup docs).
- Removes everything Android: `android-native/`, Compose/Material, Hilt/Room/DataStore/WorkManager, navigation, UI and accessibility, instrumented tests, and the Android JDK/AGP requirement.
- Adds a **client contract** (§8) that states exactly what the future Android app must do, so the backend is specified and tested independently of any UI.
- Keeps the Firebase-first architecture, the data model, the security controls and the auth-lifecycle rules from revision 2. Client-only items (resend countdown, local counters, screens) move into the client contract as requirements on the later app.
- Renames the sub-project to **B1**; the roadmap is now backend-first (§1).

### 0.1 Plan-time amendments (revision 3.1)

Found while pinning versions and designing the tests for the implementation plan (`docs/superpowers/plans/2026-10-09-b1-firebase-foundation.md`). Each is a change to approved text and needs the owner's review together with the plan.

1. **JDK 21 is required, not optional** (§5). The pinned `firebase-tools` 15.33.0 refuses Java below 21. JDK 17 is no longer a fallback.
2. **Node 24 LTS, not 22** (§5). `@firebase/rules-unit-testing` 6.0.0 requires Node 24.12 or newer, `firebase-admin` 14 requires 22 or newer, vitest 5 does not support Node 25, and Cloud Functions supports the `nodejs24` runtime. Pinned versions are listed in §5.
3. **Deleted-account tombstone** (§6.3, §7.1, §7.2). After `deleteAccount`, the deleted user's ID token stays valid for up to about an hour and rules cannot see whether the Auth user still exists, so that token could re-create `users`, `profiles` or `devices` documents. `deleteAccount` now writes a server-only `deletedAccounts/{uid}` tombstone first, and the create rules deny when it exists.
4. **App Check replay protection** (§6.3, §8). Both callables set `consumeAppCheckToken`, so the client must send limited-use App Check tokens. Enforcement is switched off only when `FUNCTIONS_EMULATOR` is `true`.
5. **Rules refinements** (§7.1). `displayName` must contain a non-whitespace character; `phoneE164` must match E.164 format as well as equal the token's number.
6. **Index test restated** (§7.3, §10). The Firestore emulator does not enforce composite indexes, so a test cannot prove "no query needs one". The test instead asserts that `firestore.indexes.json` is empty and that the rules allow no `list` outside the owner's own `devices` collection (an unordered, unfiltered read).
7. **Log-hygiene test method** (§10). Function logs are captured from the emulator's console output and scanned for the test phone number and tokens, with a positive control proving that real function log lines were captured.
8. **Default region** (§6.3). Callables are pinned to `asia-south1` (Mumbai) via one constant; the owner should choose the Firestore location to match (§11).
9. **New helper directory** `firebase/test-support/` and `firebase/e2e` flow test file location (§4.2).

## 1. Context and decomposition

The repository holds a Flutter/Dart app (Supabase data layer) and a Node/Express/MongoDB/Socket.io backend. There is no Firebase configuration. The goal is a production-oriented messenger on a Firebase-first architecture, built backend-first.

Backend sub-projects, each with its own spec, plan, tests and approval gate:

| # | Sub-project | Main content |
|---|---|---|
| **B1** | **Firebase foundation + auth backend (this spec)** | `firebase/` scaffold, Emulator Suite, `users`/`profiles`/`devices` rules, account callables, App Check config, tests, setup docs |
| B2 | 1:1 messaging | conversations/messages schema, receipts, replies, edits/deletes, reactions, pagination indexes, rules, FCM fan-out, tests |
| B3 | Groups and safety | memberships, roles, invites, moderation, block/report/mute, rate limits |
| B4 | Media | Storage rules, upload metadata, cleanup/retention functions |
| B5 | Presence and notifications | Realtime Database presence/typing rules, FCM token lifecycle, notification preferences |
| B6 | Ktor service on Cloud Run | contact discovery (hashed numbers), rate-limited abuse-sensitive endpoints, TURN credentials and call authorization, container, health/metrics, tests |
| B7 | Stories, communities, scheduled messages, call-signaling data | |
| B8 | E2EE server side | key-bundle directory and device registry, after the protocol is chosen |
| B9 | Hardening and deployment | monitoring, quotas, abuse/load tests, Cloud Run and Secret Manager deployment docs |

The Android app is a later phase, started only when the owner says so. Each Android feature then consumes the already-tested backend.

> **Rev 4 pointer (2026-10-13):** the owner started the client phase. The Android design system and the client sub-projects A1–A7, each gated on the backend sub-project it needs, are specified in `2026-10-13-android-design-system-client.md`. Order: B1, then A1, then each later pair.

### Decisions already made

- **Coexistence:** new directories next to the Flutter app. Flutter, Supabase and Node code stay untouched and are removed in a later, separate commit.
- **Firebase:** the owner creates a new Firebase project. Development and automated tests use the Emulator Suite; a live project is not needed to build or test.
- **Application ID** (used for the later app and for App Check/API-key restrictions): `com.samvad.messenger`.
- **Backend-first, one sub-project at a time** (owner's choice): B1, then B2, in order.

## 2. Firebase-first architecture

| Concern | Service | Used from | Sub-project |
|---|---|---|---|
| Phone OTP and sessions | Firebase Authentication | client SDK | B1 |
| Primary database | Cloud Firestore | client SDK under security rules | B1 (profiles), B2+ |
| Presence, typing, transient signals | Realtime Database | client SDK | B5 |
| Media and documents | Cloud Storage for Firebase | client SDK under Storage rules | B4 |
| Push notifications | Firebase Cloud Messaging | trusted sender (Function/Ktor) | B5 |
| Event-driven and callable backend work | Cloud Functions for Firebase | triggers and callables | B1 (account callables), B2+ |
| Client access protection | Firebase App Check | enforced per service | B1 |
| Crash/analytics/performance/config | Crashlytics, Analytics, Performance, Remote Config | client SDK | later (client phase) |
| Local development and tests | Firebase Emulator Suite | developer machine / CI | B1 |

Rules:

- **No other primary datastore.** MongoDB and Supabase are not used by the new backend; they remain only as legacy parts of the Flutter app until removal.
- **Clients talk to Firebase directly** for supported operations. Firestore reads/writes are not proxied through a custom server without a specific security or business requirement.
- **Admin SDK only in trusted server environments** (Cloud Functions, and Ktor on Cloud Run later). Never in an app, a repository or a build artifact. Server code uses Application Default Credentials / the runtime service account; no service-account JSON is created or committed.
- **Cloud Function when:** event-driven or short request/response work tied to Firebase data (Firestore/Auth triggers, notification fan-out, cleanup, account deletion, idempotent retries).
- **Kotlin + Ktor on Cloud Run when:** the logic does not fit a Function, for example custom WebSocket signaling, TURN credential minting, abuse-sensitive endpoints needing rate limiting and richer middleware, or JVM-only integrations. Ktor verifies Firebase ID tokens with revocation checking and App Check tokens on every request. **No Ktor service exists in B1**; B6 must justify each endpoint against this list.
- **E2EE is not claimed.** Nothing here encrypts message content end to end and no document, string or log may imply it. TLS, Firestore rules and encryption at rest are not E2EE. B8 and the client E2EE work design and verify it first.

## 3. Goals and non-goals

### Goals

- A `firebase/` project that runs entirely on the Emulator Suite: Auth, Firestore, Realtime Database, Storage, Functions.
- Deny-by-default Firestore, Storage and Realtime Database rules, with the `users`, `profiles` and `devices` rules fully specified and tested.
- Two callable Cloud Functions: `deleteAccount` and `signOutAllDevices`.
- App Check enforcement configured for callables in code, and the rollout procedure documented for the services that are configured in the console.
- A test suite that proves authorization, field validation, deletion and idempotency, including negative cases.
- Setup, environment and deployment documentation.
- A written client contract for the later Android app.

### Non-goals (later sub-projects)

Messaging, groups, media, presence, notifications sending, Ktor, E2EE, calls, stories; and the whole Android app.

## 4. Repository conflicts and file changes

### 4.1 Conflicts between the repo and the Firebase architecture

| # | Conflict | Where | Resolution |
|---|---|---|---|
| 1 | Supabase is the Flutter app's data layer | `lib/samvad_app.dart`, `lib/screens/chat_list_screen.dart`, `lib/screens/chat_screen.dart`, `pubspec.yaml`, `supabase/` | Left untouched; removed post-parity |
| 2 | Node + MongoDB + Socket.io backend | `backend/` | Left untouched; replaced by Firebase + Functions (+ Ktor if justified); removed post-parity |
| 3 | Credentials tracked in git | `backend/.env`, both `google-services.json` files (commit `b4d56c4`); possible credential-like content in `lib/samvad_app.dart`, `backend/server.js`, `PRODUCTION_SETUP.md` | Separate incident: `docs/security/incident-leaked-credentials.md` |
| 4 | Package ID inconsistency | Flutter `build.gradle.kts`, staged `MainActivity.kt`, old zingify ID | New backend/App Check config uses `com.samvad.messenger`; Flutter shell untouched |
| 5 | Flutter-centric CI | `.github/workflows/build.yml`, `.gitlab-ci.yml`, `bitrise.yml`, `codemagic.yaml`, `appcenter.json` | Not edited |
| 6 | Mid-rename working tree | 73 uncommitted changes | Preserved (§4.3) |
| 7 | No Firebase configuration | no `firebase.json`, rules, indexes, Functions | Created under `firebase/` |
| 8 | Toolchain gaps | JDK 8 only, `JAVA_HOME` unset, Node 25 (non-LTS), no `firebase-tools` | See §5 |

### 4.2 Exact files and directories B1 adds

All new. No existing file is modified or deleted.

```
firebase/
  .gitignore
  .firebaserc.example
  firebase.json                    (emulator ports, rules/functions/indexes paths)
  firestore.rules
  firestore.indexes.json           (empty: no composite indexes required)
  storage.rules                    (deny all)
  database.rules.json              (deny all)
  package.json                     (pins firebase-tools and test dependencies)
  functions/
    package.json, tsconfig.json
    src/index.ts                   (exports)
    src/deleteAccount.ts
    src/signOutAllDevices.ts
    src/lib/                       (auth/recency checks, logging helper)
    test/
  rules-tests/
    firestore.*.test.ts            (users, profiles, devices, default-deny)
    storage.rules.test.ts
    database.rules.test.ts
  test-support/                    (emulator constants, Auth-emulator phone sign-in helper, callable HTTP helper)
  README.md                        (setup, environments, deployment, App Check rollout)
docs/security/incident-leaked-credentials.md   (already committed)
docs/ARCHITECTURE.md                           (already committed; updated in this revision)
```

Optional, decided at plan time: `.github/workflows/firebase-ci.yml` (new file; `build.yml` stays untouched).

### 4.3 Git safety

- The 73 existing uncommitted changes are never reset, stashed, restored, overwritten or deleted.
- Every commit uses explicit paths (`git add -- <path>`, `git commit -- <path>`), so staged-but-unrelated files (e.g. `android/app/src/main/kotlin/com/samvad/messenger/MainActivity.kt`) are not swept in.
- Earlier spec commits are kept; revisions are new commits, never amend or rebase.
- Root `.gitignore`, `README.md` and all Flutter files are not edited; ignore rules live in `firebase/.gitignore`.
- No history rewrite or force-push is performed by this work.

## 5. Toolchain

| Tool | Requirement |
|---|---|
| JDK | **Eclipse Temurin 21 (LTS), required.** The Firestore, Realtime Database and Storage emulators run on the JVM and `firebase-tools` 15.33.0 refuses Java below 21. The installed JDK 8 is too old. Android Studio's bundled JDK 25 should not be used as the build JDK for Gradle 8.x projects later (Gradle 8.14 runs on Java up to 24). |
| Node.js | **24 LTS (24.12 or newer)** for Cloud Functions (runtime `nodejs24`), rules tests and vitest. The machine has Node 25.8.1, which vitest 5 does not support, so Node 24 LTS must be installed. |
| `firebase-tools` | Pinned devDependency in `firebase/package.json`, not global. |
| Pinned versions (looked up 2026-10-09) | `firebase-tools` 15.33.0, `firebase-functions` 7.4.0, `firebase-admin` 14.5.0, `firebase` 13.0.0, `@firebase/rules-unit-testing` 6.0.0, `typescript` 5.9.3, `vitest` 5.0.3, `@types/node` 24.19.1. Lockfiles are committed. |
| Language | TypeScript for Functions (Cloud Functions for Firebase supports Node.js and Python, not Kotlin). Kotlin enters with Ktor in B6. |

Installing a JDK or Node version changes the machine, so it happens only with the owner's explicit OK at the start of the build phase. It does not alter the repository or the JDK 8 install. Library versions (Firebase Functions SDK, Admin SDK, emulator test library) are looked up and pinned together at plan time.

## 6. Authentication backend

Firebase Authentication performs OTP sending and verification; the backend does not re-implement it and there is no OTP bypass. B1 configures, constrains and tests what surrounds it.

### 6.1 Provider and account policy

- Phone is the only enabled sign-in provider. Anonymous and every other provider are disabled in the console (documented in the README) and rejected by rules (§7.2).
- SMS region allow-list set in the console before any live use (§11).
- Test phone numbers are used only in non-production projects and in the emulator.

### 6.2 Server-enforced abuse controls

Authoritative limits are Firebase's per-number and per-project SMS quotas, App Check, the SMS region policy and billing/quota alerts. The client cannot be trusted to enforce them. Client-side guards (cooldown, retry counts) are UX only and are specified in the client contract (§8).

### 6.3 Account lifecycle on the server

| Operation | Mechanism | Behavior |
|---|---|---|
| Create | client writes `users/{uid}` and `profiles/{uid}` as one batched write under rules | document ID is always `request.auth.uid`; `phoneE164` must equal `request.auth.token.phone_number` |
| Register device | client writes `users/{uid}/devices/{deviceId}` | owner-only, field allow-list |
| Logout (this device) | client deletes its device doc, then signs out | allowed by rules; no server call |
| Sign out everywhere | callable `signOutAllDevices` | Admin `revokeRefreshTokens(uid)`; stamps `users/{uid}.tokensRevokedAt`; marks device docs `revokedAt`; rejects calls made within 60 seconds of the last revocation |
| Delete account | callable `deleteAccount` | requires `auth_time` within 5 minutes (fresh OTP sign-in); writes the server-only tombstone `deletedAccounts/{uid}`, recursively deletes `users/{uid}` (incl. `devices`) and `profiles/{uid}`, then deletes the Auth user (user-not-found counts as success); idempotent on retry |

Both callables:

- reject unauthenticated callers and non-phone sign-ins;
- enforce App Check with replay protection (`enforceAppCheck` and `consumeAppCheckToken` are both true except when `FUNCTIONS_EMULATOR` is `true`), so the client must send limited-use App Check tokens;
- run in region `asia-south1` (a single constant; clients must use the same region);
- validate that no client-supplied UID is used; the target is always `request.auth.uid`;
- are retry-safe: a repeat after partial failure completes the remaining steps without error;
- log structured, PII-free events (no phone numbers, tokens or document contents).

Token revocation limit: `revokeRefreshTokens` stops new ID tokens from being issued, but already-issued ID tokens stay valid until they expire (up to about an hour). Rules cannot assume instant revocation; server code that needs it must verify with revocation checking (`checkRevoked`). This is documented, not hidden.

Why the tombstone: a deleted user's already-issued ID token stays valid for up to about an hour, and security rules cannot see whether the Auth user still exists. Without the tombstone that token could re-create `users`, `profiles` or `devices` documents after deletion. If `deleteAccount` fails after writing the tombstone, the user cannot re-onboard until a retry completes; the retry is safe.

Data owned by later sub-projects (messages, media, memberships) adds its own deletion steps to `deleteAccount` in those specs.

### 6.4 Error handling

Callables return typed errors using callable error codes (`unauthenticated`, `permission-denied`, `failed-precondition` for stale `auth_time` or cooldown, `internal`) with generic, PII-free messages. Unexpected failures are logged with a correlation ID and surfaced as `internal`.

## 7. Data model, rules and indexes

### 7.1 Collections

**`users/{uid}`** — private to the owner.

| Field | Type | Constraints |
|---|---|---|
| `phoneE164` | string | on create must match E.164 (`^\+[1-9][0-9]{6,14}$`) and equal `request.auth.token.phone_number`; immutable |
| `consent` | map `{version:int, acceptedAt:timestamp}` | `acceptedAt == request.time`; version may only increase |
| `createdAt` | timestamp | `== request.time` on create; immutable |
| `tokensRevokedAt` | timestamp or absent | server-only (Admin SDK) |

**`profiles/{uid}`** — the only user data other signed-in users can read, one document at a time.

| Field | Type | Constraints |
|---|---|---|
| `displayName` | string | 1–64 chars and contains a non-whitespace character |
| `about` | string | ≤ 140 chars |
| `photoUrl` | null | must be null in B1; B4 restricts it to the app's own Storage paths |
| `updatedAt` | timestamp | `== request.time` |

**`deletedAccounts/{uid}`** — server-only tombstone, written by `deleteAccount`; contains only `deletedAt` (timestamp). No client may read or write it. Permanent in B1 (a UID is not PII on its own and Firebase never reuses UIDs); a TTL policy is a B9 option.

**`users/{uid}/devices/{deviceId}`** — one doc per install, owner-only. `deviceId` is a random UUID generated by the client at first launch; no hardware identifiers.

| Field | Type | Constraints |
|---|---|---|
| `platform` | string | `"android"` |
| `appVersion`, `osVersion` | string | ≤ 32 chars each |
| `createdAt` | timestamp | immutable |
| `lastSeenAt` | timestamp | `== request.time` on update |
| `fcmToken` | string or null | owner-writable; populated from B5 |
| `revokedAt` | timestamp or absent | server-only |

### 7.2 Security rules (required behavior)

- Default deny: `match /{document=**} { allow read, write: if false; }` and nothing broader.
- Every allow requires `request.auth != null` and `request.auth.token.firebase.sign_in_provider == 'phone'`.
- `users/{uid}`: owner-only `get`; `list` denied; create/update only when `request.auth.uid == uid`, field allow-list (`hasOnly`), type/length checks and the immutability rules above; client delete denied.
- `profiles/{uid}`: signed-in users may `get`, never `list`; owner-only create/update with field allow-list; client delete denied.
- `users/{uid}/devices/{deviceId}`: owner-only read/create/update/delete; `deviceId` must be a lowercase UUID; clients cannot set `revokedAt`.
- `deletedAccounts/*`: no client access of any kind.
- Creates of `users/{uid}`, `profiles/{uid}` and `devices/*` are additionally denied when `deletedAccounts/{uid}` exists.
- Contact discovery (finding users by phone number) is not enabled. Phone numbers are readable only by their owner and trusted server code; discovery is designed in B6 as a rate-limited server-mediated path.
- Storage rules: deny all. Realtime Database rules: deny all. Placeholders until B4 and B5. Note for B5: RTDB rules cannot read Firestore, so membership checks for presence/typing need a mirrored membership structure or Function-mediated writes; decided there.

### 7.3 Indexes

All access in B1 is single-document get by UID, so **no composite indexes are required**. `firestore.indexes.json` is committed empty. The emulator does not enforce composite indexes, so this cannot be proven by running queries; the test asserts the file is empty and that the rules allow no `list` outside the owner's own `devices` collection (an unordered, unfiltered read). Indexes for message ordering and conversation lists are defined in B2 together with the queries that need them.

### 7.4 Retention

B1 data lives until the owner deletes the account (`deleteAccount` removes it). Device docs with a `lastSeenAt` older than a retention window are cleaned up by a scheduled function once a retention period is chosen (policy question deferred to B9; no automatic expiry in B1).

## 8. Client contract for the future Android app

The backend is complete and tested without a UI. When the Android phase starts, the app must satisfy this contract; it is also the acceptance checklist for that phase.

- Use Firebase phone authentication only; no OTP bypass in release builds. Debug builds use Firebase test numbers against the Auth emulator.
- Validate and format numbers with libphonenumber (E.164), India (+91) default, all international codes.
- UX guards only: 60-second resend cooldown, at most 3 resends per verification session, at most 5 verification requests per number per hour, back-off on quota errors, forced new code after 5 wrong attempts.
- Map Firebase errors to typed outcomes: invalid number, invalid code, session/code expired (rely on Firebase's expiry error, no hardcoded TTL), too many requests/quota, network, App Check/reCAPTCHA failure, disabled user, unknown.
- Keep `verificationId` and the resend token in memory/`SavedStateHandle` only; never persist or log them.
- Show Terms and Privacy links with an explicit consent step; write `consent.version` and the server timestamp.
- Create `users/{uid}` and `profiles/{uid}` in one batched write; register a device doc; delete it on logout.
- Re-authenticate (fresh OTP) before calling `deleteAccount`.
- App Check: Play Integrity in staging/release, Debug provider in debug. Send a limited-use App Check token (`getLimitedUseToken`) with `deleteAccount` and `signOutAllDevices`, and call functions in region `asia-south1`.
- Analytics and Crashlytics collection off by default and enabled only after consent; never include phone numbers, OTPs, tokens or message text.
- Manifest: `allowBackup=false`, data-extraction rules exclude everything, no cleartext traffic (debug emulator host excepted).
- No E2EE wording anywhere until it is implemented and verified.

## 9. Security review

| Asset | Risk | Control in B1 |
|---|---|---|
| Phone number (PII) | exposure to other users or logs | stored only in `users/{uid}` (owner-only) and the Auth record; never in `profiles`, logs or function output; no contact discovery yet |
| Profile data | tampering, enumeration | owner-only writes with field allow-list; `get`-only for others; no `list`; UIDs unguessable; `photoUrl` forced null (no tracking-pixel URLs) |
| ID / refresh tokens | theft, replay | managed by the Firebase SDK; `signOutAllDevices`; revocation checks in server code; revocation latency documented |
| Device sessions | stale or hijacked sessions | owner-only docs, `revokedAt` server-only. **Limit:** Firebase revokes refresh tokens per user, not per device; per-device revocation needs a device-bound session design in B8 |
| SMS pumping / quota abuse | cost and lockout | App Check, SMS region allow-list, quotas, budget alerts (§11); client guards are UX only |
| Admin credentials | compromise | no service-account JSON anywhere; runtime service account only; nothing in the repo or any app |
| Scripted/modified clients | unauthorized API use | App Check on callables (code) and on Firestore/Storage/RTDB (console) |
| Logs | PII leakage | structured logs without phone numbers, tokens, OTPs or document bodies |

**App Check rollout:** Play Integrity for staging/release, Debug provider for development (tokens registered per developer, never committed). On a live project, start in monitor mode, then enforce for Firestore, Storage, Realtime Database and callable Functions; Authentication enforcement is enabled where the console supports it for the project's configuration. The Emulator Suite does not enforce App Check, so enforcement is verifiable only on a live staging project (§10).

## 10. Testing

### Automated (must pass for acceptance, all on the Emulator Suite)

- **Firestore rules** (`@firebase/rules-unit-testing`), including negative cases:
  - unauthenticated and non-phone-provider requests denied on every path;
  - user A cannot read or write `users/B`, `users/B/devices/*`, or write `profiles/B`;
  - user A can `get` but not `list` `profiles`;
  - `users/{uid}` rejects mismatched or changed `phoneE164`, changed `createdAt`, unknown fields, wrong types, oversized strings, client-set `tokensRevokedAt`/`revokedAt`, and client delete;
  - `profiles` rejects non-null `photoUrl`;
  - any unnamed collection is denied;
  - creates of `users`, `profiles` and `devices` are denied once `deletedAccounts/{uid}` exists, and clients cannot read or write `deletedAccounts`;
  - `firestore.indexes.json` is empty and the rules allow no `list` outside the owner's own `devices` collection.
- **Storage and Realtime Database rules:** deny-all verified for authenticated and unauthenticated users.
- **Callable Functions** (run against the Functions + Auth + Firestore emulators with real ID tokens from the Auth emulator):
  - both reject unauthenticated callers and non-phone sign-ins;
  - `deleteAccount` rejects a stale `auth_time`, removes `users/{uid}` incl. devices, `profiles/{uid}` and the Auth user, and is idempotent when retried after a simulated mid-way failure;
  - `signOutAllDevices` revokes tokens, stamps `tokensRevokedAt`, marks devices `revokedAt`, enforces the 60-second cooldown;
  - the target UID is always the caller's, even if the request payload names another UID.
- **End-to-end sign-in flow on the Auth emulator:** a script requests a code for a test number, reads the code from the emulator, signs in, performs the client-contract writes (profile, device), calls `signOutAllDevices`, then `deleteAccount`.
- **Log hygiene test:** the emulator console output from the end-to-end run is scanned for the test phone number and the issued tokens (none may appear); a positive control requires real function log lines (for example the `account_deleted` event) to be present, so the check cannot pass vacuously.

### Acceptance criteria

1. The full suite passes with a single command from `firebase/` (documented in the README).
2. `firebase emulators:start` brings up all five emulators from the checked-in config.
3. No secret or Admin credential is tracked in any new file.
4. `firebase/README.md` documents project creation, app registration, phone-auth enablement, rules/function deployment, environments, and the App Check rollout.
5. The 73 pre-existing uncommitted changes are byte-for-byte unchanged and earlier spec commits are still in history.

### Not verifiable without the owner's live Firebase project (reported as "setup still required")

Real SMS delivery, Play Integrity attestation, App Check enforcement, SMS region policy, deployed rules and functions, billing and quota alerts.

## 11. Firebase configuration prerequisites (owner actions)

1. Create the Firebase projects (suggested names `samvad-dev`, `samvad-staging`, `samvad-prod`) and link billing; Blaze is required for Cloud Functions and recommended for production phone auth.
2. Register the Android app `com.samvad.messenger` per environment with debug and release SHA-1/SHA-256 fingerprints (needed for App Check and API-key restriction even before the app exists).
3. Authentication: enable Phone; disable Anonymous and unused providers; add test numbers for non-production; set the SMS region allow-list.
4. Create Firestore (production mode), Realtime Database and Storage with default-deny rules; choose regions deliberately because they cannot be changed later.
5. App Check: register Play Integrity (needs a Play-distributed build, so enforcement may wait for an internal track) and Debug tokens; monitor, then enforce.
6. Restrict API keys by package name + SHA-1 and by API.
7. Set budget alerts and quotas for SMS, Firestore and Functions; add alerts on auth and quota anomalies.
8. Deploy rules and functions with the pinned `firebase-tools` per environment alias.
9. Cloud Run, Secret Manager and Ktor configuration wait for B6.
10. Provide Terms of Service and Privacy Policy text and URLs (needed by the client phase).
11. Treat the leaked legacy credentials as a separate incident (runbook in §4.1).

## 12. Risks

- **Toolchain:** emulators and tests will not run until JDK 21 and Node 24 LTS are installed (§5).
- **Emulator vs. reality:** the Auth emulator skips SMS, Play Integrity and App Check, so passing emulator tests does not prove production behavior (§10).
- **Token revocation latency:** up to about an hour for already-issued ID tokens (§6.3).
- **Per-device revocation unavailable** until B8 (§9).
- **No UI for manual checks:** correctness rests on automated tests and the end-to-end script; the client contract (§8) guards against drift when the app is built.
- **Version drift:** Functions SDK, Admin SDK, emulator and test library versions must be pinned together at plan time.
- **Legacy credentials already exposed:** rotation by the owner is urgent and independent of this work.
