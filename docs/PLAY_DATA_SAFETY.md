# Google Play: Data safety and App content answer sheet

Prepared 2026-10-21 from the code, `docs/CALLING_SETUP.md`, `docs/VAULT_DESIGN.md` and the website legal pages
(`website/src/content/legal/`). Keep this in step with the privacy policy. Items marked CHECK need the owner or a
lawyer (see `docs/LEGAL_REVIEW_NOTES.md`).

Wording rule (updated 2026-10-26): say "end-to-end encrypted" only for one-to-one chats between people who both have
keys, and only for a release built with `-Psamvad.e2ee=true`. Existing chats, group chats, polls, Moments, calls and
meetings are not end-to-end encrypted. The Data safety form field "Is all user data encrypted in transit" stays Yes.
Do not tick "independent security review" until the external review in `docs/security/E2EE_DESIGN.md` is done.
Analytics: the app contains no analytics, advertising or crash-reporting SDKs (checked in `app/build.gradle.kts`).

## 1. Data safety: top-level questions

| Question | Answer |
|---|---|
| Does the app collect or share any of the required user data types? | Yes |
| Is all user data encrypted in transit? | Yes (TLS to Firebase; call media encrypted in transit to LiveKit) |
| Do you provide a way for users to request data deletion? | Yes: Settings, Delete account; web page `https://<site>/delete-account` (redirects to `/legal/account-deletion`) |
| Independent security review | No. Do not claim one |
| Committed to the Play Families Policy | No (app is 18+, not for children) |

"Shared" in Play terms excludes transfers to a service provider that processes data on our behalf. Google
Cloud/Firebase and LiveKit Cloud are processors, so they are declared as **collected, not shared**. CHECK: lawyer to
confirm the processor position for LiveKit (no signed data processing agreement is recorded in the repo).

## 2. Data types

| Play data type | Collected | Shared | Purpose | Required or optional | Encrypted in transit | Deletion |
|---|---|---|---|---|---|---|
| Phone number (Personal info) | Yes | No | Account management, app functionality | Required | Yes | Deleted with account |
| Name (Personal info: display name, username) | Yes | No | App functionality, account management | Required (name); username optional | Yes | Edit any time; deleted with account |
| User IDs (Personal info) | Yes (account id, push token, per-install device id) | No | App functionality, account management | Required | Yes | Deleted with account |
| Other info: About text | Yes | No | App functionality | Optional | Yes | Edit any time; deleted with account |
| Contacts | Yes: phone numbers are sent to match accounts, **not stored**; names never leave the phone | No | App functionality | Optional (can still add by number) | Yes | Not retained, nothing to delete |
| Messages (Messages: other in-app messages) | Yes | No | App functionality | Required for messaging | Yes | Sender "Delete for everyone"; disappearing messages (24 h, 7 d, 90 d); messages you sent stay in others' chats after you delete your account |
| Photos and videos | Yes (sent in chats, profile photo, Moments) | No | App functionality | Optional | Yes | As messages; Moments expire after 24 h; profile photo deleted with account |
| Audio files (voice messages, voice Moments) | Yes | No | App functionality | Optional | Yes | As messages |
| Files and docs | Yes (documents sent in chats; 16 MB limit) | No | App functionality | Optional | Yes | As messages |
| Voice or sound recordings | Voice messages only (stored as audio files). **Live call audio is not recorded or stored by Samvad** | No (LiveKit is a processor) | App functionality | Optional | Yes | As messages |
| Calls (call history) | Yes: who, when, duration, outcome | No | App functionality | Optional (only if user makes calls) | Yes | Kept as the other person's call history after account deletion; live calls ended |
| App activity: other user-generated content (Moments, polls, reactions) | Yes | No | App functionality | Optional | Yes | Moments 24 h; others as messages |
| App info and performance: diagnostics | Yes: server logs without message content or phone numbers (request ids, counts, outcomes) | No | Security, fraud prevention, app functionality | Required | Yes | Up to 30 days |
| Device or other IDs | Yes: push token, install id, App Check/Play Integrity signals | No | Security and fraud prevention, app functionality | Required | Yes | Deleted with account |
| Approximate or precise location | **No** | | | | | |
| Financial info, health, web browsing, calendar, SMS or call log | **No** | | | | | |
| Advertising ID, ads, analytics | **No**. No ad or analytics SDKs | | | | | |

Notes for the form:

- Search index of messages, themes, wallpapers and app-lock settings stay on the device and are never sent, so they
  are not "collected" under Play's definition. The vault is excluded from Android's own backup and device transfer.
