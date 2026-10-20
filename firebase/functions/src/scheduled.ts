import { randomUUID } from 'node:crypto';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp, type DocumentData, type Transaction } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

/** Due messages handled per run; the next run (a minute later) picks up any remainder. */
export const SCHEDULE_BATCH = 200;
/** Sent and failed items stay visible to the sender this long, then a TTL policy removes them. */
export const SCHEDULE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export type ScheduledFailure = 'not_member' | 'blocked' | 'account_deleted' | 'invalid' | 'not_allowed';

const MAX_TEXT = 4096;

function validText(text: unknown): text is string {
  return typeof text === 'string' && text.length >= 1 && text.length <= MAX_TEXT && /\S/.test(text);
}

/** Re-checks, at send time, everything the message rules would check for the sender. */
async function problem(tx: Transaction, deps: Deps, s: DocumentData): Promise<ScheduledFailure | null> {
  const { db } = deps;
  if (!validText(s.text) || typeof s.conversationId !== 'string' || typeof s.senderId !== 'string') return 'invalid';
  const [deleted, conv] = await Promise.all([
    tx.get(db.doc(`deletedAccounts/${s.senderId}`)),
    tx.get(db.doc(`conversations/${s.conversationId}`)),
  ]);
  if (deleted.exists) return 'account_deleted';
  const members: unknown = conv.get('members');
  if (!conv.exists || !Array.isArray(members) || !members.includes(s.senderId)) return 'not_member';
  // Announcement mode: a scheduled message from someone who is no longer allowed to post is not sent.
  if (conv.get('type') === 'group' && conv.get('onlyAdminsSend') === true && !(conv.get('admins') ?? []).includes(s.senderId)) return 'not_allowed';
  // Announcements are written by community admins only, whatever a client scheduled.
  if (conv.get('type') === 'community_announcements' && !(conv.get('admins') ?? []).includes(s.senderId)) return 'not_member';
  if (conv.get('type') === 'direct') {
    const other = members.find((m) => m !== s.senderId);
    if (typeof other === 'string') {
      const [a, b] = await Promise.all([
        tx.get(db.doc(`users/${s.senderId}/blocked/${other}`)),
        tx.get(db.doc(`users/${other}/blocked/${s.senderId}`)),
      ]);
      if (a.exists || b.exists) return 'blocked';
    }
  }
  return null;
}

/**
 * Sends every pending scheduled message whose time has come. Each item is claimed in its own
 * transaction (pending → sent/failed), so overlapping runs or retries can never send it twice.
 * The message is written exactly as a normal send, so receipts and notifications follow as usual.
 */
export async function sendDueScheduled(deps: Deps, nowMs: number = deps.nowMs()): Promise<{ sent: number; failed: number }> {
  const { db } = deps;
  const due = await db.collection('scheduledMessages')
    .where('status', '==', 'pending')
    .where('sendAt', '<=', Timestamp.fromMillis(nowMs))
    .orderBy('sendAt')
    .limit(SCHEDULE_BATCH)
    .get();
  let sent = 0;
  let failed = 0;
  for (const d of due.docs) {
    const outcome = await db.runTransaction(async (tx) => {
      const snap = await tx.get(d.ref);
      const s = snap.data();
      if (!snap.exists || !s || s.status !== 'pending') return 'skipped' as const;
      const expireAt = Timestamp.fromMillis(nowMs + SCHEDULE_RETENTION_MS);
      const why = await problem(tx, deps, s);
      if (why) {
        tx.update(d.ref, { status: 'failed', failure: why, updatedAt: FieldValue.serverTimestamp(), expireAt });
        return 'failed' as const;
      }
      const messageId = randomUUID();
      tx.set(db.doc(`conversations/${s.conversationId}/messages/${messageId}`), {
        senderId: s.senderId,
        kind: 'text',
        text: s.text,
        replyTo: typeof s.replyTo === 'string' ? s.replyTo : null,
        createdAt: FieldValue.serverTimestamp(),
      });
      tx.update(d.ref, { status: 'sent', messageId, updatedAt: FieldValue.serverTimestamp(), expireAt });
      return 'sent' as const;
    }).catch(() => 'error' as const);
    if (outcome === 'sent') sent++;
    if (outcome === 'failed') failed++;
  }
  if (sent > 0) logEvent('scheduled_sent', { count: sent, outcome: 'ok' });
  if (failed > 0) logEvent('scheduled_failed', { count: failed, outcome: 'failed' });
  return { sent, failed };
}

export const sendScheduledMessages = onSchedule(
  { schedule: 'every 1 minutes', region: REGION, timeZone: 'Asia/Kolkata', retryCount: 0, maxInstances: 1 },
  async () => {
    await sendDueScheduled(defaultDeps());
  },
);
