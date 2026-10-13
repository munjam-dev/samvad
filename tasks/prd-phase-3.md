# PRD: Phase 3 — Search, Saved and Scheduled Messages, Data Saver, Calm Mode, Indian Languages

Status: draft for owner review · 2026-10-13 · branch `phase-3` (from `claude/clever-pasteur-6shc8p`)

## 1. Introduction

Samvad is an Android messenger (Kotlin, Jetpack Compose) on a Firebase backend (Firestore, Realtime
Database, Storage, Cloud Functions). Phases 1 and 2 shipped 1:1 and group chat, media, presence, safety and
account security. Phase 3 makes the app useful every day for Indian users:

1. **Search** — find any message in one chat or across all chats, with filters, in English and Indian scripts.
2. **Saved messages** — star messages and find them again later, on any of my devices.
3. **Scheduled messages** — write now, send at a chosen time, even if my phone is off.
4. **Data saver** — control what downloads automatically and how much data uploads use.
5. **Calm mode** — quiet hours and a "do not disturb" for chats, without missing what matters.
6. **Indian languages** — the app's interface in Hindi and nine more Indian languages.

Every piece must meet the production bar set by Phases 1–2: deny-by-default security rules with emulator
tests, server-side enforcement for anything shared, no personal data in logs, green CI, and a spec that
matches the code.

### Owner decisions already made (2026-10-13)

| Question | Decision |
|---|---|
| Scope of this PRD | All of Phase 3 in one PRD; built and shipped in the order of section 3 |
| What search finds | All chats and within one chat, **with filters** (photos, files, voice, links, sender, date) |
| Where the search index lives | **On the phone** (private, offline, no extra cost, compatible with Phase 4 end-to-end encryption) |
| Older history on a new phone | **Download recent history in the background on Wi-Fi** (last 90 days) |
| Search languages | **English, Hindi and the other major Indian scripts** |
| UI aesthetic baseline | Apple Native (generous spacing, unified radii, fluid motion) layered on the existing Botanical Modernism tokens |

## 2. Goals

- A user finds a message by a word in it in under 5 seconds, in any supported script, with no network.
- Search returns results in under 300 ms (p95) on a mid-range phone holding 100,000 indexed messages.
- A scheduled message is delivered within 60 seconds of its chosen time in 99% of cases, phone on or off.
- On mobile data with default settings, the app downloads no file or document unless the user taps it.
- During quiet hours or calm mode, no message notification makes a sound or vibration unless the user has
  allowed it (favourite chats, @mentions).
- The whole interface is available in Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam,
  Punjabi and Odia, each reviewed by a native speaker before release.
- No new server path is readable or writable beyond its owner or chat members; every one is covered by
  emulator tests.

## 3. User stories

Stories are grouped by feature and listed in build order. Each is sized for one focused session.

**About UI verification.** This is a native Android app, so "verify in browser" becomes: **verify on the
Android emulator against the local Firebase emulators** (`docs/LOCAL_BUILD.md`), and attach a screenshot
(`adb exec-out screencap -p`) of each new screen and state to the pull request.

**About "checks pass".** For Android stories this means `./gradlew :core:test :app:assembleDebug
:app:testDebugUnitTest :app:lintDebug` passes. For backend stories it means `npm test` and
`npm run test:e2e:logs` pass in `firebase/`.

### 3.1 Search — foundation

#### US-001: Local message store and search index
**Description:** As a developer, I need an on-device database of messages with a full-text index so that
search works offline and fast.

**Acceptance Criteria:**
- [ ] Room database `samvad.db` with a `local_messages` table: `conversationId`, `messageId`, `senderId`,
      `createdAt`, `kind` (`text`/`image`/`file`/`voice`/`poll`), `text`, `fileName`, `hasLink`, `editedAt`;
      primary key (`conversationId`, `messageId`)
