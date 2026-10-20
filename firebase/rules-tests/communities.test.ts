import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  type Firestore,
} from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 6: communities. Community documents and the announcements conversation are written only by functions;
// members read them, and only community admins can post announcements.
let env: RulesTestEnvironment;
const CID = 'comm0000000000000000';
const ANN = 'annc0000000000000000';
const ADMIN = 'asha';
const MEMBER = 'bhim';
const OUTSIDER = 'uma';
const t = (ms: number) => Timestamp.fromMillis(ms);
const T0 = Date.now() - 60 * 60 * 1000;
const fs = (uid: string) => phoneContext(env, uid).firestore();
const st = (uid: string) => phoneContext(env, uid).storage();

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

const member = (uid: string) => ({
  uid, lastReadAt: t(T0), lastDeliveredAt: null, unreadCount: 0, muted: false, pinned: false, archived: false, joinedAt: t(T0),
});
const text = (sender: string, extra: Record<string, unknown> = {}) => ({
  senderId: sender, kind: 'text', text: 'Water cut at 10', replyTo: null, createdAt: serverTimestamp(), ...extra,
});
const mid = () => randomUUID();

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await seed(async (db) => {
    await setDoc(doc(db, `communities/${CID}`), {
      name: 'Neighbours', description: 'Our lane', photoUrl: null, createdBy: ADMIN, createdAt: t(T0),
      members: [ADMIN, MEMBER], admins: [ADMIN], announcementsCid: ANN, groups: [], linkedGroupIds: [],
    });
    await setDoc(doc(db, `conversations/${ANN}`), {
      type: 'community_announcements', communityId: CID, members: [ADMIN, MEMBER], admins: [ADMIN], createdBy: ADMIN,
      createdAt: t(T0), name: 'Neighbours', description: '', photoUrl: null, lastMessage: null, lastMessageAt: t(T0),
    });
    await setDoc(doc(db, `conversations/${ANN}/members/${ADMIN}`), member(ADMIN));
    await setDoc(doc(db, `conversations/${ANN}/members/${MEMBER}`), member(MEMBER));
    await setDoc(doc(db, `conversations/${ANN}/messages/old`), {
      senderId: ADMIN, kind: 'text', text: 'Welcome', replyTo: null, createdAt: t(T0 + 1000), deleted: false, reactions: {},
    });
  });
});
afterAll(async () => {
  await env.cleanup();
});

describe('community documents', () => {
  it('members read their communities; outsiders cannot; list queries must filter by membership', async () => {
    await assertSucceeds(getDoc(doc(fs(MEMBER), `communities/${CID}`)));
    await assertSucceeds(getDoc(doc(fs(ADMIN), `communities/${CID}`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `communities/${CID}`)));
    await assertSucceeds(getDocs(query(collection(fs(MEMBER), 'communities'), where('members', 'array-contains', MEMBER))));
    await assertFails(getDocs(query(collection(fs(MEMBER), 'communities'), where('members', 'array-contains', OUTSIDER))));
    await assertFails(getDocs(collection(fs(MEMBER), 'communities')));
    await assertSucceeds(getDocs(query(collection(fs(OUTSIDER), 'communities'), where('members', 'array-contains', OUTSIDER)))); // allowed, and empty
  });

  it('clients can never create, change or delete a community, whoever they are', async () => {
    const mine = (uid: string) => doc(fs(uid), `communities/${CID}`);
    await assertFails(updateDoc(mine(ADMIN), { name: 'Hacked' }));
    await assertFails(updateDoc(mine(ADMIN), { members: [ADMIN, MEMBER, OUTSIDER] }));
    await assertFails(updateDoc(mine(MEMBER), { admins: [ADMIN, MEMBER] }));
    await assertFails(updateDoc(mine(MEMBER), { linkedGroupIds: ['x'] }));
    await assertFails(deleteDoc(mine(ADMIN)));
    await assertFails(setDoc(doc(fs(OUTSIDER), 'communities/comm0000000000000099'), {
      name: 'Mine', members: [OUTSIDER], admins: [OUTSIDER], announcementsCid: ANN, groups: [], linkedGroupIds: [],
    }));
  });

  it('invite records and secrets are server-only', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, 'communityInvites/ABCDEFGHJKLM'), { id: CID });
      await setDoc(doc(db, `communitySecrets/${CID}`), { inviteCode: 'ABCDEFGHJKLM' });
    });
    await assertFails(getDoc(doc(fs(ADMIN), 'communityInvites/ABCDEFGHJKLM')));
    await assertFails(getDoc(doc(fs(ADMIN), `communitySecrets/${CID}`)));
    await assertFails(setDoc(doc(fs(OUTSIDER), 'communityInvites/ZZZZZZZZZZZZ'), { id: CID }));
    await assertFails(setDoc(doc(fs(ADMIN), `communitySecrets/${CID}`), { inviteCode: 'ZZZZZZZZZZZZ' }));
  });
});

