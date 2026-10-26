import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions, REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
import { logEvent } from './lib/logger';
import {
  cidOf,
  eligible,
  loadGroup,
  memberDoc,
  mirror,
  MAX_GROUP_MEMBERS,
  requestRef,
  run,
  systemMessage,
  uidOf,
} from './groups';

// Phase 6: advanced groups. Settings (announcement mode, approval, who may create events), pending join
// requests, events with RSVPs, and a reminder push 30 minutes before an event. Everything is enforced
// here or in firestore.rules; the app only mirrors these rules to hide buttons.

export const MAX_EVENT_TITLE = 80;
export const MAX_EVENT_DESCRIPTION = 300;
export const MAX_EVENT_PLACE = 80;
/** An event may be at most this far ahead. */
export const MAX_EVENT_LEAD_MS = 2 * 365 * 24 * 60 * 60 * 1000;
/** Upcoming (not cancelled, not started) events per group. */
export const MAX_UPCOMING_EVENTS = 30;
export const DAILY_EVENTS = 20;
export const DAILY_RSVPS = 200;
export const DAILY_DECISIONS = 400;
export const DAILY_SETTINGS = 60;
export const REMINDER_LEAD_MS = 30 * 60 * 1000;
export const REMINDER_BATCH = 100;

export type Rsvp = 'going' | 'maybe' | 'no';
const RSVPS: readonly Rsvp[] = ['going', 'maybe', 'no'];
const EID = /^[A-Za-z0-9]{20}$/;

// ---- settings -----------------------------------------------------------------------------------

export interface GroupSettings {
  onlyAdminsSend?: boolean;
  approveMembers?: boolean;
  eventsByMembers?: boolean;
}
const SETTING_KEYS = ['onlyAdminsSend', 'approveMembers', 'eventsByMembers'] as const;

export function parseSettings(raw: unknown): GroupSettings {
  const d = (raw ?? {}) as Record<string, unknown>;
  const out: GroupSettings = {};
  for (const k of SETTING_KEYS) {
    if (d[k] === undefined) continue;
    if (typeof d[k] !== 'boolean') throw new HttpsError('invalid-argument', `${k} must be a boolean.`);
    out[k] = d[k] as boolean;
  }
  if (Object.keys(out).length === 0) throw new HttpsError('invalid-argument', 'Nothing to change.');
  return out;
}

/** Admins only. Writes only the settings that really change; announcement mode leaves a line in the chat. */
export async function setGroupSettingsCore(deps: Deps, actor: string, cid: string, settings: GroupSettings): Promise<void> {
  await consumeDailyBudget(deps, actor, 'groupSettings', DAILY_SETTINGS);
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, true);
    const snap = await tx.get(g.ref);
    const changes: Record<string, boolean> = {};
    for (const k of SETTING_KEYS) {
      const next = settings[k];
      if (next !== undefined && (snap.get(k) === true) !== next) changes[k] = next;
    }
    if (Object.keys(changes).length === 0) return;
    tx.update(g.ref, changes);
    if (changes.onlyAdminsSend !== undefined) {
      systemMessage(tx, g.ref, actor, changes.onlyAdminsSend ? 'announce_on' : 'announce_off');
    }
  });
}

// ---- join requests ------------------------------------------------------------------------------

/**
 * An admin approves or declines one pending request. Approving adds the person in the same transaction,
 * so the 256-member cap cannot be passed by two admins approving at once; people who have since deleted
 * their account, lost their profile, or are blocked by the admin (either way) are not added and their
 * request is removed.
 */
