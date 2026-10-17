# Implementation plan v2 (ten phases)

Audited 2026-10-15 against branch `phase-3`. Nothing is rebuilt: each phase builds on what exists.
Status: **Have** (built and tested), **Partial**, **Missing**, **Blocked** (needs a decision or service).

Stack: Android (Kotlin, Compose, Material 3; `ui/*`, `data/*`, `core/` for pure logic), Firebase (Auth, Firestore,
Storage, RTDB, Functions in `firebase/functions/src`, rules in `firebase/*.rules`). Backend tests run on the
emulators (`npm test`), app logic tests in `:core`.

## Progress (updated 2026-10-17)

| Phase | State |
|---|---|
| 1 Onboarding, profile, language | **Built.** Username (backend deployed, UI, live availability check), language picker, English/Hindi/Telugu/Tamil (AI-written, native review pending: `docs/LOCALIZATION.md`). Username check on a device is blocked only by the owner registering the App Check debug token. |
| 2 Core messaging | **Built.** Drafts, multi-select, pinned messages (backend deployed). Not built: contact and video sharing, global message search. |
| 3 Privacy and security | In progress: profile visibility, group-add permission, contact discovery switch, linked devices, appeals. E2EE is **not** started (licence gate). |
| 4 Personal Vault | In progress (local-first, Drive backup blocked on the scope decision). |
| 5 HD media, weak networks | In progress (original quality, resumable uploads, download progress, network hints). Direct device-to-device transfer blocked. |
| 6 Groups and communities | Not started (backend-heavy; runs after Phase 3 because the backend emulators are shared). |
| 7 Meetings | Blocked on the calling decision. |
| 8 Moments | **Built**, including report, photo text and pen, capture note. Notifications for new moments not built. |
| 9 Scam Shield | **Built** on-device (link and text risk analysis, first-contact banner). |
| 10 Tests and release | Core and backend tests pass; instrumented tests, accessibility audit and release build still to do. |

## Phase 1: onboarding, profile, language

| Item | Status | Where |
|---|---|---|
| Splash under a second | Have | `res/values/themes.xml`, `ui/app/SamvadRoot.kt` |
| Three onboarding slides with Skip and Continue | Have | `ui/onboarding/OnboardingScreen.kt` (check copy covers messaging, privacy, groups) |
| Phone auth and OTP | Have | `ui/auth/*`, `data/AuthRepository.kt` |
| Display name (required), about | Have | `ui/setup/ProfileSetupScreen.kt`, `data/AccountRepository.kt`, `profiles` rules |
| **Username**: optional, unique, atomic | **Missing** | new `usernames/{name}` reservation docs, `setUsername` and `checkUsername` callables, rules, `:core` format rules |
| **Language selection** (screen after profile, and in Settings) | **Missing** | `AppCompatDelegate`/per-app locales, `res/xml/locales_config.xml`, new screen, `SettingsScreen.kt` |
| Indian-language translations | **Missing** | `res/values-hi/` first. Only languages that are translated **and** checked are offered |

Language scope, stated honestly: about 480 UI strings exist. Hindi is the first language to ship. Telugu, Tamil,
Bengali, Marathi and the others will be added one at a time, each reviewed by a native speaker, and the picker
shows only those that are finished. Urdu needs right-to-left testing before it is offered.

## Phase 2: core messaging

| Item | Status | Where |
|---|---|---|
| Replies, reactions, edit (15 min), delete for everyone, forward, copy, typing, receipts, timestamps | Have | `ui/chat/*`, `data/ChatRepository.kt` |
| Photos, files, voice notes | Have | `data/MediaRepository.kt`, `ui/chat/MediaViews.kt` |
| Starred (saved) messages, archive, mute, pin chat, unread filter | Have | `ui/chat/StarredScreen.kt`, `ui/home/*` |
| Search in a chat | Have | `core/.../ChatSearch.kt` |
| Disappearing messages | Have, not checked on a device | `firebase/functions/src/disappearing.ts`, `ui/chat/DisappearingViews.kt` |
| Message selection (multi-select) | Missing | `ConversationScreen.kt` |
| Pinned messages (in a chat) | Missing | rules, `ConversationViewModel` |
| Global message search | Missing | needs a local index (messages are not all on the device) |
| Drafts | Missing | `data/Preferences` or a small Room table |
| Contact sharing, video sharing | Missing | `MediaRules`, `storage.rules`, composer |
| Pending, retry, duplicate prevention | Have (client-generated ids, Firestore offline cache) | `ChatRepository.send` |

