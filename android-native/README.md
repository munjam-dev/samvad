# Samvad — native Android app

Kotlin + Jetpack Compose client for Samvad Messenger (`com.samvad.messenger`), built on the
Firebase backend in [`../firebase`](../firebase). Design: spec rev 4
([`docs/superpowers/specs/2026-10-13-android-design-system-client.md`](../docs/superpowers/specs/2026-10-13-android-design-system-client.md))
and the v2 green identity explored on the design canvas.

## Modules

| Module | What | Builds without Android SDK |
|---|---|---|
| `:core` | Pure Kotlin: design tokens + WCAG contrast test, phone validation (libphonenumber, +91 default), OTP guards from the client contract, chat logic (IDs, message rules, delivery state, chat list, timeline, time labels) | **Yes** — `./gradlew :core:test` |
| `:app` | Compose UI, Firebase data layer, navigation | No — included only when `local.properties` has `sdk.dir` (or `ANDROID_HOME` is set) |

## Build and run

Requirements: JDK 17+ (21 recommended), Android SDK platform 36, Android Studio or the Gradle wrapper (9.3.1).

```bash
./gradlew :core:test                      # JVM tests, any machine
./gradlew :app:assembleDebug              # needs the Android SDK
./gradlew :app:connectedDebugAndroidTest  # needs a device or emulator
```

### Against the Firebase Emulator Suite (no Firebase project needed)

```bash
cd ../firebase && npm install && npm --prefix functions install && npm run emulators
# in another shell:
./gradlew :app:installDebug -Psamvad.useEmulators=true      # Android emulator reaches the host at 10.0.2.2
```

Sign in with any number; the Auth emulator prints the SMS code in its terminal. Debug builds use the
`.debug` application-ID suffix and the App Check debug provider.

### Against a real Firebase project

1. Create the project and register `com.samvad.messenger` (and `com.samvad.messenger.debug`) with SHA-1/SHA-256 — see `firebase/README.md` → owner checklist.
2. Download `google-services.json` to `app/` (gitignored — never commit it).
3. Deploy rules, indexes and functions from `firebase/`.
4. Set real Terms/Privacy URLs (`TERMS_URL`, `PRIVACY_URL` in `app/build.gradle.kts`).

Without `google-services.json` and without `-Psamvad.useEmulators=true`, the app shows a "Firebase setup required" screen instead of crashing.

## What the app does today

- Splash (Android 12 SplashScreen API, pinwheel mark), 3-page onboarding with skip.
- Phone sign-in with Firebase Auth: country picker (India first), as-you-type validation, consent, OTP with auto-retrieval, 60 s resend countdown, 3 resends per session, 5 codes per number per hour, new code after 5 wrong attempts, typed error messages.
- Profile setup (name, optional about) — one question per screen with progress; no gender or birth date.
- Home in three switchable layouts (Clean / Bold header / Card & bento): live chat list from Firestore, pinned first, unread badges, mute/pin indicators, filters (All / Unread / Groups / Favorites), search, unread summary, recent-people row, offline banner, error + retry, first-use empty state, floating nav and compose button.
- Conversation: paged history (30 at a time), date separators, grouped bubbles, sending/sent/delivered/read/failed states, tap-to-retry with the same message ID, replies, reactions, edit (15 min), delete for everyone, copy, pin/mute/archive, jump-to-latest, read receipts while visible.
- New chat: contacts permission explained first; matches via the rate-limited `lookupPhoneNumbers` callable; start by number; invite via share sheet.
- Settings: edit profile, theme (system/light/dark/true black), home layout, honest privacy note (no E2EE claim), sign out, sign out of all devices, delete account with a fresh SMS code.
- Calls and Communities tabs show honest "on the way" states — no mock data.

## Design system

Colours, spacing and radii live in `:core` (`SamvadPalette`, `Spacing`, `Radii`) and are mapped to
Material 3 in `ui/theme`. Screens never use hex literals. `ContrastTest` checks every declared
foreground/background pair in light, dark and AMOLED (≥ 4.5:1 text, ≥ 3:1 UI). Brand green
`#12A150` is used for fills, icons and large type; small white text sits on the text-safe `#0F8443`.
Typeface: Plus Jakarta Sans (SIL OFL 1.1, `licenses/OFL-PlusJakartaSans.txt`), Devanagari falls back to system Noto.

## Deliberate choices

- **No Hilt / Room yet.** A small `AppContainer` replaces Hilt until the build can be verified end to end; Firestore's persistent cache provides offline history, so Room adds nothing yet.
- **No fake features.** Media, voice notes, calls, stories, communities and push notifications are not shown as working; they wait for backend sub-projects B4–B7.
