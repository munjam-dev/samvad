# Phase 1.1 — Typing indicator and online / last seen

Status: implemented under the approved phased roadmap (2026-10-13).

## Data (Realtime Database)

| Path | Value | Who writes | Who reads |
|---|---|---|---|
| `presence/{uid}` | `{ online: bool, lastSeen: timestamp }` | the owner (app foreground/background; `onDisconnect` marks offline) | the owner always; other phone-signed-in users only when `privacy/{uid}/hideLastSeen` is not `true` |
| `privacy/{uid}/hideLastSeen` | `true` or absent | the owner | the owner |
| `typing/{cid}/{uid}` | timestamp of the last keystroke, or absent | the member themself | members of `cid` |
| `members/{cid}/{uid}` | `true` | **server only** — mirrored from Firestore by `onConversationCreated` | members of `cid` |

RTDB rules cannot read Firestore, so conversation membership is mirrored into `members/` by a trusted trigger; clients can never write it. Account deletion removes the user's `presence`, `privacy` and `members/*/{uid}` entries.

## Behaviour

- Online while the app is in the foreground; going to the background or losing the connection records `online: false` and `lastSeen` (server time).
- "Hide last seen & online" hides both from everyone (no reciprocity rule yet).
- Typing is throttled to one write per 3 s while the user types; it is cleared on send, when the field empties and when leaving the chat (plus `onDisconnect`). Readers treat a timestamp older than 6 s as "not typing".
- Header shows, in priority order: "typing…", "online", "last seen today at 9:24 AM" / "last seen yesterday at …" / "last seen 12/10/26", or nothing when hidden.

## Tests

RTDB rules (emulator): owner-only presence writes with shape validation; presence hidden from others when `hideLastSeen`; privacy readable/writable only by the owner; typing readable/writable only by mirrored members, own key only, numeric value; `members/` server-only. Functions: the trigger mirrors members and is idempotent; deleteAccount clears RTDB entries. `:core`: last-seen label and typing freshness.
