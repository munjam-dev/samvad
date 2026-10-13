import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { applyMessageCreated } from '../src/onMessageCreated';
import { deleteAccountCore } from '../src/deleteAccount';
import { testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const CID = 'alice_bob';
const members = (uid: string) => ({
  uid,
  lastReadAt: null,
  lastDeliveredAt: null,
  unreadCount: 0,
  muted: false,
  pinned: false,
  archived: false,
});

async function seedConversation() {
  await deps.db.doc(`conversations/${CID}`).set({
    type: 'direct',
    members: ['alice', 'bob'],
    createdBy: 'alice',
    createdAt: Timestamp.now(),
    lastMessage: null,
    lastMessageAt: Timestamp.now(),
  });
  await deps.db.doc(`conversations/${CID}/members/alice`).set(members('alice'));
  await deps.db.doc(`conversations/${CID}/members/bob`).set(members('bob'));
}

const unread = async (uid: string) => (await deps.db.doc(`conversations/${CID}/members/${uid}`).get()).get('unreadCount');
const conv = async () => (await deps.db.doc(`conversations/${CID}`).get()).data()!;

beforeEach(resetEmulators);

describe('applyMessageCreated', () => {
  it('sets lastMessage with a bounded preview and bumps only the other member', async () => {
    await seedConversation();
    const createdAt = Timestamp.now();
    await applyMessageCreated(deps, {
      cid: CID,
      mid: 'm1',
      eventId: 'e1',
      data: { senderId: 'alice', kind: 'text', text: 'x'.repeat(500), createdAt },
    });
    const c = await conv();
    expect(c.lastMessage).toMatchObject({ id: 'm1', senderId: 'alice', kind: 'text' });
    expect(c.lastMessage.preview).toHaveLength(120);
    expect(c.lastMessageAt.toMillis()).toBe(createdAt.toMillis());
    expect(await unread('bob')).toBe(1);
    expect(await unread('alice')).toBe(0);
  });

  it('is idempotent per event ID', async () => {
    await seedConversation();
    const msg = {
      cid: CID,
      mid: 'm1',
      eventId: 'same-event',
      data: { senderId: 'alice', kind: 'text', text: 'hi', createdAt: Timestamp.now() },
    };
    await applyMessageCreated(deps, msg);
    await applyMessageCreated(deps, msg);
    expect(await unread('bob')).toBe(1);
  });

  it('an older message arriving late counts as unread but does not replace lastMessage', async () => {
    await seedConversation();
    await applyMessageCreated(deps, {
      cid: CID, mid: 'new', eventId: 'e-new',
      data: { senderId: 'bob', kind: 'text', text: 'newer', createdAt: Timestamp.fromMillis(Date.now()) },
    });
    await applyMessageCreated(deps, {
      cid: CID, mid: 'old', eventId: 'e-old',
      data: { senderId: 'bob', kind: 'text', text: 'older', createdAt: Timestamp.fromMillis(Date.now() - 60_000) },
    });
    expect((await conv()).lastMessage.id).toBe('new');
    expect(await unread('alice')).toBe(2);
  });

  it('creates a missing member doc with defaults and unreadCount 1', async () => {
    await seedConversation();
    await deps.db.doc(`conversations/${CID}/members/bob`).delete();
    await applyMessageCreated(deps, {
      cid: CID, mid: 'm', eventId: 'e',
      data: { senderId: 'alice', kind: 'text', text: 'hi', createdAt: Timestamp.now() },
    });
    const bob = (await deps.db.doc(`conversations/${CID}/members/bob`).get()).data();
    expect(bob).toMatchObject({ uid: 'bob', unreadCount: 1, muted: false, pinned: false, archived: false });
  });

  it('runs as a Firestore trigger in the emulator', async () => {
    await seedConversation();
    const mid = randomUUID();
    await deps.db.doc(`conversations/${CID}/messages/${mid}`).set({
      senderId: 'bob', kind: 'text', text: 'Okay', replyTo: null, createdAt: Timestamp.now(),
    });
    let c: any;
    for (let i = 0; i < 40; i++) {
      c = await conv();
      if (c.lastMessage?.id === mid) break;
      await new Promise((r) => setTimeout(r, 250));
    }
    expect(c.lastMessage?.id).toBe(mid);
    expect(await unread('alice')).toBe(1);
  });
});

describe('deleteAccount with conversations', () => {
  it("removes the user's member docs but keeps messages for the other member", async () => {
    await seedConversation();
    await deps.auth.createUser({ uid: 'alice' });
    const mid = randomUUID();
    await deps.db.doc(`conversations/${CID}/messages/${mid}`).set({
      senderId: 'alice', kind: 'text', text: 'hello', replyTo: null, createdAt: Timestamp.now(),
    });
    await deleteAccountCore(deps, 'alice');
    expect((await deps.db.doc(`conversations/${CID}/members/alice`).get()).exists).toBe(false);
    expect((await deps.db.doc(`conversations/${CID}/members/bob`).get()).exists).toBe(true);
    expect((await deps.db.doc(`conversations/${CID}/messages/${mid}`).get()).exists).toBe(true);
  });
});
