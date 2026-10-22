# Personal Vault: design

Status: local-first vault built (Android), plus **optional encrypted backup to the person's own Google Drive**
(hidden app-data folder, scope `drive.appdata` only). The vault stays on the phone unless the person opts in to
backup, and no screen, string or document may say more than that (in particular: Samvad cannot recover a lost
recovery code). Backup needs the owner to configure Google Cloud once: `docs/VAULT_BACKUP_SETUP.md`. Drive calls
have not been exercised on a device (they need that OAuth client). Nothing here concerns chat encryption: chats are
still readable by the server (see `docs/security/E2EE_DECISION.md`).

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
| Backup key (only if backup is on) | `noBackupFilesDir/vault/backupkey.bin` | archive key wrapped by the Keystore, plus the data key sealed under the archive key |
| Backup scratch files | `noBackupFilesDir/vault/backup_tmp/`, `restore_tmp/` | ciphertext only; removed after each run and at app start |

Plaintext vault content exists only in memory, apart from the one-time viewing copy and a copy the person
explicitly exports. Nothing is written to logs, notifications, shared storage or the chat tables, and nothing is
uploaded unless the person turns on backup (see "Backup to Google Drive"). `data_extraction_rules.xml` and the manifest already excluded everything from auto backup and
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
  shows "can't be opened" and offers to erase it. The items on this phone cannot be recovered. If the person had
  turned on backup, they can erase the vault, unlock the new empty one and use "Restore from backup" with the recovery code.
- **Crash during import**: leftover `.part` files and blobs without a row are removed the next time the vault opens.
- **Lock while writing a note**: the unsaved text is dropped from memory with the lock.
- **App data cleared / uninstall**: everything is gone, by design.
- **Sign-out**: the vault belongs to the phone, not the account, and is not touched by sign-out. A second account on the
  same phone gets the same vault behind the same phone unlock. Revisit if shared phones matter.

## Limits

100 MB per file, 100,000 characters per note, titles up to 80 characters (control, direction-override and zero-width
characters removed; Hindi joiners kept).


## Backup to Google Drive

Opt-in, off by default. Code: `core/.../vault/VaultArchive.kt`, `VaultRecovery.kt`, `VaultBackup.kt` (pure, tested on
the JVM in `VaultBackupTest`); `app/.../data/vault/backup/*` (Android and network); `ui/vault/VaultBackup*.kt`
(screens, in the Vault three-dot menu under "Backup"). Owner setup: `docs/VAULT_BACKUP_SETUP.md`.

### Decision: Drive scope

Only `https://www.googleapis.com/auth/drive.appdata` is requested. The backup lives in the hidden `appDataFolder`: it
is not listed in the person's Drive, only this app (matched by package name and signing certificate) can read it, and
it counts toward the person's Drive storage. The scope is non-sensitive, so no Google security review is needed (only
the basic OAuth brand check). `drive` and `drive.file` are deliberately not used. Authorization uses the Google
Identity Services Authorization API (`Identity.getAuthorizationClient`); there is no client secret in the app. Drive is
called over plain HTTPS (`DriveAppDataClient`): resumable upload in 8 MiB chunks that resume after a dropped
connection, list, ranged download, delete.

### What is backed up

One archive file, `samvad-vault-backup.svbk`: the vault's already-encrypted blobs (`.svl`, `.thumb`), the item rows
(type, size, dates and the sealed title/MIME column, exactly as in the database) and the vault data key, all sealed
again on the phone under an **archive key**. The Keystore key does not survive a new phone, so the archive key comes
from a **recovery secret** the person keeps:

| Choice | How the archive key is made |
|---|---|
| Recovery code (recommended) | 256 random bits shown as 52 characters in 13 groups of 4 (Crockford base32); key = HKDF-SHA256(code, random salt). The person must type back 3 random groups before backup turns on |
| Passphrase (at least 12 characters) | PBKDF2-HMAC-SHA256, 600,000 iterations, random salt. Argon2/scrypt would be better but need a native dependency; the iteration count is stored in the header and may be raised later |

