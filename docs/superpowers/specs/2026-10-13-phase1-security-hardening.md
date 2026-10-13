# Phase 1.4 — Client security hardening

Status: implemented on `claude/clever-pasteur-6shc8p`. Everything here is free: it uses Android platform
features only, with no new services.

## What the server already enforces (unchanged)

Phone-only sign-in, deny-by-default Firestore / RTDB / Storage rules, App Check with replay protection
on every callable, rate limits on phone lookup (500/day) and reports (20/day), blocks enforced in rules,
immutable and type-restricted media uploads, PII-free logs. This phase hardens the device side.

## Features

| Feature | Setting | Default | How |
|---|---|---|---|
| App lock | Privacy → App lock; lock immediately / after 1 min / after 30 min | off, 1 min | `BiometricPrompt` with strong biometrics or the phone's screen lock (weak biometrics or screen lock on Android 9–10) |
| Hide app in recents while locked | follows App lock | — | `setRecentsScreenshotEnabled(false)` on Android 13+, `FLAG_SECURE` before that |
| Block screenshots | Privacy → Block screenshots | off | `FLAG_SECURE` (no screenshots, recordings or recents preview) |
| Notification previews | Notifications → Show message preview | on | off shows "Samvad / New message"; the lock-screen public version never shows sender or text |
| Chat-link validation | — | — | notification extras must be a well-formed direct chat ID that contains the other UID |
| Tapjacking guard | — | — | the delete-account confirmation ignores touches passing through other apps' overlays |
| Release log stripping | — | — | R8 removes `Log.v` / `Log.d` calls (the app has none today; this guards future ones) |

### App lock rules (`:core` `AppLockPolicy`)

- Locked at process start when enabled, so a killed and restarted app always asks.
- On return from the background, locked when the elapsed time reaches the chosen timeout. The clock is
  `elapsedRealtime` (monotonic), so changing the phone's time cannot skip the lock; a negative elapsed
  time locks.
- Leaving for a picker or viewer the app itself opened (photo picker, document picker, file viewer,
  notification settings) gets at least 60 s, so "Immediately" does not demand an unlock after attaching a
  photo.
- Leaving for the system's PIN / pattern screen during an unlock never counts as going to the background.
- Turning app lock on or off requires a successful unlock. Turning it on is refused when the phone has
  no screen lock.
- While locked, the app underneath is hidden from screen readers, every touch is swallowed, the keyboard
  is closed and Back sends the app to the background.
- Samvad never sees or stores a PIN, pattern or biometric data. There is no separate Samvad PIN, so there
  is nothing to brute-force inside the app.

## Decisions

- **No app-wide `filterTouchesWhenObscured`:** it makes the app unusable with screen-dimmer apps. It is
  applied only to irreversible confirmations. Android 12+ also blocks untrusted overlays system-wide.
- **No custom root or emulator detection:** easily bypassed and prone to false positives. Play Integrity
  through App Check is the supported signal.
- **No encrypted SharedPreferences:** local preferences hold no secrets (theme, layout, flags, a random
  install ID). Messages cached by Firestore live in app-private storage, protected by Android file-based
  encryption; `allowBackup=false` keeps them out of adb and cloud device backups.
- **No certificate pinning:** Google rotates its certificates and advises against pinning them.

## Tests

`:core` `AppLockPolicyTest`: disabled, never backgrounded, each timeout boundary, a clock that went
backwards, the external grace period, and chat-link validation.

The biometric prompt, `FLAG_SECURE` and notification visibility need a device; see the manual checklist
in `docs/IMPLEMENTATION_STATUS.md`.
