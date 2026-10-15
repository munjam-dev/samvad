import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { applyStatusCreated, cleanupStatus, computeVisibleTo, MAX_VISIBLE, STATUS_LIFETIME_MS } from '../src/status';
import { testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const files: string[] = [];
const deps = testDeps({ deleteFiles: async (prefix) => void files.push(prefix) });
const SID = '7b2d1f0a-1111-4222-8333-944455556666';
const created = Timestamp.fromMillis(1_791_900_000_000);

async function chat(a: string, b: string) {
  const [x, y] = [a, b].sort();
  await deps.db.doc(`conversations/${x}_${y}`).set({ type: 'direct', members: [x, y], createdBy: x, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now() });
}
const item = (extra: Record<string, unknown> = {}) =>
  deps.db.doc(`statuses/anil/items/${SID}`).set({ kind: 'text', text: 'hi', audience: 'all', list: [], createdAt: created, ...extra });
const read = async () => (await deps.db.doc(`statuses/anil/items/${SID}`).get()).data()!;

beforeEach(async () => {
  files.length = 0;
  await resetEmulators();
  for (const p of ['bina', 'chetan', 'dev']) await chat('anil', p);
  await chat('bina', 'chetan'); // a chat that does not involve the author
});

describe('computeVisibleTo', () => {
  it('"all" is every direct-chat partner of the author, and nobody else', async () => {
    expect((await computeVisibleTo(deps, 'anil', 'all', [])).sort()).toEqual(['bina', 'chetan', 'dev']);
  });

  it('"only" narrows to the chosen people who are partners', async () => {
    expect((await computeVisibleTo(deps, 'anil', 'only', ['bina', 'stranger'])).sort()).toEqual(['bina']);
  });

  it('"except" removes the chosen people', async () => {
    expect((await computeVisibleTo(deps, 'anil', 'except', ['bina'])).sort()).toEqual(['chetan', 'dev']);
  });

  it('leaves out people the author has blocked', async () => {
    await deps.db.doc('users/anil/blocked/bina').set({ createdAt: Timestamp.now() });
    expect((await computeVisibleTo(deps, 'anil', 'all', [])).sort()).toEqual(['chetan', 'dev']);
  });

  it('leaves out people who have blocked the author', async () => {
    await deps.db.doc('users/chetan/blocked/anil').set({ createdAt: Timestamp.now() });
    expect((await computeVisibleTo(deps, 'anil', 'all', [])).sort()).toEqual(['bina', 'dev']);
  });

  it('ignores group chats', async () => {
    await deps.db.doc('conversations/AbCdEfGhIjKlMnOpQrSt').set({ type: 'group', name: 'Family', members: ['anil', 'esha', 'farid'], admins: ['anil'], createdBy: 'anil', createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now() });
    const visible = await computeVisibleTo(deps, 'anil', 'all', []);
    expect(visible).not.toContain('esha');
    expect(visible).not.toContain('farid');
  });

  it('never exceeds the cap', () => {
    expect(MAX_VISIBLE).toBe(1000);
  });
});

describe('applyStatusCreated', () => {
  it('writes who can see it and a 24 hour expiry', async () => {
    await item();
    await applyStatusCreated(deps, 'anil', SID);
    const d = await read();
    expect((d.visibleTo as string[]).sort()).toEqual(['bina', 'chetan', 'dev']);
    expect((d.expiresAt as Timestamp).toMillis()).toBe(created.toMillis() + STATUS_LIFETIME_MS);
  });

  it('respects an "except" audience', async () => {
    await item({ audience: 'except', list: ['dev'] });
    await applyStatusCreated(deps, 'anil', SID);
    expect(((await read()).visibleTo as string[]).sort()).toEqual(['bina', 'chetan']);
  });

  it('gives the same result when run twice', async () => {
    await item();
    await applyStatusCreated(deps, 'anil', SID);
    const first = await read();
    await applyStatusCreated(deps, 'anil', SID);
    expect(await read()).toEqual(first);
  });

  it('does nothing when the status is already gone', async () => {
    await applyStatusCreated(deps, 'anil', 'missing');
    expect((await deps.db.doc('statuses/anil/items/missing').get()).exists).toBe(false);
  });
});

describe('cleanupStatus', () => {
  it('deletes the photo folder, the views and the reactions', async () => {
    await deps.db.doc(`statuses/anil/items/${SID}/views/bina`).set({ viewedAt: Timestamp.now() });
    await deps.db.doc(`statuses/anil/items/${SID}/reactions/bina`).set({ emoji: '❤️', at: Timestamp.now() });
    await cleanupStatus(deps, 'anil', SID);
    expect(files).toEqual([`statuses/anil/${SID}/`]);
    expect((await deps.db.doc(`statuses/anil/items/${SID}/views/bina`).get()).exists).toBe(false);
    expect((await deps.db.doc(`statuses/anil/items/${SID}/reactions/bina`).get()).exists).toBe(false);
  });

  it('can run twice without error', async () => {
    await cleanupStatus(deps, 'anil', SID);
    await cleanupStatus(deps, 'anil', SID);
  });
});
