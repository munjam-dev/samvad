# PRD: Chat Look and Chat Themes

Status: draft for owner review · 2026-10-13 · branch `phase-3`

## 1. Introduction

Samvad's conversation screen works (the two-phone check on 2026-10-13 covered sending, receipts, typing,
reactions, edits, replies, groups, mentions, polls and photos), but it does not yet feel like the
messengers people in India already use every day. The owner asked for three things:

1. **Bold Header** as the default home layout.
2. **Familiar message rows** in both group and personal chats: a **round profile photo beside** incoming
   messages, the sender's **saved name above** the message, and the **time below** the text inside the
   bubble, the way WhatsApp lays them out.
3. **Chat themes:** five built-in themes plus the option to use **your own photo** as the chat background.

Two small items from the owner's wider brief are included because they belong on the same screens:

4. A **privacy notice** at the top of a conversation, with wording that is **true today**.
5. A quiet **branded footer** at the end of the chat list.

Everything else in the brief (Moments, Communities, calls, camera, broadcast lists, linked devices, and so on)
is planned in section 10 as separate phases, each with its own PRD.

### Owner decisions (2026-10-13)

| Question | Decision |
|---|---|
| App name | **Samvad** ("Somewhere" in the brief was a placeholder) |
| Default home layout | **Bold Header**; Clean and Card & bento stay available in Settings |
| Scope of this PRD | The three requests above (plus notice and footer); the rest of the brief goes to a phased roadmap |
| Glass effects | **Not used.** The premium feel comes from soft solid surfaces, fine borders and spacing (`.ux-profile.md` ban list stays) |
| Privacy notice | **Accurate wording now**; switch to end-to-end wording only after Phase 4 ships and is verified |
| Photo beside messages | In **group and personal** chats (WhatsApp shows it only in groups; the owner wants both) |
| Themes | One default theme for all chats, which any chat can override; your own photo as an option |

## 2. Goals

- New installs open on the Bold Header home layout.
- In any chat, a glance at an incoming message shows who sent it (photo and name) and when.
- Names match the user's own phone contacts wherever the sender is a saved contact.
- Users can pick from 5 built-in themes or their own photo, for all chats or for one chat, in 3 taps or fewer.
- Every theme keeps message text at **7:1 contrast or better** (WCAG AAA, per `.ux-profile.md`) in light and dark mode.
- Nothing on the screen claims a protection the app does not provide.

## 3. User stories

