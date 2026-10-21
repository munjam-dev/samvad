import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, Timestamp, deleteDoc, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Meetings (Phase 7): written only by the meetings.ts callables. Clients may read a meeting only when
// they belong to its group, and can never write one directly.
let env: RulesTestEnvironment;
const A = 'anil';
const B = 'bina';
const C = 'chetan';
const GID = 'grp0000000000000000p';
const MEETING_ID = '7b2d1f0a-1111-4222-8333-944455556666';
const T0 = Timestamp.now();

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
    await setDoc(doc(d, `conversations/${GID}`), {
      type: 'group', members: [A, B], admins: [A], createdBy: A, createdAt: T0, name: 'Family', description: '', photoUrl: null, lastMessage: null, lastMessageAt: T0,
    });
    await setDoc(doc(d, `meetings/${MEETING_ID}`), {
      groupId: GID, hostUid: A, title: 'Sync', kind: 'video', startsAt: T0, status: 'scheduled', roomName: 'meeting-xyz', participants: [], createdAt: T0,
    });
  });
});

describe('meetings rules', () => {
  it('lets a group member read the meeting', async () => {
    await assertSucceeds(getDoc(doc(db(A), `meetings/${MEETING_ID}`)));
    await assertSucceeds(getDoc(doc(db(B), `meetings/${MEETING_ID}`)));
  });

  it('refuses someone outside the group', async () => {
    await assertFails(getDoc(doc(db(C), `meetings/${MEETING_ID}`)));
  });

  it('refuses any client write', async () => {
    await assertFails(setDoc(doc(db(A), `meetings/other`), { groupId: GID }));
    await assertFails(updateDoc(doc(db(A), `meetings/${MEETING_ID}`), { status: 'ended' }));
    await assertFails(deleteDoc(doc(db(A), `meetings/${MEETING_ID}`)));
  });
});
