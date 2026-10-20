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

// Phase 6: announcement mode, group events, join requests. All group state is written by functions only.
let env: RulesTestEnvironment;
const GID = 'grp0000000000000000b';
const OWNER = 'ravi';
const MEMBER = 'sita';
const PENDING = 'uma'; // asked to join, not a member
const OUTSIDER = 'vik';
const t = (ms: number) => Timestamp.fromMillis(ms);
const T0 = Date.now() - 60 * 60 * 1000;
const fs = (uid: string) => phoneContext(env, uid).firestore();
const st = (uid: string) => phoneContext(env, uid).storage();

async function seed(fn: (db: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}

const memberDoc = (uid: string) => ({
  uid, lastReadAt: null, lastDeliveredAt: null, unreadCount: 0, muted: false, pinned: false, archived: false, joinedAt: t(T0),
});
const text = (sender: string, extra: Record<string, unknown> = {}) => ({
  senderId: sender, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), ...extra,
});
const poll = { question: 'Where?', options: ['A', 'B'], ids: ['0', '1'], multi: false };
const newMsg = (uid: string) => doc(fs(uid), `conversations/${GID}/messages/${randomUUID()}`);

async function seedGroup(extra: Record<string, unknown>) {
  await seed(async (db) => {
    await setDoc(doc(db, `conversations/${GID}`), {
      type: 'group', members: [OWNER, MEMBER], admins: [OWNER], createdBy: OWNER, createdAt: t(T0),
      name: 'Family', description: '', photoUrl: null, lastMessage: null, lastMessageAt: t(T0), ...extra,
    });
    await setDoc(doc(db, `conversations/${GID}/members/${OWNER}`), memberDoc(OWNER));
    await setDoc(doc(db, `conversations/${GID}/members/${MEMBER}`), memberDoc(MEMBER));
    await setDoc(doc(db, `conversations/${GID}/events/ev1`), {
      title: 'Picnic', description: '', place: '', startsAt: t(Date.now() + 86_400_000), createdBy: OWNER, createdAt: t(T0),
      cancelled: false, reminderSent: false, counts: { going: 1, maybe: 0, no: 0 },
    });
    await setDoc(doc(db, `conversations/${GID}/events/ev1/rsvps/${MEMBER}`), { uid: MEMBER, status: 'going', at: t(T0) });
    await setDoc(doc(db, `groupJoinRequests/${GID}_${PENDING}`), { cid: GID, uid: PENDING, groupName: 'Family', status: 'pending', requestedAt: t(T0) });
  });
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await seedGroup({ onlyAdminsSend: false, approveMembers: true, eventsByMembers: false });
});
afterAll(async () => {
  await env.cleanup();
});

