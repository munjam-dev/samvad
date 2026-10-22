# Vault backup: Google Cloud setup (owner steps)

The code for Vault backup is in the app. It cannot talk to Google until you do the steps below once. Until then the
Backup screen shows "Backup isn't set up yet" and nothing else is affected. Design and security notes:
`docs/VAULT_DESIGN.md` ("Backup to Google Drive").

What the app asks Google for: exactly one scope, `https://www.googleapis.com/auth/drive.appdata` ("See, create, and
delete its own configuration data in your Google Drive"). It is a **non-sensitive** scope: the backup goes to a hidden
app-data folder that does not show in the person's Drive listing and that only your app can read. Do **not** add
`drive`, `drive.file` or `drive.readonly`; the app does not use them, and they would trigger Google's review.

There is no client secret and no client ID in the app code. Google recognises the app by its **package name plus the
SHA-1 of the certificate that signed it**, so what you create below must match those exactly.

## 0. What you need to know first

| Build | Package name | Signed with |
|---|---|---|
| Debug (`assembleDebug`, Android Studio Run) | `com.samvad.messenger.debug` (the debug build adds `.debug`) | the debug keystore |
| Release from your own keystore | `com.samvad.messenger` | your release keystore |
| Release installed from Google Play | `com.samvad.messenger` | the **Play App Signing** certificate, not your upload key |

Each (package name, SHA-1) pair needs its own Android OAuth client. For a full setup that is three clients (debug,
your own release keystore, Play App Signing). All three live in the same Google Cloud project.

## 1. Choose the Google Cloud project

Use the project that belongs to the Firebase project (Firebase console, Project settings, "Project ID"), so that all
Samvad services and the OAuth consent screen are in one place. Open https://console.cloud.google.com/ and select it.

## 2. Enable the Google Drive API

APIs & Services, Library, search "Google Drive API", **Enable**. If this is skipped, backups fail with
"accessNotConfigured" and the app shows "Backup isn't set up yet".

## 3. OAuth consent screen (Google Auth Platform)

Open APIs & Services, OAuth consent screen (in newer consoles: Google Auth Platform, then Branding, Audience, Data Access).

1. **Branding:** app name `Samvad`, user support email, developer contact email. Add the app logo only if you want the
   brand check (see step 6). Authorized domain: the domain of your website (for example `web.app` is not allowed as a
   top-level authorized domain, so use your own domain once you have one). App home page, privacy policy
   (`https://<site>/legal/privacy-policy`) and terms (`https://<site>/legal/terms-of-service`) links must be public pages on that
   domain. The privacy policy already describes Vault backup (section 3.14).
2. **Audience:** user type **External**.
3. **Data access:** Add or remove scopes, tick `.../auth/drive.appdata`, save. It is listed under "Non-sensitive scopes".
   Nothing else should be there.
4. **Audience, Test users:** while the app is in **Testing**, add the Google accounts of everyone who will try backup
   (up to 100). Anyone else gets "access blocked" on Google's screen.

## 4. Create the Android OAuth clients

APIs & Services, Credentials, **Create credentials, OAuth client ID**, application type **Android**. Repeat for each row:

| Name | Package name | SHA-1 certificate fingerprint |
|---|---|---|
| Samvad debug | `com.samvad.messenger.debug` | debug keystore SHA-1 |
| Samvad release (own keystore) | `com.samvad.messenger` | release keystore SHA-1 |
| Samvad Play | `com.samvad.messenger` | Play App Signing SHA-1 |

Where to get the fingerprints:

- Debug: `cd android-native` then `./gradlew :app:signingReport` and copy the `SHA1` of variant `debug`; or
  `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android`.
- Release (own keystore): `keytool -list -v -keystore <your .jks> -alias <your alias>` (the keystore named by
  `SAMVAD_RELEASE_STORE_FILE`, see `docs/RELEASE_CHECKLIST.md`).
- Play App Signing: Play Console, your app, Test and release, App integrity, App signing, "App signing key certificate",
  SHA-1. (Also add the "Upload key certificate" SHA-1 if you want to test an upload-key-signed bundle on a device.)

Nothing from these clients has to be copied into the app. Do not download a secret; Android clients have none.

A fingerprint change (new keystore, new Play App Signing key) means a new client; until it exists, backup shows
"Backup isn't set up yet" on that build.

## 5. Check that it works (no code changes)

1. Install a build whose package and SHA-1 you registered. Make sure the phone has a Google account and Google Play services.
2. Open Samvad, Settings, Privacy, Personal Vault, unlock, three-dot menu, **Backup**, **Set up backup**,
   **Allow Google Drive access**. Google's consent screen must list only access to the app's own hidden data.
3. Save the recovery code, type back the three groups, **Turn on backup**. The first backup starts at once.
4. Prove the file exists and is hidden: https://drive.google.com, Settings (gear), Manage apps: Samvad shows "Hidden app
   data" with a size and an option to delete it. It does not appear in My Drive.
5. Restore test: on a second phone or emulator (or after Clear storage), sign in, open the vault, Backup,
   **Restore from backup**, type the recovery code. Items must come back and open.
6. Negative tests: wrong code (should say it doesn't open the backup and let you retry), airplane mode during a backup
   (clear message, retry works), remove access at https://myaccount.google.com/permissions (the screen shows "Connect
   again"), Turn off and delete backup (file disappears from Hidden app data).

If the app says "Backup isn't set up yet" after you pressed **Allow Google Drive access**, Google reported
DEVELOPER_ERROR for this package and certificate. Check, in this order: the package name (debug builds end in `.debug`),
the SHA-1 (copy it again, no spaces), that the client is in the same project as the consent screen, and wait a few minutes
(new clients can take a short while to work).

## 6. Publishing status and verification

| Status | What happens |
|---|---|
| **Testing** | Only listed test users can connect. Google expires the grant after 7 days, so backups on test accounts show "Connect again" about weekly. Fine for development |
| **In production** | Everyone can connect and grants do not expire. Because `drive.appdata` is non-sensitive, there is **no security assessment and no sensitive-scope verification**. Google may still run the basic **brand verification** (checks app name, logo, home page and privacy policy links on your authorized domain) if you set a logo or home page; it usually takes a few days and, until it is done, the consent screen can show "unverified" branding. You can publish without a logo to keep it minimal |

Publish: Audience, **Publish app**, confirm. Do this before real users rely on backup. Re-check that the privacy policy
and the Play Data safety form (`docs/PLAY_DATA_SAFETY.md`) match what the consent screen says.

## 7. Costs and limits

The Drive API is free to use. The backup counts toward the **person's own** Google storage (15 GB free per account), not
yours. Each backup is one file replaced after the new copy is uploaded, so the person briefly needs room for two copies.
API quota (about 12,000 requests per minute per project by default) is far above what backups use.

## 8. Things only you can decide or check

- Whether the lawyer agrees with the privacy-policy paragraph and the Data safety position (`docs/LEGAL_REVIEW_NOTES.md`).
- Whether to later add a memory-hard passphrase function (Argon2id) via a native dependency.
- The recovery code cannot be reset by anyone. Decide what your support team says to people who lose it: the backup cannot
  be opened, and the right move is to turn backup off and set it up again.

## 9. Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Backup isn't set up yet" right after allowing access | No Android OAuth client for this package + SHA-1 (step 4) |
| "Backup isn't set up yet" after access was granted | Google Drive API not enabled in the project (step 2) |
| Google shows "access blocked" or "app not verified for this account" | App in Testing and the account is not a test user (step 3.4), or the consent screen has extra sensitive scopes |
| "Connect again" every week | App still in Testing (step 6) |
| "Samvad no longer has access" | The person removed access in their Google account, or the grant expired; tap Connect again |
| "Your Google Drive storage is full" | The person's quota; they free space or buy storage |
