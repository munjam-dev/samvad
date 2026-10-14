# PRD: Disappearing messages

## Introduction

Let people choose that messages in a chat delete themselves after a set time. The timer is stored on the
conversation and **enforced by the backend**, not just hidden in the app: expired messages and their
photos, voice notes and files are actually deleted. This is the first privacy feature of the "Chat lock,
disappearing messages and view-once" group in the Samvad master prompt, and it works the same in direct
chats and groups.

What it is not: Samvad is not end-to-end encrypted yet. Disappearing messages limit how long the server
and the other person's phone keep a message. They do not stop screenshots, another phone photographing
the screen, or a recipient copying text. The app and the policies must say so.

Current backend facts this PRD builds on:
- Messages live at `conversations/{cid}/messages/{mid}`. Rules allow clients to write only a fixed list of
  fields, so a client cannot set an expiry on its own.
- Media lives at `conversations/{cid}/{mid}/<file>` in Storage. `onMessageUpdated` already deletes media
  when a message is marked deleted. It does not run when a document is removed.
- Direct conversations are created by the client under strict rules; group conversations are created and
  changed only through callable functions (admin checks live there).
- Per-user chat state (pin, mute, archive) lives in the member's own `members/{uid}` document.

## Goals

- A per-chat timer with these choices: Off, 24 hours, 7 days, 90 days.
- A default timer for new chats, chosen in Settings.
- Expiry is set by the server and applied to the message, its media and its notification data.
- Everyone in the chat sees when the timer changes, and what it is, as a system line.
- Expired messages disappear from the screen promptly even before the server job runs.
- Nothing about the feature breaks existing chats: timer Off is the default.

## User Stories

### US-001: Store and validate the timer on the conversation
**Description:** As a developer, I need the timer stored where the rules and functions can read it, and
changeable only through the server.

**Acceptance Criteria:**
- [ ] `conversations/{cid}.disappearAfterSec` is an integer: `0` (off), `86400`, `604800` or `7776000`; missing means off
- [ ] Firestore rules do **not** allow any client to write this field directly (creation and updates denied by the existing key allow-lists)
- [ ] A rules test proves a member cannot set or change `disappearAfterSec`
- [ ] `firestore.indexes.json` needs no new index for existing queries (state this after checking)
- [ ] Emulator tests pass

### US-002: Callable to change the timer
**Description:** As a chat member, I want to turn the timer on or off so my chat matches how private I want it.

**Acceptance Criteria:**
- [ ] New callable `setDisappearingTimer({ conversationId, seconds })` with App Check and the existing two-step check
- [ ] Direct chat: either member may change it. Group chat: only admins may change it
- [ ] Rejects non-members, blocked pairs, invalid values and deleted accounts with the same error style as other callables
- [ ] Rate limited (for example 20 changes per chat per day) using the existing `rateLimits` helper
- [ ] Writes a server-authored system line message ("Anjanna turned on disappearing messages: 7 days" / "turned them off") using the existing group-event message shape
- [ ] Emulator tests cover: member allowed, non-member denied, non-admin in a group denied, invalid value denied, system line written, rate limit

### US-003: Server stamps an expiry on every new message
**Description:** As a user, I want a message to expire on time even if the sender's phone is offline.

**Acceptance Criteria:**
- [ ] `onMessageCreated` reads the conversation's timer and, when above 0, writes `expiresAt = createdAt + timer` (a Firestore Timestamp) on the message
- [ ] The timer in force **at send time** applies; changing the timer later does not rewrite old messages
- [ ] System lines, polls and media messages all get `expiresAt`; messages in chats with the timer off do not
- [ ] `expiresAt` is not in the client write allow-list, and a rules test proves a client cannot set or change it
- [ ] Emulator test: send in a 24-hour chat, assert `expiresAt` is within a second of `createdAt + 24 h`

### US-004: Delete expired messages for real
**Description:** As a user, I want expired messages gone from the server, not just hidden.

