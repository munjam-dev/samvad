import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  checkUsernameCore, DAILY_CHANGES, handleCheckUsername, handleSetUsername, normalizeUsername, setUsernameCore, usernameProblem,
} from '../src/username';
import { deleteAccountCore } from '../src/deleteAccount';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.message ?? e.code);
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const profile = async (uid: string) => (await deps.db.doc(`profiles/${uid}`).get()).data()!;
const owner = async (name: string) => (await deps.db.doc(`usernames/${name}`).get()).get('uid');
const set = (uid: string, username: unknown) => handleSetUsername(phoneRequest(uid, { data: { username } }), deps);
const check = (uid: string, username: unknown) => handleCheckUsername(phoneRequest(uid, { data: { username } }), deps);

async function makeProfile(uid: string) {
  await deps.db.doc(`profiles/${uid}`).set({ displayName: uid, about: '', photoUrl: null, updatedAt: Timestamp.now() });
}

beforeEach(async () => {
  await resetEmulators();
  for (const u of ['asha', 'ravi', 'meena']) await makeProfile(u);
});

describe('username format', () => {
  it('accepts normal names', () => {
    for (const n of ['asha', 'asha_k', 'a1b', 'ravi_kumar_92', 'abcdefghijklmnopqrst']) expect(usernameProblem(n)).toBeNull();
  });

  it('rejects too short, too long, bad start, bad characters, underscores and reserved words', () => {
    expect(usernameProblem('ab')).toBe('too-short');
    expect(usernameProblem('a'.repeat(21))).toBe('too-long');
    expect(usernameProblem('1asha')).toBe('bad-start');
    expect(usernameProblem('_asha')).toBe('bad-start');
    expect(usernameProblem('asha-k')).toBe('bad-chars');
    expect(usernameProblem('asha.k')).toBe('bad-chars');
    expect(usernameProblem('asha k')).toBe('bad-chars');
    expect(usernameProblem('ashá')).toBe('bad-chars');
    expect(usernameProblem('ásha')).toBe('bad-start');
    expect(usernameProblem('asha__k')).toBe('underscores');
    expect(usernameProblem('asha_')).toBe('underscores');
    expect(usernameProblem('admin')).toBe('reserved');
    expect(usernameProblem('samvad')).toBe('reserved');
  });

  it('is not case sensitive, and ignores a leading @ and spaces', () => {
    expect(normalizeUsername('  @Asha_K ')).toBe('asha_k');
    expect(normalizeUsername(42)).toBe('');
    expect(normalizeUsername(null)).toBe('');
  });
});

describe('setUsername', () => {
  it('claims a name: the reservation and the profile agree', async () => {
    expect(await set('asha', 'Asha_K')).toEqual({ username: 'asha_k' });
    expect((await profile('asha')).username).toBe('asha_k');
    expect(await owner('asha_k')).toBe('asha');
  });

  it('refuses a name someone else has, whatever its letter case', async () => {
    await set('asha', 'asha_k');
    expect(await errorOf(set('ravi', 'ASHA_K'))).toBe('username-taken');
    expect((await profile('ravi')).username).toBeUndefined();
    expect(await owner('asha_k')).toBe('asha');
  });

  it('is fine when you ask for the name you already have', async () => {
    await set('asha', 'asha_k');
    expect(await set('asha', '@Asha_K')).toEqual({ username: 'asha_k' });
    expect(await owner('asha_k')).toBe('asha');
  });

  it('changing the name frees the old one for others', async () => {
    await set('asha', 'asha_k');
    await set('asha', 'asha_new');
    expect((await deps.db.doc('usernames/asha_k').get()).exists).toBe(false);
    expect(await owner('asha_new')).toBe('asha');
    expect(await set('ravi', 'asha_k')).toEqual({ username: 'asha_k' });
  });

  it('an empty value removes the name and frees it', async () => {
    await set('asha', 'asha_k');
    expect(await set('asha', '')).toEqual({ username: null });
    expect((await profile('asha')).username).toBeUndefined();
    expect((await deps.db.doc('usernames/asha_k').get()).exists).toBe(false);
  });

  it('removing a name you never had is harmless', async () => {
    expect(await set('asha', null)).toEqual({ username: null });
  });

  it('rejects invalid names with the reason', async () => {
    expect(await errorOf(set('asha', 'ab'))).toBe('too-short');
    expect(await errorOf(set('asha', 'admin'))).toBe('reserved');
    expect(await errorOf(set('asha', 'no spaces'))).toBe('bad-chars');
    expect(await codeOf(set('asha', 'ab'))).toBe('invalid-argument');
  });

  it('needs a profile first', async () => {
    expect(await errorOf(set('nobody', 'someone'))).toBe('profile-required');
  });

  it('two people asking for the same name at the same moment: exactly one gets it', async () => {
    const results = await Promise.allSettled([set('asha', 'popular'), set('ravi', 'popular'), set('meena', 'popular')]);
    const winners = results.filter((r) => r.status === 'fulfilled');
    expect(winners).toHaveLength(1);
    const losers = results.filter((r) => r.status === 'rejected') as PromiseRejectedResult[];
    expect(losers.map((l) => l.reason.message)).toEqual(['username-taken', 'username-taken']);
    const holder = await owner('popular');
    expect((await profile(holder)).username).toBe('popular');
    for (const u of ['asha', 'ravi', 'meena'].filter((x) => x !== holder)) expect((await profile(u)).username).toBeUndefined();
  });

  it('is rate limited per day', async () => {
    for (let i = 0; i < DAILY_CHANGES; i++) await set('asha', `name_${String.fromCharCode(97 + i)}x`);
    expect(await codeOf(set('asha', 'one_more'))).toBe('resource-exhausted');
  });

  it('a client cannot reach the reservation through the directly exported core with a bad uid shape', async () => {
    expect(await codeOf(setUsernameCore(deps, 'asha', { not: 'a string' }))).toBe('ok');
    expect((await profile('asha')).username).toBeUndefined();
  });
});

describe('checkUsername', () => {
  it('says available, taken, or why it is invalid', async () => {
    await set('asha', 'asha_k');
    expect(await check('ravi', 'ravi_k')).toEqual({ available: true });
    expect(await check('ravi', 'asha_k')).toEqual({ available: false, reason: 'taken' });
    expect(await check('ravi', 'ab')).toEqual({ available: false, reason: 'too-short' });
    expect(await check('ravi', 'admin')).toEqual({ available: false, reason: 'reserved' });
  });

  it('a name you own is available to you', async () => {
    await set('asha', 'asha_k');
    expect(await check('asha', 'asha_k')).toEqual({ available: true });
  });

  it('is rate limited, so it cannot be used to harvest names', async () => {
    let last = 'ok';
    for (let i = 0; i < 125; i++) last = await codeOf(checkUsernameCore(deps, 'meena', 'anything'));
    expect(last).toBe('resource-exhausted');
  });
});

describe('account deletion', () => {
  it('frees the username', async () => {
    await set('asha', 'asha_k');
    await deleteAccountCore(deps, 'asha');
    expect((await deps.db.doc('usernames/asha_k').get()).exists).toBe(false);
    expect(await set('ravi', 'asha_k')).toEqual({ username: 'asha_k' });
  });

  it('does not free a name that now belongs to someone else', async () => {
    await set('asha', 'asha_k');
    await deps.db.doc('usernames/asha_k').set({ uid: 'ravi', createdAt: Timestamp.now() });
    await deleteAccountCore(deps, 'asha');
    expect(await owner('asha_k')).toBe('ravi');
  });
});
