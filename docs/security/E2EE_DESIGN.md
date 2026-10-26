# End-to-end encryption: design

Status: **implemented for one-to-one chats, behind a build flag; not reviewed by an external cryptographer; not deployed.**
Decision record and the AGPL release gate: `docs/security/E2EE_DECISION.md`. Read the "Release" section below before any build leaves this repository.

This document says what the code does, what it deliberately does not do, and what a reviewer must examine. Where the
implementation falls short of what a messenger could do, that is written down under "Limits".

## 1. Scope (version 1)

| In scope | Not in scope (and the app says so) |
|---|---|
| One-to-one chats: text, replies, edits, delete-for-everyone, contact cards, photos, voice notes, files, videos | Groups, communities, polls, Moments (statuses), Moment replies |
| Reactions (emoji are visible to the server, see section 6) | Calls (LiveKit, encrypted in transit to the call server only, **not** end-to-end) |
| Safety numbers (60 digits) and a QR code, local "verified" mark | Linked devices, cloud key backup, history transfer to a new phone |
| Key-change detection that holds messages until the person decides | Sealed sender, sender keys, disappearing-message enforcement on the recipient's device |

One device per account. Signing in on a new phone creates new keys; old encrypted history is **not recoverable**, and the
other person is shown "Security code changed". There is no cloud key backup. This is stated in the app (Learn more sheet,
security code screen) and here.

## 2. Threat model

Protects against:

- Samvad's operators and anyone who obtains the Firebase data (Firestore, Storage, logs, backups) learning message text,
  contact cards, attachment content, attachment names, types and exact sizes.
- A passive network attacker and Google (FCM) learning content (push messages carry at most the ciphertext).
- A server that rewrites or injects ciphertext: messages are authenticated by the Double Ratchet; attachments by a digest and AES-GCM.

Does **not** protect against:

- A compromised or malicious key server that substitutes pre-key bundles at the moment two people first talk (the usual
  trust-on-first-use gap). Mitigation: safety numbers and the QR check, key-change holds. Nothing prevents the server from
  withholding messages or from refusing to hand out keys.
- A compromised phone (malware, root, a person with the unlocked phone). Decrypted messages are on the phone; see Limits for exactly where.
- Metadata: who talks to whom, when, message kind, ciphertext length (padded to 160-byte blocks), reactions, receipts, typing, presence.
- Traffic analysis by Google (FCM) of push timing and sender name.

## 3. Primitives

All protocol cryptography is **libsignal 0.86.5** (`org.signal:libsignal-android` in `:app`, `libsignal-client` for the
JVM tests in `:core`). Samvad invents no cryptography.

- Session setup: PQXDH (X25519 plus Kyber-1024) from a published pre-key bundle. Messages: Double Ratchet. Signatures: XEdDSA over the signed and Kyber pre-keys, made with the identity key.
- Attachments: a random AES-256-GCM key and a random 96-bit IV per file (`AttachmentCrypto`); the SHA-256 digest of the stored bytes is checked before decryption. Stored file = IV + ciphertext + tag. AAD is a fixed label.
- Payload padding: a `0x80` marker and zeros to a multiple of 160 bytes (as Signal does).
- Local storage: AES-256-GCM chunked envelope of the Personal Vault (`AesGcmVaultCrypto`) under a random 256-bit data key wrapped by an Android Keystore key (hardware-backed, StrongBox where present), exactly the vault's key-wrapping approach (`VaultKeyring`, `AndroidKeystoreKeyWrapper`) with its own alias `samvad_e2ee_wrap_v1`.

Code: `android-native/core/src/main/kotlin/com/samvad/core/e2ee/` (pure Kotlin, JVM-tested), `android-native/app/src/main/java/com/samvad/messenger/data/e2ee/` (Android glue).

## 4. Keys

Per install (`SamvadSignalStore`, one blob, `state.sealed`, sealed with the wrapped data key, in `noBackupFilesDir`, excluded from backup and device transfer):

