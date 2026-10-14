import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { cleanupGoneMessage, DAILY_TIMER_CHANGES, handleSetDisappearingTimer } from '../src/disappearing';
import { applyMessageCreated } from '../src/onMessageCreated';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const files: string[] = [];
const deps = testDeps({ deleteFiles: async (prefix) => void files.push(prefix) });
const CID = 'anil_bina';
const GID = 'AbCdEfGhIjKlMnOpQrSt';
const call = (uid: string, data: unknown) => handleSetDisappearingTimer(phoneRequest(uid, { data }), deps);
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const conv = async (cid = CID) => (await deps.db.doc(`conversations/${cid}`).get()).data()!;
const systemLines = async (cid = CID) =>
  (await deps.db.collection(`conversations/${cid}/messages`).where('kind', '==', 'system').get()).docs.map((d) => d.data());

async function seed() {
  await deps.db.doc(`conversations/${CID}`).set({ type: 'direct', members: ['anil', 'bina'], createdBy: 'anil', createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now() });
  await deps.db.doc(`conversations/${GID}`).set({ type: 'group', name: 'Family', members: ['anil', 'bina', 'chetan'], admins: ['anil'], createdBy: 'anil', createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now() });
}

beforeEach(async () => {
  files.length = 0;
  await resetEmulators();
  await seed();
});

describe('setDisappearingTimer', () => {
  it('lets either member of a direct chat set it and writes a system line', async () => {
    await call('bina', { conversationId: CID, seconds: 604_800 });
    expect((await conv()).disappearAfterSec).toBe(604_800);
    const lines = await systemLines();
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ senderId: 'bina', system: { event: 'disappearing', seconds: 604_800 } });
  });

  it('can turn it off again', async () => {
    await call('anil', { conversationId: CID, seconds: 86_400 });
    await call('anil', { conversationId: CID, seconds: 0 });
    expect((await conv()).disappearAfterSec).toBe(0);
    expect(await systemLines()).toHaveLength(2);
  });

  it('writes nothing when the value does not change', async () => {
    await call('anil', { conversationId: CID, seconds: 0 });
    expect(await systemLines()).toHaveLength(0);
  });

  it('refuses people who are not in the chat', async () => {
    expect(await errorOf(call('chetan', { conversationId: CID, seconds: 86_400 }))).toBe('permission-denied');
    expect((await conv()).disappearAfterSec).toBeUndefined();
  });

  it('in a group only admins can change it', async () => {
    expect(await errorOf(call('bina', { conversationId: GID, seconds: 86_400 }))).toBe('permission-denied');
    expect(await errorOf(call('anil', { conversationId: GID, seconds: 86_400 }))).toBe('ok');
    expect((await conv(GID)).disappearAfterSec).toBe(86_400);
  });

  it('rejects values that are not one of the four choices', async () => {
    for (const seconds of [1, 3600, -1, 999_999_999, '86400', null]) {
      expect(await errorOf(call('anil', { conversationId: CID, seconds }))).toBe('invalid-argument');
    }
  });

  it('rejects a missing or malformed chat id, and an unknown chat', async () => {
    expect(await errorOf(call('anil', { seconds: 0 }))).toBe('invalid-argument');
    expect(await errorOf(call('anil', { conversationId: '../x', seconds: 0 }))).toBe('invalid-argument');
    expect(await errorOf(call('anil', { conversationId: 'nobody_here', seconds: 0 }))).toBe('permission-denied');
  });

  it('is rate limited per day', async () => {
    for (let i = 0; i < DAILY_TIMER_CHANGES; i++) await call('anil', { conversationId: CID, seconds: i % 2 ? 0 : 86_400 });
    expect(await errorOf(call('anil', { conversationId: CID, seconds: 604_800 }))).toBe('resource-exhausted');
  });
});

describe('expiry stamp', () => {
  const created = Timestamp.fromMillis(1_791_900_000_000);
  const msg = (mid: string) => ({ cid: CID, mid, eventId: `evt-${mid}`, data: { senderId: 'anil', kind: 'text', text: 'hello', createdAt: created } });
  const put = (mid: string) => deps.db.doc(`conversations/${CID}/messages/${mid}`).set({ senderId: 'anil', kind: 'text', text: 'hello', createdAt: created, replyTo: null });
  const expiry = async (mid: string) => (await deps.db.doc(`conversations/${CID}/messages/${mid}`).get()).get('expiresAt') as Timestamp | undefined;

  it('stamps createdAt plus the timer when a timer is on', async () => {
    await call('anil', { conversationId: CID, seconds: 86_400 });
    await put('m1');
    await applyMessageCreated(deps, msg('m1'));
    expect((await expiry('m1'))!.toMillis()).toBe(created.toMillis() + 86_400_000);
  });

  it('stamps nothing when the timer is off', async () => {
    await put('m2');
    await applyMessageCreated(deps, msg('m2'));
    expect(await expiry('m2')).toBeUndefined();
  });

  it('does not rewrite an older message when the timer changes later', async () => {
    await put('m3');
    await applyMessageCreated(deps, msg('m3'));
    await call('anil', { conversationId: CID, seconds: 86_400 });
    expect(await expiry('m3')).toBeUndefined();
  });

  it('is applied once even if the trigger is delivered twice', async () => {
    await call('anil', { conversationId: CID, seconds: 86_400 });
    await put('m4');
    await applyMessageCreated(deps, msg('m4'));
    await applyMessageCreated(deps, msg('m4'));
    expect((await expiry('m4'))!.toMillis()).toBe(created.toMillis() + 86_400_000);
  });
});

describe('cleanupGoneMessage', () => {
  it('deletes the message files, its votes and every starred reference to it', async () => {
    await deps.db.doc(`conversations/${CID}/messages/gone/votes/anil`).set({ choices: ['a'], updatedAt: Timestamp.now() });
    await deps.db.doc(`users/anil/savedMessages/${CID}_gone`).set({ conversationId: CID, messageId: 'gone', savedAt: Timestamp.now() });
    await deps.db.doc(`users/bina/savedMessages/${CID}_gone`).set({ conversationId: CID, messageId: 'gone', savedAt: Timestamp.now() });
    await deps.db.doc(`users/bina/savedMessages/${CID}_kept`).set({ conversationId: CID, messageId: 'kept', savedAt: Timestamp.now() });

    await cleanupGoneMessage(deps, CID, 'gone');

    expect(files).toEqual([`conversations/${CID}/gone/`]);
    expect((await deps.db.doc(`conversations/${CID}/messages/gone/votes/anil`).get()).exists).toBe(false);
    expect((await deps.db.doc(`users/anil/savedMessages/${CID}_gone`).get()).exists).toBe(false);
    expect((await deps.db.doc(`users/bina/savedMessages/${CID}_gone`).get()).exists).toBe(false);
    expect((await deps.db.doc(`users/bina/savedMessages/${CID}_kept`).get()).exists).toBe(true);
  });

  it('can run twice without error', async () => {
    await cleanupGoneMessage(deps, CID, 'nothing');
    await cleanupGoneMessage(deps, CID, 'nothing');
  });
});
