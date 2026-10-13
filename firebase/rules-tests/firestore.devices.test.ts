import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  updateDoc,
  Timestamp,
} from 'firebase/firestore';
import { createRulesEnv, otherProviderContext, phoneContext } from '../test-support/rulesEnv';

let env: RulesTestEnvironment;

const validDevice = (over: Record<string, unknown> = {}) => ({
  platform: 'android',
  appVersion: '1.0.0',
  osVersion: '15',
  createdAt: serverTimestamp(),
  lastSeenAt: serverTimestamp(),
  ...over,
});

async function seedDevice(uid: string, id: string, extra: Record<string, unknown> = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${uid}/devices/${id}`), {
      platform: 'android',
      appVersion: '1.0.0',
      osVersion: '15',
      createdAt: Timestamp.now(),
      lastSeenAt: Timestamp.now(),
      fcmToken: null,
      ...extra,
    });
  });
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
});
afterAll(async () => {
  await env.cleanup();
});

describe('users/{uid}/devices/{deviceId}', () => {
  it('owner can create, update, get, list and delete own devices', async () => {
    const id = randomUUID();
    const db = phoneContext(env, 'alice').firestore();
    const ref = doc(db, `users/alice/devices/${id}`);
    await assertSucceeds(setDoc(ref, validDevice({ fcmToken: null })));
    await assertSucceeds(updateDoc(ref, { appVersion: '1.0.1', lastSeenAt: serverTimestamp(), fcmToken: 'tok' }));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(getDocs(collection(db, 'users/alice/devices')));
    await assertSucceeds(deleteDoc(ref));
  });

  it('another user cannot read, list, create, update or delete users/A/devices/*', async () => {
    const id = randomUUID();
    await seedDevice('alice', id);
    const db = phoneContext(env, 'bob').firestore();
    const ref = doc(db, `users/alice/devices/${id}`);
    await assertFails(getDoc(ref));
    await assertFails(getDocs(collection(db, 'users/alice/devices')));
    await assertFails(setDoc(doc(db, `users/alice/devices/${randomUUID()}`), validDevice()));
    await assertFails(updateDoc(ref, { lastSeenAt: serverTimestamp() }));
    await assertFails(deleteDoc(ref));
  });

  it('unauthenticated and other-provider contexts are denied', async () => {
    const id = randomUUID();
    await seedDevice('alice', id);
    for (const ctx of [env.unauthenticatedContext(), otherProviderContext(env, 'alice', 'anonymous')]) {
      const db = ctx.firestore();
      await assertFails(getDoc(doc(db, `users/alice/devices/${id}`)));
      await assertFails(setDoc(doc(db, `users/alice/devices/${randomUUID()}`), validDevice()));
    }
  });

  it('rejects deviceId that is not a lowercase UUID', async () => {
    const db = phoneContext(env, 'alice').firestore();
    for (const bad of [randomUUID().toUpperCase(), 'device-1', 'android-id-abcdef']) {
      await assertFails(setDoc(doc(db, `users/alice/devices/${bad}`), validDevice()));
    }
  });

  it('rejects bad platform, oversized versions and unknown fields', async () => {
    const db = phoneContext(env, 'alice').firestore();
    const ref = () => doc(db, `users/alice/devices/${randomUUID()}`);
    await assertFails(setDoc(ref(), validDevice({ platform: 'ios' })));
    await assertFails(setDoc(ref(), validDevice({ appVersion: 'x'.repeat(33) })));
    await assertFails(setDoc(ref(), validDevice({ osVersion: 'x'.repeat(33) })));
    await assertFails(setDoc(ref(), validDevice({ imei: '123' })));
  });

  it('rejects client-set revokedAt on create and on update', async () => {
    const db = phoneContext(env, 'alice').firestore();
    await assertFails(
      setDoc(doc(db, `users/alice/devices/${randomUUID()}`), validDevice({ revokedAt: serverTimestamp() })),
    );
    const id = randomUUID();
    await seedDevice('alice', id, { revokedAt: Timestamp.now() });
    const ref = doc(db, `users/alice/devices/${id}`);
    await assertFails(updateDoc(ref, { revokedAt: null, lastSeenAt: serverTimestamp() }));
    await assertFails(updateDoc(ref, { revokedAt: serverTimestamp(), lastSeenAt: serverTimestamp() }));
  });

  it('update may change only appVersion, osVersion, lastSeenAt, fcmToken with lastSeenAt == request.time', async () => {
    const id = randomUUID();
    await seedDevice('alice', id);
    const ref = doc(phoneContext(env, 'alice').firestore(), `users/alice/devices/${id}`);
    await assertSucceeds(updateDoc(ref, { osVersion: '16', lastSeenAt: serverTimestamp() }));
    await assertFails(updateDoc(ref, { osVersion: '16', lastSeenAt: Timestamp.now() }));
    await assertFails(updateDoc(ref, { platform: 'android', createdAt: serverTimestamp(), lastSeenAt: serverTimestamp() }));
  });

  it('create is denied once deletedAccounts/{uid} exists', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'deletedAccounts/alice'), { deletedAt: Timestamp.now() });
    });
    const db = phoneContext(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, `users/alice/devices/${randomUUID()}`), validDevice()));
  });
});