- [ ] An FTS4 virtual table (`unicode61` tokenizer) over the **normalized** text, file name and poll text
- [ ] A `conversation_sync` table: `conversationId`, `lastIndexedAt`, `backfilledUntil`, `backfillDone`
- [ ] Room schema exported to `android-native/app/schemas/` and checked in
- [ ] The database file is excluded from Android backup and device-transfer
- [ ] Checks pass

#### US-002: Text normalization for every supported script
**Description:** As a user writing in Hindi or Tamil, I want my search to match messages typed slightly
differently, so I find what I'm looking for.

**Acceptance Criteria:**
- [ ] A pure function `SearchText.normalize(String)` in `:core` that applies Unicode NFC, removes zero-width
      joiner/non-joiner (U+200D, U+200C) and soft hyphens, and lower-cases Latin letters (locale-independent)
- [ ] The same function is applied at index time and query time
- [ ] JVM tests cover English, Hindi, Marathi (Devanagari), Bengali, Gurmukhi, Gujarati, Odia, Tamil, Telugu,
      Kannada and Malayalam samples, including words with nukta, virama, conjuncts and composed/decomposed forms
- [ ] A query that is a prefix of a word matches it (for example `नम` finds `नमस्ते`, `hel` finds `Hello`)
- [ ] Checks pass

#### US-003: Index messages as they arrive
**Description:** As a user, I want new messages to be searchable as soon as I've received them.

**Acceptance Criteria:**
- [ ] Every message the app receives through a conversation listener is written to `local_messages`
- [ ] An edited message replaces its indexed text; a message deleted for everyone is removed from the index
- [ ] Group system events ("Asha added Ravi") are not indexed
- [ ] Poll questions and option texts are indexed; votes are not
- [ ] Indexing runs off the main thread; it never delays showing the message
- [ ] Checks pass

#### US-004: Background catch-up sync
**Description:** As a user, I want messages in chats I haven't opened to be searchable too.

**Acceptance Criteria:**
- [ ] A WorkManager job fetches messages newer than `lastIndexedAt` for every conversation whose
      `lastMessageAt` is later; for groups, never earlier than my `joinedAt`
- [ ] Runs on app start, after each incoming push, and every 6 hours with any network
- [ ] Pages of at most 200 messages per query; resumes after interruption without duplicates
- [ ] Unit tests cover resume, duplicate suppression, group `joinedAt` and edits/deletes
- [ ] Checks pass

#### US-005: History backfill on Wi-Fi
**Description:** As a user on a new or reinstalled phone, I want my recent history to become searchable
without using my mobile data.

**Acceptance Criteria:**
- [ ] A WorkManager job with constraints: unmetered network, battery not low
- [ ] Downloads up to 90 days of history per chat, at most 5,000 messages per chat and 50,000 in total
- [ ] Resumable and idempotent; stops cleanly when the network becomes metered
- [ ] Settings → Storage and data shows "Preparing search: N of M chats" while running and
      "Search covers the last 90 days" when done
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshot of the progress and done states)

### 3.2 Search — user interface

#### US-006: Search within a chat
**Description:** As a user, I want to search inside the chat I'm reading so I can jump to an old message.

**Acceptance Criteria:**
- [ ] A search icon in the conversation top bar opens a search field in place of the title
- [ ] Matches are highlighted in the bubbles; up/down buttons move between matches, newest first, with
      "3 of 17" shown
- [ ] Jumping to a match loads older pages as needed and scrolls the message to the centre with a brief
      highlight
- [ ] No match: "No messages match "<query>"" under the field
- [ ] Back or the close icon restores the normal top bar and scroll position
- [ ] All text comes from `strings.xml`; icon buttons have content descriptions
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: matches, no match)

#### US-007: Search across all chats
**Description:** As a user, I want one search box on the home screen that finds chats, people and messages.

**Acceptance Criteria:**
- [ ] The existing home search keeps its "Chats" results (name match) and adds a "Messages" section
- [ ] Each message result shows chat name and photo, sender (in groups), a one-line snippet with the match
      highlighted, and the date (`TimeLabels`)
