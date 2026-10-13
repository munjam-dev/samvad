# Phase 1.2 — Photos, files and voice notes

Status: implemented on `claude/clever-pasteur-6shc8p` (backend 79310aa, app 2fe81d6).

## Flow

1. The app prepares the attachment on the device:
   - **Photos:** decoded with sampling, scaled to at most 1600 px and re-encoded as JPEG (quality 82),
     which drops EXIF data such as GPS location.
   - **Documents:** read as-is.
   - **Voice notes:** AAC in M4A at 64 kbps, with a 48-bar waveform from amplitude samples every 100 ms.
2. It uploads the file to Storage at `conversations/{cid}/{messageId}/{safeName}`, with up to 3 attempts.
   The message ID is a client UUID, so retries never duplicate anything.
3. It writes the message (`kind` image / file / voice, caption in `text`, `media` description). The
   `onMessageCreated` trigger sets the chat preview ("📷 Photo", "🎤 Voice message", "📄 name").
4. Deleting the message for everyone deletes its file (`onMessageUpdated`, idempotent).

## Rules

Storage `conversations/{cid}/{mid}/{file}`:

- **Read:** members only. Membership comes from the Firestore conversation document.
- **Create:**
  - members only, and only once (`resource == null`);
  - UUID message ID and safe file name;
  - size between 1 byte and 16 MB;
  - content-type allow-list: JPEG, PNG and WebP images; MP4, AAC, MPEG and Ogg audio; PDF, text, ZIP and
    Office documents;
  - `senderId` metadata equal to the caller;
  - not after the message exists.
- **Update and delete:** never by clients.
- **Not accepted:** APKs, scripts and other executables.

Firestore message create, for media kinds:

- `media.path` must point inside the same conversation and message;
- size at most 16 MB;
- MIME must match the kind;
- voice notes need a duration of at most 15 minutes and at most 64 waveform bars;
- dimensions at most 10,000 px;
- media messages cannot be edited, only deleted.

## Privacy and safety

- Files are downloaded only through the Storage SDK, so the rules apply to every read. No public
  download URLs (bearer links) are created.
- Downloaded files are cached in app-private storage under hashed names. They are opened in other apps
  through a FileProvider with a one-time read grant.
- The microphone permission is requested only when recording starts.

## Client UI

Attach sheet (photo picker, document picker), image bubbles with a full-screen pinch-zoom viewer, file
cards, voice notes with waveform playback, recorder bar with cancel/send, pending-upload rows with retry
and cancel, and error snackbars (too large, blocked type, download failed).

## Tests

Emulator: Storage rules (members, immutability, type and size limits, metadata), message media
validation, trigger previews and file cleanup. `:core` `MediaRules`: allow-list, size cap, safe names,
scaling, waveform.
