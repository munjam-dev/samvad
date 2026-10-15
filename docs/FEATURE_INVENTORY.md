# Samvad feature inventory (master prompt audit)

Audited 2026-10-15 against the repository at branch `phase-3`. Status values:

- **Done**: built, enforced on the server where relevant, and covered by tests or a device check noted below.
- **Partial**: some of it exists; the gap is named.
- **Missing**: not started.
- **Blocked**: needs an external service, credential or decision (named).

Architecture: Android (Kotlin, Jetpack Compose, Material 3), Firebase (Auth phone, Firestore, Realtime
Database for presence and typing, Storage, Cloud Functions v2 on Node 24 in asia-south1, App Check, FCM).
The backend stays Firebase. Excluded by the product brief and not built: AI features, business features,
payments and commerce.

## Authentication and account

| Feature | Status | Notes |
|---|---|---|
| Phone registration, OTP, resend cooldown, expired and invalid code handling | Done | Firebase Phone Auth; test numbers configured |
| Profile name, photo, about | Done | Photo only through a function that validates JPEG and strips metadata |
| Contact sync with permission, add contacts, saved names | Partial | Phone lookup is a rate-limited callable (500/day). Hashed private discovery is Missing |
| Block, unblock, report | Done | Rules deny messaging both ways; reports are server-only, 20/day |
| Logout, account deletion, sign out everywhere | Done | Deletion removes the account and its data. An account disabled for abuse stays disabled, which blocks that number |
| Two-step verification PIN | Done | Server-enforced, scrypt hash, lockout, 7-day reset |
| Active sessions and device list with remote logout | Partial | `devices` collection and sign-out-everywhere exist. A per-device list and single revoke screen is Missing |
| Phone number change, account recovery beyond the PIN reset | Missing | |
| Passkeys | Blocked | Not offered by Firebase Phone Auth; would need a provider change |
| Optional username | Missing | |
| Security notifications (new device sign-in) | Missing | |
| Parent-managed accounts | Missing | Needs a consent and authorization design before any code |

## One-to-one messaging

| Feature | Status | Notes |
|---|---|---|
| Text, replies, edit (15 min), delete for everyone, reactions, forward (max 5), copy | Done | |
| Typing, online, last seen, hide last seen, read receipts setting | Done | RTDB rules, members only |
| Delivery and read states | Done | Shown only from backend state |
| Star messages (private list) | Done | |
| Schedule send | Partial | App and rules done. Deployed 2026-10-15 after the Cloud Run CPU quota in asia-south1 was raised. Not yet checked end to end on a device |
| Search within a chat | Done | On device, accent and case insensitive; 7 unit tests pass. Not yet checked on a device |
| Global message search, search by media type, links, documents, date | Missing | |
| Pin messages | Missing | |
| Formatting (bold, italic, strike, monospace) | Missing | |
| Link previews | Missing | |
| Contact cards, location, live location | Missing | Live location also needs a background-location design and Play policy declaration |
| Polls | Done | Group polls with votes; single-choice. Multi-choice Missing |
| Events and RSVP | Missing | |
| GIFs, stickers, custom sticker packs | Blocked | GIF search needs a provider (Tenor or Giphy key). Stickers are Missing |
| Unread separator, scroll-to-latest | Partial | Scroll-to-latest Done; unread separator Missing |
| Offline queue and duplicate prevention | Done | Firestore offline persistence; stable client message ids |
| Pagination | Done | |

## Media, documents, voice

| Feature | Status | Notes |
|---|---|---|
| Photos, files, voice notes, viewer, upload retry | Done | Storage rules: members only, immutable, 16 MB, type allow-list, no APKs |
| Voice playback with waveform | Done | |
| Playback speed, pause and resume recording, multiple selection, captions | Missing | |
| Video send, trimming, image crop and overlays | Missing | |
| Media gallery per chat, storage management | Missing | |
| Auto-download settings, data saver | Partial | Built 2026-10-15: setting (always / Wi-Fi only / never), Data saver switch (1024 px, JPEG 70 uploads), tap-to-download photo placeholder. 8 unit tests pass, lint 0 errors, settings screen and persistence checked on the emulator. The placeholder and metered-network behaviour are **not yet checked on a device** |
| View-once media | Missing | |

## Groups

| Feature | Status | Notes |
|---|---|---|
| Create, name, photo, add and remove, admins, invite links, mentions, polls, leave | Done | Enforced by callables and rules |
| QR invitations, join approval, who-can-add-me | Missing | |
| Group events, group calls | Missing | |

## Communities, Status, Channels

