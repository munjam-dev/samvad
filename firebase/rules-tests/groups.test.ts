import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  type Firestore,
} from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 2: groups. Group documents are written only by functions; members read from their joinedAt.
let env: RulesTestEnvironment;
const GID = 'grp0000000000000000a';
const OWNER = 'ravi';
const MEMBER = 'sita';
const LATE = 'tara'; // joined after the first messages
const OUTSIDER = 'uma';
const t = (ms: number) => Timestamp.fromMillis(ms);
const T0 = Date.now() - 60 * 60 * 1000;
const fs = (uid: string) => phoneContext(env, uid).firestore();

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

const member = (uid: string, joinedAt: number) => ({
  uid, lastReadAt: null, lastDeliveredAt: null, unreadCount: 0, muted: false, pinned: false, archived: false, joinedAt: t(joinedAt),
});
const message = (sender: string, at: number, extra: Record<string, unknown> = {}) => ({
  senderId: sender, kind: 'text', text: 'hello', replyTo: null, createdAt: t(at), deleted: false, reactions: {}, ...extra,
});
const poll = (overrides: Record<string, unknown> = {}) => ({
  question: 'Where shall we eat?', options: ['Dosa', 'Thali', 'Biryani'], ids: ['0', '1', '2'], multi: false, ...overrides,
});
const newPoll = (sender: string, p: unknown) => ({
  senderId: sender, kind: 'poll', text: '', replyTo: null, createdAt: serverTimestamp(), poll: p,
});

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${GID}`), {
      type: 'group', members: [OWNER, MEMBER, LATE], admins: [OWNER], createdBy: OWNER, createdAt: t(T0),
      name: 'Family', description: '', photoUrl: null, lastMessage: null, lastMessageAt: t(T0),
    });
    await setDoc(doc(db, `conversations/${GID}/members/${OWNER}`), member(OWNER, T0));
    await setDoc(doc(db, `conversations/${GID}/members/${MEMBER}`), member(MEMBER, T0));
    await setDoc(doc(db, `conversations/${GID}/members/${LATE}`), member(LATE, T0 + 30 * 60 * 1000));
    await setDoc(doc(db, `conversations/${GID}/messages/old`), message(OWNER, T0 + 10 * 60 * 1000));
    await setDoc(doc(db, `conversations/${GID}/messages/new`), message(OWNER, T0 + 40 * 60 * 1000));
  });
});
afterAll(async () => {
  await env.cleanup();
});

describe('group documents', () => {
  it('members can read the group; outsiders cannot; clients can never create or change one', async () => {
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${GID}`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${GID}`)));
    await assertSucceeds(getDocs(query(collection(fs(MEMBER), 'conversations'), where('members', 'array-contains', MEMBER))));
    await assertFails(updateDoc(doc(fs(OWNER), `conversations/${GID}`), { name: 'Hacked' }));
    await assertFails(updateDoc(doc(fs(OWNER), `conversations/${GID}`), { members: [OWNER, MEMBER, LATE, OUTSIDER] }));
    await assertFails(setDoc(doc(fs(OUTSIDER), 'conversations/grp000000000000000zz'), {
      type: 'group', members: [OUTSIDER], admins: [OUTSIDER], createdBy: OUTSIDER, createdAt: serverTimestamp(),
      name: 'Mine', lastMessageAt: serverTimestamp(),
    }));
  });

  it('member documents of groups are created only by functions; joinedAt cannot be changed', async () => {
    await assertFails(setDoc(doc(fs(OWNER), `conversations/${GID}/members/${OUTSIDER}`), member(OUTSIDER, T0)));
    await assertFails(updateDoc(doc(fs(LATE), `conversations/${GID}/members/${LATE}`), { joinedAt: t(T0) }));
    await assertSucceeds(updateDoc(doc(fs(LATE), `conversations/${GID}/members/${LATE}`), { muted: true }));
  });

  it('invite records are server-only', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, 'groupInvites/ABCDEFGHJKLM'), { cid: GID });
      await setDoc(doc(db, `groupSecrets/${GID}`), { inviteCode: 'ABCDEFGHJKLM' });
    });
    await assertFails(getDoc(doc(fs(OWNER), 'groupInvites/ABCDEFGHJKLM')));
    await assertFails(getDoc(doc(fs(OWNER), `groupSecrets/${GID}`)));
    await assertFails(setDoc(doc(fs(OUTSIDER), 'groupInvites/ZZZZZZZZZZZZ'), { cid: GID }));
  });
});

describe('group messages', () => {
  it('a member who joined later cannot read earlier history; a query from joinedAt works', async () => {
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${GID}/messages/old`)));
    await assertFails(getDoc(doc(fs(LATE), `conversations/${GID}/messages/old`)));
    await assertSucceeds(getDoc(doc(fs(LATE), `conversations/${GID}/messages/new`)));
    const joinedAt = t(T0 + 30 * 60 * 1000);
    await assertSucceeds(getDocs(query(collection(fs(LATE), `conversations/${GID}/messages`), where('createdAt', '>=', joinedAt), orderBy('createdAt', 'desc'))));
    await assertFails(getDocs(query(collection(fs(LATE), `conversations/${GID}/messages`), orderBy('createdAt', 'desc'))));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${GID}/messages/new`)));
  });

  it('members post; outsiders and system messages from clients are refused; mentions must be members', async () => {
    const text = (extra: Record<string, unknown> = {}) => ({ senderId: MEMBER, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), ...extra });
    await assertSucceeds(setDoc(doc(fs(MEMBER), `conversations/${GID}/messages/${randomUUID()}`), text()));
    await assertSucceeds(setDoc(doc(fs(MEMBER), `conversations/${GID}/messages/${randomUUID()}`), text({ mentions: [OWNER, LATE] })));
    await assertFails(setDoc(doc(fs(MEMBER), `conversations/${GID}/messages/${randomUUID()}`), text({ mentions: [OUTSIDER] })));
    await assertFails(setDoc(doc(fs(MEMBER), `conversations/${GID}/messages/${randomUUID()}`), text({ kind: 'system', system: { event: 'added' } })));
    await assertFails(setDoc(doc(fs(OUTSIDER), `conversations/${GID}/messages/${randomUUID()}`), { ...text(), senderId: OUTSIDER }));
  });
});

describe('polls', () => {
  it('accepts a well-formed poll and refuses malformed ones', async () => {
    const at = () => doc(fs(OWNER), `conversations/${GID}/messages/${randomUUID()}`);
    await assertSucceeds(setDoc(at(), newPoll(OWNER, poll())));
    await assertSucceeds(setDoc(at(), newPoll(OWNER, poll({ multi: true }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ options: ['Only one'], ids: ['0'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ ids: ['0', '1'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ ids: ['0', '0', '1'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ ids: ['0', '1', '99'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ question: '' }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ options: ['Dosa', '', 'Biryani'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ options: ['Dosa', 'x'.repeat(101), 'Biryani'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ options: ['Dosa', 42, 'Biryani'] }))));
    await assertFails(setDoc(at(), newPoll(OWNER, poll({ extra: true }))));
    await assertFails(setDoc(at(), { ...newPoll(OWNER, poll()), text: 'not empty' }));
    await assertFails(setDoc(at(), { senderId: OWNER, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), poll: poll() }));
  });

  it('members vote once for themselves with valid choices; everyone in the group sees votes', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, `conversations/${GID}/messages/p1`), message(OWNER, T0 + 50 * 60 * 1000, { kind: 'poll', text: '', poll: poll() }));
      await setDoc(doc(db, `conversations/${GID}/messages/p2`), message(OWNER, T0 + 50 * 60 * 1000, { kind: 'poll', text: '', poll: poll({ multi: true }) }));
    });
    const vote = (uid: string, mid: string, voter: string, choices: unknown) =>
      setDoc(doc(fs(uid), `conversations/${GID}/messages/${mid}/votes/${voter}`), { choices, updatedAt: serverTimestamp() });
    await assertSucceeds(vote(MEMBER, 'p1', MEMBER, ['1']));
    await assertSucceeds(vote(MEMBER, 'p1', MEMBER, ['2']));
    await assertFails(vote(MEMBER, 'p1', MEMBER, ['1', '2']));
    await assertFails(vote(MEMBER, 'p1', MEMBER, ['7']));
    await assertFails(vote(MEMBER, 'p1', MEMBER, []));
    await assertFails(vote(MEMBER, 'p1', OWNER, ['1']));
    await assertFails(vote(OUTSIDER, 'p1', OUTSIDER, ['1']));
    await assertFails(vote(MEMBER, 'old', MEMBER, ['1']));
    await assertSucceeds(vote(LATE, 'p2', LATE, ['0', '2']));
    await assertFails(vote(LATE, 'p2', LATE, ['0', '0']));
    await assertSucceeds(getDocs(collection(fs(OWNER), `conversations/${GID}/messages/p1/votes`)));
    await assertFails(getDocs(collection(fs(OUTSIDER), `conversations/${GID}/messages/p1/votes`)));
    await assertSucceeds(deleteDoc(doc(fs(MEMBER), `conversations/${GID}/messages/p1/votes/${MEMBER}`)));
  });
});
