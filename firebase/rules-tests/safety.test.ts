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
  Timestamp,
  updateDoc,
  type Firestore,
} from 'firebase/firestore';
import { get, ref, set } from 'firebase/database';
import { createRulesEnv, otherProviderContext, phoneContext } from '../test-support/rulesEnv';

// Phase 1.3: block list, blocked messaging, reports. Uses its own conversation so the
// messaging triggers from other suites never race with these documents.
let env: RulesTestEnvironment;
const A = 'erin';
const B = 'frank';
const CID = `${A}_${B}`;

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

async function seedRtdb(path: string, value: unknown) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await set(ref(ctx.database(), path), value);
  });
}

async function seedConversation() {
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${CID}`), {
      type: 'direct', members: [A, B], createdBy: A,
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
  });
}

async function seedBlock(uid: string, other: string) {
  await seed(async (db) => {
    await setDoc(doc(db, `users/${uid}/blocked/${other}`), { createdAt: Timestamp.now() });
  });
}

async function seedMessage(id: string, sender: string) {
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${CID}/messages/${id}`), {
      senderId: sender, kind: 'text', text: 'hello', replyTo: null,
      createdAt: Timestamp.now(), deleted: false, reactions: {},
    });
  });
}

const fs = (uid: string) => phoneContext(env, uid).firestore();
const text = (sender: string) => ({ senderId: sender, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp() });

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearDatabase();
});
afterAll(async () => {
  await env.cleanup();
});

describe('block list', () => {
  it('the owner can block, read and unblock; nobody else can', async () => {
    const mine = doc(fs(A), `users/${A}/blocked/${B}`);
    await assertSucceeds(setDoc(mine, { createdAt: serverTimestamp() }));
    await assertSucceeds(getDoc(mine));
    await assertSucceeds(getDocs(collection(fs(A), `users/${A}/blocked`)));
    await assertFails(getDoc(doc(fs(B), `users/${A}/blocked/${B}`)));
    await assertFails(getDocs(collection(fs(B), `users/${A}/blocked`)));
    await assertFails(deleteDoc(doc(fs(B), `users/${A}/blocked/${B}`)));
    await assertSucceeds(deleteDoc(mine));
  });

  it('rejects self-blocks, extra fields, client timestamps, updates and other providers', async () => {
    await assertFails(setDoc(doc(fs(A), `users/${A}/blocked/${A}`), { createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs(A), `users/${A}/blocked/${B}`), { createdAt: serverTimestamp(), note: 'x' }));
    await assertFails(setDoc(doc(fs(A), `users/${A}/blocked/${B}`), { createdAt: Timestamp.fromMillis(1) }));
    await assertFails(setDoc(doc(fs(A), `users/${A}/blocked/bad-id!`), { createdAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs(B), `users/${A}/blocked/${B}`), { createdAt: serverTimestamp() }));
    const anon = otherProviderContext(env, A, 'anonymous').firestore();
    await assertFails(setDoc(doc(anon, `users/${A}/blocked/${B}`), { createdAt: serverTimestamp() }));
    await seedBlock(A, B);
    await assertFails(updateDoc(doc(fs(A), `users/${A}/blocked/${B}`), { createdAt: serverTimestamp() }));
  });
});

describe('messaging while blocked', () => {
  it('nobody can send in either direction while one side has blocked the other', async () => {
    await seedConversation();
    await assertSucceeds(setDoc(doc(fs(B), `conversations/${CID}/messages/${randomUUID()}`), text(B)));
    await seedBlock(A, B);
    await assertFails(setDoc(doc(fs(B), `conversations/${CID}/messages/${randomUUID()}`), text(B)));
    await assertFails(setDoc(doc(fs(A), `conversations/${CID}/messages/${randomUUID()}`), text(A)));
  });

  it('unblocking restores messaging', async () => {
    await seedConversation();
    await seedBlock(B, A);
    await assertFails(setDoc(doc(fs(A), `conversations/${CID}/messages/${randomUUID()}`), text(A)));
    await seed(async (db) => deleteDoc(doc(db, `users/${B}/blocked/${A}`)));
    await assertSucceeds(setDoc(doc(fs(A), `conversations/${CID}/messages/${randomUUID()}`), text(A)));
  });

  it('edits and reactions are blocked, deleting your own message is not', async () => {
    await seedConversation();
    const mine = randomUUID();
    const theirs = randomUUID();
    await seedMessage(mine, A);
    await seedMessage(theirs, B);
    await seedBlock(A, B);
    await assertFails(updateDoc(doc(fs(A), `conversations/${CID}/messages/${mine}`), { text: 'edited', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(fs(A), `conversations/${CID}/messages/${theirs}`), { [`reactions.${A}`]: '👍' }));
    await assertSucceeds(updateDoc(doc(fs(A), `conversations/${CID}/messages/${mine}`), { deleted: true, text: '' }));
  });

  it('members can still read history while blocked', async () => {
    await seedConversation();
    const id = randomUUID();
    await seedMessage(id, B);
    await seedBlock(A, B);
    await assertSucceeds(getDoc(doc(fs(A), `conversations/${CID}/messages/${id}`)));
  });
});

describe('reports and rate limits', () => {
  it('are invisible and unwritable to clients', async () => {
    const id = randomUUID();
    await seed(async (db) => {
      await setDoc(doc(db, `reports/${id}`), { reporterId: A, reportedUid: B });
      await setDoc(doc(db, `rateLimits/${A}`), { reportCount: 1 });
    });
    await assertFails(getDoc(doc(fs(A), `reports/${id}`)));
    await assertFails(getDocs(collection(fs(A), 'reports')));
    await assertFails(setDoc(doc(fs(A), `reports/${randomUUID()}`), { reporterId: A, reportedUid: B }));
    await assertFails(getDoc(doc(fs(A), `rateLimits/${A}`)));
    await assertFails(setDoc(doc(fs(A), `rateLimits/${A}`), { reportCount: 0 }));
  });
});

describe('presence while blocked (RTDB)', () => {
  // Own UIDs: block triggers from the Firestore suites above write RTDB blocks/erin asynchronously.
  const H = 'hana';
  const I = 'ivan';
  const db = (uid: string) => phoneContext(env, uid).database();

  it('hides presence in both directions; the owner still reads their own', async () => {
    await seedRtdb(`presence/${H}`, { online: true, lastSeen: Date.now() });
    await seedRtdb(`presence/${I}`, { online: true, lastSeen: Date.now() });
    await assertSucceeds(get(ref(db(I), `presence/${H}`)));
    await seedRtdb(`blocks/${H}/${I}`, true);
    await assertFails(get(ref(db(I), `presence/${H}`)));
    await assertFails(get(ref(db(H), `presence/${I}`)));
    await assertSucceeds(get(ref(db(H), `presence/${H}`)));
    await assertSucceeds(get(ref(db('gina'), `presence/${H}`)));
  });

  it('the block mirror is server-only', async () => {
    await seedRtdb(`blocks/${H}/${I}`, true);
    await assertFails(get(ref(db(H), `blocks/${H}`)));
    await assertFails(get(ref(db(I), `blocks/${H}`)));
    await assertFails(set(ref(db(H), `blocks/${H}/${I}`), null));
    await assertFails(set(ref(db(I), `blocks/${I}/${H}`), true));
  });
});