- **Vault backup to the user's own Google Drive (optional, off by default).** If the user turns it on, the vault's
  files, notes and titles are packed into one archive and encrypted on the phone with a key derived from a recovery
  code or passphrase that only the user holds, then uploaded to the hidden app-data folder of the user's own Google
  Drive (OAuth scope `drive.appdata` only; Samvad cannot see the user's other Drive files). Samvad's servers never
  receive the archive or the key, and Samvad cannot decrypt it. Proposed answer: not "collected" or "shared" by
  Samvad, because the transfer is initiated by the user to their own account and the data is encrypted with a key
  Samvad does not have. If Play review disagrees, declare it as Files and docs and Photos and videos, collected, not
  shared, optional, encrypted in transit, "user can request deletion" (Settings, Vault, Backup, Turn off and delete).
  CHECK: owner or lawyer to confirm the position before submission. The Google Cloud OAuth consent screen and the
  Play Data safety form must describe the same thing (see `docs/VAULT_BACKUP_SETUP.md`).
- Reports: the reported account and the last 5 messages of that chat are sent to Samvad's safety team (user-initiated).
  Declared under Messages; retained 90 days. For an end-to-end encrypted chat the report holds metadata only (sender,
  time, size) unless the reporter agrees to attach messages, which are then sent as plain text.
- End-to-end encryption keys: the public identity key and one-time prekeys are stored on our servers (declared with
  User IDs); private keys never leave the phone. Encrypted message bodies are stored as ciphertext in the same
  Messages data type.
- Appeals: text the user submits; deleted with the account.
- Linked devices: device id, name and last-used time, deleted with the account.
- Privacy settings (last seen, about, photo visibility, who can add me to groups, phone-number discovery) are stored
  on our servers so they can be enforced; deleted with the account.
- Two-step PIN: stored only as a salted scrypt hash.
- GIFs and stickers: search words are sent to our server and on to the GIF provider (GIPHY), with no account data; a
  sent GIF is a link, and the receiving phone loads the image from GIPHY's servers, so GIPHY can see that phone's IP
  address. Recent and favourite GIFs stay on the phone. No new Play data type applies (search words are not stored by us).
  CHECK: confirm this against GIPHY's current terms and privacy policy before filing the form.

### Tarang (Bluetooth nearby chat): answers to give

- **Collected by the developer:** nothing new. Tarang data never reaches Samvad servers. Nickname, random Tarang ID and
  messages go only to other devices over Bluetooth (peer to peer), so they are not "collected" or "shared" with
  a third party in Play's sense. State this in the review notes (CHECK with Play's wording on device-to-device transfer).
- **Location:** not collected. `BLUETOOTH_SCAN` is declared with `neverForLocation`. `ACCESS_FINE_LOCATION` is declared
  with `maxSdkVersion="30"` only because Android 11 and older require it for BLE scans; the app never reads location.
- **On device:** nickname (optional, shared preferences) and a random identity key (encrypted preferences). Messages are in
  memory only.
- **Nearby devices see:** random peer ID, nickname, public room messages (relayable by other phones).

## 3. Permissions justification

Permissions declared in `android-native/app/src/main/AndroidManifest.xml`. `USE_BIOMETRIC` is not declared in the
manifest by Samvad's own code; AndroidX Biometric may merge it in, so check the merged manifest before submitting (CHECK).

| Permission | Why the app needs it | When asked | If refused |
|---|---|---|---|
| INTERNET | Send and receive messages, calls, sync | Install time (normal) | App cannot work |
| BLUETOOTH_SCAN (`neverForLocation`), BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT | Tarang: find, be found by, and talk to nearby phones | When the person taps "Turn on Tarang" (Android 12+) | Tarang stays off; the rest of the app is unaffected |
| BLUETOOTH, BLUETOOTH_ADMIN (max SDK 30), ACCESS_FINE_LOCATION (max SDK 30) | Tarang on Android 11 and older, where BLE scanning needs location | When the person taps "Turn on Tarang" | Tarang stays off. No location is read or stored |
| FOREGROUND_SERVICE_CONNECTED_DEVICE | Keeps the Tarang Bluetooth mesh running in the background while Tarang is on | Not a runtime prompt | Mesh stops when the app is closed |
| ACCESS_NETWORK_STATE | Detect connectivity for sending, retry and call quality | Install time (normal) | n/a |
| READ_CONTACTS | Match the phone numbers in the address book to Samvad accounts; show saved names on device | Runtime, after an explanation | Add chats by phone number |
| POST_NOTIFICATIONS | New message, call and meeting notifications | Runtime (Android 13+) | No notifications |
| RECORD_AUDIO | Voice messages and voice and video calls | When recording or when a call starts or is answered | No voice messages or calls |
| CAMERA | Video calls and meetings, in-app photo and video capture | When a video call starts or is answered, or the user opens the camera | No video |
| MODIFY_AUDIO_SETTINGS | Route call sound to speaker or earpiece | Install time (normal) | n/a |
| BLUETOOTH_CONNECT | Use a Bluetooth headset during calls | Runtime (Android 12+) | Calls use speaker or earpiece |
| FOREGROUND_SERVICE | Run the call service | Install time (normal) | n/a |
| FOREGROUND_SERVICE_MICROPHONE | Keep microphone capture alive during a call or meeting | Install time (normal) | n/a |
| FOREGROUND_SERVICE_CAMERA | Keep camera capture alive during a video call or meeting | Install time (normal) | n/a |
| USE_FULL_SCREEN_INTENT | Show the incoming-call screen over the lock screen | Special access, revocable on Android 14+ | Incoming call arrives as a heads-up notification |
| USE_BIOMETRIC (if merged) | Optional app lock and Personal Vault unlock; handled by Android, nothing is sent to us | When the user turns on app lock or opens the vault | App lock off; vault cannot be opened |
| (none new) Vault backup | Uses INTERNET and ACCESS_NETWORK_STATE; scheduled backups use WorkManager, which merges RECEIVE_BOOT_COMPLETED so the daily job survives a restart | n/a | Backup stays off |

