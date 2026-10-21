import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  MAX_MEETING_PARTICIPANTS,
  MEETING_OPEN_AFTER_MS,
  MEETING_OPEN_BEFORE_MS,
  isMeetingWindowOpen,
  handleEndMeeting,
  handleGetMeetingToken,
  handleScheduleMeeting,
} from '../src/meetings';
import { testDeps, phoneRequest, fakeLiveKit } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const { service: livekit, closed } = fakeLiveKit();
const A = 'anil';
const B = 'bina';
const C = 'chetan';
const GID = 'AbCdEfGhIjKlMnOpQrSt';
const T0 = Timestamp.fromMillis(1_791_900_000_000);
const NOW = 1_800_000_000_000;

const schedule = (uid: string, groupId: string, startsAt: number, kind: 'audio' | 'video' = 'video', title = 'Weekly sync') =>
  handleScheduleMeeting(phoneRequest(uid, { data: { groupId, title, startsAt, kind } }), deps);
const getToken = (uid: string, meetingId: string, kit: any = livekit) => handleGetMeetingToken(phoneRequest(uid, { data: { meetingId } }), deps, kit);
const endMeeting = (uid: string, meetingId: string, kit: any = livekit) => handleEndMeeting(phoneRequest(uid, { data: { meetingId } }), deps, kit);
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

async function seed() {
  for (const u of [A, B, C]) await deps.db.doc(`profiles/${u}`).set({ displayName: u, about: '', photoUrl: null, updatedAt: T0 });
  await deps.db.doc(`conversations/${GID}`).set({
    type: 'group', name: 'Family', members: [A, B, C], admins: [A], createdBy: A, createdAt: T0, lastMessage: null, lastMessageAt: T0,
  });
}

beforeEach(async () => {
  await resetEmulators();
  closed.length = 0;
  deps.nowMs = () => NOW;
  await seed();
});

describe('isMeetingWindowOpen', () => {
  it('opens 30 minutes before start and stays open 4 hours after', () => {
    const start = NOW;
    expect(isMeetingWindowOpen(start, start - MEETING_OPEN_BEFORE_MS)).toBe(true);
    expect(isMeetingWindowOpen(start, start - MEETING_OPEN_BEFORE_MS - 1000)).toBe(false);
    expect(isMeetingWindowOpen(start, start + MEETING_OPEN_AFTER_MS)).toBe(true);
    expect(isMeetingWindowOpen(start, start + MEETING_OPEN_AFTER_MS + 1000)).toBe(false);
  });
});

describe('scheduleMeeting', () => {
  it('lets an admin schedule a meeting and posts a meeting card', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 60 * 60 * 1000);
    const snap = await deps.db.doc(`meetings/${meetingId}`).get();
    expect(snap.get('status')).toBe('scheduled');
    expect(snap.get('hostUid')).toBe(A);
    const messages = await deps.db.collection(`conversations/${GID}/messages`).where('kind', '==', 'meeting').get();
    expect(messages.docs).toHaveLength(1);
    expect(messages.docs[0].get('meeting').meetingId).toBe(meetingId);
  });

  it('refuses a non-admin member', async () => {
    expect(await errorOf(schedule(B, GID, NOW + 60 * 60 * 1000))).toBe('permission-denied');
  });

  it('refuses a start time in the past', async () => {
    expect(await errorOf(schedule(A, GID, NOW - 1000))).toBe('invalid-argument');
  });
});

describe('getMeetingToken', () => {
  it('mints a token for a group member while the window is open', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    const t = await getToken(B, meetingId);
    expect(t.url).toBe(livekit.url);
    const snap = await deps.db.doc(`meetings/${meetingId}`).get();
    expect(snap.get('participants')).toContain(B);
  });

  it('refuses a non-member', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    expect(await errorOf(getToken('indu', meetingId))).toBe('permission-denied');
  });

  it('refuses outside the join window', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    deps.nowMs = () => NOW - MEETING_OPEN_BEFORE_MS - 1000;
    expect(await errorOf(getToken(B, meetingId))).toBe('failed-precondition');
    deps.nowMs = () => NOW;
  });

  it('caps participants at the limit', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    const ref = deps.db.doc(`meetings/${meetingId}`);
    const many = Array.from({ length: MAX_MEETING_PARTICIPANTS }, (_, i) => `user${i}`);
    await ref.update({ participants: many });
    expect(await errorOf(getToken(B, meetingId))).toBe('resource-exhausted');
  });

  it('answers calls-not-configured when the LiveKit secrets are missing', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    expect(await errorOf(getToken(B, meetingId, null))).toBe('failed-precondition');
    await expect(getToken(B, meetingId, null)).rejects.toMatchObject({ message: 'calls-not-configured' });
  });
});

describe('endMeeting', () => {
  it('lets the host end the meeting for everyone, closing the room', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    const roomName = (await deps.db.doc(`meetings/${meetingId}`).get()).get('roomName');
    await endMeeting(A, meetingId);
    expect((await deps.db.doc(`meetings/${meetingId}`).get()).get('status')).toBe('ended');
    expect(closed).toContain(roomName);
  });

  it('refuses a non-host', async () => {
    const { meetingId } = await schedule(A, GID, NOW + 10 * 60 * 1000);
    expect(await errorOf(endMeeting(B, meetingId))).toBe('permission-denied');
  });
});
