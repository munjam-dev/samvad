import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, where, setDoc, Timestamp, deleteDoc, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Calls (Phase 7): written only by the calls.ts callables (Admin SDK). Clients may only read a call
// they are a participant of, and can never write one directly.
let env: RulesTestEnvironment;
const A = 'anil';
const B = 'bina';
const C = 'chetan';
const CALL_ID = '7b2d1f0a-1111-4222-8333-944455556666';

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
    await setDoc(doc(d, `calls/${CALL_ID}`), {
      conversationId: `${A}_${B}`,
      callerUid: A,
      participants: [A, B],
      kind: 'video',
      status: 'ringing',
      roomName: 'call-xyz',
      createdAt: Timestamp.now(),
      expiresAt: Timestamp.now(),
      answeredAt: null,
      endedAt: null,
      endedBy: null,
    });
  });
});

describe('calls rules', () => {
  it('lets a participant read the call', async () => {
    await assertSucceeds(getDoc(doc(db(A), `calls/${CALL_ID}`)));
    await assertSucceeds(getDoc(doc(db(B), `calls/${CALL_ID}`)));
  });

  it('lets a participant list their own history, and nobody else's', async () => {
    const mine = (uid: string) => getDocs(query(collection(db(uid), 'calls'), where('participants', 'array-contains', uid)));
    await assertSucceeds(mine(A));
    await assertFails(getDocs(query(collection(db(C), 'calls'), where('participants', 'array-contains', A))));
    await assertFails(getDocs(collection(db(C), 'calls')));
  });

  it('refuses a non-participant', async () => {
    await assertFails(getDoc(doc(db(C), `calls/${CALL_ID}`)));
  });

  it('refuses any client write', async () => {
    await assertFails(setDoc(doc(db(A), `calls/other`), { participants: [A] }));
    await assertFails(updateDoc(doc(db(A), `calls/${CALL_ID}`), { status: 'active' }));
    await assertFails(deleteDoc(doc(db(A), `calls/${CALL_ID}`)));
  });
});
