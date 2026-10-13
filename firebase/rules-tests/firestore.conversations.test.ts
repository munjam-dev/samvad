import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  getDoc,
  getDocs,
  limit,
  orderBy,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  writeBatch,
  type Firestore,
} from 'firebase/firestore';
import { createRulesEnv, otherProviderContext, phoneContext } from '../test-support/rulesEnv';

let env: RulesTestEnvironment;
const A = 'alice';
const B = 'bob';
const C = 'carol';
const CID = `${A}_${B}`;

const memberDefaults = (uid: string) => ({
  uid,
  lastReadAt: null,
  lastDeliveredAt: null,
  unreadCount: 0,
  muted: false,
  pinned: false,
  archived: false,
});

const newConversation = (by: string) => ({
  type: 'direct',
  members: [A, B],
  createdBy: by,
  createdAt: serverTimestamp(),
  lastMessageAt: serverTimestamp(),
});

const newMessage = (sender: string, over: Record<string, unknown> = {}) => ({
  senderId: sender,
  kind: 'text',
  text: 'Hey! Are we still on for today?',
  replyTo: null,
  createdAt: serverTimestamp(),
  ...over,
});

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

async function seedProfiles(...uids: string[]) {
  await seed(async (db) => {
    for (const uid of uids) {
      await setDoc(doc(db, `profiles/${uid}`), { displayName: uid, about: '', photoUrl: null, updatedAt: Timestamp.now() });
    }
  });
}

async function seedConversation(createdAt = Timestamp.now()) {
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${CID}`), {
      type: 'direct',
      members: [A, B],
      createdBy: A,
      createdAt,
      lastMessage: null,
      lastMessageAt: createdAt,
    });
    await setDoc(doc(db, `conversations/${CID}/members/${A}`), memberDefaults(A));
    await setDoc(doc(db, `conversations/${CID}/members/${B}`), memberDefaults(B));
  });
}

async function seedMessage(id: string, sender: string, createdAt = Timestamp.now(), extra: Record<string, unknown> = {}) {
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${CID}/messages/${id}`), {
      senderId: sender,
      kind: 'text',
      text: 'hello',
      replyTo: null,
      createdAt,
      ...extra,
    });
  });
}

const fsOf = (uid: string) => phoneContext(env, uid).firestore();

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
});
afterAll(async () => {
  await env.cleanup();
});

describe('conversations', () => {
  it('a member can create the conversation and both member docs in one batch', async () => {
    await seedProfiles(A, B);
    const db = fsOf(A);
    const batch = writeBatch(db);
    batch.set(doc(db, `conversations/${CID}`), newConversation(A));
    batch.set(doc(db, `conversations/${CID}/members/${A}`), memberDefaults(A));
    batch.set(doc(db, `conversations/${CID}/members/${B}`), memberDefaults(B));
    await assertSucceeds(batch.commit());
  });

  it('rejects unsorted members, a mismatched ID, three members, self-chat and non-member creators', async () => {
    await seedProfiles(A, B, C);
    const db = fsOf(A);
    await assertFails(setDoc(doc(db, `conversations/${CID}`), { ...newConversation(A), members: [B, A] }));
    await assertFails(setDoc(doc(db, `conversations/${B}_${A}`), newConversation(A)));
    await assertFails(setDoc(doc(db, `conversations/x`), newConversation(A)));
    await assertFails(setDoc(doc(db, `conversations/${CID}`), { ...newConversation(A), members: [A, B, C] }));
    await assertFails(setDoc(doc(db, `conversations/${A}_${A}`), { ...newConversation(A), members: [A, A] }));
    await assertFails(setDoc(doc(fsOf(C), `conversations/${CID}`), newConversation(C)));
    await assertFails(setDoc(doc(db, `conversations/${CID}`), { ...newConversation(B) }));
  });

  it('requires both members to have a profile and neither to be tombstoned', async () => {
    await seedProfiles(A);
    await assertFails(setDoc(doc(fsOf(A), `conversations/${CID}`), newConversation(A)));
    await seedProfiles(B);
    await seed(async (db) => {
      await setDoc(doc(db, `deletedAccounts/${B}`), { deletedAt: Timestamp.now() });
    });
    await assertFails(setDoc(doc(fsOf(A), `conversations/${CID}`), newConversation(A)));
  });

  it('rejects client-set lastMessage and extra fields', async () => {
    await seedProfiles(A, B);
    const db = fsOf(A);
    await assertFails(
      setDoc(doc(db, `conversations/${CID}`), {
        ...newConversation(A),
        lastMessage: { id: 'x', senderId: A, preview: 'spoof', kind: 'text', createdAt: serverTimestamp() },
      }),
    );
    await assertFails(setDoc(doc(db, `conversations/${CID}`), { ...newConversation(A), group: true }));
  });

  it('members can get; non-members, unauthenticated and other providers cannot', async () => {
    await seedConversation();
    await assertSucceeds(getDoc(doc(fsOf(A), `conversations/${CID}`)));
    await assertSucceeds(getDoc(doc(fsOf(B), `conversations/${CID}`)));
    await assertFails(getDoc(doc(fsOf(C), `conversations/${CID}`)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), `conversations/${CID}`)));
    await assertFails(getDoc(doc(otherProviderContext(env, A, 'anonymous').firestore(), `conversations/${CID}`)));
  });

  it('the chat-list query is allowed only when filtered by the caller membership', async () => {
    await seedConversation();
    const db = fsOf(A);
    await assertSucceeds(
      getDocs(query(collection(db, 'conversations'), where('members', 'array-contains', A), orderBy('lastMessageAt', 'desc'))),
    );
    await assertFails(getDocs(query(collection(db, 'conversations'), where('members', 'array-contains', B))));
    await assertFails(getDocs(collection(db, 'conversations')));
  });

  it('clients cannot update or delete a conversation', async () => {
    await seedConversation();
    const db = fsOf(A);
    await assertFails(updateDoc(doc(db, `conversations/${CID}`), { lastMessageAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(db, `conversations/${CID}`)));
  });
});

