# Personal Vault: design

Status: local-first vault built (Android). **Backup to Google Drive is not built** and is blocked on the owner
decisions listed at the end. The vault never leaves the phone today, and no screen, string or document may say
otherwise. Nothing here concerns chat encryption: chats are still readable by the server
(see `docs/security/E2EE_DECISION.md`).

## What it is

A private space inside the app for the person's own documents, photos, notes and ID copies. Entry points:
Settings > Privacy > Personal Vault, and the three-dot menu on the home screen. Route: `vault`
(plus `vault/image/{id}`, `vault/note`, `vault/note/{id}`).

## Storage

| What | Where | Protection |
|---|---|---|
| File and note content | `noBackupFilesDir/vault/blobs/<uuid>.svl` | AES-256-GCM, chunked, per-blob derived key |
| Picture previews | `noBackupFilesDir/vault/blobs/<uuid>.thumb` | same envelope, 256 px JPEG |
| Item metadata | Room database `noBackupFilesDir/vault/vault.db` | titles and MIME types are sealed inside a blob column; the type, size and dates stay in the clear |
| Data key | `noBackupFilesDir/vault/datakey.bin` | 32 random bytes, wrapped by an Android Keystore AES-256-GCM key |
| One-time viewing copy | `cacheDir/vault_view/<uuid>/<name>` | plaintext, see "Viewing"; deleted when viewing ends |

Plaintext vault content exists only in memory, apart from the one-time viewing copy and a copy the person
explicitly exports. Nothing is written to logs, notifications, shared storage or the chat tables, and nothing is
uploaded. `data_extraction_rules.xml` and the manifest already excluded everything from auto backup and
device transfer (`allowBackup=false`, `fullBackupContent=false`, every domain excluded in both sections);
the vault adds a second layer by living in `noBackupFilesDir`, which is never backed up.

Room is used for the metadata (Room 2.8.5 with KSP 2.3.6, which supports AGP 9 built-in Kotlin). The schema is
exported to `android-native/app/schemas/`. There is no destructive migration: losing a person's documents
silently is never acceptable.

## Crypto

Code: `core/.../vault/VaultEnvelope.kt` (format and cipher), `VaultKeyring.kt` (data key lifecycle),
`app/.../data/vault/AndroidKeystoreKeyWrapper.kt` (the only Keystore code). Pure Kotlin with JDK crypto, so it is
tested on the JVM (`VaultEnvelopeTest`, `VaultKeyringTest`).

Envelope, version 1 (big-endian): `"SVLT"`, version byte, algorithm byte (1), chunk size (4), salt (32), then
chunks of ciphertext plus 16-byte GCM tag.

- A key per blob is derived with HKDF-SHA256 from the data key and the blob's random salt. A (key, nonce) pair
  therefore can never repeat, however many items there are.
- Chunk nonce = 7 zero bytes, a 4-byte counter, and a final byte that is 1 for the last chunk. Reordering,
  repeating, dropping or appending chunks fails authentication, including truncation at a chunk boundary.
- Associated data = the whole header plus a context string (`file:<id>`, `thumb:<id>`, `meta:<id>`). A blob moved to
  another item, or a thumbnail swapped for a file, does not open.
- Wrong key, tampering and truncation all give the same `VaultTamperedException` on purpose. Unknown versions and
  non-vault data give `VaultFormatException`.
- Large files are streamed in 64 KiB chunks. On a failure the caller must discard any partial output (the
  repository deletes the file).

Keystore key: AES-256-GCM, alias `samvad_vault_wrap_v1`, in StrongBox when the phone has it and falls back to the
normal hardware-backed Keystore, then to software. "About this vault" shows which one is in use.

**No user-authentication requirement on the Keystore key.** A key bound to the screen lock is deleted by Android
when the person removes or changes that lock, which would destroy the vault for good. The gate is in the app
instead (next section).

## Access

The vault opens only after `BiometricGate` (fingerprint, face or screen lock) every time it is opened. It then
stays open while the person is in it; after leaving it (back, another screen, app in the background) it stays open
for 1 minute, so a trip to the photo picker does not ask again (`VaultAccess`, tested). After that the data key is
wiped from memory. "Lock now" does so immediately. Locking the app (the existing app lock) also locks the vault.
A phone with no screen lock cannot use the vault at all (there is nothing to confirm identity with).

`FLAG_SECURE` is held while any vault screen is composed (reference counted, restored to the Settings choice
afterwards). The recents thumbnail is also disabled on Android 13+.

## Viewing and export

- Pictures decrypt into memory and are decoded in the app. Notes are edited in the app.
- Other files are decrypted to a one-time copy in `cacheDir/vault_view/`, handed to another app through the
  existing FileProvider (`vault_view` path, not exported, read grant only), and overwritten and deleted when the person
  comes back, when the vault locks, when the screen is left, and at app start. Only an allow-list of MIME types
  can be opened this way (never installers, scripts, web pages). The confirmation says that the other app may keep
  its own copy.
- Export decrypts straight into the document the person created with the system "create document" screen, after a
  confirmation that says the copy is not encrypted. If it fails halfway, the half-written document is deleted.
- Delete removes the row, overwrites the file with random bytes, then deletes it. On flash storage an overwrite is
  not guaranteed to reach the same cells, so this is a second line of defence; the first is that the file only
  ever held ciphertext.

## Failure modes

- **Keystore key lost** (factory reset, some OEM security changes, restoring files to another phone): the vault
  shows "can't be opened" and offers to erase it. The items cannot be recovered. There is no recovery key until
  backup exists.
- **Crash during import**: leftover `.part` files and blobs without a row are removed the next time the vault opens.
- **Lock while writing a note**: the unsaved text is dropped from memory with the lock.
- **App data cleared / uninstall**: everything is gone, by design.
- **Sign-out**: the vault belongs to the phone, not the account, and is not touched by sign-out. A second account on the
  same phone gets the same vault behind the same phone unlock. Revisit if shared phones matter.

## Limits

100 MB per file, 100,000 characters per note, titles up to 80 characters (control, direction-override and zero-width
characters removed; Hindi joiners kept).

## Backup to Google Drive (not built)

The interface `VaultBackup` exists in `core/.../vault/VaultBackup.kt` with no implementation and no caller.
Rules for any implementation:

1. Only ciphertext leaves the phone. Backup blobs are sealed with a **recovery key**, because the Keystore-wrapped data
   key is bound to this phone and cannot be restored on another one.
2. Opt-in, explained in plain words, with a visible list of what was uploaded and a delete that also deletes the
   remote copy.
3. Samvad's servers never see the recovery key or the blobs.

Decisions needed from the owner before building (also in `IMPLEMENTATION_PLAN_V2.md`, Phase 4):

- **Drive scope.** `drive.file` (files created by the app only, not shown in the person's normal Drive listing) or the
  visible-folder scope (a restricted scope that needs a Google security review before production).
- **Recovery key design.** A passphrase the person chooses (derive with a slow KDF such as Argon2id/scrypt; lost
  passphrase means lost backup) or a generated key shown once for the person to write down.
