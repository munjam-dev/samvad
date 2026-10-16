import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { DAILY_PIN_CHANGES, MAX_PINS, handleSetPinnedMessage, readPins, removePinnedMessage } from '../src/pinned';
import { cleanupGoneMessage } from '../src/disappearing';
import { dropPinOfDeleted } from '../src/onMessageUpdated';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps({ deleteFiles: async () => undefined });
const CID = 'anil_bina';
const GID = 'AbCdEfGhIjKlMnOpQrSt';
const id = (n: number) => `7b2d1f0a-1111-4222-8333-${String(n).padStart(12, '0')}`;
const M = [1, 2, 3, 4, 5].map(id);
const call = (uid: string, data: unknown) => handleSetPinnedMessage(phoneRequest(uid, { data }), deps);
const pin = (uid: string, cid: string, mid: string, pinned = true) => call(uid, { conversationId: cid, messageId: mid, pinned });
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const conv = async (cid = CID) => (await deps.db.doc(`conversations/${cid}`).get()).data()!;
const pinnedIds = async (cid = CID) => readPins((await conv(cid)).pinnedMessages).map((p) => p.messageId);
const T0 = Timestamp.fromMillis(1_791_900_000_000);

async function message(cid: string, mid: string, extra: Record<string, unknown> = {}) {
  await deps.db.doc(`conversations/${cid}/messages/${mid}`).set({
    senderId: 'anil', kind: 'text', text: 'hello', createdAt: T0, replyTo: null, deleted: false, reactions: {}, ...extra,
  });
}

async function seed() {
  await deps.db.doc(`conversations/${CID}`).set({ type: 'direct', members: ['anil', 'bina'], createdBy: 'anil', createdAt: T0, lastMessage: null, lastMessageAt: T0 });
  await deps.db.doc(`conversations/${GID}`).set({ type: 'group', name: 'Family', members: ['anil', 'bina', 'chetan'], admins: ['anil'], createdBy: 'anil', createdAt: T0, lastMessage: null, lastMessageAt: T0 });
  await deps.db.doc(`conversations/${GID}/members/anil`).set({ uid: 'anil', joinedAt: T0 });
  await deps.db.doc(`conversations/${GID}/members/bina`).set({ uid: 'bina', joinedAt: T0 });
  for (const m of M) {
    await message(CID, m);
    await message(GID, m);
  }
}

beforeEach(async () => {
  await resetEmulators();
  await seed();
});