describe('announcement mode', () => {
  beforeEach(async () => {
    await seed(async (db) => {
      await updateDoc(doc(db, `conversations/${GID}`), { onlyAdminsSend: true });
    });
  });

  it('refuses text, polls and media from members; admins still send', async () => {
    await assertFails(setDoc(newMsg(MEMBER), text(MEMBER)));
    await assertFails(setDoc(newMsg(MEMBER), { senderId: MEMBER, kind: 'poll', text: '', replyTo: null, createdAt: serverTimestamp(), poll }));
    const mid = randomUUID();
    await assertFails(setDoc(doc(fs(MEMBER), `conversations/${GID}/messages/${mid}`), {
      senderId: MEMBER, kind: 'image', text: '', replyTo: null, createdAt: serverTimestamp(),
      media: { path: `conversations/${GID}/${mid}/p.jpg`, name: 'p.jpg', size: 1000, mime: 'image/jpeg' },
    }));
    await assertSucceeds(setDoc(newMsg(OWNER), text(OWNER)));
    await assertSucceeds(setDoc(newMsg(OWNER), { senderId: OWNER, kind: 'poll', text: '', replyTo: null, createdAt: serverTimestamp(), poll }));
  });

  it('members can still react, vote and read', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, `conversations/${GID}/messages/m1`), { senderId: OWNER, kind: 'text', text: 'news', replyTo: null, createdAt: t(T0 + 1000), deleted: false, reactions: {} });
    });
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${GID}/messages/m1`)));
    await assertSucceeds(updateDoc(doc(fs(MEMBER), `conversations/${GID}/messages/m1`), { [`reactions.${MEMBER}`]: '👍' }));
  });

  it('refuses a member scheduling a message but not an admin', async () => {
    const item = (uid: string) => ({
      senderId: uid, conversationId: GID, text: 'later', replyTo: null, sendAt: t(Date.now() + 3_600_000), status: 'pending',
      createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    await assertFails(setDoc(doc(fs(MEMBER), 'scheduledMessages/s1'), item(MEMBER)));
    await assertSucceeds(setDoc(doc(fs(OWNER), 'scheduledMessages/s2'), item(OWNER)));
  });

  it('the setting cannot be flipped by a client, and with it off members send normally', async () => {
    await assertFails(updateDoc(doc(fs(OWNER), `conversations/${GID}`), { onlyAdminsSend: false }));
    await assertFails(updateDoc(doc(fs(MEMBER), `conversations/${GID}`), { onlyAdminsSend: false }));
    await seed(async (db) => {
      await updateDoc(doc(db, `conversations/${GID}`), { onlyAdminsSend: false });
    });
    await assertSucceeds(setDoc(newMsg(MEMBER), text(MEMBER)));
  });

  it('a pending requester, an outsider and groups created before this setting existed behave as expected', async () => {
    await assertFails(setDoc(newMsg(PENDING), text(PENDING)));
    await assertFails(setDoc(newMsg(OUTSIDER), text(OUTSIDER)));
    await seed(async (db) => {
      await setDoc(doc(db, 'conversations/grp000000000000000old'), {
        type: 'group', members: [OWNER, MEMBER], admins: [OWNER], createdBy: OWNER, createdAt: t(T0), name: 'Old', lastMessageAt: t(T0),
      });
    });
    await assertSucceeds(setDoc(doc(fs(MEMBER), `conversations/grp000000000000000old/messages/${randomUUID()}`), text(MEMBER)));
  });

  it('direct chats are never affected', async () => {
    await seed(async (db) => {
      await setDoc(doc(db, 'conversations/ravi_sita'), {
        type: 'direct', members: [OWNER, MEMBER], createdBy: OWNER, createdAt: t(T0), lastMessage: null, lastMessageAt: t(T0), onlyAdminsSend: true,
      });
    });
    await assertSucceeds(setDoc(doc(fs(MEMBER), `conversations/ravi_sita/messages/${randomUUID()}`), text(MEMBER)));
  });

  it('Storage: members cannot upload message files, admins can', async () => {
    const bytes = new Uint8Array(100).fill(1);
    const upload = (uid: string) => {
      const mid = randomUUID();
      return uploadBytes(ref(st(uid), `conversations/${GID}/${mid}/p.jpg`), bytes, { contentType: 'image/jpeg', customMetadata: { senderId: uid } });
    };
    await assertFails(upload(MEMBER));
    await assertSucceeds(upload(OWNER));
  });
});

describe('events and RSVPs', () => {
  it('members read events and RSVPs; outsiders and pending requesters cannot', async () => {
    await assertSucceeds(getDoc(doc(fs(MEMBER), `conversations/${GID}/events/ev1`)));
    await assertSucceeds(getDocs(collection(fs(MEMBER), `conversations/${GID}/events`)));
    await assertSucceeds(getDocs(collection(fs(MEMBER), `conversations/${GID}/events/ev1/rsvps`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `conversations/${GID}/events/ev1`)));
    await assertFails(getDoc(doc(fs(PENDING), `conversations/${GID}/events/ev1`)));
    await assertFails(getDocs(collection(fs(OUTSIDER), `conversations/${GID}/events/ev1/rsvps`)));
  });

  it('no client creates, edits, deletes or answers events directly, not even admins', async () => {
    const event = {
      title: 'Party', description: '', place: '', startsAt: t(Date.now() + 86_400_000), createdBy: OWNER, createdAt: serverTimestamp(),
      cancelled: false, reminderSent: false, counts: { going: 0, maybe: 0, no: 0 },
    };
    for (const uid of [OWNER, MEMBER, PENDING, OUTSIDER]) {
      await assertFails(setDoc(doc(fs(uid), `conversations/${GID}/events/new-${uid}`), event));
    }
    await assertFails(updateDoc(doc(fs(OWNER), `conversations/${GID}/events/ev1`), { cancelled: true }));
    await assertFails(updateDoc(doc(fs(MEMBER), `conversations/${GID}/events/ev1`), { 'counts.going': 99 }));
    await assertFails(deleteDoc(doc(fs(OWNER), `conversations/${GID}/events/ev1`)));
    await assertFails(setDoc(doc(fs(MEMBER), `conversations/${GID}/events/ev1/rsvps/${MEMBER}`), { uid: MEMBER, status: 'no', at: serverTimestamp() }));
    await assertFails(setDoc(doc(fs(OUTSIDER), `conversations/${GID}/events/ev1/rsvps/${OUTSIDER}`), { uid: OUTSIDER, status: 'going', at: serverTimestamp() }));
    await assertFails(deleteDoc(doc(fs(MEMBER), `conversations/${GID}/events/ev1/rsvps/${MEMBER}`)));
  });

  it('an event card message cannot be forged by a client', async () => {
    await assertFails(setDoc(newMsg(OWNER), { senderId: OWNER, kind: 'event', text: '', replyTo: null, createdAt: serverTimestamp(), eventId: 'ev1', eventTitle: 'Fake' }));
    await assertFails(setDoc(newMsg(MEMBER), text(MEMBER, { eventId: 'ev1' })));
    await assertFails(setDoc(newMsg(MEMBER), text(MEMBER, { eventTitle: 'x' })));
  });
});

describe('join requests', () => {
  const requests = (uid: string, field: string, value: string) => getDocs(query(collection(fs(uid), 'groupJoinRequests'), where(field, '==', value)));

  it('the requester reads their own request; others cannot', async () => {
    await assertSucceeds(getDoc(doc(fs(PENDING), `groupJoinRequests/${GID}_${PENDING}`)));
    await assertFails(getDoc(doc(fs(OUTSIDER), `groupJoinRequests/${GID}_${PENDING}`)));
    await assertFails(getDoc(doc(fs(MEMBER), `groupJoinRequests/${GID}_${PENDING}`)));
    await assertSucceeds(requests(PENDING, 'uid', PENDING));
    await assertFails(requests(OUTSIDER, 'uid', PENDING));
  });

  it('admins read a group\'s requests; plain members and outsiders cannot', async () => {
    await assertSucceeds(getDoc(doc(fs(OWNER), `groupJoinRequests/${GID}_${PENDING}`)));
    await assertSucceeds(requests(OWNER, 'cid', GID));
    await assertFails(requests(MEMBER, 'cid', GID));
    await assertFails(requests(OUTSIDER, 'cid', GID));
    await assertFails(getDocs(collection(fs(OWNER), 'groupJoinRequests')));
  });

  it('nobody writes requests from a client: no self-approval, no forged request, no deletion', async () => {
    const req = { cid: GID, uid: OUTSIDER, groupName: 'Family', status: 'pending', requestedAt: serverTimestamp() };
    await assertFails(setDoc(doc(fs(OUTSIDER), `groupJoinRequests/${GID}_${OUTSIDER}`), req));
    await assertFails(updateDoc(doc(fs(PENDING), `groupJoinRequests/${GID}_${PENDING}`), { status: 'approved' }));
    await assertFails(updateDoc(doc(fs(OWNER), `groupJoinRequests/${GID}_${PENDING}`), { status: 'approved' }));
    await assertFails(deleteDoc(doc(fs(OWNER), `groupJoinRequests/${GID}_${PENDING}`)));
    await assertFails(deleteDoc(doc(fs(PENDING), `groupJoinRequests/${GID}_${PENDING}`)));
  });

  it('a pending requester cannot add themselves or read the group', async () => {
    await assertFails(updateDoc(doc(fs(PENDING), `conversations/${GID}`), { members: [OWNER, MEMBER, PENDING] }));
    await assertFails(getDoc(doc(fs(PENDING), `conversations/${GID}`)));
    await assertFails(setDoc(doc(fs(PENDING), `conversations/${GID}/members/${PENDING}`), memberDoc(PENDING)));
    await assertFails(getBytes(ref(st(PENDING), `groupPhotos/${GID}/x.jpg`)));
  });
});
