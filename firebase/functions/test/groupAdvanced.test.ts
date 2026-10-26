import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_EVENTS,
  handleCancelGroupEvent,
  handleCancelJoinRequest,
  handleCreateGroupEvent,
  handleDecideJoinRequest,
  handleEditGroupEvent,
  handleRsvpGroupEvent,
  handleSetGroupSettings,
  MAX_EVENT_DESCRIPTION,
  MAX_EVENT_TITLE,
  MAX_UPCOMING_EVENTS,
  parseEvent,
  REMINDER_LEAD_MS,
  sendDueEventReminders,
} from '../src/groupAdvanced';
import { handleCreateGroup, handleCreateGroupInvite, handleJoinGroupWithCode, handlePreviewGroupInvite, MAX_GROUP_MEMBERS } from '../src/groups';
import { sendDueScheduled } from '../src/scheduled';
import { fakePush, phoneRequest, testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const KNOWN = ['group-full', 'event-in-past', 'event-too-far', 'event-cancelled', 'event-over', 'no-request', 'too-many-events', 'too-many-requests'];
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => (KNOWN.includes(e.message) ? e.message : e.code));
const call = <T>(fn: (r: any, d: any) => Promise<T>, uid: string, data: unknown, d = deps) => fn(phoneRequest(uid, { data }), d);
const conv = async (cid: string) => (await deps.db.doc(`conversations/${cid}`).get()).data()!;
const hour = 60 * 60 * 1000;
const inHours = (h: number) => Date.now() + h * hour;