- identity key pair and a random registration ID (1..16380),
- a signed EC pre-key and a "last resort" Kyber-1024 pre-key (reused by many first messages; replays are refused by remembering `(kyber id, signed id, sender base key)`),
- 100 one-time EC pre-keys, replenished by another 100 when the server holds fewer than 25,
- sessions, trusted identity keys, the verified marks, and the pending (unaccepted) identity changes.

Rotation: the signed and Kyber pre-keys are replaced every 30 days; the previous pair is kept until it is older than 60 days so first messages already in flight still open. The identity key never rotates.

Private material exists only in that sealed file and in memory. It is deleted on sign-out, on account deletion (auth-state listener in `AppContainer`) and when the app finds state it cannot open (lost Keystore key), in which case the install starts over with a new identity.

Public bundle, published by `publishPreKeys`, stored server-only at `e2eeKeys/{uid}` (identity key, registration ID, signed pre-key, Kyber pre-key, one-time count) and `e2eeKeys/{uid}/oneTime/{id}`. Firestore rules deny every client read and write of both (and of `e2eeFetchLimits/*`).

## 5. Key directory (Cloud Functions, `firebase/functions/src/prekeys.ts`, region asia-south1)

| Callable | Does |
|---|---|
| `publishPreKeys` | Validates every field strictly (Base64, exact decoded lengths 33/64/1569 bytes, ID ranges, at most 100 one-time keys per call, at most 200 stored). The uid is always the signed-in account, never a request field. A different identity key than on file replaces the bundle and every old one-time key. 30 calls per day. |
| `fetchPreKeyBundle({uid})` | In one transaction: reads the bundle, deletes **one** one-time key, returns the bundle with that key, so a one-time key is never returned twice (tested with concurrent callers). When none are left the bundle is returned without one. Refuses self; answers `not-found` identically for no bundle, deleted account and blocked pair in either direction; 200 fetches per day per caller (`consumeDailyBudget`) and 6 per day per (caller, target) pair so one account cannot drain another's one-time keys. Returns no private data and no extra fields. |
| `preKeyStatus` | The caller's own bundle state (published, one-time count, identity key) for top-ups and detecting a lost server copy. |
| `upgradeConversationE2ee({conversationId})` | For an existing direct chat: both people must have published keys, not blocked; sets `e2ee: true` and `e2eeSince`. Irreversible. |
| `deletePreKeys` | Sign-out: removes the caller's bundle, so nobody sends into a signed-out account. |

The server does **not** verify the pre-key signatures (it has no XEdDSA verifier); libsignal on the sender verifies them when it builds a session. See the reviewer list.

## 6. What the server stores, and what it can see

An encrypted message document keeps only routing and envelope fields:

```
senderId, kind (text|image|file|voice|video|contact), text: "", replyTo: null, createdAt,
enc: { v: 1, type: "prekey"|"whisper", ciphertext: <base64>, registrationId },
media: { path: ".../blob.bin", size, thumbPath?: ".../thumb.bin" }   // attachments only
reactions: { uid: "emoji" }                                          // see below
```

`registrationId` is the recipient registration ID the sender used; the rules compare it with the recipient's current one, so a message addressed to a replaced install (new phone) is refused and the sender's app learns the keys changed instead of storing something nobody can read.

| The server CAN see | The server CANNOT see |
|---|---|
| sender, recipient, time, message kind | text, captions, contact cards, reply links, forwarded flag |
| ciphertext length (padded to 160 B) and attachment ciphertext size (16 MB cap) | attachment content, file names, MIME types, dimensions, durations, waveforms, exact sizes |
| **reaction emoji and who reacted** (kept as server-visible metadata so reactions need no extra ciphertext) | what was edited (an edit is a new ciphertext) |
| delivered and read times, unread counters, typing and presence | |
| pinned message **ids**, starred message ids (references only) | pinned or starred content |
| that a message was deleted (and when), the disappearing timer value and expiry | |
| that two accounts both have keys, and their public keys | any private key |

