# Samvad Backend — B2: 1:1 Messaging (+ minimal contact lookup)

Date: 2026-10-13
Status: Implemented under the owner's instruction of 2026-10-13 ("build complete frontend and backend… proceed"); review welcome. Builds on B1 (`2026-10-09-foundation-auth-design.md`).

## 1. Scope

In: direct (1:1) conversations, text messages with replies, edits, soft deletes and reactions, per-member read/delivery state, unread counts, mute/pin/archive, paginated history, last-message denormalisation by a trusted trigger, and a rate-limited phone-number lookup callable so two users can actually find each other.

Out (later): groups (B3), media (B4), presence/typing and push fan-out (B5), hashed contact discovery on Ktor (B6 — the lookup here is the interim, server-mediated version), E2EE (B8).

## 2. Data model

**`conversations/{cid}`** — `cid` for a direct chat is `uidA_uidB` with the two UIDs sorted, so a pair can only ever have one conversation and creation is idempotent.

| Field | Type | Who writes |
|---|---|---|
| `type` | `"direct"` | client on create |
| `members` | `[uidA, uidB]` sorted | client on create |
| `createdBy` | uid | client on create (== caller) |
| `createdAt` | timestamp | client on create (== request.time) |
| `lastMessage` | `{id, senderId, preview, kind, createdAt}` or null | trigger only |
| `lastMessageAt` | timestamp | client on create (== request.time), then trigger |

**`conversations/{cid}/messages/{mid}`** — `mid` is a client-generated lowercase UUID, so an offline retry of the same send is idempotent (a second create of the same ID is an update, which the rules deny).

| Field | Type | Rule |
|---|---|---|
| `senderId` | uid | == caller |
| `kind` | `"text"` | B4 adds media kinds |
| `text` | string | 1–4096 chars, contains a non-whitespace char |
| `replyTo` | mid string or null | |
| `createdAt` | timestamp | == request.time |
| `editedAt` | timestamp or absent | sender only, within 15 min of `createdAt`, == request.time |
| `deleted` | bool | sender may set true once (text becomes ""); never back |
| `reactions` | map uid → emoji (≤ 16 chars) | each member may set/remove only their own key |

**`conversations/{cid}/members/{uid}`** — per-member state, created by the conversation creator for both members in the same batch, or by a member for themselves.

| Field | Type | Rule |
|---|---|---|
| `lastReadAt` | timestamp or null | owner; may only move forward; ≤ request.time |
| `lastDeliveredAt` | timestamp or null | owner; may only move forward; ≤ request.time |
| `unreadCount` | int | trigger increments; owner may only reset to 0 |
| `muted`, `pinned`, `archived` | bool | owner |

**`rateLimits/{uid}`** — server-only counters for the lookup callable.

## 3. Rules summary

- Every access requires a phone sign-in (B1 helpers) and membership (`request.auth.uid in conversation.members`).
- Conversation create: ID must equal `members[0] + '_' + members[1]`, members sorted, size 2, distinct, caller included, the other member must have a `profiles` doc, and neither member may be tombstoned. No client update or delete.
- Conversation `list` is allowed only for queries constrained by `members array-contains <caller>` (rules check `request.auth.uid in resource.data.members`).
- Messages: create/read by members; update limited to the edit, soft-delete and own-reaction shapes above; no delete.
- Members docs: any member may read all member docs of the conversation (for read receipts); each member writes only their own.

## 4. Trusted code

- **`onMessageCreated`** (Firestore trigger, `asia-south1`): sets `lastMessage`/`lastMessageAt` on the conversation and increments `unreadCount` for every member except the sender. Idempotent per event ID (`processedEvents/{eventId}` marker, server-only).
- **`lookupPhoneNumbers`** (callable): input up to 50 E.164 numbers; returns `[{phoneE164, uid}]` for registered, non-deleted users. Phone auth required, App Check enforced, 500 numbers per user per rolling day (`rateLimits/{uid}`), never logs numbers. Revealing whether a number is registered is inherent to phone-number messaging; the rate limit bounds enumeration. B6 replaces it with hashed discovery.
- **`deleteAccount`** additionally removes the caller's `conversations/*/members/{uid}` docs. Their sent messages stay so the other person's history is intact; clients render the sender as "Deleted account" when `profiles/{uid}` is missing.

## 5. Indexes

- `conversations`: `members` ARRAY_CONTAINS + `lastMessageAt` DESC (chat list).
- Message history uses `orderBy(createdAt desc).limit(n)` — single-field, automatic.

## 6. Client contract additions

- Generate the message ID locally (UUID v4, lowercase) before writing; retry with the same ID.
- Page history 30 messages at a time with `startAfter`.
- Reset `unreadCount` to 0 and advance `lastReadAt` when the conversation is visible.
- Treat `lastMessage` as eventually consistent (written by the trigger).

## 7. Tests (Emulator Suite)

Rules: membership isolation, ID/members invariants, profile-exists check, tombstone, message validation (length, blank, sender spoofing, createdAt), edit window, soft delete one-way, reaction key ownership, members-doc ownership and monotonic timestamps, unread reset-only, list constraint, index file contents. Functions: trigger denormalisation and unread increments (idempotent), lookup auth/limits/results/no-PII logs, deleteAccount membership cleanup.