async function profiles(...uids: string[]) {
  for (const u of uids) await deps.db.doc(`profiles/${u}`).set({ displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
}
async function group(): Promise<string> {
  return (await call(handleCreateGroup, 'anil', { name: 'Family', members: ['bina', 'chetan'] })).cid;
}
async function invite(cid: string): Promise<string> {
  return (await call(handleCreateGroupInvite, 'anil', { cid })).code;
}
const settings = (cid: string, uid: string, data: Record<string, unknown>) => call(handleSetGroupSettings, uid, { cid, ...data });
const newEvent = (cid: string, uid: string, extra: Record<string, unknown> = {}) =>
  call(handleCreateGroupEvent, uid, { cid, title: 'Picnic', description: 'Bring food', place: 'Lalbagh', startsAt: inHours(48), ...extra });

beforeEach(async () => {
  await resetEmulators();
  await profiles('anil', 'bina', 'chetan', 'dev', 'esha');
});

describe('group settings', () => {
  it('new groups have every setting off', async () => {
    const c = await conv(await group());
    expect(c).toMatchObject({ onlyAdminsSend: false, approveMembers: false, eventsByMembers: false });
  });

  it('only admins change settings; the change is stored and announcement mode leaves a chat line', async () => {
    const cid = await group();
    expect(await errorOf(settings(cid, 'bina', { onlyAdminsSend: true }))).toBe('permission-denied');
    expect(await errorOf(settings(cid, 'esha', { onlyAdminsSend: true }))).toBe('not-found');
    await settings(cid, 'anil', { onlyAdminsSend: true, approveMembers: true });
    expect(await conv(cid)).toMatchObject({ onlyAdminsSend: true, approveMembers: true, eventsByMembers: false });
    const lines = await deps.db.collection(`conversations/${cid}/messages`).where('kind', '==', 'system').get();
    expect(lines.docs.map((d) => d.get('system.event')).sort()).toEqual(['announce_on', 'created']);
    await settings(cid, 'anil', { onlyAdminsSend: true }); // no change, no new line
    expect((await deps.db.collection(`conversations/${cid}/messages`).get()).size).toBe(2);
  });

  it('rejects tampered or empty settings', async () => {
    const cid = await group();
    expect(await errorOf(settings(cid, 'anil', { onlyAdminsSend: 'yes' }))).toBe('invalid-argument');
    expect(await errorOf(settings(cid, 'anil', {}))).toBe('invalid-argument');
    expect(await errorOf(settings(cid, 'anil', { admins: ['bina'] }))).toBe('invalid-argument');
    expect(await errorOf(call(handleSetGroupSettings, 'anil', { cid: 'nope', onlyAdminsSend: true }))).toBe('invalid-argument');
  });
});

describe('approving new members', () => {
  it('without the setting, a join adds the person at once', async () => {
    const cid = await group();
    expect(await call(handleJoinGroupWithCode, 'dev', { code: await invite(cid) })).toEqual({ cid, status: 'joined' });
  });

  it('with the setting, a join only records a request; preview says so; asking twice changes nothing', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    expect(await call(handlePreviewGroupInvite, 'dev', { code })).toEqual({ name: 'Family', memberCount: 3, approval: true, pending: false });
    expect(await call(handleJoinGroupWithCode, 'dev', { code })).toEqual({ cid, status: 'pending' });
    expect(await call(handleJoinGroupWithCode, 'dev', { code })).toEqual({ cid, status: 'pending' });
    expect((await conv(cid)).members).not.toContain('dev');
    expect((await deps.db.doc(`conversations/${cid}/members/dev`).get()).exists).toBe(false);
    expect((await deps.rtdb.ref(`members/${cid}/dev`).get()).val()).toBeNull();
    const req = (await deps.db.doc(`groupJoinRequests/${cid}_dev`).get()).data()!;
    expect(req).toMatchObject({ cid, uid: 'dev', status: 'pending', groupName: 'Family' });
    expect((await call(handlePreviewGroupInvite, 'dev', { code })).pending).toBe(true);
  });

  it('a person withdraws their own pending request; it does not touch anyone else\'s and repeating is harmless', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code });
    await call(handleJoinGroupWithCode, 'esha', { code });
    await call(handleCancelJoinRequest, 'dev', { cid });
    await call(handleCancelJoinRequest, 'dev', { cid });
    expect((await deps.db.doc(`groupJoinRequests/${cid}_dev`).get()).exists).toBe(false);
    expect((await deps.db.doc(`groupJoinRequests/${cid}_esha`).get()).exists).toBe(true);
    expect(await errorOf(call(handleCancelJoinRequest, 'dev', { cid: 'short' }))).toBe('invalid-argument');
  });

  it('existing members joining by code are unaffected by the setting', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    expect(await call(handleJoinGroupWithCode, 'bina', { code })).toEqual({ cid, status: 'joined' });
    expect((await deps.db.doc(`groupJoinRequests/${cid}_bina`).get()).exists).toBe(false);
  });

  it('an admin approves: the person becomes a member with joinedAt, the mirror and a chat line; the request goes', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code });
    expect(await call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: true })).toEqual({ status: 'approved' });
    expect((await conv(cid)).members).toContain('dev');
    expect((await deps.db.doc(`conversations/${cid}/members/dev`).get()).get('joinedAt')).toBeInstanceOf(Timestamp);
    expect((await deps.rtdb.ref(`members/${cid}/dev`).get()).val()).toBe(true);
    expect((await deps.db.doc(`groupJoinRequests/${cid}_dev`).get()).exists).toBe(false);
    const joined = await deps.db.collection(`conversations/${cid}/messages`).where('system.event', '==', 'joined').get();
    expect(joined.size).toBe(1);
  });

  it('declining removes the request without adding anyone', async () => {
    const cid = await group();
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code: await invite(cid) });
    expect(await call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: false })).toEqual({ status: 'declined' });
    expect((await conv(cid)).members).not.toContain('dev');
    expect((await deps.db.doc(`groupJoinRequests/${cid}_dev`).get()).exists).toBe(false);
    expect(await errorOf(call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: true }))).toBe('no-request');
  });

  it('non-admins, non-members and bad input cannot decide', async () => {
    const cid = await group();
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code: await invite(cid) });
    expect(await errorOf(call(handleDecideJoinRequest, 'bina', { cid, uid: 'dev', approve: true }))).toBe('permission-denied');
    expect(await errorOf(call(handleDecideJoinRequest, 'dev', { cid, uid: 'dev', approve: true }))).toBe('not-found'); // the requester themselves
    expect(await errorOf(call(handleDecideJoinRequest, 'esha', { cid, uid: 'dev', approve: true }))).toBe('not-found');
    expect(await errorOf(call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: 'yes' }))).toBe('invalid-argument');
    expect(await errorOf(call(handleDecideJoinRequest, 'anil', { cid, uid: 'a/b', approve: true }))).toBe('invalid-argument');
    expect((await conv(cid)).members).not.toContain('dev');
  });

  it('refuses to approve past 256 members and keeps the request', async () => {
    const cid = await group();
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code: await invite(cid) });
    const filler = Array.from({ length: MAX_GROUP_MEMBERS - 3 }, (_, i) => `m${i}`);
    await deps.db.doc(`conversations/${cid}`).update({ members: ['anil', 'bina', 'chetan', ...filler] });
    expect(await errorOf(call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: true }))).toBe('group-full');
    expect((await deps.db.doc(`groupJoinRequests/${cid}_dev`).get()).exists).toBe(true);
  });

  it('a full group refuses new requests', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    const filler = Array.from({ length: MAX_GROUP_MEMBERS - 3 }, (_, i) => `m${i}`);
    await deps.db.doc(`conversations/${cid}`).update({ members: ['anil', 'bina', 'chetan', ...filler] });
    expect(await errorOf(call(handleJoinGroupWithCode, 'dev', { code }))).toBe('group-full');
  });

  it('blocked people (either way) and deleted accounts are not added; their request is removed', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code });
    await call(handleJoinGroupWithCode, 'esha', { code });
    await deps.db.doc('users/anil/blocked/dev').set({ createdAt: Timestamp.now() });
    await deps.db.doc('deletedAccounts/esha').set({ at: Timestamp.now() });
    expect(await call(handleDecideJoinRequest, 'anil', { cid, uid: 'dev', approve: true })).toEqual({ status: 'unavailable' });
    expect(await call(handleDecideJoinRequest, 'anil', { cid, uid: 'esha', approve: true })).toEqual({ status: 'unavailable' });
    expect((await conv(cid)).members).toEqual(['anil', 'bina', 'chetan']);
    expect((await deps.db.collection('groupJoinRequests').get()).size).toBe(0);
  });

  it('limits waiting requests per group and joins per day', async () => {
    const cid = await group();
    const code = await invite(cid);
    await settings(cid, 'anil', { approveMembers: true });
    for (let i = 0; i < 100; i++) {
      await deps.db.doc(`groupJoinRequests/${cid}_w${i}`).set({ cid, uid: `w${i}`, groupName: 'Family', status: 'pending', requestedAt: Timestamp.now() });
    }
    expect(await errorOf(call(handleJoinGroupWithCode, 'dev', { code }))).toBe('too-many-requests');
    await deps.db.doc('rateLimits/esha').set({ joinsWindowStart: Timestamp.now(), joinsCount: 20 });
    expect(await errorOf(call(handleJoinGroupWithCode, 'esha', { code }))).toBe('resource-exhausted');
  });
});