- [ ] Tapping a result opens the chat scrolled to that message (as in US-006)
- [ ] Query is debounced by 150 ms; results appear within 300 ms (p95) on a 100,000-message index
- [ ] Results are ordered newest first; the list pages in 50 at a time
- [ ] Empty query shows recent chats as today; no results shows the existing empty state
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: mixed results, Devanagari results, no results)

#### US-008: Search filters
**Description:** As a user, I want to narrow search to photos, files, voice notes, links, a person or a date
range, so I can find "the PDF Ravi sent last month".

**Acceptance Criteria:**
- [ ] Filter chips below the field: Photos, Files, Voice, Links, From…, Date…
- [ ] "From…" opens a picker of people in my chats; "Date…" opens a date-range picker
- [ ] Filters combine with text (AND); filters with no text list all matching messages newest first
- [ ] Photo results show as a grid of thumbnails; others as rows
- [ ] Links are detected at index time (`hasLink`) with the same URL pattern the bubbles use
- [ ] The same chips are available inside a chat (US-006) except "From…" in direct chats
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: each chip active)

### 3.3 Saved messages

#### US-009: Saved messages backend
**Description:** As a developer, I need a private, synced list of each user's saved messages.

**Acceptance Criteria:**
- [ ] Firestore path `users/{uid}/savedMessages/{conversationId}_{messageId}` with fields
      `conversationId`, `messageId`, `savedAt` (server timestamp) and nothing else
- [ ] Rules: only `uid` may read, create and delete; create requires that `uid` is a member of the
      conversation; no updates; at most 5,000 items (enforced by a counter or a function)
- [ ] The two-step session check applies, as for every other user path
- [ ] `deleteAccount` removes the whole subcollection
- [ ] Emulator rules tests cover: owner allowed; other user denied; non-member denied; extra fields denied;
      update denied; deletion on account delete
- [ ] Checks pass

#### US-010: Star and unstar a message
**Description:** As a user, I want to star a message from its menu so I can find it later.

**Acceptance Criteria:**
- [ ] Long-press menu shows "Star" or "Unstar"; works for text, media, voice and polls; not for system
      events or deleted messages
- [ ] Starred messages show a small star next to the time in the bubble
- [ ] Works offline (queued by Firestore) and syncs to my other devices
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-011: Saved messages screen
**Description:** As a user, I want one screen listing everything I've starred.

**Acceptance Criteria:**
- [ ] Reached from the home overflow menu → "Saved messages" and from each chat's info screen (filtered to
      that chat)
- [ ] Newest saved first; each row shows the chat, sender, content preview and date
- [ ] Tapping opens the chat at that message
- [ ] If the message was deleted for everyone or I've left the group, the row says "This message is no longer
      available" and can be removed
- [ ] Empty state explains how to star a message
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: list, unavailable row, empty state)

### 3.4 Scheduled messages

#### US-012: Scheduled messages backend
**Description:** As a developer, I need to store messages that will be sent later, safely and within limits.

**Acceptance Criteria:**
- [ ] Firestore collection `scheduledMessages/{id}` with `senderId`, `conversationId`, `text`, `replyTo`
      (optional), `sendAt`, `status` (`pending` | `sending` | `sent` | `failed` | `cancelled`),
      `createdAt`, `updatedAt`, `failure` (optional: `not_member` | `blocked` | `account_deleted` | `invalid`)
- [ ] Rules: the sender may create (status `pending`), read their own, update `text`/`sendAt` or set
      `cancelled` only while `pending`; nobody else may read; `status` beyond that is server-only
- [ ] Create requires membership, valid text (1–4,096 characters, same as messages), and `sendAt` between
      now + 1 minute and now + 30 days; at most 100 pending per sender