export async function decideJoinRequestCore(
  deps: Deps,
  actor: string,
  cid: string,
  target: string,
  approve: boolean,
): Promise<{ status: 'approved' | 'declined' | 'unavailable' }> {
  await consumeDailyBudget(deps, actor, 'joinDecisions', DAILY_DECISIONS);
  let status = 'declined' as 'approved' | 'declined' | 'unavailable';
  await deps.db.runTransaction(async (tx) => {
    status = 'declined';
    const g = await loadGroup(tx, deps, cid, actor, true);
    const reqRef = requestRef(deps, cid, target);
    const req = await tx.get(reqRef);
    if (!req.exists || req.get('status') !== 'pending') throw new HttpsError('not-found', 'no-request');
    if (!approve) {
      tx.delete(reqRef);
      return;
    }
    if (g.members.includes(target)) {
      tx.delete(reqRef);
      status = 'approved';
      return;
    }
    if (g.members.length >= MAX_GROUP_MEMBERS) throw new HttpsError('resource-exhausted', 'group-full');
    const { ok } = await eligible(tx, deps, actor, [target], { selfJoin: true });
    if (ok.length === 0) {
      tx.delete(reqRef);
      status = 'unavailable';
      return;
    }
    tx.update(g.ref, { members: FieldValue.arrayUnion(target) });
    tx.set(g.ref.collection('members').doc(target), memberDoc(target));
    systemMessage(tx, g.ref, target, 'joined');
    tx.delete(reqRef);
    status = 'approved';
  });
  const result: 'approved' | 'declined' | 'unavailable' = status;
  if (result === 'approved') await mirror(deps, cid, [target], []);
  return { status: result };
}

/** The requester withdraws their own pending join request. Nothing to do when there is none. */
export async function cancelJoinRequestCore(deps: Deps, actor: string, cid: string): Promise<void> {
  await consumeDailyBudget(deps, actor, 'joinDecisions', DAILY_DECISIONS);
  await deps.db.runTransaction(async (tx) => {
    const ref = requestRef(deps, cid, actor);
    const req = await tx.get(ref);
    if (req.exists && req.get('status') === 'pending') tx.delete(ref);
  });
}

// ---- events -------------------------------------------------------------------------------------

export interface EventInput {
  title: string;
  description: string;
  place: string;
  startsAtMs: number;
}

export function parseEvent(raw: unknown, nowMs: number): EventInput {
  const d = (raw ?? {}) as Record<string, unknown>;
  const title = typeof d.title === 'string' ? d.title.trim() : '';
  if (title.length < 1 || title.length > MAX_EVENT_TITLE) throw new HttpsError('invalid-argument', 'Title must be 1-80 characters.');
  const description = d.description === undefined || d.description === null ? '' : d.description;
  if (typeof description !== 'string' || description.trim().length > MAX_EVENT_DESCRIPTION) {
    throw new HttpsError('invalid-argument', 'Description must be at most 300 characters.');
  }
  const place = d.place === undefined || d.place === null ? '' : d.place;
  if (typeof place !== 'string' || place.trim().length > MAX_EVENT_PLACE) {
    throw new HttpsError('invalid-argument', 'Place must be at most 80 characters.');
  }
  const startsAt = d.startsAt;
  if (typeof startsAt !== 'number' || !Number.isSafeInteger(startsAt)) throw new HttpsError('invalid-argument', 'Choose when it starts.');
  if (startsAt <= nowMs) throw new HttpsError('invalid-argument', 'event-in-past');
  if (startsAt > nowMs + MAX_EVENT_LEAD_MS) throw new HttpsError('invalid-argument', 'event-too-far');
  return { title, description: description.trim(), place: place.trim(), startsAtMs: startsAt };
}

/**
 * Creates an event and its card in the chat timeline. Admins always; other members only when the group
 * allows it; and nobody but admins while "only admins can send messages" is on (the card is a message).
 */