| Feature | Status | Notes |
|---|---|---|
| Communities | Missing | Needs data model, rules and an announcement-group authorization design |
| Status updates (24 h expiry, audience, replies, reactions, viewers) | Partial | Backend built and deployed 2026-10-15: rules for text and photo posts, server-set audience (`visibleTo`) and 24 h expiry, views and reactions, Storage rules, cleanup trigger, TTL policy; 24 rules tests and 13 function tests pass. App (built 2026-10-15): Status row on Home, text composer with 5 backgrounds, photo composer, audience picker (everyone / everyone except / only), full-screen viewer with progress bars, reactions, private reply, viewers list for the author, mute, delete. 12 core tests pass, lint 0 errors. **Checked on the emulator:** posting a text status, the My status ring, and the viewer. **Not yet checked:** viewing someone else's status (needs a second account), photo statuses, reactions, replies, audience picker, expiry |
| Channels (follow, admins, updates, discovery, moderation) | Missing | |

## Calling

| Feature | Status | Notes |
|---|---|---|
| Voice and video, group calls, call history, screen share, PiP | Missing | |
| | Blocked | Real calling needs a WebRTC stack (`org.webrtc` or a managed SFU such as LiveKit), signalling through Firestore or functions, and a TURN service. TURN and group-call media are paid or self-hosted infrastructure. **Decision needed** |

## Privacy and security

| Feature | Status | Notes |
|---|---|---|
| App lock (biometric / screen lock), screenshot block, private notification previews | Done | |
| Tapjacking guard, deep-link validation, no app backups | Done | |
| Rate limits (lookup, reports, photos, PIN) | Done | |
| Disappearing messages (default and per chat) | Partial | Built 2026-10-15. Server: timer callable (admins only in groups), server-stamped expiry, TTL policy, cleanup of files, votes, starred references and the chat-list preview; 18 emulator tests, full backend suite 239 pass. App: per-chat timer sheet, header icon, system line, hides expired messages on time, default timer in Settings; 10 core tests pass, lint 0 errors. Backend deployed 2026-10-15 (`setDisappearingTimer`, `onMessageDeleted`, TTL policy); **not yet checked on a device**. Still to build: starred-copy notice and export warning (US-009), local cache cleanup (US-010) |
| Chat lock with hidden folder | Missing | |
| Privacy checkup, profile-photo and about visibility controls | Missing | |
| **End-to-end encryption** | Missing | The server can read messages today. The site and policies say so. A reviewed protocol (libsignal) must be integrated, with key storage, multi-device keys, attachment and group encryption, and key verification. This is the largest single item and must precede linked devices and encrypted backups |
| Encrypted backups | Blocked | Depends on E2EE |
| Linked devices and multi-device sync | Blocked | Depends on E2EE key design |

## Notifications

| Feature | Status | Notes |
|---|---|---|
| New-message push, muted chats, per-chat mute, dead-token pruning, deep links | Done | Data-only FCM |
| Group notifications, missed-call notifications, custom tones, mute durations | Partial / Missing | |

## Organisation, customisation, accessibility

| Feature | Status | Notes |
|---|---|---|
| Archive, pin, mute chats, filters (unread, groups), export chat | Done | |
| Favourites, custom lists, mark unread, clear and delete conversation | Missing | |
| Light, dark, system theme; chat themes with India doodles; wallpapers | Done | |
| Hide unread counts, Calm mode | Done | Built 2026-10-14; not checked on a device yet |
| Reduced motion, content descriptions, 48 dp targets, contrast tests | Done | TalkBack and 200 % font check need a device |
| Indian languages | Missing | About 440 strings; needs translations and native-speaker review |
| In-app help centre, report a problem, notification troubleshooting | Partial | Website has FAQ, support and legal centre |

## Release readiness

| Item | Status |
|---|---|
| Debug build, unit tests (`:core:test`), Firebase emulator tests | Passing as of the last run in each area |
| Instrumented UI tests, manual device pass, accessibility audit, memory-leak check | Not run |
| Play Store: developer account, signed release build, listing, data-safety form, content rating | Not started (owner) |
| Website | Live at https://samvad-messenger.web.app |

## Build order from here

1. Data saver and auto-download settings. 2. Disappearing messages (backend TTL plus UI). 3. Pin messages, formatting, link previews,
unread separator. 4. Media gallery and global search. 5. Indian languages (Hindi first). 6. Status. 7. Communities and Channels.
8. **E2EE (libsignal)**. 9. Calling, once the infrastructure decision is made. 10. Linked devices and encrypted backup, which depend on 8.

## Decisions needed from the owner

- **Calling infrastructure:** managed (LiveKit Cloud, Twilio or similar, paid per minute) or self-hosted SFU and TURN (a VM and ops work).
- **GIFs:** which provider and a key.
- **E2EE timing:** it changes how search, backups and multi-device work. Doing it before Status, Channels and linked devices avoids rework.
- **Cloud Run CPU quota** in asia-south1, so scheduled messages can be deployed.