describe('messages', () => {
  it('a member can send a text message with a client UUID; the other member can read it', async () => {
    await seedConversation();
    const id = randomUUID();
    await assertSucceeds(setDoc(doc(fsOf(A), `conversations/${CID}/messages/${id}`), newMessage(A)));
    await assertSucceeds(getDoc(doc(fsOf(B), `conversations/${CID}/messages/${id}`)));
    await assertSucceeds(
      getDocs(query(collection(fsOf(B), `conversations/${CID}/messages`), orderBy('createdAt', 'desc'), limit(30))),
    );
  });

  it('non-members cannot read or write messages', async () => {
    await seedConversation();
    const id = randomUUID();
    await seedMessage(id, A);
    await assertFails(getDoc(doc(fsOf(C), `conversations/${CID}/messages/${id}`)));
    await assertFails(getDocs(collection(fsOf(C), `conversations/${CID}/messages`)));
    await assertFails(setDoc(doc(fsOf(C), `conversations/${CID}/messages/${randomUUID()}`), newMessage(C)));
  });

  it('rejects sender spoofing, bad IDs, blank or oversized text, wrong kind and client timestamps', async () => {
    await seedConversation();
    const db = fsOf(A);
    const at = (id = randomUUID()) => doc(db, `conversations/${CID}/messages/${id}`);
    await assertFails(setDoc(at(), newMessage(B)));
    await assertFails(setDoc(at('not-a-uuid'), newMessage(A)));
    await assertFails(setDoc(at(randomUUID().toUpperCase()), newMessage(A)));
    await assertFails(setDoc(at(), newMessage(A, { text: '' })));
    await assertFails(setDoc(at(), newMessage(A, { text: '  \n ' })));
    await assertFails(setDoc(at(), newMessage(A, { text: 'a'.repeat(4097) })));
    await assertSucceeds(setDoc(at(), newMessage(A, { text: 'a'.repeat(4096) })));
    await assertFails(setDoc(at(), newMessage(A, { kind: 'image' })));
    await assertFails(setDoc(at(), newMessage(A, { createdAt: Timestamp.now() })));
    await assertFails(setDoc(at(), newMessage(A, { deleted: true })));
    await assertFails(setDoc(at(), newMessage(A, { reactions: { [A]: '👍' } })));
    await assertFails(setDoc(at(), newMessage(A, { replyTo: 'nope' })));
    await assertSucceeds(setDoc(at(), newMessage(A, { replyTo: randomUUID() })));
  });

  it('re-sending the same message ID is not allowed to overwrite it', async () => {
    await seedConversation();
    const id = randomUUID();
    await seedMessage(id, A);
    await assertFails(setDoc(doc(fsOf(A), `conversations/${CID}/messages/${id}`), newMessage(A, { text: 'changed' })));
  });

  it('the sender can edit within 15 minutes; not after, and never someone else', async () => {
    await seedConversation();
    const fresh = randomUUID();
    const old = randomUUID();
    await seedMessage(fresh, A);
    await seedMessage(old, A, Timestamp.fromMillis(Date.now() - 16 * 60 * 1000));
    const db = fsOf(A);
    await assertSucceeds(updateDoc(doc(db, `conversations/${CID}/messages/${fresh}`), { text: 'edited', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, `conversations/${CID}/messages/${old}`), { text: 'edited', editedAt: serverTimestamp() }));
    await assertFails(
      updateDoc(doc(fsOf(B), `conversations/${CID}/messages/${fresh}`), { text: 'hijack', editedAt: serverTimestamp() }),
    );
    await assertFails(updateDoc(doc(db, `conversations/${CID}/messages/${fresh}`), { senderId: B }));
  });

  it('soft delete is sender-only, clears the text and is one-way; hard delete is denied', async () => {
    await seedConversation();
    const id = randomUUID();
    await seedMessage(id, A);
    const ref = (uid: string) => doc(fsOf(uid), `conversations/${CID}/messages/${id}`);
    await assertFails(updateDoc(ref(B), { deleted: true, text: '' }));
    await assertFails(updateDoc(ref(A), { deleted: true }));
    await assertSucceeds(updateDoc(ref(A), { deleted: true, text: '' }));
    await assertFails(updateDoc(ref(A), { deleted: false, text: 'back' }));
    await assertFails(updateDoc(ref(A), { text: 'edit after delete', editedAt: serverTimestamp() }));
    await assertFails(deleteDoc(ref(A)));
  });

  it('each member can set and remove only their own reaction', async () => {
    await seedConversation();
    const id = randomUUID();
    await seedMessage(id, A);
    const ref = (uid: string) => doc(fsOf(uid), `conversations/${CID}/messages/${id}`);
    await assertSucceeds(updateDoc(ref(B), { [`reactions.${B}`]: '❤️' }));
    await assertSucceeds(updateDoc(ref(A), { [`reactions.${A}`]: '😂' }));
    await assertFails(updateDoc(ref(A), { [`reactions.${B}`]: '👎' }));
    await assertFails(updateDoc(ref(A), { [`reactions.${A}`]: 'x'.repeat(17) }));
    await assertSucceeds(updateDoc(ref(B), { [`reactions.${B}`]: deleteField() }));
    await assertFails(updateDoc(doc(fsOf(C), `conversations/${CID}/messages/${id}`), { [`reactions.${C}`]: '👍' }));
  });
});

