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
const PHONE = '+919999900001';

const validUser = () => ({
  phoneE164: PHONE,
  consent: { version: 1, acceptedAt: serverTimestamp() },
  createdAt: serverTimestamp(),
});

async function seedUser(uid: string) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `users/${uid}`), {
      phoneE164: PHONE,
      consent: { version: 1, acceptedAt: Timestamp.now() },
      createdAt: Timestamp.now(),
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

describe('users/{uid}', () => {
  it('owner can create own user doc', async () => {
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertSucceeds(setDoc(doc(db, 'users/alice'), validUser()));
  });

  it('owner can get own user doc; list is denied', async () => {
    await seedUser('alice');
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertSucceeds(getDoc(doc(db, 'users/alice')));
    await assertFails(getDocs(collection(db, 'users')));
  });

  it('another user cannot get, create or update users/B', async () => {
    await seedUser('bob');
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(getDoc(doc(db, 'users/bob')));
    await assertFails(setDoc(doc(db, 'users/carol'), validUser()));
    await assertFails(
      updateDoc(doc(db, 'users/bob'), { consent: { version: 2, acceptedAt: serverTimestamp() } }),
    );
  });

  it('unauthenticated, anonymous and other-provider contexts are denied everywhere under users', async () => {
    await seedUser('alice');
    const ctxs = [
      env.unauthenticatedContext(),
      otherProviderContext(env, 'alice', 'anonymous'),
      otherProviderContext(env, 'alice', 'password'),
      otherProviderContext(env, 'alice', 'custom'),
    ];
    for (const ctx of ctxs) {
      const db = ctx.firestore();
      await assertFails(getDoc(doc(db, 'users/alice')));
      await assertFails(setDoc(doc(db, 'users/alice'), validUser()));
    }
  });

  it("create rejects phoneE164 different from the token's phone_number", async () => {
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(setDoc(doc(db, 'users/alice'), { ...validUser(), phoneE164: '+919999900002' }));
  });

  it('create rejects a malformed phoneE164', async () => {
    for (const bad of ['12345', '+0123456789']) {
      const db = phoneContext(env, 'alice', bad).firestore();
      await assertFails(setDoc(doc(db, 'users/alice'), { ...validUser(), phoneE164: bad }));
    }
  });

  it('create rejects unknown fields, wrong types, missing fields', async () => {
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(setDoc(doc(db, 'users/alice'), { ...validUser(), extra: true }));
    await assertFails(
      setDoc(doc(db, 'users/alice'), { ...validUser(), consent: { version: '1', acceptedAt: serverTimestamp() } }),
    );
    const { createdAt: _omit, ...missing } = validUser();
    await assertFails(setDoc(doc(db, 'users/alice'), missing));
  });

  it('create rejects client-set tokensRevokedAt', async () => {
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(setDoc(doc(db, 'users/alice'), { ...validUser(), tokensRevokedAt: serverTimestamp() }));
  });

  it('update may only raise consent.version, with acceptedAt == request.time', async () => {
    await seedUser('alice');
    const db = phoneContext(env, 'alice', PHONE).firestore();
    const ref = doc(db, 'users/alice');
    await assertFails(updateDoc(ref, { consent: { version: 1, acceptedAt: serverTimestamp() } }));
    await assertFails(updateDoc(ref, { consent: { version: 0, acceptedAt: serverTimestamp() } }));
    await assertFails(updateDoc(ref, { consent: { version: 2, acceptedAt: Timestamp.now() } }));
    await assertSucceeds(updateDoc(ref, { consent: { version: 2, acceptedAt: serverTimestamp() } }));
  });

  it('update rejects changes to phoneE164 or createdAt', async () => {
    await seedUser('alice');
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(updateDoc(doc(db, 'users/alice'), { phoneE164: '+919999900003' }));
    await assertFails(updateDoc(doc(db, 'users/alice'), { createdAt: serverTimestamp() }));
  });

  it('client delete is denied', async () => {
    await seedUser('alice');
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(deleteDoc(doc(db, 'users/alice')));
  });

  it('client cannot read or write deletedAccounts/{uid}', async () => {
    const db = phoneContext(env, 'alice', PHONE).firestore();
    for (const uid of ['alice', 'bob']) {
      await assertFails(getDoc(doc(db, `deletedAccounts/${uid}`)));
      await assertFails(setDoc(doc(db, `deletedAccounts/${uid}`), { deletedAt: serverTimestamp() }));
    }
  });

  it('create is denied once deletedAccounts/{uid} exists', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'deletedAccounts/alice'), { deletedAt: Timestamp.now() });
    });
    const db = phoneContext(env, 'alice', PHONE).firestore();
    await assertFails(setDoc(doc(db, 'users/alice'), validUser()));
  });
});