**Acceptance Criteria:**
- [ ] A Firestore TTL policy on `messages.expiresAt` (collection group `messages`) is declared in `firestore.indexes.json` `fieldOverrides` and deployed
- [ ] New `onDocumentDeleted` trigger on `conversations/{cid}/messages/{mid}` deletes that message's Storage folder (`conversations/{cid}/{mid}/`) and its votes
- [ ] The trigger is idempotent and logs only ids and counts, never message text or phone numbers
- [ ] A scheduled-by-TTL deletion and a manual document delete both remove the media (emulator test deletes the document and asserts the file is gone)
- [ ] Documented honestly: Firestore TTL deletes within about 24 hours of `expiresAt`, so the app must hide expired messages itself (US-005)
- [ ] The Cloud Run CPU quota limit is noted as a deploy dependency in `docs/OWNER_SETUP.md`

### US-005: App hides expired messages immediately
**Description:** As a user, I want a message to vanish at its time, not up to a day later.

**Acceptance Criteria:**
- [ ] Pure function in `:core` (for example `Disappearing.isExpired(message, nowMs)`) with unit tests: expired, not yet expired, no expiry, clock skew tolerance of 60 s
- [ ] `Timeline.build` (or the chat list) skips expired messages; the chat list preview falls back to the last unexpired message or an empty preview
- [ ] While a chat is open, messages disappear at their time without leaving the screen (a timer re-evaluates on the next expiry only, not continuously)
- [ ] Notification text for an expired message is not shown once expired
- [ ] `./gradlew :core:test` passes
- [ ] Verify on the emulator with a short test timer build flag, not by waiting 24 hours

### US-006: Chat settings: choose the timer
**Description:** As a user, I want to set the timer from the chat menu.

**Acceptance Criteria:**
- [ ] Chat menu gets "Disappearing messages", opening a sheet with Off, 24 hours, 7 days, 90 days (radio rows, 48 dp targets)
- [ ] In a group, non-admins see the current value but the rows are disabled with a short explanation
- [ ] Choosing a value calls `setDisappearingTimer`, shows progress, and shows a clear error on failure (offline, not allowed, rate limited) without changing the UI value
- [ ] The chat header shows a small timer icon with a content description (for example "Disappearing messages: 7 days") while a timer is on
- [ ] Strings are in `strings.xml`; works at 200 % font scale and with TalkBack
- [ ] `./gradlew :app:assembleDebug :app:lintDebug` pass with 0 errors
- [ ] Verify on the emulator in light and dark theme

### US-007: System line and in-chat indicator
**Description:** As a user, I want to be told when the timer changes so nobody is surprised.

**Acceptance Criteria:**
- [ ] The system line from US-002 renders in the existing system-line style, localised through string resources with the person's name and the duration
- [ ] A short, plain notice sits at the top of an empty chat with a timer on: what it does and what it does not prevent (screenshots, copying)
- [ ] Verify on the emulator

### US-008: Default timer for new chats
**Description:** As a user, I want new chats to start with my preferred timer.

**Acceptance Criteria:**
- [ ] Settings, Privacy gets "Default message timer" (Off, 24 hours, 7 days, 90 days), default Off
- [ ] When the user creates a chat or group, the default is applied once by calling `setDisappearingTimer` after creation, and the system line says so
- [ ] Existing chats are never changed by this setting (text under the setting says so)
- [ ] The setting is stored in `AppPreferences` (DataStore) following the `autoDownload` pattern, with a unit test for parsing unknown values to Off
- [ ] Verify on the emulator

### US-009: Starred, forwarded and exported copies
**Description:** As a user, I need to know what happens to copies of an expiring message.

**Acceptance Criteria:**
- [ ] Starring a message in a chat with a timer shows a notice that the starred copy also disappears when the original does, and the starred copy is removed when the message expires (the app deletes its own `savedMessages` entry when it sees the original expire; the server also removes it on message deletion)
- [ ] Forwarding an expiring message is allowed but the copy gets the **target** chat's timer, not the original's
- [ ] "Export chat" excludes expired messages and its confirmation dialog warns that exported files are outside the timer
- [ ] Emulator tests for the server removal of `savedMessages`; unit tests for the app rules

### US-010: Cleanup of local caches
**Description:** As a user, I want expired photos and voice notes gone from my phone too.

**Acceptance Criteria:**
- [ ] When a message expires, its cached media file (`MediaRepository.cacheFile`) is deleted
- [ ] On app start, cached files whose message no longer exists are removed within a bounded amount of work (no full-disk scan on the main thread)
- [ ] Verify with `adb shell run-as` that the cache file is removed after expiry (test build flag)