- [ ] `deleteAccount` deletes the sender's scheduled messages
- [ ] Emulator rules tests cover every allow and deny above
- [ ] Checks pass

#### US-013: Send scheduled messages on time
**Description:** As a user, I want my scheduled message delivered at the right time even if my phone is off.

**Acceptance Criteria:**
- [ ] A Cloud Function runs every minute, claims due `pending` items in a transaction (`pending` →
      `sending`) so no item is sent twice, and writes the message exactly as a normal send would (same
      fields, `createdAt` = actual send time, normal push to recipients)
- [ ] At send time it re-checks: sender still a member, not blocked in either direction, sender account
      exists; otherwise sets `failed` with the reason
- [ ] On success sets `sent` with the new message ID; a `sent` item is deleted after 7 days
- [ ] The sender gets a notification for a failure ("Your scheduled message to <chat> couldn't be sent")
- [ ] Function logs carry only IDs, counts and outcomes, never text or phone numbers; the log-hygiene
      check covers this function
- [ ] Function tests on the emulators call the handler directly for: on-time send, double-claim race,
      every failure reason, cancelled and edited items
- [ ] Checks pass

#### US-014: Schedule a message
**Description:** As a user, I want to pick a date and time when sending a message.

**Acceptance Criteria:**
- [ ] Long-press on the send button opens "Schedule message" with date and time pickers in my time zone;
      quick choices "Tonight 9 PM", "Tomorrow 9 AM"
- [ ] Times earlier than 1 minute from now or later than 30 days are rejected with an explanation
- [ ] Text only in Phase 3 (media can't be scheduled; the option is hidden when a file is attached)
- [ ] After scheduling, the composer clears and a banner shows "1 scheduled message"
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-015: Manage scheduled messages
**Description:** As a user, I want to see, edit, send now or cancel my scheduled messages.

**Acceptance Criteria:**
- [ ] Tapping the chat banner opens the list for that chat; the home overflow menu → "Scheduled" lists all
- [ ] Each item: text preview, send time, status; failed items show the reason in plain words
- [ ] Actions while pending: Edit text, Change time, Send now (sends immediately as a normal message and
      cancels the scheduled item), Cancel
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: list, edit, failed item)

### 3.5 Data saver

#### US-016: Media auto-download settings
**Description:** As a user on a limited data plan, I want to choose which media download automatically on
each kind of network.

**Acceptance Criteria:**
- [ ] Settings → Storage and data → "Automatic downloads" with three rows: On Wi-Fi, On mobile data, When
      roaming; each lets me tick Photos, Voice notes, Files
- [ ] Defaults: Wi-Fi = all; mobile data = Photos and Voice notes; roaming = none
- [ ] Settings stored in DataStore and applied immediately
- [ ] When Android's system Data Saver is on, the app treats the network as roaming (nothing automatic)
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-017: Tap to download
**Description:** As a user, I want media that wasn't downloaded automatically to show what it is and how big it
is, so I can decide.

**Acceptance Criteria:**
- [ ] Undownloaded photos show a blurred placeholder (from stored width/height and a neutral fill) with a
      download button and the size ("1.2 MB")
- [ ] Voice notes show duration and size; files show name, type and size
- [ ] Tapping downloads with progress and a cancel option; failures show "Retry"
- [ ] Media I sent myself is never re-downloaded
- [ ] Checks pass
- [ ] Verify on the Android emulator (screenshots: placeholder, progress, downloaded)

#### US-018: Upload quality and "Use less data"
**Description:** As a user, I want photos I send on mobile data to use less data.

**Acceptance Criteria:**
- [ ] Settings → Storage and data → "Photo upload quality": Standard (current: 1,600 px long edge, JPEG 82)
      or Data saver (1,024 px, JPEG 70); stored in DataStore
- [ ] A "Use less data" switch that, when on: uses Data saver quality on mobile data, turns off automatic
      downloads on mobile data, and keeps history backfill on Wi-Fi only (already the case)
