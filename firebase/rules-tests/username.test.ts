import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Usernames are reserved by the server only. A client can neither write the reservation nor the profile field.
let env: RulesTestEnvironment;
const A = 'asha';
const B = 'ravi';
const db = (uid: string) => phoneContext(env, uid).firestore() as unknown as Firestore;

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.firestore() as unknown as Firestore;
    await setDoc(doc(d, `profiles/${A}`), { displayName: 'Asha', about: '', photoUrl: null, updatedAt: Timestamp.now(), username: 'asha_k' });
    await setDoc(doc(d, 'usernames/asha_k'), { uid: A, createdAt: Timestamp.now() });
  });
});

describe('usernames', () => {
  it('nobody can read, create, change or delete a reservation', async () => {
    await assertFails(getDoc(doc(db(A), 'usernames/asha_k')));
    await assertFails(setDoc(doc(db(B), 'usernames/ravi_k'), { uid: B, createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(db(A), 'usernames/asha_k'), { uid: B, createdAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db(A), 'usernames/asha_k')));
  });

  it('a client cannot set or change the username on a profile', async () => {
    await assertFails(updateDoc(doc(db(A), `profiles/${A}`), { username: 'taken_name', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db(A), `profiles/${A}`), { username: 'other', displayName: 'Asha', updatedAt: serverTimestamp() }));
  });

  it('a new profile cannot be created with a username already set', async () => {
    await assertFails(setDoc(doc(db(B), `profiles/${B}`), { displayName: 'Ravi', about: '', photoUrl: null, updatedAt: serverTimestamp(), username: 'ravi_k' }));
  });

  it('editing the name and about still works and leaves the username alone', async () => {
    await assertSucceeds(updateDoc(doc(db(A), `profiles/${A}`), { displayName: 'Asha K', about: 'hi', updatedAt: serverTimestamp() }));
  });

  it('anyone signed in can read a profile, including its username', async () => {
    await assertSucceeds(getDoc(doc(db(B), `profiles/${A}`)));
  });
});
