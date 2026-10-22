# Cost audit: Firebase and LiveKit

Static review of `firebase/functions/src`, `firestore.indexes.json` and the Android app, taken from the code at the
time of writing (Phase 10). No billing data was available: the numbers below are limits and structure, not spend.
Check current prices on the Firebase and LiveKit pricing pages before setting alert amounts.

## 1. Scheduled functions

Three schedulers exist, all `maxInstances: 1`, `retryCount: 0`, region `asia-south1`, time zone `Asia/Kolkata`.

| Function | File | Frequency | Per run | Idle cost |
|---|---|---|---|---|
| `cleanupMissedCalls` | `calls.ts` | every 1 minute (43,200 runs/month) | one query on `calls` (`status == ringing`, `expiresAt <= now`), limit 200, then one transaction and one missed-call push per expired call | one indexed query returning 0 documents: about 1 read per run |
| `sendScheduledMessages` | `scheduled.ts` | every 1 minute (43,200 runs/month) | collection-group query on `scheduledMessages` (`status == pending`, `sendAt <= now`), batch of 200 (`SCHEDULE_BATCH`); each due message is a transaction that writes the message and the schedule document | about 1 read per run |
| `sendEventReminders` | `groupAdvanced.ts` | every 5 minutes (8,640 runs/month) | collection-group query on `events` (`reminderSent == false`, start within the next 30 minutes), batch of 100 (`REMINDER_BATCH`); per event one RSVP query and one push to members | about 1 read per run |

Idle load is roughly 95,000 function invocations and about 95,000 document reads a month. That is inside the free
tier for invocations (2 million) and about two days of the free Firestore read quota (50,000 per day), so the
schedulers are not a cost driver today. The Cloud Scheduler jobs themselves are billed per job: 3 jobs, the first 3
per billing account are free.

If the 1-minute cadence is ever lowered to 5 minutes for `cleanupMissedCalls`, a call that nobody answers would show
as missed up to 5 minutes late. Keep it at 1 minute.

No scheduled function deletes data. Expiry uses Firestore TTL policies (`firestore.indexes.json`): `messages.expiresAt`
(disappearing messages) and `items.expiresAt` (24-hour moments). TTL deletes are not billed as reads, but each
deleted document is a billed delete. The deletion of moments also triggers `onStatusDeleted`
(`recursiveDelete` of views and reactions, plus a Storage prefix delete).

## 2. Event-driven functions (trigger on every write)

| Trigger | File | Fires on |
|---|---|---|
| `onMessageCreated` | `onMessageCreated.ts` | every message document in `conversations/{cid}/messages` |
| `onMessageUpdated` | `onMessageUpdated.ts` | every message update (edits, reactions, delivery fields) |
| `onConversationCreated` | `onConversationCreated.ts` | each new conversation |
| `onStatusCreated`, `onStatusDeleted` | `status.ts` | each moment created / deleted or expired |
| `onBlockWritten` | `onBlockWritten.ts` | each block or unblock (mirrors to Realtime Database) |

Callables (`onCall`) are the remaining entry points. They are rate limited per user by `consumeDailyBudget` (section 4).

## 3. Biggest cost drivers

Ranked by how fast cost grows with users. "N" is the number of recipients.

1. **Message push fan-out (`onMessageCreated`).** One message in a chat of N members costs, in one transaction, a
   read of every member document and an update (`unreadCount`) or create for each of the N-1 other members, plus the
   conversation summary write and an idempotency marker. After the transaction, `notifyRecipients` does for each
   recipient one read of the member document and one query of `users/{uid}/devices`, then one FCM multicast (FCM is
   free). Reads and writes therefore scale as roughly 3N reads and N writes per message. A 1:1 chat is about 6 reads
   and 3 writes; a 256-member group message is about 770 reads and 256 writes; a 1,000-member community
   announcement is about 3,000 reads and 1,000 writes. Recipients are processed 25 at a time
   (`RECIPIENT_PARALLELISM`), so a big announcement holds one instance for many seconds (function GB-seconds).
