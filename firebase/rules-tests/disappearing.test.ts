import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Disappearing messages: clients can never set the timer or an expiry. Only the server does.
let env: RulesTestEnvironment;
const A = 'gita';
const B = 'hari';
const CID = `${A}_${B}`;
const MID = '7b2d1f0a-1111-4222-8333-944455556666';
const NEW = '7b2d1f0a-1111-4222-8333-944455556777';
const NEW2 = '7b2d1f0a-1111-4222-8333-944455558888';

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
    for (const u of [A, B, 'indu', 'jay']) {
      await setDoc(doc(d, `profiles/${u}`), { displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
    }
    await setDoc(doc(d, `conversations/${CID}`), {
      type: 'direct', members: [A, B], createdBy: A, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    await setDoc(doc(d, `conversations/${CID}/messages/${MID}`), {
      senderId: A, text: 'hello', kind: 'text', createdAt: Timestamp.now(), replyTo: null, deleted: false, reactions: {},
    });
  });
});

const message = (extra: Record<string, unknown> = {}) => ({
  senderId: A, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), deleted: false, reactions: {}, ...extra,
});

describe('disappearing messages rules', () => {
  it('a member cannot change the chat timer', async () => {
    await assertFails(updateDoc(doc(db(A), `conversations/${CID}`), { disappearAfterSec: 86400 }));
    await assertFails(updateDoc(doc(db(B), `conversations/${CID}`), { disappearAfterSec: 0 }));
  });

  it('a new direct chat cannot be created with a timer already set', async () => {
    await assertFails(
      setDoc(doc(db('indu'), 'conversations/indu_jay'), {
        type: 'direct', members: ['indu', 'jay'], createdBy: 'indu', createdAt: serverTimestamp(), lastMessageAt: serverTimestamp(), disappearAfterSec: 3600,
      }),
    );
  });

  it('a client cannot send a message with an expiry', async () => {
    await assertSucceeds(setDoc(doc(db(A), `conversations/${CID}/messages/${NEW}`), message()));
    await assertFails(setDoc(doc(db(A), `conversations/${CID}/messages/${NEW2}`), message({ expiresAt: Timestamp.fromMillis(Date.now() + 1000) })));
  });

  it('a client cannot add or change an expiry on an existing message', async () => {
    await assertFails(updateDoc(doc(db(A), `conversations/${CID}/messages/${MID}`), { expiresAt: Timestamp.fromMillis(Date.now() + 1000) }));
    await assertFails(updateDoc(doc(db(B), `conversations/${CID}/messages/${MID}`), { expiresAt: Timestamp.fromMillis(Date.now() + 1000) }));
  });
});
