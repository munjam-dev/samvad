# Samvad — implementation status and release readiness

Updated: 2026-10-13 (end of Phase 1). Branch `claude/clever-pasteur-6shc8p`.

## Verification actually run

| Check | Result |
|---|---|
| `firebase/`: `npm test` on the Emulator Suite (Auth, Firestore, RTDB, Storage, Functions) | **174 tests pass** at the end of Phase 1 (26 files) |
| `firebase/`: `npm run test:e2e:logs` (full account flow, then scan every function log line for phone numbers and tokens) | **Pass** |
| `android-native/`: `./gradlew :core:test` | **42 tests pass** |
| GitHub Actions `Samvad CI` on every push | **Green** through the end of Phase 1 (run #34); each push runs the Firebase suite, the log check, `:core:test`, `:app:assembleDebug`, `:app:testDebugUnitTest` and `:app:lintDebug`, and uploads the debug APK |
| Instrumented UI tests / manual device pass | **Not run** — need an emulator or phone (see checklist) |

## Phase 1 — completed

| # | Feature | Server enforcement | Spec |
|---|---|---|---|
| — | Phone sign-in, profiles, devices, account deletion, sign out everywhere | rules + callables (App Check, replay protection) | `2026-10-09-foundation-auth-design.md` |
| — | 1:1 chats: text, replies, edit (15 min), delete for everyone, reactions, unread, mute/pin/archive | rules + trigger | `2026-10-13-b2-messaging-design.md` |
| — | Push notifications (data-only FCM, per-chat, muted chats skipped, dead tokens pruned) | trigger | — |
| 1.1 | Typing, online / last seen, hide last seen | RTDB rules (members only) | `2026-10-13-phase1-presence-typing.md` |
| 1.2 | Photos, files, voice notes; viewer; upload retry | Storage rules (members, immutable, 16 MB, type allow-list, no APKs); EXIF stripped on device | `2026-10-13-phase1-media.md` |
| 1.3 | Block and report | rules deny messaging and presence both ways; reports server-only with 5-message evidence, 20/day, 90-day TTL | `2026-10-13-phase1-block-report.md` |
| 1.4 | App lock (fingerprint / face / screen lock), block screenshots, private notifications, deep-link validation, tapjacking guard | device-side (no secrets stored) | `2026-10-13-phase1-security-hardening.md` |
| 1.5 | **Two-step verification PIN** (SIM-swap protection) | every rule and callable refuses sessions that did not enter the PIN; scrypt hash; lockout; 7-day reset with owner alert | `2026-10-13-phase1-two-step-verification.md` |
| 1.6 | Export chat (save to Google Drive via share sheet), read receipts setting, forward (≤ 5 chats), profile photos | forwarded flag in rules; photos only via a function that validates JPEG and strips metadata; hidden from blocked users | `2026-10-13-phase1-whatsapp-parity.md` |

## Security summary (what protects users today)

- Deny-by-default Firestore, Realtime Database and Storage rules, all tested on the emulators.
- Phone sign-in only; optional two-step PIN enforced server-side; App Check with replay protection on
  every callable; rate limits on phone lookup (500/day), reports (20/day), photo changes (20/day), PIN
  guesses (5, then exponential lock up to 24 h).
- No public download links: every media and photo read goes through the SDK and the rules.
- Location metadata removed from photos (on device for chat photos; on the server for profile photos).
- Logs carry only correlation IDs, counts and outcomes; a CI check fails the build if a phone number or
  token appears in function output.
- App: optional app lock, screenshot blocking, notification previews off, no backups of app data,
  cleartext traffic only to the local emulator in debug builds.
- **Not end-to-end encrypted.** Messages are protected in transit (TLS) and at rest by Google, and the
  server can read them. End-to-end encryption with the Signal Protocol is Phase 4.

## Partially complete

| Item | State |
|---|---|
| Android app on a device | Builds, unit tests and lint pass in CI; no manual or instrumented run yet |
| Accessibility | Content descriptions, 48 dp targets, sp type, contrast tests; TalkBack and 200 % font check need a device |
| Localization | All UI text in `strings.xml`; Hindi and other Indian languages are Phase 3 |
| Contact discovery | Normalised numbers sent to a rate-limited callable (not stored); hashed discovery is Phase 4 |
| Google Drive backup | Export chat covers manual copies now; encrypted Drive backup is planned with E2EE (Phase 4), because history is server-synced until then |

## Blocked in this environment

- **Android SDK in the cloud session:** `dl.google.com` is blocked by this environment's network policy,
  so the app is built and linted in GitHub Actions (green).
- **A real Firebase project:** SMS delivery, Play Integrity, App Check enforcement, deployed rules and
  functions, custom-token minting (two-step) and quotas need the owner's project.

## Next (roadmap)

Phase 2 groups (up to 256, admins, invite links, mentions) and polls; Phase 3 Hindi + Indian languages,
data saver, saved/scheduled messages, search, calm mode; Phase 4 disappearing messages, hashed
discovery, Signal-Protocol E2EE and encrypted Google Drive backup; Phase 5 calls, status, communities,
linked devices; Phase 6 launch readiness.

## Owner checklist before going live

- [ ] **Rotate the leaked legacy credentials** — they remain in git history (`docs/security/incident-leaked-credentials.md`).
- [ ] Create Firebase dev/staging/prod on the Blaze plan (needed for Functions; free quotas apply); Phone provider only; SMS region allow-list (India first); Firestore, RTDB and Storage in or near `asia-south1`.
- [ ] Deploy rules, indexes and functions (`firebase/README.md`).
- [ ] IAM: give the Functions service account **Service Account Token Creator** on itself (two-step PIN sessions).
- [ ] Firestore TTL policy on `reports.expireAt`.
- [ ] App Check: Play Integrity + debug tokens; monitor, then enforce for Firestore, RTDB and Storage.
- [ ] Terms of Service and Privacy Policy URLs (mention report evidence and phone-number matching); Play Data safety form.
- [ ] Release signing (Play App Signing); verify R8 build.
- [ ] Budget alerts for SMS, Firestore, Functions and Storage.
- [ ] Manual pass on two phones against the emulators, then staging: sign-in, two-step on/off/reset, chat online/offline, media, block/report, app lock, export, forward, profile photo, delete account.
- [ ] TalkBack, 200 % font scale, dark and AMOLED review.

## Production risks

1. The app has not run on a device yet (highest): runtime behaviour of phone auth, BiometricPrompt, custom-token sign-in and media pickers is untested outside CI builds.
2. ID-token lifetime: revocation, block mirroring and two-step session changes take effect at the next token refresh (up to ~1 h) for already-issued tokens.
3. A blocked person can infer the block because their messages fail to send.
4. Orphaned chat media: a member can upload files without sending the message; there is no sweeper yet.
5. Unread counters rely on the trigger; a missed trigger leaves a stale badge until the next message.