## Phase 3: privacy, encryption, account security

| Item | Status | Notes |
|---|---|---|
| App lock, biometric, notification previews, two-step PIN, block, report, account deletion | Have | |
| Disappearing messages | Have | see Phase 2 |
| **End-to-end encryption** | Missing, library chosen | libsignal added to the build; **no message is encrypted**. See `docs/security/E2EE_DECISION.md`. **Release gate:** AGPL-3.0, lawyer must confirm before any distribution |
| Linked devices and sessions | Partial | `devices` collection, sign out everywhere. A per-device list and revoke screen is Missing, and real multi-device needs E2EE first |
| Profile visibility, contact-discovery controls, group-invite permissions | Missing | |
| Appeals and recovery flows | Missing | |

## Phase 4: Personal Vault (local-first)

All **Missing**. Needs Room (not yet a dependency), AndroidX Security / Keystore-wrapped keys, Storage Access Framework,
and **Google Drive API with explicit user authorization** for backup. Two decisions are needed first: the backup
recovery key design (passphrase or generated key shown to the user) and whether Drive scope `drive.file` (app-created
files only, but not visible in the user's normal Drive listing) or the visible-folder scope is acceptable, because
the visible-folder scope is a restricted scope that requires a Google security review before production.

## Phase 5: HD media and weak networks

| Item | Status |
|---|---|
| Data saver, photo auto-download settings, tap-to-download | Have (checked on the emulator: settings; not yet: placeholder) |
| Original Quality, resumable transfers, video sharing, progress UI | Missing. Firebase Storage uploads are already resumable; the app does not yet surface progress or resume across process death |
| Direct device-to-device transfer | **Blocked**: needs WebRTC data channels or Wi-Fi Direct plus signalling; cannot guarantee delivery when the receiver is offline |

## Phase 6: advanced groups and communities

Groups: Have (create, admins, invite links, mentions, polls). Missing: events, announcements, shared files tab,
topics, member approval, quiet hours, meeting scheduling. Communities: Missing entirely (data model, rules, UI).

## Phase 7: audio and video meetings

Missing and **Blocked** on an infrastructure decision (managed service such as LiveKit Cloud, or self-hosted
SFU plus TURN). Cost must be evaluated before production.

## Phase 8: Moments

Largely Have: Moments tab, text/photo/video/voice composer, audience (all, except, only), viewer with reactions and
private replies, viewers list, mute, delete, 24 h expiry, server-set audience and expiry. Production uploads work (the 403 was a missing Google Cloud service-agent role; photo moment verified on 2026-10-15, voice and video still to be re-checked). Missing: voice replies, "disable replies",
editing the audience after posting, report from the viewer, and a plain note that recipients can capture content.

## Phase 9: Scam Shield

Missing. Doable without reading message content on the server: on-device link checks, first-contact banner (uses
chat metadata only), and rate limits server-side. Verification badges are not added until a real verification process exists.

## Phase 10: tests and release

Have: 119 `:core` tests, 239+ backend tests, lint at 0 errors. Missing: instrumented UI tests, process-death and
airplane-mode tests, accessibility and font-scale audit, cost audit, release build with the libsignal licence gate resolved.

## Order of work

1. Phase 1: username (backend, rules, tests, then UI) then language selection with Hindi.
2. Phase 2 gaps: drafts, message selection, pinned messages, then contact and video sharing, then global search.
3. Phase 3 security foundation, with E2EE only after the licence answer.
4. Phase 4 Vault, Phase 5, Phase 6, Phase 9 in that order, Phase 7 after the calling decision.

## Decisions needed

- Licence answer on publishing the Android source (blocks shipping libsignal).
- Calling: managed service or self-hosted.
- Google Drive scope for Vault backup (see Phase 4).
