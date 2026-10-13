# Phase 1.6 — Everyday parity features (zero cost)

Status: in progress on `claude/clever-pasteur-6shc8p`. Each item uses only Firebase features already in
use, with no new paid service.

## About "backup to Google Drive"

WhatsApp backs up to Google Drive because its messages exist only on the phone. Samvad's history is
stored in Firestore, so signing in with the same number on a new phone already restores every chat and
file. A Drive backup today would only copy what the server already keeps.

That changes with end-to-end encryption (Phase 4): the server then holds only ciphertext, and an
**encrypted backup in the user's own Google Drive** (app-data folder, protected by a password or 64-digit
key the user keeps, never sent to Samvad) becomes the way to restore on a new phone. It is planned there,
next to the Signal Protocol work, because the backup format and keys depend on it.

Until then, **Export chat** gives users a copy they control: a text file they can save to Google Drive,
email or keep, through Android's share sheet. No Google sign-in, API key or OAuth setup is needed.

## 1. Export chat

- Chat menu → Export chat → confirmation explaining what is included → Android share sheet.
- Content: the newest 5,000 messages (each is one Firestore read), oldest first, one line per message:
  `2026-10-13 14:05 - Asha: text`. Photos, voice notes and files appear as `<Photo>`,
  `<Voice message>`, `<File: name.pdf>`. Deleted messages say so. Edits are marked.
- The file is written to the app's private `cache/exports/` (only the latest export is kept) and shared
  through the FileProvider with a one-time read grant. File names keep letters, digits and combining
  marks, so Indic names stay intact.
- Media is not included, which keeps exports small and avoids copying other people's photos.

## 2. Read receipts

- Settings → Privacy → Read receipts (on by default).
- Off: opening a chat still clears the unread count and marks messages delivered, but does not write
  `lastReadAt`, so nobody sees blue ticks for you. In return you see at most "delivered" on your messages,
  like WhatsApp. The first half is enforced by your own client not writing your own field. The second is
  a display rule.

## 3. Forward messages (next)

Long-press → Forward → choose up to 5 chats. Text is copied as a new message marked `forwarded: true`
(new optional field allowed by the rules). Media is re-uploaded into each target chat, because Storage
rules tie every file to its conversation. Like WhatsApp, the limit of 5 chats slows down mass forwarding.

## 4. Profile photos (next)

`avatars/{uid}/{uuid}.jpg` in Storage: owner-only, immutable uploads, JPEG only, at most 1 MB, readable
by signed-in users. `profiles/{uid}.photoUrl` may then hold that path (still null by default). The app
crops to a square, scales to 512 px and re-encodes, which strips location metadata. A trigger deletes the
previous file when the photo changes or is removed.

## Tests

`:core`: export format, file names (including Devanagari and Tamil), delivered-only ticks with receipts
off. Rules and emulator tests accompany items 3 and 4.