describe('events', () => {
  it('parses and bounds input', () => {
    const now = Date.now();
    const ok = { title: ' Picnic ', startsAt: now + hour };
    expect(parseEvent(ok, now)).toMatchObject({ title: 'Picnic', description: '', place: '' });
    const bad = (extra: Record<string, unknown>) => {
      try {
        parseEvent({ ...ok, ...extra }, now);
        return 'ok';
      } catch (e: any) {
        return e.message;
      }
    };
    expect(bad({ title: '' })).not.toBe('ok');
    expect(bad({ title: 'x'.repeat(MAX_EVENT_TITLE + 1) })).not.toBe('ok');
    expect(bad({ description: 'x'.repeat(MAX_EVENT_DESCRIPTION + 1) })).not.toBe('ok');
    expect(bad({ place: 'x'.repeat(81) })).not.toBe('ok');
    expect(bad({ startsAt: now - 1 })).toBe('event-in-past');
    expect(bad({ startsAt: now + 3 * 365 * 24 * hour })).toBe('event-too-far');
    expect(bad({ startsAt: '2030-01-01' })).not.toBe('ok');
    expect(bad({ startsAt: 1.5 })).not.toBe('ok');
    expect(bad({ title: 42 })).not.toBe('ok');
    expect(bad({ startsAt: now + hour })).toBe('ok');
  });

  it('an admin creates an event: the document and a timeline card are written', async () => {
    const cid = await group();
    const { eventId } = await newEvent(cid, 'anil');
    const e = (await deps.db.doc(`conversations/${cid}/events/${eventId}`).get()).data()!;
    expect(e).toMatchObject({ title: 'Picnic', description: 'Bring food', place: 'Lalbagh', createdBy: 'anil', cancelled: false, reminderSent: false, counts: { going: 0, maybe: 0, no: 0 } });
    const cards = await deps.db.collection(`conversations/${cid}/messages`).where('kind', '==', 'event').get();
    expect(cards.size).toBe(1);
    expect(cards.docs[0].data()).toMatchObject({ senderId: 'anil', eventId, eventTitle: 'Picnic' });
  });

  it('members may create events only when the group allows it, and never in announcement mode', async () => {
    const cid = await group();
    expect(await errorOf(newEvent(cid, 'bina'))).toBe('permission-denied');
    expect(await errorOf(newEvent(cid, 'esha'))).toBe('not-found');
    await settings(cid, 'anil', { eventsByMembers: true });
    expect(await errorOf(newEvent(cid, 'bina'))).toBe('ok');
    await settings(cid, 'anil', { onlyAdminsSend: true });
    expect(await errorOf(newEvent(cid, 'bina'))).toBe('permission-denied');
    expect(await errorOf(newEvent(cid, 'anil'))).toBe('ok');
  });

  it('refuses past dates, invalid fields, and a pending (non-member) requester', async () => {
    const cid = await group();
    expect(await errorOf(newEvent(cid, 'anil', { startsAt: Date.now() - 1000 }))).toBe('event-in-past');
    expect(await errorOf(newEvent(cid, 'anil', { title: '   ' }))).toBe('invalid-argument');
    expect(await errorOf(newEvent(cid, 'anil', { description: 'x'.repeat(301) }))).toBe('invalid-argument');
    await settings(cid, 'anil', { approveMembers: true, eventsByMembers: true });
    await call(handleJoinGroupWithCode, 'dev', { code: await invite(cid) });
    expect(await errorOf(newEvent(cid, 'dev'))).toBe('not-found');
    expect((await deps.db.collection(`conversations/${cid}/events`).get()).size).toBe(0);
  });

  it('limits upcoming events per group and events per day', async () => {
    const cid = await group();
    for (let i = 0; i < MAX_UPCOMING_EVENTS; i++) {
      await deps.db.doc(`conversations/${cid}/events/e${i}`).set({ title: 'x', startsAt: Timestamp.fromMillis(inHours(5)), cancelled: i % 2 === 0 && i >= 20, createdBy: 'anil' });
    }
    expect(await errorOf(newEvent(cid, 'anil'))).toBe('ok'); // 5 of them are cancelled, so 25 are upcoming
    await deps.db.doc('rateLimits/anil').set({ groupEventsWindowStart: Timestamp.now(), groupEventsCount: DAILY_EVENTS });
    expect(await errorOf(newEvent(cid, 'anil'))).toBe('resource-exhausted');
    await deps.db.doc('rateLimits/anil').delete();
    for (let i = 0; i < 6; i++) {
      await deps.db.doc(`conversations/${cid}/events/f${i}`).set({ title: 'x', startsAt: Timestamp.fromMillis(inHours(6)), cancelled: false, createdBy: 'anil' });
    }
    expect(await errorOf(newEvent(cid, 'anil'))).toBe('too-many-events');
  });

  it('RSVPs keep counts in step, can change and be withdrawn; only members answer', async () => {
    const cid = await group();
    const { eventId: eid } = await newEvent(cid, 'anil');
    const rsvp = (uid: string, answer: unknown) => call(handleRsvpGroupEvent, uid, { cid, eid, answer });
    const counts = async () => (await deps.db.doc(`conversations/${cid}/events/${eid}`).get()).get('counts');
    await rsvp('bina', 'going');
    await rsvp('chetan', 'maybe');
    await rsvp('anil', 'going');
    expect(await counts()).toEqual({ going: 2, maybe: 1, no: 0 });
    await rsvp('bina', 'no');
    await rsvp('bina', 'no');
    expect(await counts()).toEqual({ going: 1, maybe: 1, no: 1 });
    await rsvp('chetan', null);
    expect(await counts()).toEqual({ going: 1, maybe: 0, no: 1 });
    expect((await deps.db.doc(`conversations/${cid}/events/${eid}/rsvps/chetan`).get()).exists).toBe(false);
    expect(await errorOf(rsvp('esha', 'going'))).toBe('not-found');
    expect(await errorOf(rsvp('bina', 'definitely'))).toBe('invalid-argument');
    expect(await errorOf(call(handleRsvpGroupEvent, 'bina', { cid, eid: 'short', answer: 'going' }))).toBe('invalid-argument');
    expect(await errorOf(call(handleRsvpGroupEvent, 'bina', { cid, eid: 'a'.repeat(20), answer: 'going' }))).toBe('not-found');
  });

  it('no RSVP once an event has started or been cancelled', async () => {
    const cid = await group();
    const { eventId: started } = await newEvent(cid, 'anil');
    await deps.db.doc(`conversations/${cid}/events/${started}`).update({ startsAt: Timestamp.fromMillis(Date.now() - 1000) });
    expect(await errorOf(call(handleRsvpGroupEvent, 'bina', { cid, eid: started, answer: 'going' }))).toBe('event-over');
    const { eventId: gone } = await newEvent(cid, 'anil');
    await call(handleCancelGroupEvent, 'anil', { cid, eid: gone });
    expect(await errorOf(call(handleRsvpGroupEvent, 'bina', { cid, eid: gone, answer: 'going' }))).toBe('event-cancelled');
  });

  it('the creator or an admin edits an upcoming event; others cannot; invalid and finished events are refused', async () => {
    const cid = await group();
    await settings(cid, 'anil', { eventsByMembers: true });
    const { eventId } = await newEvent(cid, 'bina');
    const ref = deps.db.doc(`conversations/${cid}/events/${eventId}`);
    const edit = (uid: string, extra: Record<string, unknown> = {}) =>
      call(handleEditGroupEvent, uid, { cid, eid: eventId, title: 'Brunch', description: '', place: 'Cubbon', startsAt: inHours(72), ...extra });
    expect(await errorOf(edit('chetan'))).toBe('permission-denied');
    expect(await errorOf(edit('esha'))).toBe('not-found');
    expect(await errorOf(edit('bina', { title: ' ' }))).toBe('invalid-argument');
    expect(await errorOf(edit('bina', { startsAt: Date.now() - 1000 }))).toBe('event-in-past');
    await ref.update({ reminderSent: true });
    await edit('bina');
    expect((await ref.get()).data()).toMatchObject({ title: 'Brunch', place: 'Cubbon', reminderSent: false });
    await edit('anil', { title: 'Brunch 2' }); // an admin may edit someone else's event
    expect((await ref.get()).get('title')).toBe('Brunch 2');
    await call(handleCancelGroupEvent, 'anil', { cid, eid: eventId });
    expect(await errorOf(edit('bina'))).toBe('event-cancelled');
    const { eventId: old } = await newEvent(cid, 'anil');
    await deps.db.doc(`conversations/${cid}/events/${old}`).update({ startsAt: Timestamp.fromMillis(Date.now() - 1000) });
    expect(await errorOf(call(handleEditGroupEvent, 'anil', { cid, eid: old, title: 'x', startsAt: inHours(5) }))).toBe('event-over');
  });

  it('the creator or an admin cancels; other members cannot; a chat line is written once', async () => {
    const cid = await group();
    await settings(cid, 'anil', { eventsByMembers: true });
    const { eventId: mine } = await newEvent(cid, 'bina');
    const cancel = (uid: string, eid: string) => call(handleCancelGroupEvent, uid, { cid, eid });
    expect(await errorOf(cancel('chetan', mine))).toBe('permission-denied');
    expect(await errorOf(cancel('esha', mine))).toBe('not-found');
    await cancel('bina', mine);
    await cancel('bina', mine);
    expect((await deps.db.doc(`conversations/${cid}/events/${mine}`).get()).get('cancelled')).toBe(true);
    const { eventId: admins } = await newEvent(cid, 'bina');
    await cancel('anil', admins); // an admin may cancel someone else's event
    const lines = await deps.db.collection(`conversations/${cid}/messages`).where('system.event', '==', 'event_cancelled').get();
    expect(lines.size).toBe(2);
    expect(lines.docs[0].get('system.name')).toBe('Picnic');
    expect(await errorOf(cancel('anil', 'z'.repeat(20)))).toBe('not-found');
  });
});