- [ ] `MediaRules` gains the Data saver constants with JVM tests
- [ ] Checks pass
- [ ] Verify on the Android emulator

### 3.6 Calm mode

#### US-019: Turn calm mode on for a while
**Description:** As a user, I want to silence chat notifications for a while without muting each chat.

**Acceptance Criteria:**
- [ ] A "Calm mode" control in the home overflow menu and in Settings → Notifications, with durations:
      1 hour, 8 hours, Until tomorrow 8 AM, Until I turn it off
- [ ] While on, message notifications are posted to a silent channel (`messages_quiet`, low importance):
      they appear in the shade without sound, vibration or pop-up
- [ ] A small moon icon on the home top bar shows calm mode is on, with time remaining in its description
- [ ] Works fully on the device (push is data-only); no server change
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-020: Quiet hours
**Description:** As a user, I want calm mode to turn on automatically every night.

**Acceptance Criteria:**
- [ ] Settings → Notifications → "Quiet hours": on/off, start and end time (default 22:00–07:00), and days
      of the week
- [ ] Windows that cross midnight work; daylight-saving and time-zone changes are handled
- [ ] Rules live in a pure `CalmPolicy` in `:core` with JVM tests for midnight crossing, day selection and
      overlap with manual calm mode
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-021: Exceptions and a summary
**Description:** As a user, I don't want to miss my family or being @mentioned, and I want to know what came in
while I was calm.

**Acceptance Criteria:**
- [ ] Two switches: "Allow favourite chats" and "Allow @mentions" (both on by default); allowed messages
      notify normally
- [ ] When calm mode or quiet hours end, if any messages arrived silently, one notification says
      "12 messages in 4 chats while calm mode was on"; tapping opens the chat list
- [ ] Muted chats stay muted regardless
- [ ] Checks pass
- [ ] Verify on the Android emulator

#### US-022: Hide unread counts
**Description:** As a user, I want to see that a chat has new messages without a number pressuring me.

**Acceptance Criteria:**
- [ ] Settings → Notifications → "Show unread counts" (on by default); when off, chat rows show a dot instead
      of a number and the app icon badge is off
- [ ] Checks pass
- [ ] Verify on the Android emulator

### 3.7 Indian languages

#### US-023: Choose the app language
**Description:** As a user, I want to use Samvad in my language, separately from my phone's language.

**Acceptance Criteria:**
- [ ] Settings → Language lists "System default" plus each shipped language in its own script
      ("हिन्दी", "বাংলা", "தமிழ்" …)
- [ ] Uses Android per-app languages (API 33+) and the AndroidX equivalent on API 26–32; the choice
      survives restart and appears in the system's per-app language settings
- [ ] `res/xml/locales_config.xml` lists only languages whose translation has passed review (US-024/025)
- [ ] Checks pass
- [ ] Verify on the Android emulator (API 34 and API 26 images)

#### US-024: Hindi translation
**Description:** As a Hindi speaker, I want every screen in natural, correct Hindi.

**Acceptance Criteria:**
- [ ] `values-hi/strings.xml` covers 100% of `values/strings.xml`, including plurals with Hindi plural
      categories and all Phase 3 strings
- [ ] A native-speaker review is recorded in `docs/l10n/hi-review.md` (reviewer, date, notes)
- [ ] A unit test fails the build if any shipped locale is missing a key that the default locale has
- [ ] No user-visible string is built on the server: notifications and group event lines are composed on
      the device from IDs and resources (verified by a code search in the review)
- [ ] Checks pass
- [ ] Verify on the Android emulator: every main screen in Hindi at 100% and 200% font scale, with no
      clipped or overlapping text

#### US-025: Nine more Indian languages
**Description:** As a speaker of Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Punjabi or
Odia, I want the app in my language.

**Acceptance Criteria:**
- [ ] One `values-<code>/strings.xml` per language (`bn`, `mr`, `te`, `ta`, `gu`, `kn`, `ml`, `pa`, `or`),
      100% coverage