export async function createEventCore(deps: Deps, actor: string, cid: string, input: EventInput): Promise<{ eventId: string }> {
  await consumeDailyBudget(deps, actor, 'groupEvents', DAILY_EVENTS);
  const eventRef = deps.db.collection(`conversations/${cid}/events`).doc();
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, false);
    const snap = await tx.get(g.ref);
    const isAdmin = g.admins.includes(actor);
    if (!isAdmin && (snap.get('eventsByMembers') !== true || snap.get('onlyAdminsSend') === true)) {
      throw new HttpsError('permission-denied', 'Only group admins can do that.');
    }
    // Single-field query (no composite index); cancelled ones are filtered here.
    const ahead = await tx.get(g.ref.collection('events').where('startsAt', '>', Timestamp.fromMillis(deps.nowMs())).limit(4 * MAX_UPCOMING_EVENTS));
    if (ahead.docs.filter((e) => e.get('cancelled') !== true).length >= MAX_UPCOMING_EVENTS) {
      throw new HttpsError('resource-exhausted', 'too-many-events');
    }
    tx.set(eventRef, {
      title: input.title,
      description: input.description,
      place: input.place,
      startsAt: Timestamp.fromMillis(input.startsAtMs),
      createdBy: actor,
      createdAt: FieldValue.serverTimestamp(),
      cancelled: false,
      reminderSent: false,
      counts: { going: 0, maybe: 0, no: 0 },
    });
    tx.set(g.ref.collection('messages').doc(randomUUID()), {
      senderId: actor,
      kind: 'event',
      text: '',
      replyTo: null,
      createdAt: FieldValue.serverTimestamp(),
      deleted: false,
      reactions: {},
      eventId: eventRef.id,
      eventTitle: input.title,
    });
  });
  return { eventId: eventRef.id };
}

/** A member's answer. [answer] null withdraws it. Counts on the event are kept in the same transaction. */
export async function rsvpEventCore(deps: Deps, actor: string, cid: string, eid: string, answer: Rsvp | null): Promise<void> {
  await consumeDailyBudget(deps, actor, 'eventRsvps', DAILY_RSVPS);
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, false);
    const eventRef = g.ref.collection('events').doc(eid);
    const rsvpRef = eventRef.collection('rsvps').doc(actor);
    const [event, rsvp] = await Promise.all([tx.get(eventRef), tx.get(rsvpRef)]);
    if (!event.exists) throw new HttpsError('not-found', 'Unknown event.');
    if (event.get('cancelled') === true) throw new HttpsError('failed-precondition', 'event-cancelled');
    if ((event.get('startsAt') as Timestamp).toMillis() <= deps.nowMs()) throw new HttpsError('failed-precondition', 'event-over');
    const before: Rsvp | null = RSVPS.includes(rsvp.get('status')) ? rsvp.get('status') : null;
    if (before === answer) return;
    const counts: Record<string, FieldValue> = {};
    if (before) counts[`counts.${before}`] = FieldValue.increment(-1);
    if (answer) counts[`counts.${answer}`] = FieldValue.increment(1);
    tx.update(eventRef, counts);
    if (answer) tx.set(rsvpRef, { uid: actor, status: answer, at: FieldValue.serverTimestamp() });
    else tx.delete(rsvpRef);
  });
}

/** The creator or any admin cancels an event; the chat gets a line and no reminder will go out. */
export async function cancelEventCore(deps: Deps, actor: string, cid: string, eid: string): Promise<void> {
  await consumeDailyBudget(deps, actor, 'eventRsvps', DAILY_RSVPS);
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, false);
    const eventRef = g.ref.collection('events').doc(eid);
    const event = await tx.get(eventRef);
    if (!event.exists) throw new HttpsError('not-found', 'Unknown event.');
    if (event.get('createdBy') !== actor && !g.admins.includes(actor)) {
      throw new HttpsError('permission-denied', 'Only the creator or an admin can cancel this event.');
    }
    if (event.get('cancelled') === true) return;
    tx.update(eventRef, { cancelled: true, cancelledBy: actor, reminderSent: true });
    systemMessage(tx, g.ref, actor, 'event_cancelled', { name: String(event.get('title') ?? '') });
  });
}

/**
 * The creator or any admin edits an event that is still ahead: same validation as creating. Moving the
 * start re-arms the reminder. The card in the chat reads the event live, so it needs no new message.
 */
