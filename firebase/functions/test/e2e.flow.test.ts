import { mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc } from 'firebase/firestore';
import { callCallable, listAuthUserIds, resetEmulators, signInWithPhone } from '../../test-support/auth';
import { testDeps } from '../../test-support/deps';
import { createRulesEnv, phoneContext } from '../../test-support/rulesEnv';

const PHONE = '+919999900042';
const deps = testDeps();
let env: RulesTestEnvironment;

beforeAll(async () => {
  await resetEmulators();
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});

describe('end-to-end account flow', () => {
  it('signs in, creates profile and device through rules, signs out everywhere, then deletes the account', async () => {
    const first = await signInWithPhone(PHONE);
    const uid = first.uid;
    const db = phoneContext(env, uid, PHONE).firestore();
    const deviceId = randomUUID();

    await assertSucceeds(
      setDoc(doc(db, `users/${uid}`), {
        phoneE164: PHONE,
        consent: { version: 1, acceptedAt: serverTimestamp() },
        createdAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(
      setDoc(doc(db, `profiles/${uid}`), { displayName: 'Asha', about: '', photoUrl: null, updatedAt: serverTimestamp() }),
    );
    await assertSucceeds(
      setDoc(doc(db, `users/${uid}/devices/${deviceId}`), {
        platform: 'android',
        appVersion: '1.0.0',
        osVersion: '15',
        createdAt: serverTimestamp(),
        lastSeenAt: serverTimestamp(),
        fcmToken: null,
      }),
    );

    expect((await callCallable('signOutAllDevices', first.idToken)).body).toEqual({ result: { revoked: true } });

    await new Promise((r) => setTimeout(r, 1100));
    const second = await signInWithPhone(PHONE);
    expect(second.uid).toBe(uid);
    expect((await callCallable('deleteAccount', second.idToken)).body).toEqual({ result: { deleted: true } });

    expect((await deps.db.doc(`users/${uid}`).get()).exists).toBe(false);
    expect((await deps.db.doc(`profiles/${uid}`).get()).exists).toBe(false);
    expect((await deps.db.collection(`users/${uid}/devices`).get()).empty).toBe(true);
    expect((await deps.db.doc(`deletedAccounts/${uid}`).get()).exists).toBe(true);
    expect(await listAuthUserIds()).not.toContain(uid);
    await assertFails(
      setDoc(doc(db, `profiles/${uid}`), { displayName: 'Asha', about: '', photoUrl: null, updatedAt: serverTimestamp() }),
    );

    mkdirSync('.tmp', { recursive: true });
    writeFileSync(
      '.tmp/e2e-secrets.json',
      JSON.stringify({ phone: PHONE, idTokens: [first.idToken, second.idToken, first.refreshToken] }),
    );
  });
});