- [ ] Each language is added to `locales_config.xml` only after its native-speaker review file exists in
      `docs/l10n/`
- [ ] Each script renders with the system Noto font at 100% and 200% font scale with no clipping
- [ ] Checks pass
- [ ] Verify on the Android emulator (one screenshot set per language)

#### US-026: Locale-aware formats
**Description:** As a user in India, I want numbers, dates and times written the way I read them.

**Acceptance Criteria:**
- [ ] Dates and times use the app locale (`TimeLabels` takes a `Locale`); 12/24-hour follows the system
      setting
- [ ] Large counts use Indian digit grouping where the locale uses it (1,00,000)
- [ ] File sizes and durations are localized
- [ ] JVM tests for `en-IN`, `hi-IN` and `ta-IN`
- [ ] Checks pass

### 3.8 Production readiness for Phase 3

#### US-027: Clean up on sign-out and account deletion
**Description:** As a user, I want nothing of mine left behind when I sign out or delete my account.

**Acceptance Criteria:**
- [ ] Sign-out and "Sign out of all devices" delete the local database, WorkManager jobs and calm-mode state
- [ ] `deleteAccount` removes `users/{uid}/savedMessages` and the user's `scheduledMessages`
      (function tests prove it)
- [ ] Checks pass

#### US-028: CI covers the new risks
**Description:** As a developer, I want CI to catch broken migrations, search regressions and missing
translations.

**Acceptance Criteria:**
- [ ] A Room migration test runs in CI (from schema version 1 onward)
- [ ] A new CI job runs instrumented tests on an API 34 emulator, including a search test over a seeded
      10,000-message index in Hindi and English, and the sign-in UI test that exists today
- [ ] The missing-translation unit test (US-024) runs in `:app:testDebugUnitTest`
- [ ] CI is green on the Phase 3 pull request

#### US-029: Documentation
**Description:** As the owner, I want the status documents to match what was built.

**Acceptance Criteria:**
- [ ] `docs/IMPLEMENTATION_STATUS.md` updated for Phase 2 (currently stops at Phase 1) and Phase 3
- [ ] `docs/LOCAL_BUILD.md` covers how to test scheduled messages and calm mode locally
- [ ] One spec per feature in `docs/superpowers/specs/` matches the code

## 4. Functional requirements

**Search**
- FR-1: The app must keep an on-device index of message text, file names and poll text for every
  conversation the user belongs to.
- FR-2: The index must update on receive, edit and delete-for-everyone, and must never include group system
  events or anything from before the user joined a group.
- FR-3: The app must catch up on new messages in the background (on start, on push, every 6 hours).
- FR-4: On Wi-Fi with battery not low, the app must backfill up to 90 days per chat (≤ 5,000 per chat,
  ≤ 50,000 total), resumably.
- FR-5: Text must be normalized identically at index and query time (NFC, strip ZWJ/ZWNJ and soft hyphen,
  lower-case Latin); prefix matching must work in every supported script.
- FR-6: Search must be available within a chat and across all chats, newest first, paged by 50.
- FR-7: Search must support filters Photos, Files, Voice, Links, From (sender) and Date range, combinable with
  text and usable without text.
- FR-8: Selecting a result must open the chat scrolled to the message, loading older pages if needed.
- FR-9: The local database must be excluded from backups and deleted on sign-out and account deletion.

**Saved messages**
- FR-10: A member must be able to star or unstar any non-deleted, non-system message.
- FR-11: Saved items must be stored at `users/{uid}/savedMessages/{conversationId}_{messageId}`, readable
  and writable only by `uid`, at most 5,000 per user.
- FR-12: Saved items must store references only, never a copy of the message, so delete-for-everyone and
  group-leave rules still apply.

