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

const validProfile = (over: Record<string, unknown> = {}) => ({
  displayName: 'Asha',
  updatedAt: serverTimestamp(),
  ...over,
});

async function seedProfile(uid: string) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `profiles/${uid}`), {
      displayName: 'Seeded',
      updatedAt: Timestamp.now(),
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

describe('profiles/{uid}', () => {
  it('owner can create and update own profile', async () => {
    const db = phoneContext(env, 'alice').firestore();
    await assertSucceeds(setDoc(doc(db, 'profiles/alice'), validProfile()));
    await assertSucceeds(
      updateDoc(doc(db, 'profiles/alice'), { displayName: 'Asha K', updatedAt: serverTimestamp() }),
    );
  });

  it('any phone-signed-in user can get another profile; list is denied for everyone', async () => {
    await seedProfile('bob');
    const db = phoneContext(env, 'alice').firestore();
    await assertSucceeds(getDoc(doc(db, 'profiles/bob')));
    await assertFails(getDocs(collection(db, 'profiles')));
    await assertFails(getDocs(collection(phoneContext(env, 'bob').firestore(), 'profiles')));
  });

  it('unauthenticated and other-provider contexts cannot get', async () => {
    await seedProfile('bob');
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'profiles/bob')));
    await assertFails(getDoc(doc(otherProviderContext(env, 'alice', 'anonymous').firestore(), 'profiles/bob')));
  });

  it('another user cannot create or update profiles/B', async () => {
    await seedProfile('bob');
    const db = phoneContext(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'profiles/carol'), validProfile()));
    await assertFails(updateDoc(doc(db, 'profiles/bob'), { displayName: 'X', updatedAt: serverTimestamp() }));
  });

  it('rejects about and photoUrl on the public document (they live in profiles/{uid}/private)', async () => {
    const db = phoneContext(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ photoUrl: null })));
    await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ photoUrl: 'https://example.com/p.png' })));
    await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ about: 'hello' })));
  });

  it('rejects displayName of 0 chars, whitespace-only, and 65 chars; accepts 64 Devanagari chars', async () => {
    const db = phoneContext(env, 'alice').firestore();
    for (const bad of ['', '   ', '\n\t', 'स'.repeat(65)]) {
      await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ displayName: bad })));
    }
    await assertSucceeds(setDoc(doc(db, 'profiles/alice'), validProfile({ displayName: 'स'.repeat(64) })));
  });

  it('rejects unknown fields and updatedAt not equal to request.time', async () => {
    const db = phoneContext(env, 'alice').firestore();
    await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ phoneE164: '+919999900001' })));
    await assertFails(setDoc(doc(db, 'profiles/alice'), validProfile({ updatedAt: Timestamp.now() })));
  });

  it('update can change only displayName and updatedAt', async () => {
    await seedProfile('alice');
    const db = phoneContext(env, 'alice').firestore();
    await assertSucceeds(updateDoc(doc(db, 'profiles/alice'), { displayName: 'Alice R', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, 'profiles/alice'), { about: 'hi', updatedAt: serverTimestamp() }));
    await assertFails(
      updateDoc(doc(db, 'profiles/alice'), { photoUrl: 'https://x.test/a', updatedAt: serverTimestamp() }),
    );
    await assertFails(updateDoc(doc(db, 'profiles/alice'), { extra: 1, updatedAt: serverTimestamp() }));
  });

  it('client delete is denied', async () => {
    await seedProfile('alice');
    await assertFails(deleteDoc(doc(phoneContext(env, 'alice').firestore(), 'profiles/alice')));
  });

  it('create is denied once deletedAccounts/{uid} exists', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'deletedAccounts/alice'), { deletedAt: Timestamp.now() });
    });
    await assertFails(setDoc(doc(phoneContext(env, 'alice').firestore(), 'profiles/alice'), validProfile()));
  });
});