2. **Group and community announcements.** Same path as above, so it is the worst case. The brakes are the member
   caps: `MAX_GROUP_MEMBERS = 256`, `MAX_COMMUNITY_MEMBERS = 1000`, `MAX_COMMUNITY_GROUPS = 10`. Posting in the
   announcements chat is limited to admins by rules, which bounds who can trigger a 1,000-member fan-out
   (`MAX_COMMUNITY_ADMINS = 10`). There is no per-day cap on the number of announcements an admin may post; that is
   the main unguarded fan-out (see recommendations).
3. **Moment audience (`onStatusCreated`).** Each moment reads up to `MAX_VISIBLE = 1000` conversations containing the
   author, plus the author's block list, plus one `blocked` document read per candidate (up to 1,000 point reads).
   That is up to about 2,000 reads per moment, then one write. Viewers read the moment through `visibleTo`
   queries, and each view writes a `views` document. Moments expire after 24 hours (TTL) and the cleanup trigger
   adds a recursive delete. Storage upload and download of the media are billed separately and are likely larger than
   the Firestore cost for photo and video moments.
4. **Message reads on the client.** Every open chat holds a Firestore listener. Reads are billed per document
   delivered, so reopening a chat re-reads the page of messages unless the offline cache serves it. Check that the
   chat page size stays bounded. Disappearing messages add TTL deletes.
5. **Realtime Database presence.** `onBlockWritten` mirrors blocks to RTDB, and presence uses RTDB. RTDB bills on
   stored GB and downloaded GB, not per operation; presence payloads are tiny, so this is low.
6. **Storage (media, voice notes, vault, profile photos).** Egress per download is the billing driver. The profile
   photo is capped at 512 KB and 1024 px (`setProfilePhoto.ts`). Check `storage.rules` for the chat media size caps.
7. **Call records (`calls`).** One document per call, a transaction at answer/decline/end, and one push per ring.
   History is kept indefinitely (no TTL policy), so storage grows linearly but slowly (a few hundred bytes per call).
   Add a TTL policy if call history should expire (`docs/CALLING_SETUP.md`). Per-user limit: `DAILY_CALLS = 40`,
   `DAILY_MEETINGS = 10`.
8. **Search index.** Global search runs on local data only (on-device Room/in-memory). It adds no Firestore reads and
   no function invocations, so it is not a backend cost. It does cost device storage and battery.
9. **Phone-number lookup (`lookupPhoneNumbers`).** Up to 50 numbers per call (`MAX_NUMBERS_PER_CALL`), 500 per user
   per day (`DAILY_LOOKUP_LIMIT`): at most 500 profile reads per user per day. Contact sync from many users at
   install time is a short-lived spike.

## 4. Existing brakes

- **Function scale cap:** `setGlobalOptions({ maxInstances: 10, cpu: 'gcf_gen1', memory: '256MiB' })` in
  `globalOptions.ts`, imported first in `index.ts`. This caps parallel instances for every function, so a flood of
  events cannot scale spend without limit. The three schedulers are further pinned to `maxInstances: 1`.
  Note: Firestore-triggered functions are subject to the same global cap, so if message volume exceeds what 10
  instances handle, triggers queue and retry rather than cost more. Watch for delayed pushes before raising the cap.
- **Per-user rolling 24-hour budgets** (`lib/budget.ts`, stored in `rateLimits/{uid}`; each check costs one
  transaction on a single document):

| Budget | Limit/day | | Budget | Limit/day |
|---|---|---|---|---|
| calls (`DAILY_CALLS`) | 40 | | communities created | 5 |
| meetings | 10 | | community member adds | 200 |
| groups created | 10 | | group/community joins | 20 |
| group settings changes | 60 | | join-request decisions | 400 |
| events created | 20 | | event RSVPs | 200 |
| pin changes | 200 | | disappearing timer changes | 20 |
| username changes / checks | 10 / 120 | | profile photo changes | 20 |
| phone lookups | 500 | | contact reports | 20 |
| appeals | 3 | | device revokes | 20 |