The recovery secret is never stored. For scheduled backups the derived archive key is cached **wrapped by the Android
Keystore** (same wrapper as the data key) in `noBackupFilesDir/vault/backupkey.bin`, together with the data key already
sealed under the archive key (so a background backup never loads the plain data key and needs no unlock prompt).

### Archive format (`SVBK`, version 1)

Header (42 bytes, in the clear, authenticated): magic, version, KDF id, PBKDF2 iterations, 32-byte salt. Then records
in a fixed order: data key, manifest, items, files, end. Every record body is sealed with the vault envelope
(chunked AES-256-GCM, per-record HKDF key, per-chunk nonce with a last-chunk flag), and the associated data binds the
header, the record's position, kind and file name. Result: a wrong key, a changed byte, a swapped, dropped or repeated
record, data appended after the end, and truncation (even at a record boundary, because the end record carries the
counts) all fail with the same `VaultTamperedException`. A newer format version gives `VaultBackupTooNewException`.
Sizes in frames are bounded before anything is allocated (1 MiB for small records, 256 MiB for a file), and file names
must be `<uuid>.svl` or `<uuid>.thumb` of an item in the archive, so a crafted archive cannot write outside its folder.

### Backup run

`VaultBackupManager.runBackup`: skip an automatic run if the vault fingerprint is unchanged; write the archive to
`noBackupFilesDir/vault/backup_tmp/` (ciphertext only; the vault write lock is held so the archive is consistent);
check Drive quota; **upload the new file first, then delete older ones** (a failed upload never removes the last good
backup); delete the temp file. Manual "Back up now" runs in the app scope and survives leaving the screen. Automatic
backup is a daily WorkManager job, by default only on an unmetered network and while charging (both adjustable), with
exponential backoff for network errors. Last time, size, item count and last error are shown on the Backup screen.

### Restore

"Restore from backup" (needs the vault unlocked, so the biometric/app-lock gate applies): download the newest archive
(resumable), open and verify the whole archive into a scratch folder, **re-seal every file under this phone's data
key** (plain text exists only in an in-memory pipe), and only when everything is ready move the files in and add the
rows in one Room transaction. Any failure wipes the scratch folder and leaves the existing vault untouched. Items whose
id is already in the vault are kept as they are. A wrong secret keeps the downloaded file for another try until the
dialog closes.

### Turning off

"Turn off and delete backup" deletes the file from Drive, cancels the schedule and removes the cached key. If the
delete fails (no network, access revoked) backup stays on and the person is offered "Turn off anyway", which says
plainly that the copy stays in their Drive's hidden app data until they delete it there. Erasing the vault changes the
data key; the backup notices (key stamp) and refuses to keep uploading with the old key ("Turn off, then set it up
again").

### Errors the person sees

No network, Drive storage full, access revoked ("Connect again"), backup not set up (no OAuth client: friendly
"Backup isn't set up yet" state, never a crash), backup from a newer app version, wrong recovery secret, no backup in
this Google account, not enough space on the phone, vault changed since setup, damaged backup or key, Google busy.

### What this cannot do

- Samvad cannot recover a lost recovery code or passphrase. The setup screens and the privacy policy say so.
- A weak passphrase is only as strong as the person's choice (PBKDF2, not a memory-hard function).
- Google sees that a file of a certain size exists in the person's hidden app data. It cannot read it.
- The whole archive is re-uploaded when anything changed (no incremental upload yet); a very large vault on a slow
  connection takes a long time, and an interrupted upload resumes only within one run.
- Background backup runs without a biometric prompt (it needs only the Keystore-wrapped archive key). Anyone who can
  run code as the app on an unlocked phone can already reach the Keystore-wrapped data key too, so this adds no new exposure.
- Not verified on a device: the Authorization API consent flow and every Drive call (they need the OAuth client).
