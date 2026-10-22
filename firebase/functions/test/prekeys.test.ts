import { randomBytes } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_FETCHES,
  DAILY_FETCHES_PER_PAIR,
  DAILY_PUBLISHES,
  MAX_ONE_TIME_STORED,
  handleDeletePreKeys,
  handleFetchPreKeyBundle,
  handlePreKeyStatus,
  handlePublishPreKeys,
  handleUpgradeConversation,
} from '../src/prekeys';
import { deleteAccountCore } from '../src/deleteAccount';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

// End-to-end encryption key directory. Lengths match what libsignal produces (checked in :core E2eeEngineTest).
const deps = testDeps();
const b64 = (n: number) => randomBytes(n).toString('base64');
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

let nextId = 1;
function upload(count = 5, over: Record<string, unknown> = {}) {
  return {
    registrationId: 1 + Math.floor(Math.random() * 16380),
    identityKey: b64(33),
    signedPreKey: { keyId: 1, publicKey: b64(33), signature: b64(64) },
    kyberPreKey: { keyId: 1, publicKey: b64(1569), signature: b64(64) },
    oneTimePreKeys: Array.from({ length: count }, () => ({ keyId: nextId++, publicKey: b64(33) })),
    ...over,
  };
}

const publish = (uid: string, data: unknown) => handlePublishPreKeys(phoneRequest(uid, { data }), deps);
const fetchBundle = (uid: string, target: unknown) => handleFetchPreKeyBundle(phoneRequest(uid, { data: { uid: target } }), deps);
const keysDoc = async (uid: string) => (await deps.db.doc(`e2eeKeys/${uid}`).get()).data();
const oneTimeIds = async (uid: string) => (await deps.db.collection(`e2eeKeys/${uid}/oneTime`).get()).docs.map((d) => d.id);

beforeEach(async () => {
  await resetEmulators();
  nextId = 1;
});