- **Size caps:** group 256 members, community 1,000 members and 10 groups, 100 pending join requests, 3 pins per chat,
  30 upcoming events per group, message text 4,096 characters (scheduled), preview 120 characters.
- **Batch limits on schedulers:** 200 scheduled messages, 200 missed calls, 100 event reminders per run.
- **Idempotency markers** on message fan-out, so a redelivered trigger never repeats the writes or the push.
- **Calls:** ring time is 45 seconds (`RING_SECONDS`); meetings are limited to 50 participants
  (`MAX_MEETING_PARTICIPANTS`); LiveKit tokens are minted only for authenticated participants of the call (the server mints them; see `calls.ts`).

Not present: there is no cap on messages per user per day (a normal chat message is a direct client write guarded
by rules, not a callable), no cap on announcements per community per day, and no Firebase-side kill switch.

## 5. LiveKit

LiveKit Cloud bills on participant-minutes and bandwidth, with a free monthly allowance on the starter plan
(see `docs/CALLING_SETUP.md` section on cost). Drivers in this app:

- Every connected person in a room counts, so a 50-person meeting for an hour is 3,000 participant-minutes.
- Video is roughly an order of magnitude more bandwidth than audio. The app does not cap video resolution by plan.
- A call that is never ended on the client keeps billing until the room is empty. Confirm the empty-room and
  departure timeouts in the LiveKit project settings; they were not verified here.
- Per-user brakes: 40 calls and 10 meetings per day. There is no cap on call length or on total minutes per user.

## 6. Recommended alerts

### Google Cloud / Firebase (Billing, Budgets & alerts)

Create one budget on the billing account and one scoped to the Samvad project. Use the amount you can afford to lose
in a month, `B`, then thresholds at 50%, 90%, 100% (actual) and 120% (forecast), with email to at least two people.

1. Budget `B` on the project, thresholds as above.
2. A low "canary" budget (for example 10% of `B`) so a runaway starts alerting early in the first months.
3. Optionally connect the budget to a Pub/Sub topic and a small function that disables billing at 150% (only if a
   full outage is better than a large bill; this stops all of Firebase, so decide this deliberately).
4. Cloud Monitoring alerts (Alerting policies):
   - Firestore: document reads per day above 5x the trailing 7-day average, and document writes the same.
   - Cloud Functions: execution count per function above 5x average, and `onMessageCreated` p95 execution time
     above 30 seconds (signals a very large fan-out).
   - Cloud Functions: error rate above 5% for 10 minutes.
   - Cloud Storage: network egress above a daily threshold you choose.
   - Realtime Database: downloaded bytes per day above 5x average.
   - Cloud Scheduler: failed job attempts above 0 for 15 minutes.
5. Enable Firebase App Check enforcement (Play Integrity) for Firestore, Storage, RTDB and Functions in production,
   so that scripted clients cannot generate reads and writes at all.

### LiveKit Cloud

1. Billing: usage alert at 50% and 80% of the included monthly participant-minutes and bandwidth, and a spend limit if
   the plan offers one.
2. Alert on concurrent participants above a level you expect (for example 2x your busiest hour).
3. Review the empty-room timeout and set the maximum room duration, so an orphaned room cannot bill for hours.
4. Rotate the API key if it is ever suspected leaked (`firebase functions:secrets:set LIVEKIT_API_KEY`).
5. Check the dashboard weekly in the first month, then monthly.

### Cheap code changes worth considering (not made here; backend is out of scope for this pass)

- A daily cap on announcements per community and on messages per user, enforced in rules or a counter.
- A TTL policy on `calls` (for example 90 days) if call history need not be kept forever.
- For `onStatusCreated`, cache the author's block list and avoid per-candidate `blocked` reads by using a
  batched `getAll`.
- A server-enforced maximum call length (end rooms after N hours with a scheduled sweep or the LiveKit room
  `maxDuration` setting).