describe('event reminders', () => {
  async function seedDevice(uid: string, token: string) {
    await deps.db.doc(`users/${uid}/devices/${token}`).set({ platform: 'android', fcmToken: token });
  }
  async function eventAt(cid: string, startsAtMs: number, extra: Record<string, unknown> = {}) {
    const ref = deps.db.collection(`conversations/${cid}/events`).doc();
    await ref.set({ title: 'Picnic', description: '', place: '', startsAt: Timestamp.fromMillis(startsAtMs), createdBy: 'anil', cancelled: false, reminderSent: false, counts: { going: 0, maybe: 0, no: 0 }, ...extra });
    return ref;
  }

  it('reminds the creator and people going or maybe, once, 30 minutes ahead', async () => {
    const cid = await group();
    const now = Date.now();
    const ref = await eventAt(cid, now + 20 * 60_000);
    await ref.collection('rsvps').doc('bina').set({ uid: 'bina', status: 'going' });
    await ref.collection('rsvps').doc('chetan').set({ uid: 'chetan', status: 'no' });
    for (const u of ['anil', 'bina', 'chetan']) await seedDevice(u, `${u}-phone`);
    const push = fakePush();
    const d = testDeps({ push: push.sender });
    expect(await sendDueEventReminders(d, now)).toEqual({ reminded: 1 });
    expect(push.sent).toHaveLength(1);
    expect(push.sent[0].tokens).toEqual(['anil-phone', 'bina-phone']);
    expect(push.sent[0].data).toMatchObject({ type: 'event_reminder', cid, eid: ref.id, title: 'Family', body: 'Picnic' });
    expect(Object.values(push.sent[0].data).every((v) => typeof v === 'string')).toBe(true);
    expect(await sendDueEventReminders(d, now)).toEqual({ reminded: 0 });
    expect(push.sent).toHaveLength(1);
  });

  it('leaves later, past and cancelled events alone, and skips muted chats and people who left', async () => {
    const cid = await group();
    const now = Date.now();
    await eventAt(cid, now + REMINDER_LEAD_MS + 60_000);
    await eventAt(cid, now - 60_000);
    await eventAt(cid, now + 10 * 60_000, { cancelled: true });
    const push = fakePush();
    const d = testDeps({ push: push.sender });
    await seedDevice('anil', 'anil-phone');
    expect(await sendDueEventReminders(d, now)).toEqual({ reminded: 0 });
    await eventAt(cid, now + 10 * 60_000);
    await deps.db.doc(`conversations/${cid}/members/anil`).update({ muted: true });
    expect(await sendDueEventReminders(d, now)).toEqual({ reminded: 0 });
    expect(push.sent).toHaveLength(0);
  });

  it('cancelling an event stops its reminder', async () => {
    const cid = await group();
    const { eventId } = await newEvent(cid, 'anil');
    await deps.db.doc(`conversations/${cid}/events/${eventId}`).update({ startsAt: Timestamp.fromMillis(Date.now() + 10 * 60_000) });
    await call(handleCancelGroupEvent, 'anil', { cid, eid: eventId });
    await seedDevice('anil', 'anil-phone');
    const push = fakePush();
    expect(await sendDueEventReminders(testDeps({ push: push.sender }))).toEqual({ reminded: 0 });
    expect(push.sent).toHaveLength(0);
  });
});

describe('announcement mode and scheduled messages', () => {
  it('a scheduled message from a member is not sent once only admins may post', async () => {
    const cid = await group();
    const now = Date.now();
    await deps.db.doc('scheduledMessages/s1').set({
      senderId: 'bina', conversationId: cid, text: 'Hello', replyTo: null, sendAt: Timestamp.fromMillis(now - 1000),
      status: 'pending', createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    });
    await settings(cid, 'anil', { onlyAdminsSend: true });
    expect(await sendDueScheduled(deps, now)).toEqual({ sent: 0, failed: 1 });
    expect((await deps.db.doc('scheduledMessages/s1').get()).get('failure')).toBe('not_allowed');
  });
});