describe('setPinnedMessage', () => {
  it('lets either member of a direct chat pin and records who and when', async () => {
    expect(await errorOf(pin('bina', CID, M[0]))).toBe('ok');
    const [p] = readPins((await conv()).pinnedMessages);
    expect(p).toMatchObject({ messageId: M[0], pinnedBy: 'bina' });
    expect(p.pinnedAt.toMillis()).toBeGreaterThan(0);
    expect(await errorOf(pin('anil', CID, M[1]))).toBe('ok');
    expect(await pinnedIds()).toEqual([M[0], M[1]]);
  });

  it('keeps pins in the order they were made', async () => {
    for (const m of [M[2], M[0], M[1]]) await pin('anil', CID, m);
    expect(await pinnedIds()).toEqual([M[2], M[0], M[1]]);
  });

  it('allows three pins and refuses the fourth', async () => {
    for (const m of M.slice(0, MAX_PINS)) await pin('anil', CID, m);
    expect(await errorOf(pin('bina', CID, M[3]))).toBe('failed-precondition');
    expect(await pinnedIds()).toEqual(M.slice(0, MAX_PINS));
  });

  it('frees a place when a message is unpinned', async () => {
    for (const m of M.slice(0, MAX_PINS)) await pin('anil', CID, m);
    await pin('bina', CID, M[1], false);
    expect(await errorOf(pin('anil', CID, M[3]))).toBe('ok');
    expect(await pinnedIds()).toEqual([M[0], M[2], M[3]]);
  });

  it('pinning the same message twice does not add a duplicate, even when the chat is full', async () => {
    await pin('anil', CID, M[0]);
    expect(await errorOf(pin('bina', CID, M[0]))).toBe('ok');
    expect(await pinnedIds()).toEqual([M[0]]);
    await pin('anil', CID, M[1]);
    await pin('anil', CID, M[2]);
    expect(await errorOf(pin('anil', CID, M[2]))).toBe('ok');
    expect(await pinnedIds()).toHaveLength(3);
  });

  it('unpinning something that is not pinned does nothing', async () => {
    expect(await errorOf(pin('anil', CID, M[0], false))).toBe('ok');
    expect((await conv()).pinnedMessages).toBeUndefined();
  });

  it('refuses people who are not in the chat, for pin and unpin', async () => {
    await pin('anil', CID, M[0]);
    expect(await errorOf(pin('chetan', CID, M[1]))).toBe('permission-denied');
    expect(await errorOf(pin('chetan', CID, M[0], false))).toBe('permission-denied');
    expect(await pinnedIds()).toEqual([M[0]]);
  });

  it('gives an unknown chat the same answer as a chat the caller is not in', async () => {
    expect(await errorOf(pin('anil', 'nobody_here', M[0]))).toBe('permission-denied');
  });

  it('refuses a message that does not exist or belongs to another chat', async () => {
    expect(await errorOf(pin('anil', CID, id(99)))).toBe('not-found');
    await deps.db.doc(`conversations/other_chat`).set({ type: 'direct', members: ['anil', 'dev'], createdAt: T0 });
    await message('other_chat', id(50));
    expect(await errorOf(pin('anil', CID, id(50)))).toBe('not-found');
    expect(await pinnedIds()).toEqual([]);
  });

  it('refuses a message deleted for everyone, a system line and an expired message', async () => {
    await message(CID, id(10), { deleted: true, text: '' });
    await message(CID, id(11), { kind: 'system', text: '' });
    await message(CID, id(12), { expiresAt: Timestamp.fromMillis(Date.now() - 60_000) });
    for (const n of [10, 11, 12]) expect(await errorOf(pin('anil', CID, id(n)))).toBe('not-found');
    expect(await pinnedIds()).toEqual([]);
  });

  it('can still unpin a message that has since been deleted', async () => {
    await pin('anil', CID, M[0]);
    await deps.db.doc(`conversations/${CID}/messages/${M[0]}`).update({ deleted: true, text: '' });
    expect(await errorOf(pin('bina', CID, M[0], false))).toBe('ok');
    expect(await pinnedIds()).toEqual([]);
  });

  it('refuses to pin while either person has blocked the other, but still lets them unpin', async () => {
    await pin('anil', CID, M[0]);
    await deps.db.doc('users/bina/blocked/anil').set({ createdAt: T0 });
    expect(await errorOf(pin('anil', CID, M[1]))).toBe('permission-denied');
    expect(await errorOf(pin('bina', CID, M[1]))).toBe('permission-denied');
    expect(await errorOf(pin('bina', CID, M[0], false))).toBe('ok');
    expect(await pinnedIds()).toEqual([]);
  });

  it('refuses to pin when the blocked person is the one who blocked first', async () => {
    await deps.db.doc('users/anil/blocked/bina').set({ createdAt: T0 });
    expect(await errorOf(pin('bina', CID, M[0]))).toBe('permission-denied');
  });

  it('in a group only admins can pin or unpin', async () => {
    expect(await errorOf(pin('bina', GID, M[0]))).toBe('permission-denied');
    expect(await errorOf(pin('anil', GID, M[0]))).toBe('ok');
    expect(await errorOf(pin('bina', GID, M[0], false))).toBe('permission-denied');
    expect(await pinnedIds(GID)).toEqual([M[0]]);
    expect(await errorOf(pin('anil', GID, M[0], false))).toBe('ok');
    expect(await pinnedIds(GID)).toEqual([]);
  });

  it('a member who was removed from a group can no longer pin or unpin', async () => {
    await pin('anil', GID, M[0]);
    await deps.db.doc(`conversations/${GID}`).update({ members: ['anil', 'bina'], admins: ['anil', 'chetan'] });
    expect(await errorOf(pin('chetan', GID, M[1]))).toBe('permission-denied');
    expect(await errorOf(pin('chetan', GID, M[0], false))).toBe('permission-denied');
    expect(await pinnedIds(GID)).toEqual([M[0]]);
  });

  it('in a group an admin cannot pin a message from before they joined', async () => {
    await deps.db.doc(`conversations/${GID}/members/anil`).set({ uid: 'anil', joinedAt: Timestamp.fromMillis(T0.toMillis() + 1000) });
    expect(await errorOf(pin('anil', GID, M[0]))).toBe('not-found');
  });

  it('rejects malformed input', async () => {
    for (const data of [
      {},
      { conversationId: CID },
      { conversationId: '../x', messageId: M[0], pinned: true },
      { conversationId: CID, messageId: 'not-a-uuid', pinned: true },
      { conversationId: CID, messageId: M[0].toUpperCase(), pinned: true },
      { conversationId: CID, messageId: M[0] },
      { conversationId: CID, messageId: M[0], pinned: 'yes' },
      { conversationId: 5, messageId: M[0], pinned: true },
    ]) {
      expect(await errorOf(call('anil', data))).toBe('invalid-argument');
    }
  });

  it('requires a signed-in phone user', async () => {
    const r = phoneRequest('anil', { data: { conversationId: CID, messageId: M[0], pinned: true }, provider: 'password' });
    expect(await errorOf(handleSetPinnedMessage(r, deps))).toBe('permission-denied');
    expect(await errorOf(handleSetPinnedMessage({ data: {} } as any, deps))).toBe('unauthenticated');
  });

  it('is rate limited per day', async () => {
    for (let i = 0; i < DAILY_PIN_CHANGES; i++) await pin('anil', CID, M[0], i % 2 === 0);
    expect(await errorOf(pin('anil', CID, M[0]))).toBe('resource-exhausted');
  });
});

