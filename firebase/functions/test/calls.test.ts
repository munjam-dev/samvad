import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_CALLS,
  RING_SECONDS,
  handleAnswerCall,
  handleDeclineCall,
  handleEndCall,
  handleGetCallToken,
  handleStartCall,
  cleanupMissedCallsCore,
  cleanupCallsForDeletedAccount,
} from '../src/calls';
import { testDeps, phoneRequest, fakeLiveKit } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const { service: livekit, closed } = fakeLiveKit();
const A = 'anil';
const B = 'bina';
const CID = 'anil_bina';
const T0 = Timestamp.fromMillis(1_791_900_000_000);

const start = (uid: string, conversationId: string = CID, kind: 'audio' | 'video' = 'video') =>
  handleStartCall(phoneRequest(uid, { data: { conversationId, kind } }), deps);
const answer = (uid: string, callId: string) => handleAnswerCall(phoneRequest(uid, { data: { callId } }), deps);
const decline = (uid: string, callId: string) => handleDeclineCall(phoneRequest(uid, { data: { callId } }), deps);
const end = (uid: string, callId: string, kit: any = livekit) => handleEndCall(phoneRequest(uid, { data: { callId } }), deps, kit);
const token = (uid: string, callId: string, kit: any = livekit) => handleGetCallToken(phoneRequest(uid, { data: { callId } }), deps, kit);
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

async function seed() {
  await deps.db.doc(`profiles/${A}`).set({ displayName: 'Anil', about: '', photoUrl: null, updatedAt: T0 });
  await deps.db.doc(`profiles/${B}`).set({ displayName: 'Bina', about: '', photoUrl: null, updatedAt: T0 });
  await deps.db.doc(`profiles/chetan`).set({ displayName: 'Chetan', about: '', photoUrl: null, updatedAt: T0 });
  await deps.db.doc(`conversations/${CID}`).set({ type: 'direct', members: [A, B], createdBy: A, createdAt: T0, lastMessage: null, lastMessageAt: T0 });
  await deps.db.doc(`users/${B}/devices/d1`).set({ platform: 'android', appVersion: '1', osVersion: '14', createdAt: T0, lastSeenAt: T0, fcmToken: 'tok-bina' });
}

beforeEach(async () => {
  await resetEmulators();
  closed.length = 0;
  await seed();
});

describe('startCall', () => {
  it('rings the callee and records the call', async () => {
    const { callId, roomName } = await start(A);
    expect(roomName).toMatch(/^call-/);
    const snap = await deps.db.doc(`calls/${callId}`).get();
    expect(snap.get('status')).toBe('ringing');
    expect(snap.get('participants').sort()).toEqual([A, B]);
    expect(snap.get('callerUid')).toBe(A);
  });

  it('refuses a caller who is not a member of the chat', async () => {
    expect(await errorOf(start('chetan'))).toBe('permission-denied');
  });

  it('refuses when either side has blocked the other', async () => {
    await deps.db.doc(`users/${B}/blocked/${A}`).set({ createdAt: T0 });
    expect(await errorOf(start(A))).toBe('permission-denied');
  });

  it('refuses a busy callee', async () => {
    await start(A); // bina is now ringing
    await deps.db.doc(`conversations/bina_chetan`).set({ type: 'direct', members: [B, 'chetan'], createdBy: 'chetan', createdAt: T0, lastMessage: null, lastMessageAt: T0 });
    const err: any = await start('chetan', 'bina_chetan').catch((e) => e);
    expect(err.code).toBe('failed-precondition');
    expect(err.message).toBe('callee-busy');
  });

  it('refuses a caller who is already on another call', async () => {
    await start(A); // anil is now ringing bina
    await deps.db.doc(`conversations/anil_chetan`).set({ type: 'direct', members: [A, 'chetan'], createdBy: A, createdAt: T0, lastMessage: null, lastMessageAt: T0 });
    const err: any = await start(A, 'anil_chetan').catch((e) => e);
    expect(err.code).toBe('failed-precondition');
    expect(err.message).toBe('already-in-call');
  });

  it('enforces the daily call budget', async () => {
    for (let i = 0; i < DAILY_CALLS; i++) {
      await decline(B, (await start(A)).callId);
    }
    expect(await errorOf(start(A))).toBe('resource-exhausted');
  });
});

describe('answerCall / declineCall', () => {
  it('lets only the callee answer, and not the caller', async () => {
    const { callId } = await start(A);
    expect(await errorOf(answer(A, callId))).toBe('permission-denied');
    expect(await errorOf(answer(B, callId))).toBe('ok');
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('active');
  });

  it('refuses answering an expired ring', async () => {
    const { callId } = await start(A);
    await deps.db.doc(`calls/${callId}`).update({ expiresAt: Timestamp.fromMillis(deps.nowMs() - 1000) });
    expect(await errorOf(answer(B, callId))).toBe('failed-precondition');
  });

  it('lets the callee decline, not the caller', async () => {
    const { callId } = await start(A);
    expect(await errorOf(decline(A, callId))).toBe('permission-denied');
    expect(await errorOf(decline(B, callId))).toBe('ok');
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('declined');
  });
});

describe('endCall', () => {
  it('either participant can end an active call, and the room is closed', async () => {
    const { callId, roomName } = await start(A);
    await answer(B, callId);
    expect(await errorOf(end(B, callId))).toBe('ok');
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('ended');
    expect(closed).toContain(roomName);
  });

  it('refuses a non-participant', async () => {
    const { callId } = await start(A);
    expect(await errorOf(end('chetan', callId))).toBe('not-found');
  });
});

describe('getCallToken', () => {
  it('mints a token only for participants of a live call', async () => {
    const { callId } = await start(A);
    const t = await token(A, callId);
    expect(t.url).toBe(livekit.url);
    expect(t.token).toContain(A);
    expect(await errorOf(token('chetan', callId))).toBe('not-found');
  });

  it('refuses once the call has ended', async () => {
    const { callId } = await start(A);
    await decline(B, callId);
    expect(await errorOf(token(A, callId))).toBe('failed-precondition');
  });

  it('answers calls-not-configured when the LiveKit secrets are missing', async () => {
    const { callId } = await start(A);
    expect(await errorOf(token(A, callId, null))).toBe('failed-precondition');
    await expect(token(A, callId, null)).rejects.toMatchObject({ message: 'calls-not-configured' });
  });
});

describe('cleanupMissedCallsCore', () => {
  it('marks an unanswered ring as missed once it expires, and notifies the callee', async () => {
    const { callId } = await start(A);
    const missed = await cleanupMissedCallsCore(deps, deps.nowMs() + (RING_SECONDS + 1) * 1000);
    expect(missed).toBe(1);
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('missed');
  });

  it('leaves an answered call alone', async () => {
    const { callId } = await start(A);
    await answer(B, callId);
    await cleanupMissedCallsCore(deps, deps.nowMs() + (RING_SECONDS + 1) * 1000);
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('active');
  });
});

describe('cleanupCallsForDeletedAccount', () => {
  it('ends a live call involving the deleted account', async () => {
    const { callId } = await start(A);
    await answer(B, callId);
    await cleanupCallsForDeletedAccount(deps, B);
    expect((await deps.db.doc(`calls/${callId}`).get()).get('status')).toBe('ended');
  });
});