describe('announcements conversation', () => {
  it('members read the conversation and all its history; outsiders cannot', async () => {
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${ANN}`)));
    await assertSucceeds(getDocs(query(collection(fs(MEMBER), 'conversations'), where('members', 'array-contains', MEMBER))));
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${ANN}/messages/old`)));
    await assertSucceeds(getDocs(collection(fs(MEMBER), `conversations/${ANN}/messages`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${ANN}`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${ANN}/messages/old`)));
    await assertFails(getDocs(collection(fs(OUTSIDER), `conversations/${ANN}/messages`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${ANN}/members/${MEMBER}`)));
  });

  it('only community admins post; members and outsiders are refused', async () => {
    await assertSucceeds(setDoc(doc(fs(ADMIN), `conversations/${ANN}/messages/${mid()}`), text(ADMIN)));
    await assertFails(setDoc(doc(fs(MEMBER), `conversations/${ANN}/messages/${mid()}`), text(MEMBER)));
    await assertFails(setDoc(doc(fs(OUTSIDER), `conversations/${ANN}/messages/${mid()}`), text(OUTSIDER)));
    // Posting as someone else, or as a system line, is refused even for an admin.
    await assertFails(setDoc(doc(fs(ADMIN), `conversations/${ANN}/messages/${mid()}`), text(MEMBER)));
    await assertFails(setDoc(doc(fs(ADMIN), `conversations/${ANN}/messages/${mid()}`), text(ADMIN, { kind: 'system', system: { event: 'added' } })));
  });

  it('an admin who is demoted can no longer post, and a promoted member can', async () => {
    await seed(async (db) => {
      await updateDoc(doc(db, `conversations/${ANN}`), { admins: [MEMBER] });
    });
    await assertFails(setDoc(doc(fs(ADMIN), `conversations/${ANN}/messages/${mid()}`), text(ADMIN)));
    await assertSucceeds(setDoc(doc(fs(MEMBER), `conversations/${ANN}/messages/${mid()}`), text(MEMBER)));
  });

  it('members can react to an announcement but not edit or delete it', async () => {
    const message = doc(fs(MEMBER), `conversations/${ANN}/messages/old`);
    await assertSucceeds(updateDoc(message, { [`reactions.${MEMBER}`]: '👍' }));
    await assertFails(updateDoc(message, { text: 'Edited', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(message, { deleted: true, text: '' }));
    await assertFails(deleteDoc(message));
    await assertFails(updateDoc(doc(fs(OUTSIDER), `conversations/${ANN}/messages/old`), { [`reactions.${OUTSIDER}`]: '👍' }));
  });

  it('nobody can change the conversation, its member lists or create another announcements conversation', async () => {
    await assertFails(updateDoc(doc(fs(ADMIN), `conversations/${ANN}`), { members: [ADMIN, MEMBER, OUTSIDER] }));
    await assertFails(updateDoc(doc(fs(MEMBER), `conversations/${ANN}`), { admins: [ADMIN, MEMBER] }));
    await assertFails(updateDoc(doc(fs(ADMIN), `conversations/${ANN}`), { name: 'Hacked' }));
    await assertFails(deleteDoc(doc(fs(ADMIN), `conversations/${ANN}`)));
    await assertFails(setDoc(doc(fs(OUTSIDER), 'conversations/annc0000000000000099'), {
      type: 'community_announcements', members: [OUTSIDER], admins: [OUTSIDER], createdBy: OUTSIDER,
      createdAt: serverTimestamp(), lastMessageAt: serverTimestamp(),
    }));
  });

  it('member documents are created by functions only; members update only their own read state', async () => {
    await assertFails(setDoc(doc(fs(ADMIN), `conversations/${ANN}/members/${OUTSIDER}`), member(OUTSIDER)));
    await assertFails(setDoc(doc(fs(OUTSIDER), `conversations/${ANN}/members/${OUTSIDER}`), member(OUTSIDER)));
    await assertSucceeds(updateDoc(doc(fs(MEMBER), `conversations/${ANN}/members/${MEMBER}`), { lastReadAt: serverTimestamp(), unreadCount: 0 }));
    await assertSucceeds(updateDoc(doc(fs(MEMBER), `conversations/${ANN}/members/${MEMBER}`), { muted: true }));
    await assertFails(updateDoc(doc(fs(MEMBER), `conversations/${ANN}/members/${MEMBER}`), { joinedAt: t(0) }));
    await assertFails(updateDoc(doc(fs(MEMBER), `conversations/${ANN}/members/${ADMIN}`), { muted: true }));
    await assertFails(updateDoc(doc(fs(OUTSIDER), `conversations/${ANN}/members/${MEMBER}`), { muted: true }));
  });

  it('a member cannot schedule a message into the announcements; an admin can', async () => {
    const scheduled = (sender: string) => ({
      senderId: sender, conversationId: ANN, text: 'Later', replyTo: null, sendAt: Timestamp.fromMillis(Date.now() + 3600_000),
      status: 'pending', createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    await assertFails(setDoc(doc(fs(MEMBER), `scheduledMessages/${mid()}`), scheduled(MEMBER)));
    await assertFails(setDoc(doc(fs(OUTSIDER), `scheduledMessages/${mid()}`), scheduled(OUTSIDER)));
    await assertSucceeds(setDoc(doc(fs(ADMIN), `scheduledMessages/${mid()}`), scheduled(ADMIN)));
  });
});

describe('announcements and community photos in Storage', () => {
  const bytes = new Uint8Array(1000).fill(7);
  const metadata = (uid: string) => ({ contentType: 'image/jpeg', customMetadata: { senderId: uid } });

  it('only admins upload files for an announcement', async () => {
    const m = mid();
    await assertSucceeds(uploadBytes(ref(st(ADMIN), `conversations/${ANN}/${m}/photo.jpg`), bytes, metadata(ADMIN)));
    await assertFails(uploadBytes(ref(st(MEMBER), `conversations/${ANN}/${mid()}/photo.jpg`), bytes, metadata(MEMBER)));
    await assertFails(uploadBytes(ref(st(OUTSIDER), `conversations/${ANN}/${mid()}/photo.jpg`), bytes, metadata(OUTSIDER)));
    await assertSucceeds(getBytes(ref(st(MEMBER), `conversations/${ANN}/${m}/photo.jpg`)));
    await assertFails(getBytes(ref(st(OUTSIDER), `conversations/${ANN}/${m}/photo.jpg`)));
  });

  it('community photos are readable by members only and writable by nobody', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), `communityPhotos/${CID}/a.jpg`), bytes, { contentType: 'image/jpeg' });
    });
    await assertSucceeds(getBytes(ref(st(MEMBER), `communityPhotos/${CID}/a.jpg`)));
    await assertFails(getBytes(ref(st(OUTSIDER), `communityPhotos/${CID}/a.jpg`)));
    await assertFails(uploadBytes(ref(st(ADMIN), `communityPhotos/${CID}/b.jpg`), bytes, { contentType: 'image/jpeg' }));
  });
});
