import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { mirrorBlock } from '../src/onBlockWritten';
import { deleteAccountCore } from '../src/deleteAccount';
import {
  DAILY_REPORT_LIMIT,
  EVIDENCE_MESSAGES,
  handleReportContact,
  parseReport,
  REPORT_RETENTION_MS,
} from '../src/reportContact';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, resetEmulators, signInWithPhone } from '../../test-support/auth';

const deps = testDeps();
const CID = 'alice_bob';
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);
const val = async (path: string) => (await deps.rtdb.ref(path).get()).val();

async function seedConversation(cid = CID, members = ['alice', 'bob']) {
  await deps.db.doc(`conversations/${cid}`).set({
    type: 'direct', members, createdBy: members[0],
    createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
  });
}

async function seedMessage(sender: string, text: string, atMs: number, extra: Record<string, unknown> = {}) {
  await deps.db.doc(`conversations/${CID}/messages/${randomUUID()}`).set({
    senderId: sender, kind: 'text', text, replyTo: null,
    createdAt: Timestamp.fromMillis(atMs), deleted: false, reactions: {}, ...extra,
  });
}

const report = (over: Record<string, unknown> = {}) => ({
  requestId: randomUUID(), conversationId: CID, reason: 'spam', block: false, ...over,
});

beforeEach(resetEmulators);

describe('block mirror', () => {
  it('mirrors the current Firestore state into RTDB and is idempotent', async () => {
    await deps.db.doc('users/alice/blocked/bob').set({ createdAt: Timestamp.now() });
    await mirrorBlock(deps, 'alice', 'bob');
    await mirrorBlock(deps, 'alice', 'bob');
    expect(await val('blocks/alice/bob')).toBe(true);
    await deps.db.doc('users/alice/blocked/bob').delete();
    await mirrorBlock(deps, 'alice', 'bob');
    expect(await val('blocks/alice')).toBeNull();
  });

  it('runs as a trigger on block and unblock', async () => {
    const waitFor = async (expected: unknown) => {
      let got: unknown;
      for (let i = 0; i < 40; i++) {
        got = await val('blocks/carol/dan');
        if (got === expected) return got;
        await new Promise((r) => setTimeout(r, 250));
      }
      return got;
    };
    await deps.db.doc('users/carol/blocked/dan').set({ createdAt: Timestamp.now() });
    expect(await waitFor(true)).toBe(true);
    await deps.db.doc('users/carol/blocked/dan').delete();
    expect(await waitFor(null)).toBeNull();
  });

  it('deleteAccount clears the RTDB block mirror', async () => {
    await deps.auth.createUser({ uid: 'alice' });
    await deps.rtdb.ref('blocks/alice').set({ bob: true });
    await deleteAccountCore(deps, 'alice');
    expect(await val('blocks/alice')).toBeNull();
  });
});