describe('publishPreKeys', () => {
  it('stores the public bundle and the one-time keys under the signed-in account', async () => {
    const u = upload(5);
    const res = await publish('alice', u);
    expect(res).toEqual({ oneTimeRemaining: 5, identityChanged: false });
    const doc = (await keysDoc('alice'))!;
    expect(doc).toMatchObject({ registrationId: u.registrationId, identityKey: u.identityKey, oneTimeCount: 5 });
    expect(doc.signedPreKey).toEqual(u.signedPreKey);
    expect(doc.kyberPreKey).toEqual(u.kyberPreKey);
    expect((await oneTimeIds('alice')).sort()).toEqual(u.oneTimePreKeys.map((k) => String(k.keyId)).sort());
  });

  it('ignores a uid in the request: keys are always stored for the caller', async () => {
    await publish('mallory', { ...upload(2), uid: 'victim' });
    expect(await keysDoc('victim')).toBeUndefined();
    expect(await keysDoc('mallory')).toBeDefined();
  });

  it('needs a signed-in phone account', async () => {
    expect(await codeOf(handlePublishPreKeys({ data: upload() } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handlePublishPreKeys(phoneRequest('alice', { provider: 'password', data: upload() }), deps))).toBe('permission-denied');
  });

  it('refuses malformed keys', async () => {
    const bad: unknown[] = [
      null,
      'keys',
      upload(1, { registrationId: 0 }),
      upload(1, { registrationId: 16381 }),
      upload(1, { registrationId: 1.5 }),
      upload(1, { identityKey: b64(32) }),
      upload(1, { identityKey: 'not base64!' }),
      upload(1, { signedPreKey: { keyId: 1, publicKey: b64(33), signature: b64(63) } }),
      upload(1, { signedPreKey: { keyId: 0, publicKey: b64(33), signature: b64(64) } }),
      upload(1, { kyberPreKey: { keyId: 1, publicKey: b64(33), signature: b64(64) } }),
      upload(1, { oneTimePreKeys: [{ keyId: 5, publicKey: b64(32) }] }),
      upload(1, { oneTimePreKeys: [{ keyId: 5, publicKey: b64(33) }, { keyId: 5, publicKey: b64(33) }] }),
      upload(101),
      upload(1, { oneTimePreKeys: 'many' }),
    ];
    for (const data of bad) expect(await codeOf(publish('alice', data))).toBe('invalid-argument');
    expect(await keysDoc('alice')).toBeUndefined();
  });

  it('tops up one-time keys for the same identity and caps what is stored', async () => {
    const first = upload(100);
    await publish('alice', first);
    const more = { ...first, oneTimePreKeys: Array.from({ length: 100 }, () => ({ keyId: nextId++, publicKey: b64(33) })) };
    expect((await publish('alice', more)).oneTimeRemaining).toBe(200);
    // Re-sending keys already on file adds nothing.
    expect((await publish('alice', more)).oneTimeRemaining).toBe(200);
    const overflow = { ...first, oneTimePreKeys: [{ keyId: nextId++, publicKey: b64(33) }] };
    expect(await codeOf(publish('alice', overflow))).toBe('resource-exhausted');
    expect((await oneTimeIds('alice')).length).toBe(MAX_ONE_TIME_STORED);
  });

  it('a new identity key replaces the bundle and every old one-time key', async () => {
    await publish('alice', upload(10));
    const res = await publish('alice', upload(3));
    expect(res).toEqual({ oneTimeRemaining: 3, identityChanged: true });
    expect((await oneTimeIds('alice')).length).toBe(3);
  });

  it('is rate limited per day', async () => {
    const u = upload(0);
    for (let i = 0; i < DAILY_PUBLISHES; i++) await publish('alice', u);
    expect(await codeOf(publish('alice', u))).toBe('resource-exhausted');
    expect(await codeOf(publish('bina', u))).toBe('ok');
  });
});

describe('fetchPreKeyBundle', () => {
  it('returns the public bundle with one one-time key, and removes that key from the server', async () => {
    const u = upload(3);
    await publish('bina', u);
    const got = await fetchBundle('alice', 'bina');
    expect(Object.keys(got).sort()).toEqual(['identityKey', 'kyberPreKey', 'oneTimePreKey', 'registrationId', 'signedPreKey']);
    expect(got.identityKey).toBe(u.identityKey);
    expect(got.registrationId).toBe(u.registrationId);
    expect(got.signedPreKey).toEqual(u.signedPreKey);
    expect(got.kyberPreKey).toEqual(u.kyberPreKey);
    expect(u.oneTimePreKeys).toContainEqual(got.oneTimePreKey);
    expect(await oneTimeIds('bina')).not.toContain(String(got.oneTimePreKey!.keyId));
    expect((await keysDoc('bina'))!.oneTimeCount).toBe(2);
  });

  it('never hands the same one-time key to two callers, even at the same moment', async () => {
    await publish('bina', upload(3));
    const callers = ['c1', 'c2', 'c3', 'c4', 'c5'];
    const results = await Promise.all(callers.map((c) => fetchBundle(c, 'bina')));
    const ids = results.map((r) => r.oneTimePreKey?.keyId).filter((x): x is number => x !== undefined && x !== null);
    expect(ids.length).toBe(3);
    expect(new Set(ids).size).toBe(3);
    expect(await oneTimeIds('bina')).toEqual([]);
    expect((await keysDoc('bina'))!.oneTimeCount).toBe(0);
  });

  it('still returns a usable bundle when the one-time keys are used up', async () => {
    await publish('bina', upload(1));
    expect((await fetchBundle('alice', 'bina')).oneTimePreKey).not.toBeNull();
    const second = await fetchBundle('carol', 'bina');
    expect(second.oneTimePreKey).toBeNull();
    expect(second.signedPreKey.publicKey.length).toBeGreaterThan(0);
  });

  it('answers not-found for missing bundles, deleted accounts and blocked pairs (both directions), alike', async () => {
    expect(await codeOf(fetchBundle('alice', 'nobody'))).toBe('not-found');

    await publish('bina', upload(2));
    await deps.db.doc('users/alice/blocked/bina').set({ createdAt: Timestamp.now() });
    expect(await codeOf(fetchBundle('alice', 'bina'))).toBe('not-found');
    await deps.db.doc('users/alice/blocked/bina').delete();
    await deps.db.doc('users/bina/blocked/alice').set({ createdAt: Timestamp.now() });
    expect(await codeOf(fetchBundle('alice', 'bina'))).toBe('not-found');
    await deps.db.doc('users/bina/blocked/alice').delete();

    await deps.db.doc('deletedAccounts/bina').set({ deletedAt: Timestamp.now() });
    expect(await codeOf(fetchBundle('alice', 'bina'))).toBe('not-found');
    // None of the refused attempts used up a one-time key.
    expect((await oneTimeIds('bina')).length).toBe(2);
  });

  it('refuses bad input, self-fetch and callers who are not signed in', async () => {
    expect(await codeOf(fetchBundle('alice', 'alice'))).toBe('invalid-argument');
    expect(await codeOf(fetchBundle('alice', '../x'))).toBe('invalid-argument');
    expect(await codeOf(fetchBundle('alice', 42))).toBe('invalid-argument');
    expect(await codeOf(handleFetchPreKeyBundle({ data: { uid: 'bina' } } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handleFetchPreKeyBundle(phoneRequest('alice', { provider: 'anonymous', data: { uid: 'bina' } }), deps))).toBe('permission-denied');
  });

  it('limits how many bundles one person can take of the same other person, so keys cannot be drained', async () => {
    await publish('bina', upload(20));
    for (let i = 0; i < DAILY_FETCHES_PER_PAIR; i++) expect(await codeOf(fetchBundle('mallory', 'bina'))).toBe('ok');
    expect(await codeOf(fetchBundle('mallory', 'bina'))).toBe('resource-exhausted');
    expect((await oneTimeIds('bina')).length).toBe(20 - DAILY_FETCHES_PER_PAIR);
    // Someone else is not affected.
    expect(await codeOf(fetchBundle('alice', 'bina'))).toBe('ok');
  });

  it('has a daily budget per caller', async () => {
    await publish('bina', upload(0));
    await deps.db.doc('rateLimits/alice').set({ prekeyFetchWindowStart: Timestamp.fromMillis(deps.nowMs()), prekeyFetchCount: DAILY_FETCHES });
    expect(await codeOf(fetchBundle('alice', 'bina'))).toBe('resource-exhausted');
    expect(await codeOf(fetchBundle('carol', 'bina'))).toBe('ok');
  });

  it('never returns anything that is not public: only the documented fields exist on the stored bundle', async () => {
    await publish('bina', { ...upload(1), privateKey: b64(32), secret: 'x' });
    const stored = (await keysDoc('bina'))!;
    expect(Object.keys(stored).sort()).toEqual(['identityKey', 'identityUpdatedAt', 'kyberPreKey', 'oneTimeCount', 'registrationId', 'signedPreKey', 'updatedAt']);
  });
});

describe('preKeyStatus', () => {
  it('reports what the server holds for the caller only', async () => {
    const empty = await handlePreKeyStatus(phoneRequest('alice'), deps);
    expect(empty).toEqual({ published: false, oneTimeRemaining: 0, identityKey: null, registrationId: null });
    const u = upload(4);
    await publish('alice', u);
    expect(await handlePreKeyStatus(phoneRequest('alice'), deps)).toEqual({
      published: true, oneTimeRemaining: 4, identityKey: u.identityKey, registrationId: u.registrationId,
    });
  });
});

describe('upgradeConversationE2ee', () => {
  const CID = 'alice_bina';
  const upgrade = (uid: string, cid: unknown = CID) => handleUpgradeConversation(phoneRequest(uid, { data: { conversationId: cid } }), deps);
  const seed = async () => {
    await deps.db.doc(`conversations/${CID}`).set({
      type: 'direct', members: ['alice', 'bina'], createdBy: 'alice',
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
  };

  it('turns on encryption for a chat when both people have keys, and records since when', async () => {
    await seed();
    await publish('alice', upload(1));
    await publish('bina', upload(1));
    expect(await upgrade('bina')).toEqual({ e2ee: true });
    const conv = (await deps.db.doc(`conversations/${CID}`).get()).data()!;
    expect(conv.e2ee).toBe(true);
    expect(conv.e2eeSince.toMillis()).toBeGreaterThan(0);
    expect(await codeOf(upgrade('alice'))).toBe('ok'); // already on: no change
  });

  it('is refused when the other person has no keys', async () => {
    await seed();
    await publish('alice', upload(1));
    expect(await codeOf(upgrade('alice'))).toBe('failed-precondition');
    expect((await deps.db.doc(`conversations/${CID}`).get()).data()!.e2ee).toBeUndefined();
  });

  it('is refused for outsiders, unknown chats, groups and blocked pairs', async () => {
    await seed();
    await publish('alice', upload(1));
    await publish('bina', upload(1));
    expect(await codeOf(upgrade('carol'))).toBe('permission-denied');
    expect(await codeOf(upgrade('alice', 'alice_zed'))).toBe('permission-denied');
    expect(await codeOf(upgrade('alice', '../x'))).toBe('invalid-argument');
    await deps.db.doc('users/bina/blocked/alice').set({ createdAt: Timestamp.now() });
    expect(await codeOf(upgrade('alice'))).toBe('permission-denied');
    await deps.db.doc('conversations/GroupGroupGroup_Other').set({ type: 'group', members: ['alice', 'bina'], createdAt: Timestamp.now() });
    expect(await codeOf(upgrade('alice', 'GroupGroupGroup_Other'))).toBe('permission-denied');
  });
});

describe('deletePreKeys (sign-out)', () => {
  it('removes only the callers own bundle, so nobody can start a session with a signed-out account', async () => {
    await publish('alice', upload(3));
    await publish('bina', upload(3));
    await handleDeletePreKeys(phoneRequest('alice'), deps);
    expect(await keysDoc('alice')).toBeUndefined();
    expect(await oneTimeIds('alice')).toEqual([]);
    expect(await keysDoc('bina')).toBeDefined();
    expect(await codeOf(fetchBundle('bina', 'alice'))).toBe('not-found');
    expect(await codeOf(handleDeletePreKeys({ data: {} } as any, deps))).toBe('unauthenticated');
  });
});

describe('account deletion', () => {
  it('removes the public key bundle and its one-time keys', async () => {
    await deps.auth.createUser({ uid: 'alice' });
    await publish('alice', upload(4));
    await deleteAccountCore(deps, 'alice');
    expect(await keysDoc('alice')).toBeUndefined();
    expect(await oneTimeIds('alice')).toEqual([]);
    expect(await codeOf(fetchBundle('bina', 'alice'))).toBe('not-found');
  });
});
