import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { arrayUnion, deleteField, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Pinned messages: the list lives on the conversation document. Members can read it; only the
// setPinnedMessage function (Admin SDK) can write it, so the three-pin limit and the admin rule hold.
let env: RulesTestEnvironment;
const A = 'gita';
const B = 'hari';
const CID = `${A}_${B}`;
const GID = 'grp0000000000000000p';
const MID = '7b2d1f0a-1111-4222-8333-944455556666';
const t = (ms: number) => Timestamp.fromMillis(ms);
const T0 = Date.now() - 60 * 60 * 1000;
const pin = (uid: string, at = Timestamp.now()) => ({ messageId: MID, pinnedBy: uid, pinnedAt: at });

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
      pinnedMessages: [pin(A)],
    });
    await setDoc(doc(d, `conversations/${CID}/members/${A}`), {
      uid: A, lastReadAt: null, lastDeliveredAt: null, unreadCount: 0, muted: false, pinned: false, archived: false,
    });
    await setDoc(doc(d, `conversations/${GID}`), {
      type: 'group', members: [A, B, 'indu'], admins: [A], createdBy: A, createdAt: t(T0),
      name: 'Family', description: '', photoUrl: null, lastMessage: null, lastMessageAt: t(T0),
      pinnedMessages: [pin(A)],
    });
  });
});

describe('pinned messages rules', () => {
  it('members of a chat can read the pins; outsiders cannot', async () => {
    await assertSucceeds(getDoc(doc(db(A), `conversations/${CID}`)));
    await assertSucceeds(getDoc(doc(db(B), `conversations/${CID}`)));
    await assertFails(getDoc(doc(db('jay'), `conversations/${CID}`)));
  });

  it('a member cannot pin, replace or clear pins in a direct chat', async () => {
    for (const uid of [A, B]) {
      await assertFails(updateDoc(doc(db(uid), `conversations/${CID}`), { pinnedMessages: [pin(uid)] }));
      await assertFails(updateDoc(doc(db(uid), `conversations/${CID}`), { pinnedMessages: arrayUnion(pin(uid)) }));
      await assertFails(updateDoc(doc(db(uid), `conversations/${CID}`), { pinnedMessages: deleteField() }));
      await assertFails(updateDoc(doc(db(uid), `conversations/${CID}`), { pinnedMessages: [] }));
    }
  });

  it('nobody, not even a group admin, can change pins in a group from the app', async () => {
    for (const uid of [A, B, 'indu']) {
      await assertFails(updateDoc(doc(db(uid), `conversations/${GID}`), { pinnedMessages: [pin(uid)] }));
      await assertFails(updateDoc(doc(db(uid), `conversations/${GID}`), { pinnedMessages: [] }));
    }
  });

  it('an outsider cannot write pins either', async () => {
    await assertFails(updateDoc(doc(db('jay'), `conversations/${CID}`), { pinnedMessages: [pin('jay')] }));
  });

  it('a new direct chat cannot be created with pins already set', async () => {
    await assertFails(
      setDoc(doc(db('indu'), 'conversations/indu_jay'), {
        type: 'direct', members: ['indu', 'jay'], createdBy: 'indu', createdAt: serverTimestamp(), lastMessageAt: serverTimestamp(),
        pinnedMessages: [pin('indu')],
      }),
    );
  });

  it('a member document cannot carry the pin list', async () => {
    await assertFails(updateDoc(doc(db(A), `conversations/${CID}/members/${A}`), { pinnedMessages: [pin(A)] }));
    await assertSucceeds(updateDoc(doc(db(A), `conversations/${CID}/members/${A}`), { pinned: true }));
  });
});
