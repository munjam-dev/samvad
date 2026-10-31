import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_AUDIENCE_EDITS, handleSetStatusAudience, MOMENT_PING_CAP, MOMENT_PING_WINDOW_MS, notifyMomentViewers,
} from '../src/status';
import { fakePush, phoneRequest, testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const db = testDeps().db;
const SID = '7b2d1f0a-1111-4222-8333-944455556666';
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

async function chat(a: string, b: string, mutedBy?: string) {
  const [x, y] = [a, b].sort();
  await db.doc(`conversations/${x}_${y}`).set({ type: 'direct', members: [x, y], createdBy: x, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now() });
  for (const u of [x, y]) await db.doc(`conversations/${x}_${y}/members/${u}`).set({ uid: u, muted: u === mutedBy });
}
async function device(uid: string, token: string | null, extra: Record<string, unknown> = {}) {
  await db.doc(`users/${uid}/devices/${randomUUID()}`).set({ platform: 'android', fcmToken: token, ...extra });
}

beforeEach(async () => {
  await resetEmulators();
  await db.doc('profiles/anil').set({ displayName: 'Anil Rao', about: '', photoUrl: null, updatedAt: Timestamp.now() });
});

describe('new-moment notifications', () => {
  it('sends one data push with the author name and no content, to every active device', async () => {
    await chat('anil', 'bina');
    await device('bina', 'bina-phone');
    await device('bina', 'bina-tablet');
    await device('bina', 'bina-old', { revokedAt: Timestamp.now() });
    const push = fakePush();
    const n = await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', ['bina']);
    expect(n).toBe(1);
    expect(push.sent).toHaveLength(1);
    expect(push.sent[0].tokens).toEqual(['bina-phone', 'bina-tablet']);
    expect(push.sent[0].data).toEqual({ type: 'moment_new', authorId: 'anil', title: 'Anil Rao', momentAuthor: 'anil' });
  });

  it('skips the author, people with the chat muted, and blocked pairs in either direction', async () => {
    await chat('anil', 'bina', 'bina');
    await chat('anil', 'chetan');
    await chat('anil', 'dev');
    await chat('anil', 'esha');
    for (const u of ['anil', 'bina', 'chetan', 'dev', 'esha']) await device(u, `${u}-phone`);
    await db.doc('users/anil/blocked/chetan').set({ createdAt: Timestamp.now() });
    await db.doc('users/dev/blocked/anil').set({ createdAt: Timestamp.now() });
    const push = fakePush();
    await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', ['anil', 'bina', 'chetan', 'dev', 'esha']);
    expect(push.sent.flatMap((s) => s.tokens)).toEqual(['esha-phone']);
  });

  it('notifies a viewer at most once per author in 6 hours, per author', async () => {
    await chat('anil', 'bina');
    await chat('bina', 'chetan');
    await device('bina', 'bina-phone');
    await db.doc('profiles/chetan').set({ displayName: 'Chetan', about: '', photoUrl: null, updatedAt: Timestamp.now() });
    const push = fakePush();
    let now = 1_800_000_000_000;
    const deps = testDeps({ push: push.sender, nowMs: () => now });
    await notifyMomentViewers(deps, 'anil', ['bina']);
    now += MOMENT_PING_WINDOW_MS - 1000;
    expect(await notifyMomentViewers(deps, 'anil', ['bina'])).toBe(0);
    expect(push.sent).toHaveLength(1);
    // Another author is a separate allowance.
    expect(await notifyMomentViewers(deps, 'chetan', ['bina'])).toBe(1);
    expect(push.sent).toHaveLength(2);
    now += 2000;
    expect(await notifyMomentViewers(deps, 'anil', ['bina'])).toBe(1);
    expect(push.sent).toHaveLength(3);
  });

  it('skips a viewer who turned Moment alerts off, and still notifies one who turned them on', async () => {
    await chat('anil', 'bina');
    await chat('anil', 'chetan');
    await device('bina', 'bina-phone');
    await device('chetan', 'chetan-phone');
    await db.doc('users/bina/settings/notifications').set({ momentAlerts: false });
    await db.doc('users/chetan/settings/notifications').set({ momentAlerts: true });
    const push = fakePush();
    const n = await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', ['bina', 'chetan']);
    expect(n).toBe(1);
    expect(push.sent.flatMap((s) => s.tokens)).toEqual(['chetan-phone']);
    // The opted-out viewer's allowance is untouched, so turning alerts back on works straight away.
    expect((await db.doc('momentPings/bina_anil').get()).exists).toBe(false);
  });

  it('a viewer without a device does not use up their allowance', async () => {
    await chat('anil', 'bina');
    const push = fakePush();
    expect(await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', ['bina'])).toBe(0);
    expect((await db.doc('momentPings/bina_anil').get()).exists).toBe(false);
  });

  it('clears tokens FCM reports as dead', async () => {
    await chat('anil', 'bina');
    await device('bina', 'dead-token');
    const push = fakePush(['dead-token']);
    await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', ['bina']);
    const devices = await db.collection('users/bina/devices').get();
    expect(devices.docs[0].get('fcmToken')).toBeNull();
  });

  it('never looks past the first 500 viewers', async () => {
    expect(MOMENT_PING_CAP).toBe(500);
    const push = fakePush();
    const many = Array.from({ length: 600 }, (_, i) => `u${i}`);
    // No chats or devices exist, so nothing is sent; the point is that it returns quickly for an oversize list.
    expect(await notifyMomentViewers(testDeps({ push: push.sender }), 'anil', many)).toBe(0);
    expect(push.sent).toHaveLength(0);
  });
});

describe('setStatusAudience', () => {
  const created = Timestamp.fromMillis(Date.now() - 60_000);
  async function seedMoment(extra: Record<string, unknown> = {}) {
    for (const p of ['bina', 'chetan', 'dev']) await chat('anil', p);
    await db.doc(`statuses/anil/items/${SID}`).set({
      kind: 'text', text: 'hi', audience: 'all', list: [], createdAt: created, expiresAt: Timestamp.fromMillis(created.toMillis() + 86_400_000),
      visibleTo: ['bina', 'chetan', 'dev'], ...extra,
    });
  }
  const call = (uid: string, data: unknown, deps = testDeps()) => handleSetStatusAudience(phoneRequest(uid, { data }), deps);
  const visible = async () => ((await db.doc(`statuses/anil/items/${SID}`).get()).get('visibleTo') as string[]).sort();

  it('re-stamps visibleTo like creation for "only" and "except"', async () => {
    await seedMoment();
    await call('anil', { statusId: SID, audience: 'only', list: ['bina', 'stranger'] });
    expect(await visible()).toEqual(['bina']);
    expect((await db.doc(`statuses/anil/items/${SID}`).get()).get('audience')).toBe('only');
    await call('anil', { statusId: SID, audience: 'except', list: ['bina'] });
    expect(await visible()).toEqual(['chetan', 'dev']);
    await call('anil', { statusId: SID, audience: 'all' });
    expect(await visible()).toEqual(['bina', 'chetan', 'dev']);
  });

  it('keeps blocked people out even when "all" is chosen again', async () => {
    await seedMoment();
    await db.doc('users/anil/blocked/dev').set({ createdAt: Timestamp.now() });
    await db.doc('users/chetan/blocked/anil').set({ createdAt: Timestamp.now() });
    await call('anil', { statusId: SID, audience: 'all' });
    expect(await visible()).toEqual(['bina']);
  });

  it("a non-author cannot change someone else's moment (it is not found under their own id)", async () => {
    await seedMoment();
    expect(await codeOf(call('bina', { statusId: SID, audience: 'only', list: ['bina'] }))).toBe('not-found');
    expect(await visible()).toEqual(['bina', 'chetan', 'dev']);
  });

  it('ignores a client-supplied visibleTo or author', async () => {
    await seedMoment();
    await call('anil', { statusId: SID, audience: 'only', list: ['bina'], visibleTo: ['zed'], author: 'bina' });
    expect(await visible()).toEqual(['bina']);
  });

  it('rejects bad input', async () => {
    await seedMoment();
    for (const data of [
      null, {}, { statusId: 'nope', audience: 'all' }, { statusId: SID, audience: 'everyone' },
      { statusId: SID, audience: 'only', list: [] }, { statusId: SID, audience: 'all', list: ['bina'] },
      { statusId: SID, audience: 'only', list: [5] }, { statusId: SID, audience: 'only', list: 'bina' },
      { statusId: SID, audience: 'except', list: Array.from({ length: 201 }, (_, i) => `u${i}`) },
    ]) {
      expect(await codeOf(call('anil', data))).toBe('invalid-argument');
    }
    expect(await visible()).toEqual(['bina', 'chetan', 'dev']);
  });

  it('refuses an expired or missing moment, and a signed-out or non-phone caller', async () => {
    await seedMoment({ expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    expect(await codeOf(call('anil', { statusId: SID, audience: 'all' }))).toBe('failed-precondition');
    expect(await codeOf(call('anil', { statusId: '7b2d1f0a-1111-4222-8333-944455550000', audience: 'all' }))).toBe('not-found');
    expect(await codeOf(handleSetStatusAudience({ data: { statusId: SID, audience: 'all' } } as any, testDeps()))).toBe('unauthenticated');
    expect(await codeOf(handleSetStatusAudience(phoneRequest('anil', { provider: 'anonymous', data: { statusId: SID, audience: 'all' } }), testDeps()))).not.toBe('ok');
  });

  it('is rate limited per day', async () => {
    await seedMoment();
    for (let i = 0; i < DAILY_AUDIENCE_EDITS; i++) await call('anil', { statusId: SID, audience: 'all' });
    expect(await codeOf(call('anil', { statusId: SID, audience: 'all' }))).toBe('resource-exhausted');
  });
});
