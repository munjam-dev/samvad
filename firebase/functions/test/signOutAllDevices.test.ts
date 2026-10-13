import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { signOutAllDevicesCore, handleSignOutAllDevices } from '../src/signOutAllDevices';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, resetEmulators, signInWithPhone } from '../../test-support/auth';

const deps = testDeps();

async function seed(uid: string, devices = 2) {
  await deps.auth.createUser({ uid });
  await deps.db.doc(`users/${uid}`).set({ phoneE164: '+919999900001', createdAt: Timestamp.now() });
  const writer = deps.db.bulkWriter();
  for (let i = 0; i < devices; i++) {
    writer.set(deps.db.doc(`users/${uid}/devices/${randomUUID()}`), { platform: 'android' });
  }
  await writer.close();
}

const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

beforeEach(resetEmulators);

describe('signOutAllDevicesCore', () => {
  it('stamps tokensRevokedAt, revokes refresh tokens and marks all devices revoked', async () => {
    await seed('alice');
    const before = (await deps.auth.getUser('alice')).tokensValidAfterTime;
    await new Promise((r) => setTimeout(r, 1100));
    await signOutAllDevicesCore(deps, 'alice');
    expect((await deps.db.doc('users/alice').get()).get('tokensRevokedAt')).toBeInstanceOf(Timestamp);
    expect((await deps.auth.getUser('alice')).tokensValidAfterTime).not.toBe(before);
    const devices = await deps.db.collection('users/alice/devices').get();
    expect(devices.docs.every((d) => d.get('revokedAt') instanceof Timestamp)).toBe(true);
  });

  it('a second call within 60 s fails with failed-precondition; 61 s later succeeds', async () => {
    await seed('alice', 1);
    await signOutAllDevicesCore(deps, 'alice');
    expect(await codeOf(signOutAllDevicesCore(deps, 'alice'))).toBe('failed-precondition');
    const later = testDeps({ nowMs: () => Date.now() + 61_000 });
    expect(await codeOf(signOutAllDevicesCore(later, 'alice'))).toBe('ok');
  });

  it('does not create users/{uid} when it does not exist, but still revokes tokens', async () => {
    await deps.auth.createUser({ uid: 'ghost' });
    await signOutAllDevicesCore(deps, 'ghost');
    expect((await deps.db.doc('users/ghost').get()).exists).toBe(false);
  });

  it('handles 1000 device documents', async () => {
    await seed('alice', 1000);
    await signOutAllDevicesCore(deps, 'alice');
    const devices = await deps.db.collection('users/alice/devices').get();
    expect(devices.size).toBe(1000);
    expect(devices.docs.every((d) => d.get('revokedAt') instanceof Timestamp)).toBe(true);
  }, 120_000);

  it("does not touch another user's devices", async () => {
    await seed('alice', 1);
    await seed('bob', 2);
    await signOutAllDevicesCore(deps, 'alice');
    const bob = await deps.db.collection('users/bob/devices').get();
    expect(bob.docs.some((d) => d.get('revokedAt') !== undefined)).toBe(false);
  });
});

describe('handleSignOutAllDevices', () => {
  it('rejects no auth and anonymous provider', async () => {
    expect(await codeOf(handleSignOutAllDevices({ data: {} } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handleSignOutAllDevices(phoneRequest('alice', { provider: 'anonymous' }), deps))).toBe(
      'permission-denied',
    );
  });

  it('ignores a uid supplied in request.data', async () => {
    await seed('alice', 1);
    await seed('bob', 1);
    await handleSignOutAllDevices(phoneRequest('alice', { data: { uid: 'bob' } }), deps);
    expect((await deps.db.doc('users/bob').get()).get('tokensRevokedAt')).toBeUndefined();
    expect((await deps.db.doc('users/alice').get()).get('tokensRevokedAt')).toBeInstanceOf(Timestamp);
  });
});

describe('signOutAllDevices over HTTP', () => {
  it('rejects an unauthenticated call', async () => {
    const res = await callCallable('signOutAllDevices', null);
    expect(res.body?.error?.status).toBe('UNAUTHENTICATED');
  });

  it('returns revoked:true for a phone-signed-in caller', async () => {
    const { idToken } = await signInWithPhone('+919999900031');
    const res = await callCallable('signOutAllDevices', idToken);
    expect(res.body).toEqual({ result: { revoked: true } });
  });
});