Rules (`firebase/firestore.rules`, `storage.rules`): in a chat with `e2ee == true`, a message **must** carry a well-formed `enc` (exact keys, version 1, type, Base64 charset, 8..32768 chars, registration ID equal to the recipient's) and an empty `text`; plaintext text, polls, mentions, moment references, reply links, forwarded flags and plaintext media descriptions are refused. Edits must replace `enc`; soft delete must remove `enc`. All existing checks (sender, membership, blocks, timestamps, ID format) still apply. Storage accepts only `application/octet-stream` named `blob.bin`/`thumb.bin` in an encrypted chat, and refuses it elsewhere. The `e2ee` flag can only be set at conversation creation (both bundles must exist) or by `upgradeConversationE2ee`; no client update path exists.

## 7. Flows

**Creating a chat** (`ChatRepository.openDirect`): when `BuildConfig.E2EE_ENABLED` and both people have published keys, the conversation is created with `e2ee: true` and the session is already established. Otherwise it is created as an ordinary chat. If the server refuses the flag, the chat is created plain; the UI never claims otherwise (`E2eePolicy.banner`).

**Existing chats** stay plain. The note at the top says so and offers **Upgrade this chat**, which protects only new messages. The note says that earlier messages stay as they are and are not encrypted, and shows the upgrade time (`e2eeSince`).

**Send** (`ChatRepository.writeEncrypted`): payload (JSON, padded) → `SignalEngine.encrypt` → document with `enc`. The plaintext is kept in the local cache first (a sender cannot decrypt its own ciphertext). If encryption is impossible the send throws (`E2eeSendException`); it never falls back to plaintext. A rules refusal triggers one session rebuild (fresh bundle), then a clear notice.

**Receive** (`E2eeService.open`): decrypt oldest first, keep the plaintext in the local cache (a Signal ciphertext opens only once), build the `Message` for the UI. A message that cannot be opened is shown as a placeholder with the reason (security code changed, no keys on this phone, damaged, own message lost, this build has no encryption). Pages are re-read after a trust decision so held messages open.

**Edit**: a new envelope replaces `enc` (15 minute window, own text messages that were themselves encrypted). **Delete for everyone**: `deleted: true`, `enc` removed, local copy removed. **Reactions**: unchanged, server-visible.

**Attachments** (`PendingUploadManager`, `MediaRepository`): the file is encrypted on the phone before it is even saved for upload; Storage receives opaque bytes; the key, digest, size, name, type and metadata travel only in the envelope. Download verifies the digest, decrypts with the key from the opened message, and caches only the plain file. A ciphertext is never cached as if it were the photo, and a file whose key is unknown is never stored. Forwarding into an encrypted chat encrypts again with a new key. Moment replies and polls are refused in encrypted chats with an explanation.

**Notifications**: the server's push for an encrypted message has an empty body, the kind, and the ciphertext when it is at most 2,400 characters (FCM allows 4 KB). The app decrypts it only if the person allows previews (`E2eePolicy.mayDecryptForNotification`), then shows sender name and text; otherwise a generic "New encrypted message". The lock-screen public version never shows content. `lastMessage` on the conversation carries kind and time only (`preview: ""`, `encrypted: true`); the chat list shows what the phone decrypted.

**Chat list, unread, receipts, disappearing timers, pins, stars, search** keep working: counters and receipts are server-side metadata; timers and expiry are server-side; pins and stars hold message ids; chat-list previews and the on-device search index are built from locally decrypted text. Decrypted copies of messages the server confirms are gone (expired or deleted) are removed from the local cache when the chat is next opened (best effort).

**Server features that need plaintext** are handled explicitly:

- Scheduled messages: refused by the rules for encrypted chats, hidden in the UI (long-press on Send, and the scheduling sheet), and `sendDueScheduled` fails (reason `encrypted`, shown to the sender) any item whose chat became encrypted after it was scheduled; it never writes the text into the chat.
- Reports: the server holds only ciphertext, so evidence from an encrypted chat is metadata only. The report dialog offers, unticked by default, to include the reported person's last messages **as decrypted by the reporter's phone**; they are stored as `reporterEvidence`, marked `unverified`, only for messages the reported person really wrote in that chat, and only with an explicit consent flag. Samvad cannot verify them.
- Disappearing timers and pins: see above.

## 8. Identity changes and verification

`SamvadSignalStore` implements trust on first use. A different identity key for a person is **never** trusted automatically: `isTrustedIdentity` answers false, libsignal refuses to send or open, the key is stored as a pending change, a banner "Security code changed" appears with **Review**, sends are refused (`E2eeSendException(IDENTITY_CHANGED)`), and incoming messages stay held (not opened, so nothing is lost). The review screen shows the **new** 60-digit number and QR; **Continue with the new code** trusts it, clears the verified mark, drops the old session, and releases held messages. This is stricter than Signal's default (which continues after a notice).

The safety number is libsignal's `NumericFingerprintGenerator` (5200 iterations, version 2) over both identity keys, with the Firebase UIDs as stable identifiers. The QR carries the scannable fingerprint (`samvad-verify:1:` + Base64url); scanning uses Google's code scanner (no camera permission) and `ScannableFingerprint.compareTo`. A match marks the chat verified; the mark is local only and lost when the key changes.

## 9. One device per account, sign-out, deletion

- First sign-in on a phone creates the identity and publishes the bundle. Signing in on a second phone replaces the bundle (the identity key differs), so the first phone can no longer receive new encrypted messages; its senders are refused by the rules until they refetch. Documented limitation, not a feature.
- Sign-out: `deletePreKeys` (best effort), then every key, session and decrypted message is erased; the search index and drafts are cleared as before. Remote sign-out and account deletion erase local state through the auth-state listener.
- Account deletion on the server removes `e2eeKeys/{uid}`.
- No backup: `allowBackup="false"`, and data-extraction rules exclude files and databases.

## 10. Release (read this)

- `BuildConfig.E2EE_ENABLED`: **true in debug**, in release controlled by Gradle property `samvad.e2ee` and **false by default** (`-Psamvad.e2ee=true` to build a release with encryption). With it off the app never publishes keys, never creates encrypted chats, shows existing encrypted chats as "this version can't read them", and the notices say what is true.
- This flag removes the feature at run time; **libsignal is still linked** (AGPL-3.0). A release that must contain no AGPL code has to remove the `libsignal-android` dependency and the `data/e2ee` package as well; the `:core` e2ee code compiles against `compileOnly` libsignal and has no other dependency on it. The AGPL release gate in `E2EE_DECISION.md` (lawyer's written answer, published source, in-app licence link) applies to any distribution of a build that links libsignal, whether or not the flag is on.
- Per conversation, the server flag `e2ee` decides whether a chat is encrypted. Turning the app flag off does not decrypt anything.
- Wording on other surfaces (website, privacy policy, Play data-safety, Settings text `settings_privacy_body`, `privacy_sheet_*`) still says Samvad has no end-to-end encryption. They must change together, in one release, only after the review below; they were intentionally not edited here.

## 11. Limits (what this version does not do)

1. **Plaintext at rest on the phone outside the sealed cache**: the on-device search index (`samvad_search.db`) stores decrypted text of every message the chat screen has loaded, unsealed; the decrypted media cache (`cacheDir/media`) holds plain files; "Export chat" writes a plain text file the person chooses to share. All are app-private, excluded from backup, and removed on sign-out (search index) or the OS cache clearing (media). They are not under the Keystore key.
2. **Decrypt-once**: if the app is killed between decrypting a message and saving its plaintext, that message cannot be opened again and shows as unreadable. Protocol state itself is saved atomically before the result is used.
3. **History is device-bound**: no recovery on a new phone, no recovery after "Clear data" (own messages included).
4. **Ordering**: delivery relies on Firestore; libsignal handles out-of-order messages (skipped-key window), but a very long offline gap (thousands of messages) can exceed libsignal's limits.
5. **The key server is trusted for availability and for the first key exchange** (see threat model).
6. **Notifications**: if previews are allowed, decrypted text appears in the notification (private lock-screen version hidden). If the ciphertext did not fit in the push, the notification is generic.
7. **Counters and abuse controls remain server-side**, so block/report/rate limiting work on metadata only; spam inside an encrypted chat can only be handled by the recipient (block, report with evidence).
8. **Reactions leak the emoji**; **kind and ciphertext sizes leak** the type and rough size of content.
9. No sealed sender: the server knows the sender of every message.
10. Disappearing messages are enforced by the server deleting the document and by the phone dropping the cached copy when the chat is opened; the other person can still keep their own copy.
11. Group and community encryption (sender keys), multi-device, key transparency, and an encrypted cloud backup are future work.

## 12. Tests

`:core` (JVM, real libsignal): session setup and two-way talk, out-of-order delivery, replay refusal, one-time pre-key exhaustion, identity change on send and on receive (held until accepted), persistence round trip and damaged state, rotation and expiry, replenishment IDs, published key sizes, safety numbers and QR matching, envelope shape (valid and malformed), payload encode/decode and path binding, attachment round trip, tamper at every position, wrong key, truncation, `E2eePolicy` honesty rules.

Backend (emulators): `publishPreKeys`, `fetchPreKeyBundle` (blocked pairs in both directions, deleted account, rate limits, spoofed uid, one-time key never returned twice, concurrent fetches), `preKeyStatus`, `upgradeConversationE2ee`, `deletePreKeys`, account deletion, previews and push for encrypted messages, scheduled messages refused, reports from encrypted chats.

Rules (emulators): encrypted chat rejects plaintext and every malformed or smuggling `enc`, stale registration IDs, encrypted media shapes, edit/delete/reaction paths, chat creation and the flag, scheduling, Storage ciphertext-only; clients cannot read anyone's (or their own) key bundle.

Not tested automatically (no device or emulator in the build environment): the Compose screens, the Android Keystore wrapper, push handling, camera/QR scanning, the Storage download path. These need a manual run on a device.

## 13. What an external cryptography review must examine

1. **Use of libsignal**: the custom `SamvadSignalStore` (all `SignalProtocolStore` methods), especially trust handling (`isTrustedIdentity`/`saveIdentity`/`acceptIdentityChange`), `markKyberPreKeyUsed` (last-resort key replay bookkeeping, bounded at 5,000 entries), session deletion on accept, one-time key removal ordering, and whether persisting after every call gives the atomicity libsignal assumes (state is saved synchronously inside each store call, not once per message).
2. **Bundle handling**: that the server cannot downgrade (a bundle without a one-time key is accepted by design); that unsigned fields cannot be altered; Kyber last-resort key reuse; registration ID semantics and the rule that compares them; signed/Kyber pre-key rotation timing and grace.
3. **Trust-on-first-use and key-change policy**: hold-until-accepted behaviour, clearing of "verified", behaviour when the changed key arrives inside a PreKey message, and whether holding messages is a denial-of-service vector.
4. **Safety numbers**: UIDs as stable identifiers (not phone numbers), version 2 fingerprints, 5200 iterations, QR encoding and comparison.
5. **Payload and envelope**: JSON-in-padding format, strictness of decoding, the storage-path binding check, replay/duplicate handling and the decrypt-once cache, edit semantics (new ciphertext for the same message id), and the cache keyed by `(conversation, message, ciphertext digest)`.
6. **Attachments**: key per file, IV generation, AAD, digest-then-decrypt order, thumbnails, forwarding re-encryption, size/overhead handling, in-memory handling of 16 MB buffers.
7. **Local storage**: use of the vault's key wrapping and chunked AES-GCM for state and cache, Keystore alias lifecycle and failure handling (state treated as lost), `noBackupFilesDir`, the unsealed search index and media cache (Limit 1), wipe completeness.
8. **Server**: pre-key directory authorization, transaction correctness (one-time key uniqueness under contention), rate limits and the per-pair cap, the absence of an XEdDSA signature check on the server, the Firestore/Storage rules above (a rules bypass would allow plaintext or mis-addressed messages), what logs contain (`logEvent` outcomes only), push payloads.
9. **Metadata** section 6: whether anything else (indexes, `lastMessage`, report documents, Storage metadata `senderId`) leaks more than listed.
10. **Process**: the AGPL-3.0 obligations and the decision to ship with the flag off by default.
