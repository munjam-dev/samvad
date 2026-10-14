import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { sendDueScheduled } from '../src/scheduled';
import { deleteAccountCore } from '../src/deleteAccount';
import { testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

// Phase 3: scheduled messages are sent by the server at their time, exactly once.
const deps = testDeps();
const CID = 'kavya_lalit';
const NOW = Date.UTC(2026, 9, 14, 12, 0, 0);

async function seedConversation(members = ['kavya', 'lalit'], type = 'direct') {
  await deps.db.doc(`conversations/${CID}`).set({
    type, members, createdBy: members[0],
    createdAt: Timestamp.fromMillis(NOW - 60_000), lastMessage: null, lastMessageAt: Timestamp.fromMillis(NOW - 60_000),
  });
}

async function schedule(id: string, fields: Record<string, unknown> = {}) {
  await deps.db.doc(`scheduledMessages/${id}`).set({
    senderId: 'kavya', conversationId: CID, text: 'Happy birthday!', replyTo: null,
    sendAt: Timestamp.fromMillis(NOW - 1_000), status: 'pending',
    createdAt: Timestamp.fromMillis(NOW - 3_600_000), updatedAt: Timestamp.fromMillis(NOW - 3_600_000),
    ...fields,
  });
}

const item = async (id: string) => (await deps.db.doc(`scheduledMessages/${id}`).get()).data()!;
const messages = async () => (await deps.db.collection(`conversations/${CID}/messages`).get()).docs.map((d) => d.data());

beforeEach(async () => {
  await resetEmulators();
  await seedConversation();
});

describe('sending scheduled messages', () => {
  it('sends a due message as a normal message and records it', async () => {
    await schedule('s1');
    expect(await sendDueScheduled(deps, NOW)).toEqual({ sent: 1, failed: 0 });
    const sent = await messages();
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ senderId: 'kavya', kind: 'text', text: 'Happy birthday!', replyTo: null });
    const s = await item('s1');
    expect(s.status).toBe('sent');
    expect(typeof s.messageId).toBe('string');
    expect(s.expireAt.toMillis()).toBeGreaterThan(NOW);
  });

  it('leaves future messages alone', async () => {
    await schedule('later', { sendAt: Timestamp.fromMillis(NOW + 60_000) });
    expect(await sendDueScheduled(deps, NOW)).toEqual({ sent: 0, failed: 0 });
    expect((await item('later')).status).toBe('pending');
  });

  it('never sends twice', async () => {
    await schedule('once');
    await sendDueScheduled(deps, NOW);
    await sendDueScheduled(deps, NOW + 60_000);
    expect(await messages()).toHaveLength(1);
  });

  it('ignores cancelled messages', async () => {
    await schedule('cancelled', { status: 'cancelled' });
    expect(await sendDueScheduled(deps, NOW)).toEqual({ sent: 0, failed: 0 });
    expect(await messages()).toHaveLength(0);
  });

  it('fails when the sender is no longer in the chat', async () => {
    await seedConversation(['lalit', 'mohan'], 'group');
    await schedule('left');
    expect(await sendDueScheduled(deps, NOW)).toEqual({ sent: 0, failed: 1 });
    expect(await item('left')).toMatchObject({ status: 'failed', failure: 'not_member' });
    expect(await messages()).toHaveLength(0);
  });

  it('fails when either person has blocked the other', async () => {
    await deps.db.doc('users/lalit/blocked/kavya').set({ createdAt: Timestamp.now() });
    await schedule('blocked');
    await sendDueScheduled(deps, NOW);
    expect(await item('blocked')).toMatchObject({ status: 'failed', failure: 'blocked' });
  });

  it('fails when the sender deleted their account', async () => {
    await deps.db.doc('deletedAccounts/kavya').set({ deletedAt: Timestamp.now() });
    await schedule('gone');
    await sendDueScheduled(deps, NOW);
    expect(await item('gone')).toMatchObject({ status: 'failed', failure: 'account_deleted' });
  });

  it('fails invalid text instead of sending it', async () => {
    await schedule('blank', { text: '   ' });
    await sendDueScheduled(deps, NOW);
    expect(await item('blank')).toMatchObject({ status: 'failed', failure: 'invalid' });
  });

  it('removes the sender\'s scheduled messages when the account is deleted', async () => {
    await schedule('mine');
    await schedule('theirs', { senderId: 'lalit' });
    await deleteAccountCore(deps, 'kavya');
    expect((await deps.db.doc('scheduledMessages/mine').get()).exists).toBe(false);
    expect((await deps.db.doc('scheduledMessages/theirs').get()).exists).toBe(true);
  });
});