**Scheduled messages**
- FR-13: A member must be able to schedule a text message for 1 minute to 30 days ahead, at most 100 pending.
- FR-14: Only the sender may read a scheduled message; only the server may move it past `pending`.
- FR-15: The server must send due messages within 60 seconds of `sendAt`, exactly once, re-checking
  membership, blocks and account state.
- FR-16: A failed scheduled message must notify the sender; a sent one is deleted after 7 days.
- FR-17: The sender must be able to edit, reschedule, send now or cancel while `pending`.

**Data saver**
- FR-18: The user must be able to choose automatic downloads per network type (Wi-Fi, mobile, roaming) and
  per media kind (photos, voice notes, files), with the defaults in US-016.
- FR-19: Android's system Data Saver must be treated as roaming.
- FR-20: Undownloaded media must show its kind and size, and download on tap with progress, cancel and retry.
- FR-21: Photo upload quality must be selectable (Standard, Data saver); "Use less data" applies Data saver
  quality and turns off automatic downloads on mobile data.

**Calm mode**
- FR-22: The user must be able to turn calm mode on for 1 hour, 8 hours, until 8 AM or until turned off.
- FR-23: The user must be able to set recurring quiet hours (start, end, days).
- FR-24: During calm mode or quiet hours, message notifications must post silently, except favourite chats
  and @mentions when allowed.
- FR-25: When calm ends, one summary notification must report silently received messages, if any.
- FR-26: The user must be able to hide unread counts (dot instead of number, no icon badge).

**Languages**
- FR-27: The interface must be available in English plus `hi`, `bn`, `mr`, `te`, `ta`, `gu`, `kn`, `ml`,
  `pa`, `or`, selectable per app.
- FR-28: A language may ship only with 100% string coverage and a recorded native-speaker review.
- FR-29: No user-visible text may be generated on the server.
- FR-30: Dates, times, numbers, sizes and durations must follow the app locale.

**Cross-cutting**
- FR-31: Every new Firestore path must be deny-by-default with emulator rules tests for each allow and deny.
- FR-32: New functions must log only IDs, counts and outcomes, and be covered by the log-hygiene check.
- FR-33: All new strings must live in `strings.xml` with plurals; all icon-only controls must have content
  descriptions; all touch targets must be ≥ 48 dp.

## 5. Non-goals (out of scope for Phase 3)

- Server-side or cloud search, and searching messages older than what the phone has indexed
- Cross-script search (typing `namaste` to find `नमस्ते`) and transliteration
- Scheduling photos, files, voice notes or polls
- Recurring scheduled messages
- Saved-message folders, tags or notes
- Per-chat calm settings beyond the existing mute
- A data-usage statistics screen
- Urdu and other right-to-left languages (the layout stays RTL-safe for later)
- Encrypting the local database with SQLCipher (Android file-based encryption and the app sandbox apply)
- End-to-end encryption (Phase 4)

## 6. Design considerations

- **Baseline:** Apple Native restraint — generous spacing, unified radii, fluid but short motion — on the
  existing Botanical Modernism tokens (`docs/superpowers/specs/2026-10-13-android-design-system-client.md`).
  Use only theme tokens; no hex values in screens.
- **Reuse:** `SamvadTextField` for search fields; existing chip, empty-state and list-row components; the
  home search field; `TimeLabels` for dates; the long-press message menu.
- **Search results:** highlight with `primaryContainer` behind the matched characters, never colour alone.
- **Placeholders:** neutral `surfaceContainer` fill for undownloaded photos; no shimmer loops.
- **Calm mode:** a quiet moon icon, no banners or nagging; the summary notification is a single line.
- **Languages:** never fix widths on labels; allow two-line buttons; test every script at 200% font scale.
- **Accessibility:** TalkBack reads search results as "Message from Asha in Family, 3 March: …"; every new
  control has a content description and a ≥ 48 dp target.

## 7. Technical considerations

