import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 3: starred (saved) messages, private to their owner.
let env: RulesTestEnvironment;
const A = 'gita';
const B = 'hari';
const C = 'indu';
const CID = `${A}_${B}`;
const MID = '7b2d1f0a-1111-4222-8333-944455556666';
const KEY = `${CID}_${MID}`;

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

const db = (uid: string) => phoneContext(env, uid).firestore() as unknown as Firestore;
const saved = (uid: string, key = KEY) => doc(db(uid), `users/${uid}/savedMessages/${key}`);
const valid = (cid = CID, mid = MID) => ({ conversationId: cid, messageId: mid, savedAt: serverTimestamp() });

beforeAll(async () => {
  env = await createRulesEnv();
});

afterAll(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (d) => {
    await setDoc(doc(d, `conversations/${CID}`), {
      type: 'direct', members: [A, B], createdBy: A,
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    await setDoc(doc(d, `conversations/${CID}/messages/${MID}`), {
      senderId: B, text: 'Biryani at 8?', kind: 'text', createdAt: Timestamp.now(), replyTo: null,
    });
  });
});

describe('saved messages', () => {
  it('lets a member star a message and read and remove their own star', async () => {
    await assertSucceeds(setDoc(saved(A), valid()));
    await assertSucceeds(getDoc(saved(A)));
    await assertSucceeds(deleteDoc(saved(A)));
  });

  it('keeps stars private: nobody else can read, write or delete them', async () => {
    await seed(async (d) => setDoc(doc(d, `users/${A}/savedMessages/${KEY}`), { conversationId: CID, messageId: MID, savedAt: Timestamp.now() }));
    await assertFails(getDoc(doc(db(B), `users/${A}/savedMessages/${KEY}`)));
    await assertFails(setDoc(doc(db(B), `users/${A}/savedMessages/${KEY}`), valid()));
    await assertFails(deleteDoc(doc(db(B), `users/${A}/savedMessages/${KEY}`)));
  });

  it('refuses stars on chats the user is not in', async () => {
    await assertFails(setDoc(saved(C), valid()));
  });

  it('refuses stars on messages that do not exist', async () => {
    const missing = '00000000-0000-4000-8000-000000000000';
    await assertFails(setDoc(saved(A, `${CID}_${missing}`), valid(CID, missing)));
  });

  it('requires the document key to match the message it points to', async () => {
    await assertFails(setDoc(saved(A, `${CID}_other`), valid()));
  });

  it('refuses extra fields, a client-chosen time, and edits', async () => {
    await assertFails(setDoc(saved(A), { ...valid(), text: 'copy of the message' }));
    await assertFails(setDoc(saved(A), { ...valid(), savedAt: Timestamp.fromMillis(0) }));
    await assertSucceeds(setDoc(saved(A), valid()));
    await assertFails(updateDoc(saved(A), { savedAt: serverTimestamp() }));
  });
});
