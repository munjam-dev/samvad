import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 3: scheduled messages are private to the sender and change only while pending.
let env: RulesTestEnvironment;
const A = 'nisha';
const B = 'om';
const C = 'priya';
const CID = `${A}_${B}`;

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

const db = (uid: string) => phoneContext(env, uid).firestore() as unknown as Firestore;
const ref = (uid: string, id = 's1') => doc(db(uid), `scheduledMessages/${id}`);
const inMinutes = (m: number) => Timestamp.fromMillis(Date.now() + m * 60_000);
const valid = (overrides: Record<string, unknown> = {}) => ({
  senderId: A, conversationId: CID, text: 'Good morning!', replyTo: null,
  sendAt: inMinutes(60), status: 'pending', createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
  ...overrides,
});

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
  });
});

async function seedPending(fields: Record<string, unknown> = {}) {
  await seed(async (d) => setDoc(doc(d, 'scheduledMessages/s1'), {
    senderId: A, conversationId: CID, text: 'Good morning!', replyTo: null,
    sendAt: inMinutes(60), status: 'pending', createdAt: Timestamp.now(), updatedAt: Timestamp.now(), ...fields,
  }));
}

describe('scheduled messages', () => {
  it('lets a member schedule a message and read it back', async () => {
    await assertSucceeds(setDoc(ref(A), valid()));
    await assertSucceeds(getDoc(ref(A)));
  });

  it('is private to the sender', async () => {
    await seedPending();
    await assertFails(getDoc(ref(B)));
    await assertFails(updateDoc(ref(B), { status: 'cancelled', updatedAt: serverTimestamp() }));
    await assertFails(deleteDoc(ref(B)));
  });

  it('refuses scheduling as someone else, or in a chat you are not in', async () => {
    await assertFails(setDoc(ref(A), valid({ senderId: B })));
    await assertFails(setDoc(ref(C), valid({ senderId: C })));
  });

  it('accepts times from one minute to 30 days ahead only', async () => {
    await assertFails(setDoc(ref(A, 'past'), valid({ sendAt: inMinutes(-5) })));
    await assertFails(setDoc(ref(A, 'too-far'), valid({ sendAt: inMinutes(31 * 24 * 60) })));
    await assertSucceeds(setDoc(ref(A, 'soon'), valid({ sendAt: inMinutes(2) })));
  });

  it('refuses blank text, other statuses and extra fields', async () => {
    await assertFails(setDoc(ref(A), valid({ text: '   ' })));
    await assertFails(setDoc(ref(A), valid({ status: 'sent' })));
    await assertFails(setDoc(ref(A), valid({ messageId: 'x' })));
  });

  it('refuses scheduling while blocked', async () => {
    await seed(async (d) => setDoc(doc(d, `users/${B}/blocked/${A}`), { createdAt: Timestamp.now() }));
    await assertFails(setDoc(ref(A), valid()));
  });

  it('allows editing text and time, or cancelling, while pending', async () => {
    await seedPending();
    await assertSucceeds(updateDoc(ref(A), { text: 'Good afternoon!', sendAt: inMinutes(120), updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(ref(A), { status: 'cancelled', updatedAt: serverTimestamp() }));
  });

  it('leaves sending to the server', async () => {
    await seedPending();
    await assertFails(updateDoc(ref(A), { status: 'sent', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(ref(A), { conversationId: 'elsewhere', updatedAt: serverTimestamp() }));
  });

  it('freezes items once sent, but lets the sender delete them', async () => {
    await seedPending({ status: 'sent', messageId: 'm1' });
    await assertFails(updateDoc(ref(A), { text: 'changed', updatedAt: serverTimestamp() }));
    await assertSucceeds(deleteDoc(ref(A)));
  });
});
