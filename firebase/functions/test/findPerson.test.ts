import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { handleFindPerson, DAILY_FIND_LIMIT } from '../src/findPerson';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const find = (data: unknown, uid = 'alice') => handleFindPerson(phoneRequest(uid, { data }), deps);

async function seed(uid: string, phoneE164: string, name: string, username?: string) {
  await deps.db.doc(`users/${uid}`).set({ phoneE164, createdAt: Timestamp.now() });
  await deps.db.doc(`profiles/${uid}`).set({ displayName: name, ...(username ? { username } : {}) });
  if (username) await deps.db.doc(`usernames/${username}`).set({ uid });
  await deps.db.doc(`profiles/${uid}/private/photo`).set({ photoUrl: `profilePhotos/${uid}/p.jpg` });
}

beforeEach(resetEmulators);

describe('findPerson', () => {
  it('requires a phone sign-in and exactly one of phone or username', async () => {
    expect(await codeOf(handleFindPerson({ data: { username: 'bob' } } as any, deps))).toBe('unauthenticated');
    for (const data of [{}, { phoneE164: '+919999900002', username: 'bob' }, { phoneE164: '98765' }, { username: 'a' }]) {
      expect(await codeOf(find(data))).toBe('invalid-argument');
    }
  });

  it('finds a registered number, with name and photo', async () => {
    await seed('bob', '+919999900002', 'Bob');
    expect(await find({ phoneE164: '+919999900002' })).toEqual({
      found: true,
      uid: 'bob',
      displayName: 'Bob',
      photoPath: 'profilePhotos/bob/p.jpg',
    });
    expect(await find({ phoneE164: '+919999900077' })).toEqual({ found: false });
  });

  it('finds an exact username (case and @ ignored) but never a partial one', async () => {
    await seed('bob', '+919999900002', 'Bob', 'bobby');
    expect(await find({ username: '@Bobby' })).toMatchObject({ found: true, uid: 'bob' });
    expect(await find({ username: 'bob' })).toEqual({ found: false });
  });

  it('does not find the caller, or a number whose owner turned off phone discovery', async () => {
    await seed('alice', '+919999900001', 'Alice');
    await seed('bob', '+919999900002', 'Bob', 'bobby');
    await deps.db.doc('users/bob/settings/privacy').set({ phoneDiscovery: false });
    expect(await find({ phoneE164: '+919999900001' })).toEqual({ found: false });
    expect(await find({ phoneE164: '+919999900002' })).toEqual({ found: false });
    expect(await find({ username: 'bobby' })).toMatchObject({ found: true, uid: 'bob' });
  });

  it('hides the photo according to the owner setting', async () => {
    await seed('bob', '+919999900002', 'Bob');
    await deps.db.doc('users/bob/settings/privacy').set({ photoVisibility: 'nobody' });
    expect(await find({ phoneE164: '+919999900002' })).toMatchObject({ found: true, photoPath: null });
    await deps.db.doc('users/bob/settings/privacy').set({ photoVisibility: 'partners' });
    expect(await find({ phoneE164: '+919999900002' })).toMatchObject({ photoPath: null });
    await deps.db.doc('conversations/alice_bob').set({ engagedBy: ['bob'] });
    expect(await find({ phoneE164: '+919999900002' })).toMatchObject({ photoPath: 'profilePhotos/bob/p.jpg' });
  });

  it('is rate limited', async () => {
    await deps.db.doc('rateLimits/alice').set({ findPersonWindowStart: Timestamp.now(), findPersonCount: DAILY_FIND_LIMIT });
    expect(await codeOf(find({ phoneE164: '+919999900002' }))).toBe('resource-exhausted');
  });
});