describe('member state', () => {
  it('members can read all member docs; non-members cannot', async () => {
    await seedConversation();
    await assertSucceeds(getDoc(doc(fsOf(A), `conversations/${CID}/members/${B}`)));
    await assertFails(getDoc(doc(fsOf(C), `conversations/${CID}/members/${A}`)));
  });

  it('a member updates only their own doc: read/delivered forward-only, unread reset-only, flags', async () => {
    await seedConversation();
    await seed(async (db) => {
      await updateDoc(doc(db, `conversations/${CID}/members/${A}`), { unreadCount: 4, lastReadAt: Timestamp.now() });
    });
    const mine = doc(fsOf(A), `conversations/${CID}/members/${A}`);
    await assertSucceeds(updateDoc(mine, { lastReadAt: serverTimestamp(), unreadCount: 0 }));
    await assertSucceeds(updateDoc(mine, { pinned: true, muted: true, archived: false }));
    await assertFails(updateDoc(mine, { lastReadAt: Timestamp.fromMillis(Date.now() - 3_600_000) }));
    await assertFails(updateDoc(mine, { lastDeliveredAt: Timestamp.fromMillis(Date.now() + 3_600_000) }));
    await assertFails(updateDoc(mine, { unreadCount: 7 }));
    await assertFails(updateDoc(mine, { pinned: 'yes' }));
    await assertFails(updateDoc(mine, { uid: B }));
    await assertFails(updateDoc(doc(fsOf(A), `conversations/${CID}/members/${B}`), { pinned: true }));
  });

  it('member docs must start from defaults and only for actual members', async () => {
    // A conversation no other test sends messages to, so no late trigger can touch its member docs.
    const D = 'dave';
    const cid = `${A}_${D}`;
    await seed(async (db) => {
      await setDoc(doc(db, `conversations/${cid}`), {
        type: 'direct', members: [A, D], createdBy: A, createdAt: Timestamp.now(),
        lastMessage: null, lastMessageAt: Timestamp.now(),
      });
    });
    const db = fsOf(A);
    await assertFails(setDoc(doc(db, `conversations/${cid}/members/${D}`), { ...memberDefaults(D), unreadCount: 3 }));
    await assertFails(setDoc(doc(db, `conversations/${cid}/members/${C}`), memberDefaults(C)));
    await assertSucceeds(setDoc(doc(db, `conversations/${cid}/members/${D}`), memberDefaults(D)));
  });
});
