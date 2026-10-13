# Phase 1.3 — Block and report

Status: implemented on `claude/clever-pasteur-6shc8p`. Builds on B2 messaging and Phase 1.1 presence.

## Goals

1. A user can block a contact. While either person in a direct chat has blocked the other, **neither can send,
   edit or react to messages** in that chat, and **neither sees the other's online / last-seen state**.
   This is enforced by Firestore and Realtime Database rules, not only by the app.
2. A user can report a contact for review. The report and a snapshot of the reported person's latest messages
   are stored server-side, where clients cannot read them.
3. Blocking can be undone at any time (chat menu or Settings → Privacy → Blocked contacts).

## Non-goals

- A moderation console. Reports are reviewed by the owner in the Firebase console until one exists.
- Hiding that a block happened. A blocked person's messages fail to send, so a determined person can infer a
  block. Samvad never tells them directly. (WhatsApp hides this with single-tick delivery; that needs a
  server-side delivery queue and is out of scope.)
- Blocking in groups (Phase 2 decides group behaviour).

## Data model

| Path | Who writes | Shape |
|---|---|---|
| Firestore `users/{uid}/blocked/{otherUid}` | owner (create, delete) or `reportContact` | `{ createdAt: timestamp }` |
| RTDB `blocks/{uid}/{otherUid}` | `onBlockWritten` trigger only | `true` |
| Firestore `reports/{requestId}` | `reportContact` only | see below |
| Firestore `rateLimits/{uid}` | functions only | adds `reportWindowStart`, `reportCount` |

The Firestore block list is the source of truth. The RTDB copy exists only because RTDB rules cannot read
Firestore; clients can neither read nor write it.

Report document:

```
reporterId, reportedUid, conversationId, reason ('spam' | 'abuse' | 'harassment' | 'impersonation' | 'other'),
evidence: [{ messageId, kind, text, mediaPath?, createdAt }]   // ≤ 5, newest first, reported person's own messages
createdAt, expireAt (createdAt + 90 days), status: 'open'
```

## Rules

- `users/{uid}/blocked/{other}`: owner may `read` (get and list), `create` with exactly `{createdAt: request.time}`
  when `other != uid` and `other` is a plausible UID, and `delete`. No update.
- Messages: `create`, edits and reactions require `notBlocked(cid)`: for a direct conversation,
  no block document exists in either direction. Soft-deleting your own message stays allowed.
- RTDB `presence/{uid}` is readable by another user only when neither has blocked the other:
  `blocks/{uid}/{reader}` and `blocks/{reader}/{uid}` must both be absent.
- RTDB `blocks`: no client access.
- `reports/**`: no client access.

Rule-call budget: message create now uses getAfter (1) + 2 exists = 3 of the 10 allowed.

## `reportContact` callable

Input `{ requestId: uuid, conversationId, reason, block: boolean }`.

1. Phone sign-in and App Check (with replay protection), like every callable.
2. The conversation must exist, be direct, and include the caller; the reported person is the other member.
3. Idempotent on `requestId`: a retry from the same caller returns success without using budget.
4. Budget: 20 reports per rolling 24 h per caller (`resource-exhausted` after that).
5. Evidence: the reported person's newest 5 non-deleted messages from the last 50 in the chat
   (text up to 4096 chars, media path only, never media bytes).
6. With `block: true`, also creates `users/{caller}/blocked/{reported}`.
7. Logs only a correlation ID, the evidence count and the outcome.

The report dialog tells the user, before sending, that the last five messages from this contact are sent to
Samvad for review. The same disclosure belongs in the privacy policy.

## Account deletion

`deleteAccount` removes `blocks/{uid}` from RTDB; the Firestore block list goes with the recursive delete of
`users/{uid}`. Reports are kept for review and expire through the `expireAt` TTL policy (owner sets it up once:
Firestore → TTL → collection group `reports`, field `expireAt`; no cost beyond normal deletes).

## Client

- Chat top bar menu: Block / Unblock, Report. Block asks for confirmation.
- A blocked chat replaces the composer with "You blocked {name}" and an Unblock button, hides presence and
  typing, and stops sending typing signals.
- Report dialog: reason list, "Also block" (on by default), the evidence disclosure.
- Settings → Privacy → Blocked contacts: list with Unblock.
- `:core` `ReportReason`: report reasons and their wire values.

## Tests

- Rules: block list owner-only, shape, no update, self-block denied; messages blocked in both directions;
  soft delete still allowed; reports and RTDB `blocks` unreadable; presence hidden both ways.
- Functions: mirror trigger set and clear; `reportContact` membership, validation, evidence selection,
  idempotency, budget, block flag; deleteAccount clears `blocks/{uid}`.
- `:core`: reason wire values.
