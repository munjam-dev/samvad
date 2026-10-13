# Building and running Samvad locally

This guide takes you from a fresh clone to the app running on an Android emulator or phone, against a
local Firebase backend. You need no Firebase project, no SMS costs and no API keys.

Branch: `claude/clever-pasteur-6shc8p`.

---

## 1. Install the tools (once)

| Tool | Version | Why |
|---|---|---|
| Android Studio | latest stable | builds and runs the app (bundles a JDK and the Android SDK) |
| Node.js | 24 LTS (24.12 or newer) | runs the Firebase emulators and the backend tests |
| JDK | 21 (Temurin) | the Firestore, Realtime Database and Storage emulators run on Java |
| git | any recent | |

In Android Studio's SDK Manager, install **Android SDK Platform 36** and a system image with Google
APIs for the emulator (API 34 or newer is fine; the app supports Android 8.0 / API 26 and up).

## 2. Get the code

```bash
git clone https://github.com/munjam-dev/samvad.git
cd samvad
git checkout claude/clever-pasteur-6shc8p
git pull
```

Layout:

```
android-native/   Android app (Kotlin, Jetpack Compose): :app and the pure-Kotlin :core module
firebase/         security rules, Cloud Functions (TypeScript), emulator tests
docs/             specs, implementation status, this guide
```

## 3. Start the local Firebase backend

In a terminal (leave it running):

```bash
cd firebase
npm install
npm --prefix functions install
npm run emulators
```

This builds the functions and starts Auth (9099), Firestore (8080), Realtime Database (9000), Storage (9199)
and Functions (5001) for the demo project `demo-samvad`. A demo project can never touch real data or
send real SMS. The first start downloads the emulator JARs (about 100 MB).

**Sign-in codes:** no SMS is sent. When the app asks for a code, the emulator terminal prints a line like:

```
To verify the phone number +919999900001, use the code 123456.
```

(The emulator web UI is turned off in `firebase.json`, so the terminal is the place to look.)

## 4. Point the app at the emulators

Create or edit `android-native/gradle.properties` on your machine and add the lines below. Don't commit
them: they are for local runs only.

```properties
samvad.useEmulators=true
```

- **Android emulator:** nothing more to do. The app reaches your computer at `10.0.2.2`.
- **Real phone over USB:** also add

  ```properties
  samvad.emulatorHost=localhost
  ```

  and forward the emulator ports to the phone each time you connect it:

  ```bash
  adb reverse tcp:9099 tcp:9099
  adb reverse tcp:8080 tcp:8080
  adb reverse tcp:9000 tcp:9000
  adb reverse tcp:9199 tcp:9199
  adb reverse tcp:5001 tcp:5001
  ```

  (Debug builds allow plain HTTP only to `10.0.2.2` and `localhost`, so use `localhost`, not `127.0.0.1`.)

Without `samvad.useEmulators=true` and without a `google-services.json`, the app opens a
"setup required" screen. That is expected.

## 5. Build and run

**Android Studio:** File → Open → choose the `android-native` folder → wait for Gradle sync → select the
`app` configuration → Run ▶. The debug app installs as `com.samvad.messenger.debug`, so it can sit next to
a future release build.

**Command line** (from `android-native/`):

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install on the connected device/emulator
```

## 6. Try it with two people

Use two emulators, or a phone and an emulator, both pointing at the same running emulators:

1. Sign in on each with a different number, e.g. `+91 99999 00001` and `+91 99999 00002` (any valid
   Indian mobile number works; read the code from the emulator terminal).
2. Create a profile on each.
3. On one device: New chat → type the other number → Start.
4. Things to check:
   - **Messaging:** text, reply, edit, delete, reactions.
   - **Media:** photo, file, voice note.
   - **Presence:** typing and online status.
   - **Safety:** block / report (chat menu).
   - **Sharing:** export chat, forward.
   - **Groups:** New chat → New group; invite codes via Group info; @mentions; polls (attach → Poll).
   - **Settings:** app lock, two-step PIN, profile photo, read receipts, block screenshots, blocked contacts.

## 7. Run the tests

```bash
# Backend: rules + functions on the emulators (196 tests), then the log-hygiene check
cd firebase
npm test
npm run test:e2e:logs

# App logic (pure Kotlin, no device needed; 49 tests)
cd ../android-native
./gradlew :core:test

# Everything CI runs for the app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Stop the `npm run emulators` terminal first. `npm test` starts its own emulators on the same ports.

## 8. What does not work locally (by design)

| Feature | Why | Where it works |
|---|---|---|
| Push notifications | need a real Firebase project and Play Services | staging/production project |
| App Check / Play Integrity | skipped in emulator mode | real project, Play-distributed build |
| Real SMS | emulator prints codes instead | real project with Phone sign-in enabled |
| Two-step PIN on a real project | needs the Token Creator IAM role (see owner checklist) | after that setup |

Everything else runs fully on the emulators.

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| "Setup required" screen | `samvad.useEmulators=true` missing from `android-native/gradle.properties`; rebuild |
| Code screen never succeeds / network errors | emulators not running, or (real phone) `adb reverse` not set or `emulatorHost` not `localhost` |
| `Cleartext HTTP traffic not permitted` | you used `127.0.0.1` as the host; use `localhost` (USB) or the default `10.0.2.2` (emulator) |
| Emulators fail with Java errors | install JDK 21 and make sure `java -version` shows it |
| `firebase` command not found | use `npm run emulators` inside `firebase/` (firebase-tools is a pinned dev dependency, not a global install) |
| Emulator requests hang behind a corporate proxy | run with the proxy unset: `env -u HTTPS_PROXY -u https_proxy npm run emulators` |
| Gradle sync fails on JDK version | Android Studio → Settings → Build Tools → Gradle → Gradle JDK = the bundled JBR (17 or 21) |
| A screen crashes | copy the red stack trace from Logcat (filter: `com.samvad.messenger.debug`) and share it |

## 10. State of the code when this guide was written

- **Built and checked in CI (GitHub Actions run #36, backend; #34, app):** Phase 1 complete, plus the
  groups and polls backend (Phase 2.1).
  - **Phase 1:** chats, media, presence, block/report, app lock, two-step PIN, export, forward, read
    receipts, profile photos.
  - **Checks run:** emulator tests, log hygiene, `:core` tests, app build, unit tests and lint.
- **Pushed, CI still running at the time of writing:** group chats in the app, with @mentions and polls
  (commit `a2ef172`). Check its result on the repository's Actions tab.
- **Pushed last, not yet checked by CI or a compiler:** the New group, Add members, Join with code and
  Group info screens (`android-native/app/src/main/java/com/samvad/messenger/ui/groups/`). If the build
  fails in those files, either share the error, or build the previous commit while it is fixed:

  ```bash
  git checkout a2ef172      # group chats without the new group screens
  # or
  git checkout 18e919d      # last commit with both CI jobs green
  ```

- **Never run on a real device yet.** The first local run will likely surface runtime issues that automated
  builds can't catch (layouts, permissions, BiometricPrompt, pickers). Please report them.

See `docs/IMPLEMENTATION_STATUS.md` for the full feature list, security summary, owner checklist (Firebase
project, credential rotation, IAM role, TTL policy) and known risks. The specs for every feature are in
`docs/superpowers/specs/`.
