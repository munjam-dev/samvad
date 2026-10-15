# End-to-end encryption: decision record

Status: **libsignal added to the build on the owner's instruction (2026-10-15). A release gate applies, see below.**
The Android app does not use it yet: only the version catalog, the `:core` module (compile-only, for tests)
and a spike test of the protocol are in place.

## Why this matters

Today the server can read messages (they are encrypted in transit and at rest by Google, not end to end).
The website, the in-app notice and the legal pages all say so. They must keep saying so until the protocol,
key management and every message path below are implemented and tested.

## The options, checked on 2026-10-15

| Option | Licence | Maintained | Notes |
|---|---|---|---|
| **libsignal** (Signal Protocol: X3DH/PQXDH, Double Ratchet, Sender Keys for groups) | **AGPL-3.0** | Yes (`org.signal:libsignal-android` 0.86.5, Nov 2025) | Audited and widely deployed. AGPL means the Samvad Android app's source must be published under AGPL-3.0 (the Firebase backend does not link it and can stay private). Signal does not sell a general commercial licence |
| vodozemac / Olm and Megolm | Apache-2.0 | Rust library maintained; **Android bindings are not** (`org.matrix.android:olm-sdk` last released 2022) | App can stay closed. We would build and maintain JNI bindings ourselves |
| MLS (RFC 9420) via a permissive library | MIT or Apache-2.0 | Libraries exist; Android bindings are immature | Group-first design; larger build effort |
| Tink HPKE plus our own protocol | Apache-2.0 | Yes | **Rejected.** The product brief forbids inventing a protocol |

## Owner's answers so far

- Preferred path: **libsignal, open-source the Android app.**
- Can the source be public? **Not sure, ask a lawyer.**

So the Android app stays free of AGPL code until that is confirmed in writing.

## Question for the lawyer

Samvad Android would link libsignal (AGPL-3.0). Confirm that publishing the Samvad Android app's complete
source under AGPL-3.0 is acceptable for the business (trademark, the logo and brand assets, and Firebase
configuration files are not part of the source and stay out), and that the backend in `firebase/` (which
does not link libsignal) may stay private.

## What to build once the path is confirmed

1. Identity and pre-key distribution: a `keys/{uid}/devices/{deviceId}` bundle (identity public key, signed pre-key, one-time pre-keys), written only by the owner, readable only through a rate-limited callable that hands out one one-time key at a time.
2. Android key storage: private keys in the Android Keystore (hardware-backed where available), protocol state in an encrypted local database, nothing in backups.
3. 1:1 messages: encrypted envelope in the existing message document (ciphertext, sender device, counter); server keeps routing fields only. Search, previews and notifications must work from decrypted local data.
4. Groups: sender keys with rotation on every membership change.
5. Attachments: random key per file, file encrypted before upload, key sent inside the encrypted message.
6. Key verification (safety numbers or QR), key-change warnings, and recovery behaviour when a phone is lost.
7. Migration: existing plaintext history stays readable, new messages are encrypted, and the UI says exactly which is which.
8. Only after all of the above are tested: change the website, the in-app notice and the policies, with the same wording on every surface.

## Features that must wait for this

Linked devices, encrypted backups, and any server-side feature that needs message text (the server will not
have it). Disappearing-message expiry and the chat-list preview use server-side fields today and will need
rework once the server can no longer read previews.

## Update 2026-10-15: libsignal added

The owner instructed that libsignal be added. Done so far:

- `gradle/libs.versions.toml`: `libsignal-client` (JVM, for `:core` unit tests) and `libsignal-android` (for the app), version 0.86.5.
- `:core` declares the JVM artifact `compileOnly` and for tests only, so the protocol API is available to pure Kotlin logic without shipping desktop binaries in the app.
- `core/src/test/.../SignalSpikeTest.kt`: 5 tests pass. Two parties establish a session (PQXDH with a one-time pre-key and a Kyber pre-key) and exchange messages both ways; a third party cannot decrypt; each ciphertext differs; out-of-order delivery works; a replayed message is refused.

**App module:** now depends on `libsignal-android` (with core-library desugaring, which the library requires). The debug app builds and
the real native libraries are packaged for all four CPU types. **Open item before release:** the debug APK grew from 28 MB to 132 MB and still
contains Signal's test-only `libsignal_jni_testing.so`; the `packaging { jniLibs.excludes }` lines in `app/build.gradle.kts` did not remove
it. Fix this (or confirm the release App Bundle strips it) and check the release size per CPU type.

**Not done yet:** no app code uses the protocol. No message is encrypted. The website, app notice and policies still say messages are not end-to-end encrypted, and they must until the full flow is built and tested.

## RELEASE GATE (do not skip)

Adding the dependency does not publish anything. The AGPL-3.0 obligations apply **when the app is distributed**
(for example uploaded to Google Play or shared as an APK). Before the first distribution that includes libsignal:

1. The lawyer must confirm in writing that publishing the Samvad Android app's complete source under AGPL-3.0 is acceptable.
2. The source must actually be published (a public repository with the AGPL-3.0 licence file), with a link inside the app (Settings, About, Open-source licences).
3. Brand assets (name, logo) are not licensed under AGPL; say so in the repository.

If the answer is no, remove `libsignal-android` and choose another path from the table above. Nothing else in
the app is built to depend on libsignal's licence terms except the encryption code that is still to be written.
