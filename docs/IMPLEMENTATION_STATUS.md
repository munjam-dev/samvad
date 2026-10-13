# Samvad — implementation status and release readiness

Updated: 2026-10-13. Branch `claude/clever-pasteur-6shc8p`.

## Verification actually run

| Check | Result |
|---|---|
| `firebase/`: `npm test` on the Emulator Suite (Auth, Firestore, RTDB, Storage, Functions) | **94 tests pass** (15 files) |
| `firebase/`: `npm run test:e2e:logs` (sign-in → profile → device → sign out everywhere → delete; scan function logs) | **Pass**; positive and negative controls verified |
| `android-native/`: `./gradlew :core:test` | **20 tests pass** (contrast in 3 schemes, phone validation, OTP guards, chat logic) |
| GitHub Actions `Samvad CI` run #3 (`.github/workflows/samvad-ci.yml`) | **Green**: Firebase suite + e2e log check, `:core:test`, `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` (0 errors). Debug APK uploaded as artifact `samvad-debug-apk` |
| Instrumented UI tests (`connectedDebugAndroidTest`) | **Not run** — need an emulator/device |

## Completed

- Design canvas (foundations, brand directions, v1 screens, v2 three-layout home, pinwheel mark redraw, components).
- Spec rev 4 (Android design system + A-series roadmap); B2 messaging spec.
- B1 backend: deny-by-default rules for users/profiles/devices, deleted-account tombstone, `deleteAccount` and `signOutAllDevices` callables (App Check + replay protection, asia-south1), PII-free logging, README with owner checklist and App Check rollout.
- B2 backend: direct conversations, messages (client UUIDs, edit window, soft delete, reactions), member state, `onMessageCreated` trigger (lastMessage + unread, idempotent), `lookupPhoneNumbers` (rate-limited), account deletion leaves conversations.
- Android `:core` module (tested) and full `:app` source: onboarding, phone auth, profile setup, three home layouts, conversation, new chat, settings, theme/AMOLED, launcher/monochrome/splash icons.

## Partially complete

| Item | State |
|---|---|
| Android `:app` | Compiles, assembles and passes lint in CI; not yet exercised on a device (manual run against the emulators still needed) |
| Accessibility | Content descriptions, 48 dp targets, sp type, contrast test done; TalkBack walkthrough and 200 % font-scale check need a device |
| Localization | All UI strings in `strings.xml`; time labels in `:core` are English-only; Hindi not translated |
| Contact discovery | Interim callable sends normalised numbers for matching (not stored); hashed discovery is B6 |
| Typing / presence | Not implemented (B5) |

## Blocked in this environment

- **Android SDK / Google Maven in the cloud session:** `dl.google.com` is denied by this environment's network policy, so the app is built in GitHub Actions instead (green). Allowing `dl.google.com` would let future sessions build locally too.
- **Real Firebase project:** SMS delivery, Play Integrity, App Check enforcement, deployed rules/functions, quotas — need the owner's project (`firebase/README.md` checklist).

## Not started (roadmap)

Groups (B3/A3), media and voice notes (B4/A4), presence, typing and push notifications (B5/A5), calls with WebRTC + TURN and hashed discovery (B6/A6), stories, communities, scheduled/saved messages (B7/A7), E2EE (B8), monitoring and hardening (B9). Hilt/Room adoption, Crashlytics/Performance (after consent).

## Release-readiness checklist

- [x] Remove the legacy code (Flutter, Node/Mongo, Supabase, Sanraksha, old CI) and `backend/.env` from the working tree (2026-10-13).
- [ ] **Rotate the leaked legacy credentials** — they remain in git history (`docs/security/incident-leaked-credentials.md`); optionally purge history afterwards.
- [x] Build `:app` with the SDK and pass lint (CI run #3).
- [ ] Run `connectedDebugAndroidTest` on an emulator/device.
- [ ] Manual pass against the emulators: sign-in, profile, two users chatting offline/online, retry, edit/delete, read receipts, delete account.
- [ ] TalkBack and 200 % font-scale review; dark/AMOLED visual review.
- [ ] Create Firebase dev/staging/prod; Phone provider only; SMS region allow-list; Firestore in asia-south1; deploy rules, indexes, functions.
- [ ] App Check: Play Integrity + debug tokens; monitor, then enforce.
- [ ] Real Terms of Service and Privacy Policy URLs; Play Data safety form (phone number, contacts used for matching, messages).
- [ ] Release signing (Play App Signing); R8 shrinking verified; `allowBackup=false` confirmed.
- [ ] Budget alerts and quotas; crash reporting after consent.
- [ ] Push notifications (B5) before public launch — without them messages arrive only while the app is open.

## Production risks

1. Android app not yet run on a device (highest): it builds and passes lint in CI, but runtime behaviour (phone auth, Firestore listeners, layouts at large font scales) is untested.
2. No push notifications yet: users won't learn about new messages in the background.
3. Contact matching sends raw (normalised) numbers to the callable; acceptable interim, replace with hashing in B6.
4. ID-token revocation latency (up to ~1 h) after sign-out-everywhere or deletion.
5. Unread counters rely on the trigger; a missed trigger leaves a stale badge until the next message (idempotent, but not reconciled).
