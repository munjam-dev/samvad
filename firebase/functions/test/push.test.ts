import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { applyMessageCreated } from '../src/onMessageCreated';
import { fakePush, testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const base = testDeps();
const db = base.db;
const CID = 'alice_bob';

async function seed(opts: { bobMuted?: boolean } = {}) {
  await db.doc(`conversations/${CID}`).set({
    type: 'direct', members: ['alice', 'bob'], createdBy: 'alice',
    createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
  });
  for (const uid of ['alice', 'bob']) {
    await db.doc(`conversations/${CID}/members/${uid}`).set({
      uid, lastReadAt: null, lastDeliveredAt: null, unreadCount: 0,
      muted: uid === 'bob' && !!opts.bobMuted, pinned: false, archived: false,
    });
  }
  await db.doc('profiles/alice').set({ displayName: 'Aarav Sharma', about: '', photoUrl: null, updatedAt: Timestamp.now() });
}

async function device(uid: string, token: string | null, extra: Record<string, unknown> = {}) {
  const id = randomUUID();
  await db.doc(`users/${uid}/devices/${id}`).set({ platform: 'android', fcmToken: token, ...extra });
  return id;
}

const message = (eventId = randomUUID()) => ({
  cid: CID,
  mid: randomUUID(),
  eventId,
  data: { senderId: 'alice', kind: 'text', text: 'Hey! Are we still on for today?', createdAt: Timestamp.now() },
});

beforeEach(resetEmulators);

describe('push notifications on new messages', () => {
  it("sends one data push to every active device of the recipient, never the sender's", async () => {
    await seed();
    await device('bob', 'bob-phone');
    await device('bob', 'bob-tablet');
    await device('bob', null);
    await device('alice', 'alice-phone');
    const push = fakePush();
    await applyMessageCreated(testDeps({ push: push.sender }), message());
    expect(push.sent).toHaveLength(1);
    expect(push.sent[0].tokens).toEqual(['bob-phone', 'bob-tablet']);
    expect(push.sent[0].data).toMatchObject({
      type: 'message', cid: CID, senderId: 'alice', title: 'Aarav Sharma', body: 'Hey! Are we still on for today?',
    });
    expect(Object.values(push.sent[0].data).every((v) => typeof v === 'string')).toBe(true);
  });

  it('skips muted chats and revoked devices', async () => {
    await seed({ bobMuted: true });
    await device('bob', 'bob-phone');
    const push = fakePush();
    await applyMessageCreated(testDeps({ push: push.sender }), message());
    expect(push.sent).toHaveLength(0);

    await resetEmulators();
    await seed();
    await device('bob', 'old-phone', { revokedAt: Timestamp.now() });
    await applyMessageCreated(testDeps({ push: push.sender }), message());
    expect(push.sent).toHaveLength(0);
  });

  it('does not notify twice when the same event is redelivered', async () => {
    await seed();
    await device('bob', 'bob-phone');
    const push = fakePush();
    const m = message('same-event');
    await applyMessageCreated(testDeps({ push: push.sender }), m);
    await applyMessageCreated(testDeps({ push: push.sender }), m);
    expect(push.sent).toHaveLength(1);
  });

  it('clears tokens FCM reports as dead', async () => {
    await seed();
    const dead = await device('bob', 'dead-token');
    const live = await device('bob', 'live-token');
    const push = fakePush(['dead-token']);
    await applyMessageCreated(testDeps({ push: push.sender }), message());
    expect((await db.doc(`users/bob/devices/${dead}`).get()).get('fcmToken')).toBeNull();
    expect((await db.doc(`users/bob/devices/${live}`).get()).get('fcmToken')).toBe('live-token');
  });

  it('a push failure never breaks message fan-out', async () => {
    await seed();
    await device('bob', 'bob-phone');
    const failing = testDeps({ push: { send: async () => { throw new Error('fcm down'); } } });
    await applyMessageCreated(failing, message());
    expect((await db.doc(`conversations/${CID}/members/bob`).get()).get('unreadCount')).toBe(1);
  });
});
