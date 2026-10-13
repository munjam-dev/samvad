# Phase 2 — Groups and polls

Status: in progress on `claude/clever-pasteur-6shc8p`. Uses only Firestore, Functions, RTDB, Storage
and FCM.

## Goals

- **Groups of up to 256 people:** a name, an optional description and photo; admins and members.
- **Membership:** admins add and remove people and promote or demote admins. Anyone can leave. Admins
  can create and revoke an invite code that lets others join.
- **New members** see messages from the moment they joined, not the history before.
- **Removed or departed members** lose access to the group on the server.
- **@mentions:** they reach the mentioned person even when the group is muted.
- **Polls:** single or multiple choice, up to 12 options, live results.
- **Server enforcement:** every permission lives in rules or functions, never only in the UI.

## Data model

`conversations/{cid}` with `type: 'group'` (`cid` is a random ID):

| Field | Writer | Notes |
|---|---|---|
| `type`, `createdBy`, `createdAt` | functions | |
| `members: [uid]` | functions | at most 256; drives the chat-list query and the rules |
| `admins: [uid]` | functions | subset of members; at least one while members remain |
| `name` (1–64), `description` (≤ 512), `photoUrl` (Storage path or null) | functions | |
| `lastMessage`, `lastMessageAt` | `onMessageCreated` | same as direct chats |

`conversations/{cid}/members/{uid}` adds `joinedAt` (server time) for groups. The owner may change
read/delivered marks and mute/pin/archive exactly as for direct chats. `unreadCount` stays 0 for groups:
the app counts unread messages with an aggregation query. Incrementing a counter for every member on
every message would cost up to 256 writes per message.

`groupInvites/{code}`: `{cid, createdBy, createdAt}`, server-only. The code is 12 random characters
from a 32-letter alphabet (60 bits). A group has at most one live code (`inviteCode` is kept on a
server-only `groupSecrets/{cid}` document).

## Rules

- Group conversation documents are **read** by members and **written only by functions**.
- Messages in a group:
  - **create:** members only, as today. Optional `mentions` is a list of at most 50 member UIDs.
  - **read:** members only, and only messages created at or after their own `joinedAt`. The app queries
    with `createdAt >= joinedAt`; the rule reads one member document per request.
- Blocks keep applying to direct chats only. In groups, a block hides the blocked person's presence and
  photo, but not their group messages (as in WhatsApp).
- Poll votes: `conversations/{cid}/messages/{mid}/votes/{uid}`.
  - **Write:** the voter only, members only, and only on poll messages. Choices must be valid option
    indexes, and exactly one unless the poll allows several.
  - **Read:** members.

## Callables (App Check, verified session, rate limited)

| Callable | Who | Effect |
|---|---|---|
| `createGroup {name, members[]}` | anyone | creator + up to 255 others who have profiles; creator is admin; skips people who blocked the creator |
| `addGroupMembers {cid, uids[]}` | admin | adds up to the 256 cap; same block check |
| `removeGroupMember {cid, uid}` | admin | any member, including other admins (as in WhatsApp); not yourself (use leave) |
| `leaveGroup {cid}` | member | if the last admin leaves, the longest-standing member becomes admin |
| `setGroupAdmin {cid, uid, admin}` | admin | |
| `updateGroupInfo {cid, name?, description?}` | admin | |
| `setGroupPhoto {cid, jpegBase64 \| null}` | admin | same validation and metadata stripping as profile photos |
| `createGroupInvite {cid}` / `revokeGroupInvite {cid}` | admin | one live code per group |
| `joinGroupWithCode {code}` | anyone | joins if the group is not full; 20 joins per day per user |

Each membership change also:
- writes a system message ("Asha added Ravi", "Ravi left"), `kind: 'system'`, server-only;
- updates the RTDB `members/{cid}` mirror used by typing rules;
- creates or deletes the member document.

## Notifications

`onMessageCreated` for groups sends one data push per member device (not the sender's), with the group
name as title and "Sender: text" as body. Muted members' phones drop it unless the message mentions them.
Device tokens are read per member (`users/{uid}/devices`, at most 256 queries), the cost of the
privacy-preserving approach. Topic messaging was rejected because any app instance can subscribe to a
topic name it learns, and removed members would learn it.

## App

- New chat → New group: pick people (chats + contacts on Samvad), name it, create.
- Group chat: sender name and colour on incoming bubbles, system messages centred, "@" opens a member
  picker, mentions highlighted.
- Group info: photo, name, description, members with admin badges, add / remove / make admin, invite
  code (share or revoke), shared photos grid, mute, leave.
- New chat → Join with code.
- Polls: create from the attach sheet, vote with live bars, see who voted (members only).

## Tests

Rules: group docs server-only, history limited to `joinedAt`, mentions shape, votes. Functions: every
callable's permissions, caps, block checks, last-admin handover, invite create/revoke/join, system
messages, RTDB mirror, group push fan-out. `:core`: membership roles, mention parsing, poll tallies.