describe('pins follow their message', () => {
  beforeEach(async () => {
    for (const m of M.slice(0, 3)) await pin('anil', CID, m);
  });

  it('removePinnedMessage drops only that pin', async () => {
    expect(await removePinnedMessage(deps, CID, M[1])).toBe(true);
    expect(await pinnedIds()).toEqual([M[0], M[2]]);
  });

  it('removePinnedMessage can run twice and tolerates a missing chat or an unpinned message', async () => {
    await removePinnedMessage(deps, CID, M[1]);
    expect(await removePinnedMessage(deps, CID, M[1])).toBe(false);
    expect(await removePinnedMessage(deps, CID, M[4])).toBe(false);
    expect(await removePinnedMessage(deps, 'gone_chat', M[0])).toBe(false);
    expect(await pinnedIds()).toEqual([M[0], M[2]]);
  });

  it('an expired or otherwise removed message (cleanupGoneMessage) leaves the pins', async () => {
    await cleanupGoneMessage(deps, CID, M[0]);
    expect(await pinnedIds()).toEqual([M[1], M[2]]);
  });

  it('a message deleted for everyone leaves the pins', async () => {
    const before = { deleted: false, text: 'hello' };
    const after = { deleted: true, text: '' };
    expect(await dropPinOfDeleted(deps, CID, M[2], before, after)).toBe(true);
    expect(await pinnedIds()).toEqual([M[0], M[1]]);
  });

  it('other updates of a pinned message (edit, reaction, already deleted) keep the pin', async () => {
    expect(await dropPinOfDeleted(deps, CID, M[0], { deleted: false, text: 'a' }, { deleted: false, text: 'b' })).toBe(false);
    expect(await dropPinOfDeleted(deps, CID, M[0], { deleted: true }, { deleted: true })).toBe(false);
    expect(await dropPinOfDeleted(deps, CID, M[0], undefined, { deleted: true })).toBe(false);
    expect(await pinnedIds()).toEqual([M[0], M[1], M[2]]);
  });
});