**Checks.** "Checks pass" means `./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
passes. This is a native Android app, so the brief's "verify in browser" becomes **verify on the Android
emulator** against the local Firebase emulators (`docs/LOCAL_BUILD.md`), with screenshots of each new
state attached to the pull request.

### US-001: Bold Header is the default home layout
**Description:** As a new user, I want the home screen to open in the Bold Header layout so that the app
looks the way the owner intends from the first launch.

**Acceptance Criteria:**
- [ ] `Preferences.homeLayout` defaults to `HomeLayout.BOLD` when nothing is stored
- [ ] A user who already picked a layout in Settings keeps their choice
- [ ] Settings → Home layout shows Bold header selected on a fresh install
- [ ] Unit test for the default and for a stored value
- [ ] Checks pass
- [ ] Verify on the Android emulator (fresh install screenshot)

### US-002: Saved contact names in chats
**Description:** As a user, I want people to appear under the name I saved in my phone so that I recognise
them instantly, as I do in other messengers.

**Acceptance Criteria:**
- [ ] A local `SavedNames` store maps account ID → name from the phone's contacts, built from the existing
      contacts lookup (`ContactsRepository.lookup`) when contacts permission is granted
- [ ] Refreshed at most once a day, and immediately after the user grants contacts permission; it respects the
      server's existing 500-lookups-per-day limit
- [ ] Display name rule everywhere a person is named (chat header, message rows, group member lists, chat
      list, mentions): **saved contact name** if the person is in my contacts, otherwise **"~ Profile name"**
      (tilde prefix, as WhatsApp does for people you haven't saved)
- [ ] Phone numbers of people who are not in my contacts are **never shown** (the server doesn't reveal them)
- [ ] The map lives only on the device, is never uploaded, and is deleted on sign-out
- [ ] JVM tests for the name rule (saved, unsaved, blank profile name, my own messages)
- [ ] Checks pass

### US-003: Message rows with photo, name and time
**Description:** As a user reading a chat, I want each incoming message to show the sender's round photo
beside it, their name above the text and the time below it, so I can follow the conversation easily.

**Acceptance Criteria:**
- [ ] **Incoming, first message of a run** (a run = consecutive messages from one sender within 2 minutes,
      as `Timeline` already groups them): a **32 dp round avatar** sits to the left of the bubble, aligned to
      the bubble's top; the **sender name** is the first line inside the bubble, in that sender's stable
      colour (existing `SenderLabel`); the bubble has its tail on the top-left corner
- [ ] **Incoming, later messages in the run:** no avatar, no name, no tail; the bubble is indented by the
      avatar's width so the run lines up
- [ ] Applies to **group and personal chats**; in personal chats the name shown is the other person's
      (US-002 rule)
- [ ] **Outgoing messages:** no avatar and no name (unchanged); tail on the top-right of the first in a run
- [ ] **Time and ticks** sit on the last line inside the bubble, aligned to the end; for short single-line
      messages they share the text's line when there is room, otherwise they wrap below, as in WhatsApp
- [ ] Photos load through the existing `ProfilePhotos` path (SDK read through the rules); people without a
      photo, or who have blocked me, show initials on their stable colour
- [ ] Tapping the avatar or name opens that person's info sheet: name, photo, about, Message, and in groups
      the admin actions the viewer is allowed
- [ ] Media, voice, file, poll, reply, forwarded and deleted bubbles all follow the same layout
- [ ] TalkBack reads each message as "<Name>, <message>, <time>" once, not the avatar separately
- [ ] `Timeline.Bubble` gains `showAvatar` (first incoming of a run) computed in `:core`, with JVM tests
- [ ] Checks pass
- [ ] Verify on the Android emulator: a group run, a personal-chat run, mixed media, at 100% and 200% font
      scale

### US-004: Theme catalogue
**Description:** As a developer, I need five built-in chat themes defined as design tokens so that every
screen can draw them consistently in light and dark mode.

**Acceptance Criteria:**
- [ ] `ChatTheme` in `core/design` with: id, name (string resource key), background colour, optional
      pattern, incoming bubble colour, outgoing bubble colour, bubble text colour, meta (time/tick) colour,
      date-chip colour, for **light** and **dark** (AMOLED uses dark with a black base)
- [ ] Five themes, all within the botanical palette:
  1. **Ivory** (default): today's look, ivory background
  2. **Sage**: pale sage background, white incoming bubbles
  3. **Monsoon**: cool blue-grey background
  4. **Marigold**: warm champagne background with a soft marigold accent
  5. **Night Garden**: deep forest background (dark-first; uses its dark tokens in both modes)
- [ ] An optional fine botanical line pattern (vector, ≤ 6% opacity) for Ivory and Sage; no gradients, no
      glass, no blur
- [ ] A JVM contrast test proves, for every theme × light/dark: bubble text on its bubble ≥ 7:1, meta text
      on its bubble ≥ 4.5:1, date-chip text ≥ 7:1
- [ ] No hex values outside `core/design`
- [ ] Checks pass

### US-005: Choose a chat theme
**Description:** As a user, I want to pick a theme for all my chats and see a preview before applying it.

**Acceptance Criteria:**
- [ ] Settings → Chats → **Chat theme** opens a picker: a grid of the five themes plus **"My photo"**, each
      tile showing the background and two small bubbles
- [ ] A live preview at the top shows an incoming and an outgoing bubble on the selected theme, labelled
      "Preview" (no fake names or messages presented as real)
- [ ] **Apply** saves the choice (DataStore) and returns; **Cancel** leaves it unchanged
- [ ] The choice applies immediately to every conversation screen without a restart
- [ ] Works in light, dark and AMOLED modes
- [ ] Checks pass
- [ ] Verify on the Android emulator (each theme in light and dark)

### US-006: My photo as a chat background
**Description:** As a user, I want to use one of my own photos as the chat background so that the app
feels personal.

**Acceptance Criteria:**
- [ ] "My photo" opens the Android Photo Picker (no storage permission needed)
- [ ] A positioning screen lets the user pan and zoom the photo to fit the phone's shape, then **Set**
- [ ] A **Dim** slider (0–60%) darkens the photo so bubbles stay readable; default 20%, 35% in dark mode
- [ ] The chosen photo is downscaled to the screen size, re-encoded as JPEG (location and other EXIF data
      removed), stored in app-private storage, and **never uploaded**
- [ ] Bubbles stay opaque on photos, so text contrast is guaranteed by the bubble colours
- [ ] Replacing or removing the photo deletes the old file; sign-out deletes it
- [ ] If the file is missing (e.g. app data cleared), the chat falls back to the default theme without errors
- [ ] Checks pass
- [ ] Verify on the Android emulator (portrait photo, landscape photo, dim 0% and 60%)

### US-007: A different theme for one chat
**Description:** As a user, I want a special theme for one chat (for example, family) while others keep the
default.

**Acceptance Criteria:**
- [ ] Chat menu (⋮) → **Chat theme** opens the same picker with an extra first option **"Use default"**
- [ ] The override is stored per conversation ID on the device
- [ ] A chat with an override keeps it when the default changes
- [ ] Leaving a group or deleting a chat removes its override and photo file
- [ ] Checks pass
- [ ] Verify on the Android emulator

### US-008: Apply the theme to the conversation screen
**Description:** As a user, I want the whole conversation screen to follow my theme.

**Acceptance Criteria:**
- [ ] The background (colour, pattern or photo) fills the area behind the messages; the top bar and composer
      keep their solid surfaces
- [ ] Incoming and outgoing bubbles, time and tick colours, date chips, system lines, the poll card and the
      reply preview use the theme's tokens
- [ ] Scrolling performance stays smooth: the background is drawn once and does not recompose with the list
- [ ] Checks pass
- [ ] Verify on the Android emulator: scroll a 200-message chat on each theme without visible jank

### US-009: Accurate privacy notice
**Description:** As a user opening a chat, I want a short, honest note about how my messages are protected.

**Acceptance Criteria:**
- [ ] Shown as a small centred card at the top of every conversation's history (first thing above the
      oldest message, and in empty chats above the welcome line)
- [ ] Copy (until Phase 4): **"Your messages are protected"**, then *"Messages are encrypted while they
      travel and are stored securely on Google's servers. End-to-end encryption is coming to Samvad."*,
      then a **Learn more** link
- [ ] **Learn more** opens a bottom sheet that explains: what is protected now (encryption in transit, at
      rest, access rules, two-step PIN, app lock); what is not yet protected (the server can technically read
      messages; reports include the last 5 messages; exported chats are plain text); what changes with
      end-to-end encryption; a **Privacy policy** link and a **Close** button
- [ ] The notice can be revisited from Chat info → Encryption
- [ ] The copy lives in `strings.xml` behind one flag (`e2eeEnabled = false`), so Phase 4 switches the
      wording in one place
- [ ] Checks pass
- [ ] Verify on the Android emulator

### US-010: Branded footer on the chat list
**Description:** As the owner, I want a quiet signature at the end of the chat list.

**Acceptance Criteria:**
- [ ] After the last chat row: **#SamvadBeginsHere**, then *"Proudly built in Hyderabad, India."* and
      *"Designed for meaningful connections, wherever life takes you."* in `labelSmall`, `onSurfaceVariant`,
      centred
- [ ] Shown only when the list has at least one chat, below the content (never floating or sticky); not
      shown in search results
- [ ] Not read as a list item by TalkBack (one combined description)
- [ ] Checks pass
- [ ] Verify on the Android emulator

## 4. Functional requirements

- FR-1: With no stored preference, the home layout must be Bold Header.
- FR-2: A person's display name must be their saved contact name when they are in the user's contacts,
  otherwise "~ " + profile name; phone numbers of non-contacts must never be displayed.
- FR-3: The account-to-contact name map must be stored only on the device and deleted on sign-out.
- FR-4: The first incoming message of each run must show a 32 dp round avatar to its left and the sender
  name as the first line inside the bubble; later messages in the run must be indented without them.
- FR-5: This layout must apply to both group and personal chats; outgoing messages show neither.
- FR-6: Each bubble must show its time (and ticks for outgoing) at the end of its last line.
- FR-7: Tapping a sender's avatar or name must open their info sheet.
- FR-8: The app must offer 5 built-in chat themes (Ivory, Sage, Monsoon, Marigold, Night Garden) plus "My photo".
- FR-9: Every theme must meet 7:1 contrast for message text and date chips, and 4.5:1 for time and ticks, in
  light and dark mode, enforced by a unit test.
- FR-10: The user must be able to set a default theme and a per-chat override, with "Use default" to clear it.
- FR-11: A custom photo must be chosen with the Photo Picker, positioned, dimmable (0–60%), stored downscaled
  and EXIF-free in app-private storage, and never uploaded.
- FR-12: Conversation screens must apply the theme to the background, bubbles, chips, system lines and polls.
- FR-13: The privacy notice must state only what is true now and switch wording via a single flag.
- FR-14: The chat list must end with the #SamvadBeginsHere footer when it has at least one chat.

## 5. Non-goals (out of scope for this PRD)

- Glassmorphism, blur or translucent layers (banned in `.ux-profile.md`)
- Renaming the app to "Somewhere"
- Changing app-wide light/dark/AMOLED themes or accent colours (chat themes only)
- Syncing themes or custom photos across devices or into backups
- Animated or video backgrounds
- Moments, Communities, calls, camera, broadcast lists, linked devices, QR scanning, custom lists and the
  other brief items: see section 10
- Claiming end-to-end encryption before Phase 4

## 6. Design considerations

- **Baseline:** Apple Native restraint on Botanical Modernism tokens (`.ux-profile.md`): generous spacing,
  unified radii (bubble 20 dp with a 6 dp tail corner), short motion, no decorative gradients.
- **Message row geometry:** avatar 32 dp with 8 dp gap to the bubble; screen gutter 12 dp; consecutive
  bubbles 2 dp apart, new runs 8 dp apart; maximum bubble width 78% of the row.
- **Names:** `labelMedium` 600 in the sender's stable colour, checked to ≥ 4.5:1 on the incoming bubble of
  every theme (the colour set is adjusted per theme where needed).
- **Theme tiles:** 3 per row on phones, 5 on 840 dp screens; selected tile has a 2 dp primary outline and a
  check icon (Material Icons Outlined), never colour alone.
- **Reuse:** `Avatar`/`ProfilePhotos`, `SenderLabel`, `MessageBubble`, `SamvadTextField`, existing bottom
  sheets, the Settings row components.
- **Accessibility:** every theme tile and the dim slider have content descriptions; 48 dp targets; the
  preview is not focusable as messages.

## 7. Technical considerations

- **Default layout:** change the fallback in `Preferences.kt` (`HomeLayout.BENTO` → `HomeLayout.BOLD`) for
  both the data class default and the read path.
- **Names:** `SavedNames` (DataStore or a small Room table once Phase 3 search adds Room) filled from
  `ContactsRepository.deviceContacts()` + `lookup()`; `ConversationViewModel.displayName()` and the
  `groupNames` lambda passed to `Timeline.build` use it first.
- **Timeline:** add `showAvatar` to `TimelineItem.Bubble` (true for the first incoming bubble of a run in
  both chat types); set `senderName` for personal chats too.
- **Themes:** tokens in `core/design/ChatThemes.kt`; selection in `Preferences` (`chat_theme_default`,
  `chat_theme_overrides` as a JSON map of conversation ID → theme ID, `chat_wallpaper_dim`); custom photo file
  at `filesDir/wallpapers/<default|cid>.jpg`.
- **Photo processing:** decode with `ImageDecoder` at a target size, re-encode JPEG 85, which drops EXIF;
  run on `Dispatchers.IO`.
- **Performance:** draw the background with `Modifier.drawBehind` on the `Box` behind the `LazyColumn`; load
  the photo once into an `ImageBitmap` held by the screen.
- **No backend changes** in this PRD, apart from none needed for names (the lookup callable already exists).

## 8. Success metrics

- Fresh installs open on Bold Header (verified on a clean emulator).
- In a 5-person group test, testers identify the sender of 20 random messages with 100% accuracy at a glance.
- Every theme × mode passes the contrast unit test; zero AAA failures.
- Setting a theme takes ≤ 3 taps from Settings; setting My photo takes ≤ 5 taps.
- Conversation scroll stays at 60 fps on a mid-range emulator with a photo background.

## 9. Open questions

1. In personal chats, should the name above each run be shown, or only the photo? (The PRD shows both, as
   asked; WhatsApp shows neither in 1:1 chats.)
2. Should the five theme names be translated, or kept as brand names in every language?
3. Is the privacy-notice wording in US-009 acceptable, or should legal review it first?

## 10. Roadmap for the rest of the brief (each phase gets its own PRD)

Order follows the brief's development sequence, adjusted to what already exists.

| Phase | Scope | Notes |
|---|---|---|
| **R0 · Backend hardening** (owner request: "make the backend more perfect") | Deploy to the real "Samvad messenger" project; regression tests for the 2026-10-13 bugs; App Check enforcement; budget alerts; orphaned-media sweeper; unread-counter repair; Firestore backups; monitoring and alerting; load test; rules audit; rotate leaked legacy credentials | Detailed below |
| R1 · Chats home | Header with account, camera and ⋮ menu (new group, new community, broadcast, linked devices, starred, mark all read, settings); filters All · Unread · Favorites · Groups · +; custom lists; swipe and long-press actions; draft previews | Custom lists already planned in Phase 3 |
| R2 · Messaging completeness | Video, audio files, contact and location sharing, link previews, text formatting, pinned messages, multi-select, disappearing messages, in-chat search, media browser, draft persistence | Search, starred and scheduled messages are in `tasks/prd-phase-3.md` |
| R3 · Camera | Real CameraX capture (photo, video, flash, front/rear), preview, captions, send to chats or Moments | |
| R4 · Moments | 24-hour updates with audience controls, viewer lists, mute; enforced by rules and a TTL policy | New bottom-bar destination |
| R5 · Communities | Communities containing groups, roles, join requests, moderation, discovery of public communities | Builds on Phase 2 groups |
| R6 · Calls | 1:1 and group voice/video (WebRTC with a TURN service or a managed provider), call history, scheduled calls, incoming-call notifications | Largest effort; needs a calling-provider decision |
| R7 · Profile and Settings | Profile with Samvad ID and personal note, QR scan, linked devices, the full settings set (privacy, notifications, storage, accessibility, language, help, invite, updates) | Parental controls only with a real, consent-based design |
| Phase 4 · End-to-end encryption | Signal Protocol, hashed contact discovery, encrypted backups; then the privacy notice switches wording | Required before any E2EE claim |

### R0 · Backend hardening in detail

Found or confirmed during the two-phone test on 2026-10-13:

1. **Deploy to production project** (blocked on `firebase login`): rules, indexes, functions; Phone auth with an
   India-first SMS region policy; App Check (Play Integrity) monitored then enforced; the Functions service
   account's Token Creator role (two-step PIN); `reports.expireAt` TTL; budget alerts.
2. **Regression tests** for today's fixes: device registration on a missing doc; RTDB instance under the
   emulator; typing listener retry; poll rendering (UI test).
3. **Run the full emulator suite in CI on every push** (already there) and add an instrumented two-user
   journey on an emulator job.
4. **Orphaned media sweeper:** a scheduled function deleting uploads with no message after 24 h (known risk 4).
5. **Unread counter repair:** a scheduled reconciliation for stale badges (known risk 5).
6. **Token revocation latency:** shorten the window for block/two-step changes (force token refresh via a
   revocation marker the app watches).
7. **Backups and recovery:** daily Firestore exports or point-in-time recovery; restore drill documented.
8. **Monitoring:** log-based alerts on function error rates, App Check rejects, SMS volume spikes and quota use.
9. **Load test** on a staging project: 1,000 concurrent users, 50 messages/s, group fan-out to 256 members.
10. **Security review:** full rules audit against every collection, secret scan of history, and rotation of the
    leaked legacy credentials (`docs/security/incident-leaked-credentials.md`).