- **Local database:** Room (KSP) with an `@Fts4` entity using the `unicode61` tokenizer, which Android's
  built-in SQLite supports from API 26. Unicode61 treats combining marks (matras, virama) as part of a word,
  so Indic words index whole; normalization (US-002) handles composed/decomposed forms. Bundling a newer
  SQLite is not needed.
- **Background work:** add WorkManager. Backfill uses `NetworkType.UNMETERED` and `requiresBatteryNotLow`.
- **Firestore cost:** backfill is up to 50,000 reads per user once; catch-up reads only new messages. Budget
  alerts (owner checklist) must include this.
- **Groups:** every message query must filter `createdAt >= joinedAt`, as the rules require.
- **Scheduled sending:** `onSchedule("every 1 minutes")` (Cloud Scheduler, Blaze plan) querying
  `status == pending && sendAt <= now`, limited to 500 per run, with a composite index. The Functions
  emulator does not fire schedules, so tests call the handler directly. Cloud Tasks per message is the
  alternative if one-minute polling proves too coarse.
- **Scheduled text at rest:** stored in Firestore until sent, like normal messages today. In Phase 4 the client
  will store ciphertext instead.
- **Notifications:** push is data-only and composed on the device, so calm mode, exceptions and localization
  are all device-side. A second channel `messages_quiet` (low importance) is needed because Android fixes a
  channel's importance once created.
- **Per-app language:** API 33+ `LocaleManager`; API 26–32 via `AppCompatDelegate.setApplicationLocales`
  with `autoStoreLocales` (adds the AppCompat dependency if not present).
- **Fonts:** Inter covers Latin only; Indic scripts fall back to system Noto fonts. Check line height for
  tall scripts (Malayalam, Tamil, Odia) so nothing clips.
- **Files touched (expected):** `data/ChatRepository.kt` (index hooks, jump-to-message), new
  `data/search/*`, `data/SavedRepository.kt`, `data/ScheduledRepository.kt`, `data/Preferences.kt`,
  `push/SamvadMessagingService.kt`, `push/Notifications.kt`, `ui/home/*`, `ui/chat/*`, `ui/settings/*`;
  `core/search/SearchText.kt`, `core/calm/CalmPolicy.kt`, `core/media/MediaRules.kt`,
  `core/time/TimeLabels.kt`; `firebase/firestore.rules`, `firebase/firestore.indexes.json`,
  `firebase/functions/src/scheduled.ts`, `firebase/functions/src/deleteAccount.ts`.

## 8. Success metrics

- Search p95 under 300 ms on a 100,000-message index on an API 30, 4 GB RAM device; index size under
  1.5× the raw text size.
- 99% of scheduled messages delivered within 60 s of `sendAt` in staging over one week; zero duplicates.
- With default settings on mobile data, zero automatic file downloads (verified by a test).
- Zero sounds or vibrations from disallowed chats during calm mode (verified by unit tests on `CalmPolicy`
  and a manual pass).
- 100% string coverage and a recorded review for each shipped language.
- Crash-free sessions ≥ 99.5% on the staging build during the Phase 3 test pass.

## 9. Open questions

1. **Calm mode** is defined here as manual calm + quiet hours + exceptions + summary + hidden counts. Is that
   what you mean by calm mode, or something else (for example, hiding online status or read pressure)?
2. **Languages:** should all ten ship in Phase 3, or Hindi first and the other nine as each review finishes?
   Who will review each language?
3. **Backfill window:** is 90 days and 5,000 messages per chat right, or do you want a setting
   ("30 days / 90 days / 1 year")?
4. **Scheduled messages and two-step PIN:** if the sender turns on two-step after scheduling, should pending
   items still send? (Proposed: yes; they were created in a valid session.)
5. **Saved messages after leaving a group:** keep the row as "no longer available" (proposed) or remove it
   automatically?
6. **Device check first:** the app has never been run end-to-end on a device. Fix what the two-emulator check
   finds before starting US-001? (Proposed: yes.)