export async function editEventCore(deps: Deps, actor: string, cid: string, eid: string, input: EventInput): Promise<void> {
  await consumeDailyBudget(deps, actor, 'eventRsvps', DAILY_RSVPS);
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, false);
    const eventRef = g.ref.collection('events').doc(eid);
    const event = await tx.get(eventRef);
    if (!event.exists) throw new HttpsError('not-found', 'Unknown event.');
    if (event.get('createdBy') !== actor && !g.admins.includes(actor)) {
      throw new HttpsError('permission-denied', 'Only the creator or an admin can edit this event.');
    }
    if (event.get('cancelled') === true) throw new HttpsError('failed-precondition', 'event-cancelled');
    const oldStart = (event.get('startsAt') as Timestamp).toMillis();
    if (oldStart <= deps.nowMs()) throw new HttpsError('failed-precondition', 'event-over');
    const update: Record<string, unknown> = {
      title: input.title,
      description: input.description,
      place: input.place,
      startsAt: Timestamp.fromMillis(input.startsAtMs),
      editedAt: FieldValue.serverTimestamp(),
    };
    if (input.startsAtMs !== oldStart) update.reminderSent = false;
    tx.update(eventRef, update);
  });
}

// ---- reminders ----------------------------------------------------------------------------------

/** Device tokens for [uids] (revoked or empty ones skipped), each mapped back to its device document. */
async function tokensFor(deps: Deps, uids: string[]): Promise<Map<string, FirebaseFirestore.DocumentReference>> {
  const owners = new Map<string, FirebaseFirestore.DocumentReference>();
  for (const uid of uids) {
    const devices = await deps.db.collection(`users/${uid}/devices`).where('fcmToken', '!=', null).get();
    for (const d of devices.docs) {
      const token = d.get('fcmToken');
      if (!d.get('revokedAt') && typeof token === 'string' && token.length > 0) owners.set(token, d.ref);
    }
  }
  return owners;
}

/**
 * Sends the reminder for every event that starts within the next 30 minutes and has not been reminded.
 * Each event is claimed in its own transaction (reminderSent false → true), so overlapping runs never
 * send twice. Goes to the creator and everyone who answered Going or Maybe, if still a member and the
 * chat is not muted.
 */
export async function sendDueEventReminders(deps: Deps, nowMs: number = deps.nowMs()): Promise<{ reminded: number }> {
  const due = await deps.db
    .collectionGroup('events')
    .where('reminderSent', '==', false)
    .where('startsAt', '>', Timestamp.fromMillis(nowMs))
    .where('startsAt', '<=', Timestamp.fromMillis(nowMs + REMINDER_LEAD_MS))
    .orderBy('startsAt')
    .limit(REMINDER_BATCH)
    .get();
  let reminded = 0;
  for (const d of due.docs) {
    const claimed = await deps.db
      .runTransaction(async (tx) => {
        const snap = await tx.get(d.ref);
        if (!snap.exists || snap.get('reminderSent') === true || snap.get('cancelled') === true) return false;
        tx.update(d.ref, { reminderSent: true });
        return true;
      })
      .catch(() => false);
    if (!claimed) continue;
    try {
      const convRef = d.ref.parent.parent!;
      const conv = await convRef.get();
      if (!conv.exists) continue;
      const members: string[] = conv.get('members') ?? [];
      const answers = await d.ref.collection('rsvps').where('status', 'in', ['going', 'maybe']).get();
      const wanted = new Set<string>([String(d.get('createdBy')), ...answers.docs.map((a) => a.id)]);
      const recipients: string[] = [];
      for (const uid of wanted) {
        if (!members.includes(uid)) continue;
        const member = await convRef.collection('members').doc(uid).get();
        if (member.get('muted') !== true) recipients.push(uid);
      }
      const owners = await tokensFor(deps, recipients);
      if (owners.size === 0) continue;
      const startsAt = (d.get('startsAt') as Timestamp).toMillis();
      const { invalidTokens } = await deps.push.send([...owners.keys()], {
        type: 'event_reminder',
        cid: convRef.id,
        eid: d.id,
        title: String(conv.get('name') ?? ''),
        body: String(d.get('title') ?? ''),
        startsAt: String(startsAt),
      });
      for (const t of invalidTokens) await owners.get(t)?.update({ fcmToken: null });
      reminded++;
    } catch {
      logEvent('event_reminder_failed', { outcome: 'error' });
    }
  }
  if (reminded > 0) logEvent('event_reminders_sent', { count: reminded, outcome: 'ok' });
  return { reminded };
}

