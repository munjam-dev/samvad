import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

export const REPORT_REASONS = ['spam', 'abuse', 'harassment', 'impersonation', 'other'] as const;
export type ReportReason = (typeof REPORT_REASONS)[number];
export const DAILY_REPORT_LIMIT = 20;
export const EVIDENCE_MESSAGES = 5;
export const EVIDENCE_SCAN = 50;
export const REPORT_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
const WINDOW_MS = 24 * 60 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const DIRECT_CID = /^[A-Za-z0-9]{1,128}_[A-Za-z0-9]{1,128}$/;

export interface ReportInput {
  requestId: string;
  conversationId: string;
  reason: ReportReason;
  block: boolean;
}

export function parseReport(data: unknown): ReportInput {
  const d = (data ?? {}) as Record<string, unknown>;
  if (typeof d.requestId !== 'string' || !UUID.test(d.requestId)) {
    throw new HttpsError('invalid-argument', 'requestId must be a UUID.');
  }
  if (typeof d.conversationId !== 'string' || !DIRECT_CID.test(d.conversationId)) {
    throw new HttpsError('invalid-argument', 'Unknown conversation.');
  }
  if (typeof d.reason !== 'string' || !(REPORT_REASONS as readonly string[]).includes(d.reason)) {
    throw new HttpsError('invalid-argument', 'Unknown reason.');
  }
  if (d.block !== undefined && typeof d.block !== 'boolean') {
    throw new HttpsError('invalid-argument', 'block must be a boolean.');
  }
  return {
    requestId: d.requestId,
    conversationId: d.conversationId,
    reason: d.reason as ReportReason,
    block: d.block === true,
  };
}

interface Evidence {
  messageId: string;
  kind: string;
  text: string;
  mediaPath?: string;
  createdAt: Timestamp | null;
}

export async function reportCore(
  deps: Deps,
  uid: string,
  input: ReportInput,
): Promise<{ evidenceCount: number; blocked: boolean }> {
  const conv = await deps.db.doc(`conversations/${input.conversationId}`).get();
  const members = (conv.get('members') as string[] | undefined) ?? [];
  if (!conv.exists || conv.get('type') !== 'direct' || !members.includes(uid)) {
    // Same answer whether the chat is missing or not the caller's, so IDs cannot be probed.
    throw new HttpsError('not-found', 'Unknown conversation.');
  }
  const reported = members.find((m) => m !== uid);
  if (!reported) throw new HttpsError('not-found', 'Unknown conversation.');

  const reportRef = deps.db.doc(`reports/${input.requestId}`);
  const budgetRef = deps.db.doc(`rateLimits/${uid}`);
  const recent = deps.db
    .collection(`conversations/${input.conversationId}/messages`)
    .orderBy('createdAt', 'desc')
    .limit(EVIDENCE_SCAN);

  const evidenceCount = await deps.db.runTransaction(async (tx) => {
    const existing = await tx.get(reportRef);
    if (existing.exists) {
      if (existing.get('reporterId') !== uid) throw new HttpsError('invalid-argument', 'requestId must be a UUID.');
      return (existing.get('evidence') as unknown[] | undefined)?.length ?? 0;
    }
    const budget = await tx.get(budgetRef);
    const now = deps.nowMs();
    const windowStart: Timestamp | undefined = budget.get('reportWindowStart');
    const fresh = !windowStart || now - windowStart.toMillis() >= WINDOW_MS;
    const used = fresh ? 0 : Number(budget.get('reportCount') ?? 0);
    if (used + 1 > DAILY_REPORT_LIMIT) {
      throw new HttpsError('resource-exhausted', 'Report limit reached. Try again later.');
    }

    const messages = await tx.get(recent);
    const evidence: Evidence[] = messages.docs
      .filter((m) => m.get('senderId') === reported && m.get('deleted') !== true)
      .slice(0, EVIDENCE_MESSAGES)
      .map((m) => {
        const item: Evidence = {
          messageId: m.id,
          kind: String(m.get('kind') ?? 'text'),
          text: String(m.get('text') ?? '').slice(0, 4096),
          createdAt: (m.get('createdAt') as Timestamp | undefined) ?? null,
        };
        const path = m.get('media.path');
        if (typeof path === 'string') item.mediaPath = path;
        return item;
      });

    tx.set(budgetRef, {
      reportWindowStart: fresh ? Timestamp.fromMillis(now) : windowStart,
      reportCount: used + 1,
    }, { merge: true });
    tx.create(reportRef, {
      reporterId: uid,
      reportedUid: reported,
      conversationId: input.conversationId,
      reason: input.reason,
      evidence,
      status: 'open',
      createdAt: FieldValue.serverTimestamp(),
      expireAt: Timestamp.fromMillis(now + REPORT_RETENTION_MS),
    });
    return evidence.length;
  });

  if (input.block) {
    // set() is idempotent; the trigger mirrors it to RTDB.
    await deps.db.doc(`users/${uid}/blocked/${reported}`).set({ createdAt: FieldValue.serverTimestamp() });
  }
  return { evidenceCount, blocked: input.block };
}

export async function handleReportContact(
  request: CallableRequest<unknown>,
  deps: Deps = defaultDeps(),
): Promise<{ ok: true; blocked: boolean }> {
  const { uid } = requirePhoneAuth(request);
  const input = parseReport(request.data);
  const correlationId = newCorrelationId();
  try {
    const result = await reportCore(deps, uid, input);
    logEvent('contact_reported', { correlationId, count: result.evidenceCount, outcome: 'ok' });
    return { ok: true, blocked: result.blocked };
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('contact_report_rejected', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('contact_report_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Report failed. Please retry.');
  }
}

export const reportContact = onCall(buildCallableOptions(process.env), (request) =>
  handleReportContact(request),
);