Hardware features `camera` and `microphone` are declared `required="false"`.

## 4. Declarations in Play Console, App content

### Foreground service permissions

`CallService` declares `foregroundServiceType="microphone|camera"`.

- Types: Microphone, Camera.
- Description: *User-initiated voice and video calls and group meetings. The service runs only while a call or
  meeting is connected, shows an ongoing notification with a Hang up button, and stops when the call ends.*
- Video to attach: start a call, show the ongoing notification, hang up.

`TarangService` declares `foregroundServiceType="connectedDevice"`.

- Type: Connected device.
- Description: *User-started nearby chat over Bluetooth. The service runs only after the person turns Tarang on, shows an
  ongoing notification with a Stop Tarang button, and stops when Tarang is stopped.*
- Video to attach: open Tarang, turn it on, show the notification, stop Tarang.

### Full-screen intent

- Use case: *Calling or alarm*.
- Justification: *Samvad is a messenger with voice and video calling. The permission shows the incoming-call screen when
  the phone is locked. On Android 14 and above the user can revoke it; the call then arrives as a heads-up
  notification with Accept and Decline.*

### Other declarations

- Ads: No ads.
- Target audience: 18 and over only. Not designed for children.
- News app, health, financial features, government app: No.
- Child safety standards URL: `/legal/child-safety-standards` (CSAE standards, in-app report, contact
  `contact@arlotechnologies.in`).

## 5. Content rating and user-generated content

Messenger with user-generated content and user-to-user communication. Answer the IARC questionnaire as a
communication app that allows users to interact and share content, with no age-restricted content produced by
the developer. Expect a rating that notes "users interact" and "shares content". Do not claim "none" for
user interaction. CHECK: confirm the final rating and the 18+ target audience with the owner.

User-generated-content safeguards (UGC policy):

- **Reporting:** in-app Report on messages, users, groups and Moments; the report carries the last 5 messages to the safety team.
- **Blocking:** block from a chat menu; blocked users cannot message, see presence or the profile photo.
- **Moderation and appeals:** the safety team reviews reports (retained 90 days); users can appeal in Settings, Help, Appeal a decision.
- **Rules and complaints:** Community Guidelines, Terms of Service, Grievance Redressal (acknowledge in 24 hours, resolve in 15 days; sensitive-content takedown in 24 hours), Child Safety Standards.
- **Prevention:** app-integrity checks (App Check), rate limits, file-type limits (no APKs), 18+ only.

## 6. Account deletion

- In app: Settings, Delete account, confirm with an SMS code or two-step PIN.
- Web (required by Play for apps with accounts): `/delete-account`, which redirects to `/legal/account-deletion`; also
  works without the app by emailing the grievance address. Deleted: account, profile, devices, tokens, starred and
  scheduled messages, blocks, two-step data, presence, memberships, appeals. Kept: messages already sent (other
  people's history), call records, reports up to 90 days, logs up to 30 days, a bare record that the account was deleted.
- Enter the web URL in Play Console, Data safety, Data deletion.

## 7. Privacy policy URL

`/legal/privacy-policy` (also `/privacy`). Add the full production domain when entering it in Play Console.

## Message translation (on device)

The Translate action in a chat uses Google ML Kit Translation and Language ID on the device. Message text is not sent to Samvad, Google or anyone else, and translations are held in memory only (not stored, not sent). The only network use is the one-time download of a language pack by Google Play services, which carries no message or account data. No new Data safety type is collected; no change to the answers above. Translation is offered for any text message you can read, including end-to-end encrypted chats, because it runs locally.
