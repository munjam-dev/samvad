import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { MAX_PUSH_CIPHERTEXT_CHARS, applyMessageCreated, previewOf } from '../src/onMessageCreated';
import { sendDueScheduled } from '../src/scheduled';
import { handleReportContact, parseReport } from '../src/reportContact';
import { fakePush, phoneRequest, testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

// What the server does (and, above all, does not do) with end-to-end encrypted chats.
const base = testDeps();
const db = base.db;
const CID = 'alice_bob';
const NOW = Date.UTC(2026, 9, 14, 12, 0, 0);
const SECRET = 'the secret words nobody on the server may read';
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const CIPHER = 'QUJD'.repeat(40);
const enc = (over: Record<string, unknown> = {}) => ({ v: 1, type: 'whisper', ciphertext: CIPHER, registrationId: 222, ...over });

async function seed(e2ee = true) {
  await db.doc(`conversations/${CID}`).set({
    type: 'direct', members: ['alice', 'bob'], createdBy: 'alice',
    createdAt: Timestamp.fromMillis(NOW - 60_000), lastMessage: null, lastMessageAt: Timestamp.fromMillis(NOW - 60_000),
    ...(e2ee ? { e2ee: true } : {}),
  });
  for (const uid of ['alice', 'bob']) {
    await db.doc(`conversations/${CID}/members/${uid}`).set({
      uid, lastReadAt: null, lastDeliveredAt: null, unreadCount: 0, muted: false, pinned: false, archived: false,
    });
  }
  await db.doc('profiles/alice').set({ displayName: 'Aarav', about: '', photoUrl: null, updatedAt: Timestamp.now() });
  await db.doc('users/bob/devices/phone').set({ platform: 'android', fcmToken: 'bob-phone' });
}

const created = (data: Record<string, unknown>, eventId = randomUUID()) => ({
  cid: CID, mid: randomUUID(), eventId,
  data: { senderId: 'alice', kind: 'text', text: '', createdAt: Timestamp.now(), ...data } as any,
});
const conv = async () => (await db.doc(`conversations/${CID}`).get()).data()!;

beforeEach(resetEmulators);

describe('new encrypted message', () => {
  it('stores no preview, flags the last message as encrypted, and still counts unread', async () => {
    await seed();
    await applyMessageCreated(testDeps(), created({ enc: enc() }));
    const c = await conv();
    expect(c.lastMessage).toMatchObject({ senderId: 'alice', kind: 'text', preview: '', encrypted: true });
    expect(JSON.stringify(c)).not.toContain(CIPHER);
    expect((await db.doc(`conversations/${CID}/members/bob`).get()).get('unreadCount')).toBe(1);
  });

  it('gives no description of the content for media either: no photo, file name or caption', async () => {
    await seed();
    await applyMessageCreated(testDeps(), created({ kind: 'file', enc: enc(), media: { path: `conversations/${CID}/x/blob.bin`, size: 10 } }));
    expect((await conv()).lastMessage).toMatchObject({ kind: 'file', preview: '', encrypted: true });
    expect(previewOf({ senderId: 'a', kind: 'image', text: '', createdAt: Timestamp.now(), enc: enc() })).toBe('');
  });

  it('a push carries a generic body and the ciphertext, never text', async () => {
    await seed();
    const push = fakePush();
    await applyMessageCreated(testDeps({ push: push.sender }), created({ enc: enc({ type: 'prekey' }) }));
    expect(push.sent).toHaveLength(1);
    const data = push.sent[0].data;
    expect(data).toMatchObject({ type: 'message', cid: CID, senderId: 'alice', title: 'Aarav', body: '', e2ee: '1', kind: 'text', encType: 'prekey', encCipher: CIPHER, encReg: '222' });
    expect(Object.values(data).every((v) => typeof v === 'string')).toBe(true);
  });

  it('leaves the ciphertext out of a push that would be too big, and out of every other field', async () => {
    await seed();
    const push = fakePush();
    const big = 'A'.repeat(MAX_PUSH_CIPHERTEXT_CHARS + 4);
    await applyMessageCreated(testDeps({ push: push.sender }), created({ enc: enc({ ciphertext: big }) }));
    const data = push.sent[0].data;
    expect(data.e2ee).toBe('1');
    expect(data.encCipher).toBeUndefined();
    expect(JSON.stringify(data)).not.toContain(big);
  });

  it('an ordinary chat is unchanged', async () => {
    await seed(false);
    const push = fakePush();
    await applyMessageCreated(testDeps({ push: push.sender }), created({ text: 'hello there' }));
    expect((await conv()).lastMessage).toMatchObject({ preview: 'hello there' });
    expect((await conv()).lastMessage.encrypted).toBeUndefined();
    expect(push.sent[0].data).toMatchObject({ body: 'hello there' });
    expect(push.sent[0].data.e2ee).toBeUndefined();
  });
});

describe('scheduled messages in an encrypted chat', () => {
  const schedule = (id: string) => db.doc(`scheduledMessages/${id}`).set({
    senderId: 'alice', conversationId: CID, text: SECRET, replyTo: null,
    sendAt: Timestamp.fromMillis(NOW - 1_000), status: 'pending',
    createdAt: Timestamp.fromMillis(NOW - 3_600_000), updatedAt: Timestamp.fromMillis(NOW - 3_600_000),
  });

  it('the server refuses to send them and never writes the text into the chat', async () => {
    await seed();
    await schedule('s1');
    expect(await sendDueScheduled(base, NOW)).toEqual({ sent: 0, failed: 1 });
    const item = (await db.doc('scheduledMessages/s1').get()).data()!;
    expect(item).toMatchObject({ status: 'failed', failure: 'encrypted' });
    expect((await db.collection(`conversations/${CID}/messages`).get()).size).toBe(0);
  });

  it('still sends them in ordinary chats', async () => {
    await seed(false);
    await schedule('s2');
    expect(await sendDueScheduled(base, NOW)).toEqual({ sent: 1, failed: 0 });
  });
});

describe('reporting from an encrypted chat', () => {
  const report = (over: Record<string, unknown> = {}) => ({ requestId: randomUUID(), conversationId: CID, reason: 'abuse', block: false, ...over });
  const send = (uid: string, data: unknown) => handleReportContact(phoneRequest(uid, { data }), base);

  async function seedMessages() {
    const ids = [randomUUID(), randomUUID()];
    await db.doc(`conversations/${CID}/messages/${ids[0]}`).set({
      senderId: 'bob', kind: 'text', text: '', replyTo: null, createdAt: Timestamp.fromMillis(NOW - 2000), deleted: false, reactions: {}, enc: enc(),
    });
    await db.doc(`conversations/${CID}/messages/${ids[1]}`).set({
      senderId: 'bob', kind: 'image', text: '', replyTo: null, createdAt: Timestamp.fromMillis(NOW - 1000), deleted: false, reactions: {}, enc: enc(),
      media: { path: `conversations/${CID}/${ids[1]}/blob.bin`, size: 100 },
    });
    return ids;
  }
  const reportDoc = async (requestId: string) => (await db.doc(`reports/${requestId}`).get()).data()!;

  it('stores metadata only from the server side, plus what the reporter chose to attach, marked unverified', async () => {
    await seed();
    const [textId] = await seedMessages();
    const r = report({ evidenceConsent: true, evidence: [{ messageId: textId, kind: 'text', text: 'what the reporter saw' }] });
    await send('alice', r);
    // alice reports bob: the reported messages are bob's.
    const stored = await reportDoc(r.requestId);
    expect(stored.encryptedChat).toBe(true);
    expect(stored.evidence).toHaveLength(2);
    for (const e of stored.evidence) {
      expect(e.text).toBe('');
      expect(e.encrypted).toBe(true);
      expect(e.mediaPath).toBeUndefined();
    }
    expect(JSON.stringify(stored)).not.toContain(CIPHER);
    expect(stored.reporterEvidence).toHaveLength(1);
    expect(stored.reporterEvidence[0]).toMatchObject({ messageId: textId, text: 'what the reporter saw', unverified: true });
  });

  it('works without attaching anything, and ignores attached text for messages the reported person did not write', async () => {
    await seed();
    await seedMessages();
    const mine = randomUUID();
    await db.doc(`conversations/${CID}/messages/${mine}`).set({
      senderId: 'alice', kind: 'text', text: '', replyTo: null, createdAt: Timestamp.fromMillis(NOW), deleted: false, reactions: {}, enc: enc(),
    });
    const plain = report();
    await send('alice', plain);
    expect((await reportDoc(plain.requestId)).reporterEvidence).toEqual([]);

    const framing = report({ evidenceConsent: true, evidence: [{ messageId: mine, kind: 'text', text: 'bob said something' }, { messageId: randomUUID(), kind: 'text', text: 'nothing' }] });
    await send('alice', framing);
    expect((await reportDoc(framing.requestId)).reporterEvidence).toEqual([]);
  });

  it('refuses attached messages without explicit consent, or too many, or oversized', async () => {
    const e = { messageId: randomUUID(), kind: 'text', text: 'x' };
    expect(() => parseReport(report({ evidence: [e] }))).toThrow();
    expect(() => parseReport(report({ evidence: [e], evidenceConsent: false }))).toThrow();
    expect(() => parseReport(report({ evidence: Array.from({ length: 6 }, () => e), evidenceConsent: true }))).toThrow();
    expect(() => parseReport(report({ evidence: [{ ...e, text: 'x'.repeat(4097) }], evidenceConsent: true }))).toThrow();
    expect(() => parseReport(report({ evidence: [{ ...e, messageId: 'nope' }], evidenceConsent: true }))).toThrow();
    expect(() => parseReport(report({ evidence: [{ ...e, kind: 'poll' }], evidenceConsent: true }))).toThrow();
    expect(parseReport(report({ evidence: [e], evidenceConsent: true })).evidence).toHaveLength(1);
    expect(parseReport(report()).evidence).toEqual([]);
  });

  it('an ordinary chat keeps the server-side copy as before and ignores attached text', async () => {
    await seed(false);
    const id = randomUUID();
    await db.doc(`conversations/${CID}/messages/${id}`).set({
      senderId: 'bob', kind: 'text', text: 'real text on the server', replyTo: null, createdAt: Timestamp.fromMillis(NOW), deleted: false, reactions: {},
    });
    const r = report({ evidenceConsent: true, evidence: [{ messageId: id, kind: 'text', text: 'forged text' }] });
    expect(await codeOf(send('alice', r))).toBe('ok');
    const stored = await reportDoc(r.requestId);
    expect(stored.evidence[0].text).toBe('real text on the server');
    expect(stored.reporterEvidence).toBeUndefined();
    expect(JSON.stringify(stored)).not.toContain('forged text');
  });
});
