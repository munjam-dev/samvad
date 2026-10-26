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

/** What the reporter's app attaches from an end-to-end encrypted chat, after the person agreed. The server cannot check the text. */
export interface ReporterEvidence {
  messageId: string;
  kind: string;
  text: string;
}

export interface ReportInput {
  requestId: string;
  conversationId: string;
  reason: ReportReason;
  block: boolean;
  /** Plaintext of the reported messages, decrypted on the reporter's phone; only used for encrypted chats. */
  evidence: ReporterEvidence[];
}

const EVIDENCE_KINDS = ['text', 'image', 'file', 'voice', 'video', 'contact'];

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
    evidence: parseEvidence(d.evidence, d.evidenceConsent),
  };
}

/** Attached evidence needs the explicit consent flag, is capped, and is plain strings only. */
function parseEvidence(raw: unknown, consent: unknown): ReporterEvidence[] {
  if (raw === undefined || raw === null) return [];
  if (consent !== true) throw new HttpsError('invalid-argument', 'Consent is required to attach messages.');
  if (!Array.isArray(raw) || raw.length > EVIDENCE_MESSAGES) throw new HttpsError('invalid-argument', 'Too many messages attached.');
  return raw.map((item) => {
    const e = (item ?? {}) as Record<string, unknown>;
    if (typeof e.messageId !== 'string' || !UUID.test(e.messageId)) throw new HttpsError('invalid-argument', 'Unknown message.');
    if (typeof e.kind !== 'string' || !EVIDENCE_KINDS.includes(e.kind)) throw new HttpsError('invalid-argument', 'Unknown message kind.');
    if (typeof e.text !== 'string' || e.text.length > 4096) throw new HttpsError('invalid-argument', 'Message text is too long.');
    return { messageId: e.messageId, kind: e.kind, text: e.text };
  });
}

interface Evidence {
  messageId: string;
  kind: string;
  text: string;
  mediaPath?: string;
  createdAt: Timestamp | null;
  /** End-to-end encrypted chat: the server holds only ciphertext, so `text` is empty and nothing here is readable. */
  encrypted?: boolean;
}

/** Text the reporter's phone decrypted. The server cannot verify it; reviewers must treat it as the reporter's claim. */
interface ReporterClaim extends ReporterEvidence {
  unverified: true;
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
    const encryptedChat = conv.get('e2ee') === true;
    const reportedDocs = messages.docs.filter((m) => m.get('senderId') === reported && m.get('deleted') !== true);
    const evidence: Evidence[] = reportedDocs
      .slice(0, EVIDENCE_MESSAGES)
      .map((m) => {
        const item: Evidence = {
          messageId: m.id,
          kind: String(m.get('kind') ?? 'text'),
          // Encrypted chats: ciphertext is useless as evidence and is not copied. Only metadata is kept.
          text: m.get('enc') ? '' : String(m.get('text') ?? '').slice(0, 4096),
          createdAt: (m.get('createdAt') as Timestamp | undefined) ?? null,
          ...(m.get('enc') ? { encrypted: true } : {}),
        };
        const path = m.get('media.path');
        if (typeof path === 'string' && !m.get('enc')) item.mediaPath = path;
        return item;
      });
    // Text the reporter chose to attach from an encrypted chat. Only messages that really are the reported
    // person's messages in this chat are kept, so a report cannot attach words to someone who never wrote them.
    const reportedById = new Map(reportedDocs.map((m) => [m.id, m]));
    const claims: ReporterClaim[] = encryptedChat
      ? input.evidence.flatMap((e) => {
          const doc = reportedById.get(e.messageId);
          return doc ? [{ ...e, unverified: true as const, createdAt: (doc.get('createdAt') as Timestamp | undefined) ?? null }] : [];
        })
      : [];

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
      ...(encryptedChat ? { encryptedChat: true, reporterEvidence: claims } : {}),
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
