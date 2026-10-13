import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { assertFails, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc } from 'firebase/firestore';
import { deleteAccountCore, handleDeleteAccount } from '../src/deleteAccount';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, listAuthUserIds, resetEmulators, signInWithPhone } from '../../test-support/auth';
import { createRulesEnv, phoneContext } from '../../test-support/rulesEnv';

const deps = testDeps();
let env: RulesTestEnvironment;

async function seedAccount(uid: string, devices = 2) {
  await deps.auth.createUser({ uid, phoneNumber: `+9199999${Math.floor(10000 + Math.random() * 89999)}` });
  await deps.db.doc(`users/${uid}`).set({ phoneE164: '+919999900001', createdAt: Timestamp.now() });
  await deps.db.doc(`profiles/${uid}`).set({ displayName: 'X', about: '', photoUrl: null, updatedAt: Timestamp.now() });
  for (let i = 0; i < devices; i++) {
    await deps.db.doc(`users/${uid}/devices/${randomUUID()}`).set({ platform: 'android' });
  }
}

async function accountGone(uid: string) {
  expect((await deps.db.doc(`users/${uid}`).get()).exists).toBe(false);
  expect((await deps.db.collection(`users/${uid}/devices`).get()).empty).toBe(true);
  expect((await deps.db.doc(`profiles/${uid}`).get()).exists).toBe(false);
  expect((await deps.db.doc(`deletedAccounts/${uid}`).get()).exists).toBe(true);
  expect(await listAuthUserIds()).not.toContain(uid);
}

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(resetEmulators);

describe('deleteAccountCore', () => {
  it('removes user doc, devices, profile and auth user, and writes the tombstone', async () => {
    await seedAccount('alice');
    await deleteAccountCore(deps, 'alice');
    await accountGone('alice');
  });

  it("leaves another user's documents and auth user untouched", async () => {
    await seedAccount('alice');
    await seedAccount('bob');
    await deleteAccountCore(deps, 'alice');
    expect((await deps.db.doc('users/bob').get()).exists).toBe(true);
    expect((await deps.db.doc('profiles/bob').get()).exists).toBe(true);
    expect((await deps.db.collection('users/bob/devices').get()).size).toBe(2);
    expect(await listAuthUserIds()).toContain('bob');
  });

  it('is idempotent when run twice', async () => {
    await seedAccount('alice');
    await deleteAccountCore(deps, 'alice');
    await deleteAccountCore(deps, 'alice');
    await accountGone('alice');
  });

  it('completes on retry after auth.deleteUser failed once', async () => {
    await seedAccount('alice');
    let failed = false;
    const flaky = testDeps({
      auth: new Proxy(deps.auth, {
        get(target, prop, recv) {
          if (prop === 'deleteUser' && !failed) {
            return async () => {
              failed = true;
              throw new Error('transient');
            };
          }
          const v = Reflect.get(target, prop, recv);
          return typeof v === 'function' ? v.bind(target) : v;
        },
      }),
    });
    await expect(deleteAccountCore(flaky, 'alice')).rejects.toThrow('transient');
    expect((await deps.db.doc('users/alice').get()).exists).toBe(false);
    expect(await listAuthUserIds()).toContain('alice');
    await deleteAccountCore(flaky, 'alice');
    await accountGone('alice');
  });

  it('succeeds when the auth user is already gone', async () => {
    await seedAccount('alice');
    await deps.auth.deleteUser('alice');
    await deleteAccountCore(deps, 'alice');
    await accountGone('alice');
  });
});

describe('handleDeleteAccount', () => {
  const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
  const now = () => Math.floor(Date.now() / 1000);

  it('rejects no auth with unauthenticated', async () => {
    expect(await codeOf(handleDeleteAccount({ data: {} } as any, deps))).toBe('unauthenticated');
  });

  it('rejects provider anonymous with permission-denied', async () => {
    expect(await codeOf(handleDeleteAccount(phoneRequest('alice', { provider: 'anonymous' }), deps))).toBe(
      'permission-denied',
    );
  });

  it('rejects auth_time 301 s old and accepts 299 s old', async () => {
    await seedAccount('alice');
    expect(await codeOf(handleDeleteAccount(phoneRequest('alice', { authTime: now() - 301 }), deps))).toBe(
      'failed-precondition',
    );
    expect(await handleDeleteAccount(phoneRequest('alice', { authTime: now() - 299 }), deps)).toEqual({
      deleted: true,
    });
  });

  it('ignores a uid supplied in request.data', async () => {
    await seedAccount('alice');
    await seedAccount('bob');
    await handleDeleteAccount(phoneRequest('alice', { data: { uid: 'bob' } }), deps);
    await accountGone('alice');
    expect((await deps.db.doc('users/bob').get()).exists).toBe(true);
  });
});

describe('deleteAccount over HTTP', () => {
  it('without an Authorization header returns unauthenticated', async () => {
    const res = await callCallable('deleteAccount', null);
    expect(res.body?.error?.status).toBe('UNAUTHENTICATED');
  });

  it('deletes the caller, succeeds again on retry, and the old token cannot re-create documents', async () => {
    const { uid, idToken } = await signInWithPhone('+919999900021');
    await deps.db.doc(`users/${uid}`).set({ phoneE164: '+919999900021', createdAt: Timestamp.now() });

    const first = await callCallable('deleteAccount', idToken);
    expect(first.body).toEqual({ result: { deleted: true } });
    const second = await callCallable('deleteAccount', idToken);
    expect(second.body).toEqual({ result: { deleted: true } });
    await accountGone(uid);

    const db = phoneContext(env, uid, '+919999900021').firestore();
    await assertFails(
      setDoc(doc(db, `users/${uid}`), {
        phoneE164: '+919999900021',
        consent: { version: 1, acceptedAt: serverTimestamp() },
        createdAt: serverTimestamp(),
      }),
    );
  });
});