describe('reportContact', () => {
  it('requires a phone sign-in', async () => {
    expect(await codeOf(handleReportContact({ data: report() } as any, deps))).toBe('unauthenticated');
    expect(await codeOf(handleReportContact(phoneRequest('alice', { provider: 'anonymous', data: report() }), deps))).toBe(
      'permission-denied',
    );
  });

  it('validates input', () => {
    for (const bad of [
      {},
      report({ requestId: 'nope' }),
      report({ conversationId: '../x' }),
      report({ conversationId: 'alice' }),
      report({ reason: 'meh' }),
      report({ block: 'yes' }),
    ]) {
      expect(() => parseReport(bad)).toThrow();
    }
    expect(parseReport(report({ block: undefined })).block).toBe(false);
  });

  it('only members of a direct chat can report, with the same error for missing and foreign chats', async () => {
    await seedConversation();
    expect(await codeOf(handleReportContact(phoneRequest('carol', { data: report() }), deps))).toBe('not-found');
    expect(await codeOf(handleReportContact(phoneRequest('alice', { data: report({ conversationId: 'alice_zed' }) }), deps))).toBe(
      'not-found',
    );
  });

  it('stores the reported person\'s newest non-deleted messages as evidence, newest first', async () => {
    await seedConversation();
    const t = Date.now() - 60_000;
    for (let i = 0; i < 7; i++) await seedMessage('bob', `bob ${i}`, t + i * 1000);
    await seedMessage('bob', '', t + 7500, { deleted: true });
    await seedMessage('alice', 'mine', t + 8000);
    await seedMessage('bob', 'photo', t + 9000, {
      kind: 'image',
      media: { path: `conversations/${CID}/x/photo.jpg`, name: 'photo.jpg', size: 10, mime: 'image/jpeg' },
    });
    const input = report({ reason: 'harassment' });
    const res = await handleReportContact(phoneRequest('alice', { data: input }), deps);
    expect(res).toEqual({ ok: true, blocked: false });

    const doc = await deps.db.doc(`reports/${input.requestId}`).get();
    expect(doc.get('reporterId')).toBe('alice');
    expect(doc.get('reportedUid')).toBe('bob');
    expect(doc.get('reason')).toBe('harassment');
    expect(doc.get('status')).toBe('open');
    const evidence = doc.get('evidence') as any[];
    expect(evidence).toHaveLength(EVIDENCE_MESSAGES);
    expect(evidence.map((e) => e.text)).toEqual(['photo', 'bob 6', 'bob 5', 'bob 4', 'bob 3']);
    expect(evidence[0].mediaPath).toBe(`conversations/${CID}/x/photo.jpg`);
    expect(evidence[1].mediaPath).toBeUndefined();
    const expireAt = (doc.get('expireAt') as Timestamp).toMillis();
    expect(Math.abs(expireAt - (Date.now() + REPORT_RETENTION_MS))).toBeLessThan(60_000);
    expect((await deps.db.doc('users/alice/blocked/bob').get()).exists).toBe(false);
  });

  it('can block at the same time', async () => {
    await seedConversation();
    const res = await handleReportContact(phoneRequest('alice', { data: report({ block: true }) }), deps);
    expect(res.blocked).toBe(true);
    expect((await deps.db.doc('users/alice/blocked/bob').get()).exists).toBe(true);
  });

  it('is idempotent per requestId and does not spend budget on retries', async () => {
    await seedConversation();
    const input = report();
    await handleReportContact(phoneRequest('alice', { data: input }), deps);
    await handleReportContact(phoneRequest('alice', { data: input }), deps);
    expect((await deps.db.collection('reports').get()).size).toBe(1);
    expect((await deps.db.doc('rateLimits/alice').get()).get('reportCount')).toBe(1);
    // Someone else cannot reuse (or learn about) another person's requestId.
    await seedConversation('bob_carol', ['bob', 'carol']);
    expect(
      await codeOf(handleReportContact(phoneRequest('carol', { data: { ...input, conversationId: 'bob_carol' } }), deps)),
    ).toBe('invalid-argument');
  });

  it('enforces the rolling daily budget', async () => {
    await seedConversation();
    await deps.db.doc('rateLimits/alice').set({ reportWindowStart: Timestamp.now(), reportCount: DAILY_REPORT_LIMIT });
    expect(await codeOf(handleReportContact(phoneRequest('alice', { data: report() }), deps))).toBe('resource-exhausted');
    const tomorrow = testDeps({ nowMs: () => Date.now() + 24 * 60 * 60 * 1000 + 1000 });
    expect(await codeOf(handleReportContact(phoneRequest('alice', { data: report() }), tomorrow))).toBe('ok');
  });

  it('works over HTTP for a phone-signed-in caller', async () => {
    const { uid, idToken } = await signInWithPhone('+919999900061');
    const cid = [uid, 'zed'].sort().join('_');
    await seedConversation(cid, [uid, 'zed'].sort());
    const res = await callCallable('reportContact', idToken, report({ conversationId: cid, block: true }));
    expect(res.body).toEqual({ result: { ok: true, blocked: true } });
  });
});