## Functional Requirements

- FR-1: A conversation has `disappearAfterSec` in `{0, 86400, 604800, 7776000}`; absent means 0.
- FR-2: Only the `setDisappearingTimer` callable may change it. Clients cannot write it, and cannot write `expiresAt`.
- FR-3: In a direct chat either member can change the timer; in a group only admins can.
- FR-4: Each new message gets `expiresAt = createdAt + timer` from the server when the timer is on at send time.
- FR-5: Changing the timer affects only messages sent afterwards.
- FR-6: Firestore TTL deletes messages after `expiresAt`; a delete trigger removes the message's media, votes and starred copies.
- FR-7: The app hides messages once `expiresAt` has passed (with a 60 s skew tolerance) and removes their cached media.
- FR-8: Every timer change writes a visible system line naming who changed it and the new value.
- FR-9: A default timer setting applies to chats the user creates from then on, never to existing chats.
- FR-10: Logs contain only ids, counts and outcomes. No message text, phone numbers or tokens.

## Non-Goals (Out of Scope)

- View-once photos, videos and voice notes (separate PRD).
- Chat lock and a hidden locked-chat folder (separate PRD).
- Per-message custom timers, or timers shorter than 24 hours.
- Preventing screenshots, screen recording or copying. The product must not claim it can.
- End-to-end encryption, and anything that depends on it.
- Calling, Status, Channels and Communities.
- Changing or deleting messages that were sent before the timer was turned on.

## Design Considerations

- Follow the project's UX profile (Apple Native restraint on the Botanical Modernism tokens): `MaterialTheme` and `SamvadTheme` tokens only, 150 to 300 ms ease-out, nothing animated on a loop, all non-essential motion off when system animations are off.
- Reuse `RadioRow` and `SwitchRow` patterns from `SettingsScreen.kt`, `SystemLineChip` from `ConversationScreen.kt`, and the bottom-sheet style used by the schedule and forward sheets (`ScheduleViews.kt`, `ForwardViews.kt`).
- Layouts must pass 360 dp and 840 dp widths and 200 % font scale.
- Copy must be plain and honest, for example "Messages in this chat disappear after 7 days. People can still screenshot or copy them."

## Technical Considerations

- Firestore TTL requires a Timestamp field and a TTL policy per collection group; deletion is not instant (typically within 24 hours), which is why US-005 is required.
- TTL deletes fire `onDocumentDeleted`, so one trigger covers both TTL and manual deletes. Deleting a Storage folder by prefix is supported by the Admin SDK (`bucket.deleteFiles({ prefix })`).
- Adding a new callable, a new trigger and a TTL policy means a Functions deploy. The deploy is currently blocked for new functions by the Cloud Run CPU quota in asia-south1 (see `docs/OWNER_SETUP.md`); this feature cannot go live until that is raised.
- Messages already carry `createdAt` as a server timestamp, so `expiresAt` math is server-side and clock-safe; the app uses the server timestamp plus a skew tolerance.
- Test with an emulator-only short timer (for example 20 seconds) behind a build flag so no test waits 24 hours. The flag must not exist in release builds.
- The notification data-only push carries no message text when previews are off; when previews are on it must not be shown after expiry.

## Success Metrics

- Emulator test suite: all new rules and function tests pass, and the existing suite (200+ tests) still passes.
- `:core:test` passes with the new expiry tests.
- In a test chat with a 20-second timer: the message disappears from both phones' screens within 5 seconds of expiry, and its Storage object and cached file are gone within 1 minute of the trigger running.
- Zero client-writable paths to `disappearAfterSec` and `expiresAt` (proved by rules tests).

## Open Questions

- Should the starred copy of an expiring message survive? This PRD says no (US-009); WhatsApp's "Keep in chat" exception is deliberately left out of v1.
- Should group admins be able to let all members change the timer? v1 says admins only.
- Should the default timer also apply to chats the user is *added to* in a group? v1 says no; only chats they create.
- Should expired messages leave a "message expired" placeholder, or vanish with no trace? v1 vanishes with no trace, but the chat list may need a stable preview.
- Whether to offer a 1-hour or 5-minute option later (shorter timers make TTL's 24-hour delay more visible; they would need a scheduled sweeper function).