// ---- callables ----------------------------------------------------------------------------------

type Req = CallableRequest<unknown>;

function eventIdOf(data: unknown): string {
  const eid = (data as { eid?: unknown } | null)?.eid;
  if (typeof eid !== 'string' || !EID.test(eid)) throw new HttpsError('invalid-argument', 'Unknown event.');
  return eid;
}

export const handleSetGroupSettings = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const settings = parseSettings(r.data);
  return run('group_settings_set', async () => (await setGroupSettingsCore(deps, uid, cid, settings), { ok: true }));
};
export const handleDecideJoinRequest = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const target = uidOf(r.data);
  const approve = (r.data as { approve?: unknown } | null)?.approve;
  if (typeof approve !== 'boolean') throw new HttpsError('invalid-argument', 'approve must be a boolean.');
  return run('group_join_decided', () => decideJoinRequestCore(deps, uid, cid, target, approve));
};
export const handleCancelJoinRequest = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  return run('group_join_cancelled', async () => (await cancelJoinRequestCore(deps, uid, cid), { ok: true }));
};
export const handleCreateGroupEvent = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const input = parseEvent(r.data, deps.nowMs());
  return run('group_event_created', () => createEventCore(deps, uid, cid, input));
};
export const handleRsvpGroupEvent = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const eid = eventIdOf(r.data);
  const answer = (r.data as { answer?: unknown } | null)?.answer ?? null;
  if (answer !== null && !(typeof answer === 'string' && (RSVPS as readonly string[]).includes(answer))) {
    throw new HttpsError('invalid-argument', 'Answer must be going, maybe or no.');
  }
  return run('group_event_rsvp', async () => (await rsvpEventCore(deps, uid, cid, eid, answer as Rsvp | null), { ok: true }));
};
export const handleCancelGroupEvent = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const eid = eventIdOf(r.data);
  return run('group_event_cancelled', async () => (await cancelEventCore(deps, uid, cid, eid), { ok: true }));
};

export const handleEditGroupEvent = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const eid = eventIdOf(r.data);
  const input = parseEvent(r.data, deps.nowMs());
  return run('group_event_edited', async () => (await editEventCore(deps, uid, cid, eid, input), { ok: true }));
};

const options = buildCallableOptions(process.env);
export const setGroupSettings = onCall(options, (r) => handleSetGroupSettings(r));
export const decideGroupJoinRequest = onCall(options, (r) => handleDecideJoinRequest(r));
export const cancelGroupJoinRequest = onCall(options, (r) => handleCancelJoinRequest(r));
export const createGroupEvent = onCall(options, (r) => handleCreateGroupEvent(r));
export const rsvpGroupEvent = onCall(options, (r) => handleRsvpGroupEvent(r));
export const cancelGroupEvent = onCall(options, (r) => handleCancelGroupEvent(r));
export const editGroupEvent = onCall(options, (r) => handleEditGroupEvent(r));

/** Every 5 minutes, like the scheduled-message sender: one instance, short run, nothing to do most of the time. */
export const sendEventReminders = onSchedule(
  { schedule: 'every 5 minutes', region: REGION, timeZone: 'Asia/Kolkata', retryCount: 0, maxInstances: 1 },
  async () => {
    await sendDueEventReminders(defaultDeps());
  },
);
