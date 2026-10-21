import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { DAILY_REVOKES, handleRevokeDevice, revokeDeviceCore } from '../src/devices';
import { fakePush, testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, resetEmulators, signInWithPhone } from '../../test-support/auth';

const push = fakePush();
const deps = testDeps({ push: push.sender });
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const call = (uid: string, data: unknown) => handleRevokeDevice(phoneRequest(uid, { data }), deps);

async function device(uid: string, fcmToken: string | null = 'token-' + randomUUID()) {
  const id = randomUUID();
  await deps.db.doc(`users/${uid}/devices/${id}`).set({
    platform: 'android', appVersion: '1.0', osVersion: '14', createdAt: Timestamp.now(), lastSeenAt: Timestamp.now(), fcmToken,
  });
  return id;
}
const exists = async (path: string) => (await deps.db.doc(path).get()).exists;

beforeEach(async () => {
  await resetEmulators();
  push.sent.length = 0;
});

describe('revokeDevice', () => {
  it('deletes the device document, writes a server-only marker and sends a data message to that device', async () => {
    const keep = await device('alice');
    const gone = await device('alice', 'tok-gone');
    const res = await call('alice', { deviceId: gone });
    expect(res).toEqual({ revoked: true, notified: true });
    expect(await exists(`users/alice/devices/${gone}`)).toBe(false);
    expect(await exists(`users/alice/devices/${keep}`)).toBe(true);
    const marker = await deps.db.doc(`users/alice/revokedDevices/${gone}`).get();
    expect(marker.get('revokedAt')).toBeInstanceOf(Timestamp);
    expect(push.sent).toEqual([{ tokens: ['tok-gone'], data: { type: 'device_revoked', deviceId: gone } }]);
  });

  it('works for a device without a push token (it signs out on next start)', async () => {
    const id = await device('alice', null);
    expect(await call('alice', { deviceId: id })).toEqual({ revoked: true, notified: false });
    expect(push.sent).toEqual([]);
    expect(await exists(`users/alice/revokedDevices/${id}`)).toBe(true);
  });

  it('still revokes when the push fails', async () => {
    const failing = testDeps({ push: { send: async () => { throw new Error('fcm down'); } } });
    const id = await device('alice');
    const res = await handleRevokeDevice(phoneRequest('alice', { data: { deviceId: id } }), failing);
    expect(res).toEqual({ revoked: true, notified: false });
    expect(await exists(`users/alice/revokedDevices/${id}`)).toBe(true);
  });

  it('is repeatable: a second call for the same device succeeds without a second push', async () => {
    const id = await device('alice', 'tok');
    await call('alice', { deviceId: id });
    push.sent.length = 0;
    expect(await codeOf(call('alice', { deviceId: id }))).toBe('ok');
    expect(push.sent).toEqual([]);
  });

  it('cannot touch another account: a device id from someone else is unknown, and a spoofed uid is ignored', async () => {
    const bobs = await device('bob');
    expect(await codeOf(call('alice', { deviceId: bobs }))).toBe('not-found');
    expect(await codeOf(call('alice', { deviceId: bobs, uid: 'bob' }))).toBe('not-found');
    expect(await exists(`users/bob/devices/${bobs}`)).toBe(true);
    expect(await exists(`users/bob/revokedDevices/${bobs}`)).toBe(false);
    expect(await exists(`users/alice/revokedDevices/${bobs}`)).toBe(false);
    expect(push.sent).toEqual([]);
  });

  it('rejects an unknown device and malformed ids', async () => {
    expect(await codeOf(call('alice', { deviceId: randomUUID() }))).toBe('not-found');
    for (const data of [{}, { deviceId: 5 }, { deviceId: '../users/bob' }, { deviceId: 'ABC' }, { deviceId: randomUUID().toUpperCase() }]) {
      expect(await codeOf(call('alice', data))).toBe('invalid-argument');
    }
  });

  it('requires a phone sign-in', async () => {
    const id = await device('alice');
    expect(await codeOf(handleRevokeDevice({ data: { deviceId: id } } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handleRevokeDevice(phoneRequest('alice', { provider: 'anonymous', data: { deviceId: id } }), deps))).toBe('permission-denied');
    expect(await exists(`users/alice/devices/${id}`)).toBe(true);
  });

  it('limits sign-outs per day', async () => {
    await deps.db.doc('rateLimits/alice').set({ deviceRevokesWindowStart: Timestamp.now(), deviceRevokesCount: DAILY_REVOKES });
    const id = await device('alice');
    expect(await codeOf(call('alice', { deviceId: id }))).toBe('resource-exhausted');
    expect(await exists(`users/alice/devices/${id}`)).toBe(true);
    // The count is per person.
    const other = await device('bob');
    expect(await codeOf(call('bob', { deviceId: other }))).toBe('ok');
  });

  it('revokeDeviceCore leaves the other devices and their tokens alone', async () => {
    const a = await device('alice', 'tok-a');
    await device('alice', 'tok-b');
    await revokeDeviceCore(deps, 'alice', a);
    expect(push.sent.map((s) => s.tokens)).toEqual([['tok-a']]);
  });
});

describe('revokeDevice over HTTP', () => {
  it('rejects an unauthenticated call', async () => {
    const res = await callCallable('revokeDevice', null, { deviceId: randomUUID() });
    expect(res.body?.error?.status).toBe('UNAUTHENTICATED');
  });

  it('removes a device of the signed-in caller', async () => {
    const { uid, idToken } = await signInWithPhone('+919999900071');
    const id = await device(uid, null);
    const res = await callCallable('revokeDevice', idToken, { deviceId: id });
    expect(res.body).toEqual({ result: { revoked: true, notified: false } });
    expect(await exists(`users/${uid}/revokedDevices/${id}`)).toBe(true);
  });
});
