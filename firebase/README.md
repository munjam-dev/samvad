# Samvad — Firebase backend

Firebase project for Samvad Messenger: security rules, Cloud Functions and an Emulator Suite test
suite. Specs: [B1 foundation + auth](../docs/superpowers/specs/2026-10-09-foundation-auth-design.md),
[B2 1:1 messaging](../docs/superpowers/specs/2026-10-13-b2-messaging-design.md).
The Android client must follow the client contract in §8 of that spec.

> Nothing here encrypts message content end to end. Do not describe Samvad as end-to-end encrypted.

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 (Temurin) — the Firestore, Database and Storage emulators run on the JVM |
| Node.js | 24 LTS (24.12 or newer) |
| firebase-tools | pinned devDependency (15.33.0); never install it globally for this project |

## Commands (run in `firebase/`)

```bash
npm install && npm --prefix functions install
npm test                 # builds functions, starts all five emulators, runs every test
npm run test:file -- rules-tests/firestore.users.test.ts   # one file
npm run test:e2e:logs    # end-to-end flow + log-hygiene check
npm run emulators        # start the emulators for manual work (project demo-samvad)
```

The `demo-samvad` project ID guarantees the emulators never touch a live project.

**Behind an HTTPS proxy:** firebase-tools sends its own requests to the local emulators through
`https_proxy`, ignoring `NO_PROXY`. Run the commands with the proxy unset once the emulator JARs are
cached, for example `env -u HTTPS_PROXY -u https_proxy npm test`.

## What is here

| Path | Purpose |
|---|---|
| `firestore.rules` | Deny-by-default rules for users, devices, block lists, profiles, conversations, messages, members |
| `storage.rules` | Chat media: members only, immutable uploads, 16 MB cap, content-type allow-list |
| `database.rules.json` | Presence (hidden by privacy setting or block), typing (members only), server-only mirrors |
| `firestore.indexes.json` | Chat-list index (`members` CONTAINS + `lastMessageAt` DESC) and a `members.uid` collection-group override |
| `functions/src/deleteAccount.ts` | Callable: tombstone → recursive delete → profile delete → Auth user delete; idempotent |
| `functions/src/signOutAllDevices.ts` | Callable: 60 s cooldown, revoke refresh tokens, stamp `tokensRevokedAt`, mark devices `revokedAt` |
| `functions/src/onMessageCreated.ts` | Trigger: sets the conversation's `lastMessage`, bumps other members' `unreadCount`; idempotent per event |
| `functions/src/lookupPhoneNumbers.ts` | Callable: up to 50 E.164 numbers → registered UIDs; 500 numbers per user per rolling day |
| `functions/src/onConversationCreated.ts`, `onBlockWritten.ts` | Triggers: mirror membership and block lists into RTDB for its rules |
| `functions/src/onMessageUpdated.ts` | Trigger: deletes a message's media file when the message is deleted |
| `functions/src/reportContact.ts` | Callable: report a contact with a 5-message evidence snapshot; optional block; 20 per day |
| `functions/src/lib/` | Phone-auth and recent-login guards; PII-free structured logger |
| `rules-tests/`, `functions/test/` | Emulator tests (rules, callables over HTTP, e2e flow) |
| `scripts/check-logs.mjs` | Fails if function output contains the test phone number or tokens |

Data model summary (full tables in spec §7):

- `users/{uid}` — owner-only; `phoneE164` must equal the token's phone number; immutable `createdAt`; consent version can only rise.
- `profiles/{uid}` — any phone-signed-in user can `get` one document; nobody can `list`; `photoUrl` must be null until B4.
- `users/{uid}/devices/{uuid}` — owner-only; `revokedAt` is server-only.
- `deletedAccounts/{uid}` — server-only tombstone; blocks re-creation with a deleted user's still-valid token.
- `conversations/{uidA_uidB}` — direct chats, members only; created by a member when both have profiles; `lastMessage` is trigger-written.
- `conversations/{cid}/messages/{uuid}` — client-generated IDs (idempotent retries); edit within 15 min, one-way soft delete, own-key reactions.
- `conversations/{cid}/members/{uid}` — read/delivered timestamps (forward-only), unread count (reset-only), mute/pin/archive.
- `users/{uid}/blocked/{otherUid}` — owner-only block list; while either side has blocked the other, nobody can send, edit or react in their direct chat.
- `reports/{id}`, `rateLimits/{uid}` — server-only.

The callables run in `asia-south1`, accept only phone sign-ins, ignore any UID in the payload, and enforce
App Check with replay protection (`consumeAppCheckToken`) everywhere except the Functions emulator.
Clients must send limited-use App Check tokens.

## Owner checklist before going live (spec §11)

1. Create projects `samvad-dev`, `samvad-staging`, `samvad-prod`; link billing (Blaze is required for Functions).
2. Register the Android app `com.samvad.messenger` with debug and release SHA-1/SHA-256 fingerprints.
3. Authentication: enable **Phone** only; disable Anonymous and every other provider; add test numbers in non-production; set the SMS region allow-list (India first).
4. Create Firestore (production mode) in a location matching the functions region (`asia-south1`), plus Realtime Database and Storage with default-deny rules. Locations cannot be changed later.
5. Restrict API keys by package name + SHA-1 and by API.
6. Set budget alerts and quotas for SMS, Firestore and Functions.
7. Provide the Terms of Service and Privacy Policy URLs for the app.
8. Rotate the legacy credentials listed in `docs/security/incident-leaked-credentials.md`.
9. Firestore → TTL: add a policy on collection group `reports`, field `expireAt` (reports expire after 90 days).

## Deployment

```bash
cp .firebaserc.example .firebaserc      # .firebaserc is gitignored; edit the project IDs
npx firebase login
npx firebase deploy --only firestore:rules,firestore:indexes,storage,database,functions --project dev
```

Deploy `dev` → `staging` → `prod`. Server code uses the runtime service account; never create, download
or commit a service-account key. `.gitignore` blocks `*-adminsdk-*.json`, `serviceAccount*.json` and `functions/.env*`.

## App Check rollout

1. Register **Play Integrity** (staging and release builds; needs a Play-distributed build) and the **Debug** provider (development; each developer registers their own debug token, never committed).
2. Ship the client with App Check initialised; watch the App Check metrics in **monitor** mode.
3. When verified traffic is close to 100%, **enforce** for Firestore, Storage, Realtime Database and Authentication (where available). Callables already enforce in code.

## What the emulators cannot prove

Real SMS delivery, Play Integrity attestation, App Check enforcement, the SMS region policy, deployed
rules and functions, and billing/quota alerts. Verify these on a live staging project.
