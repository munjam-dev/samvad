import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

// Phase 3: appeals. appeals/{id} is written only here; a person reads only their own (rules). Staff decide by
// changing status in the console; the app shows the status.

export const APPEAL_KINDS = ['report_decision', 'account_restriction', 'other'] as const;
export type AppealKind = (typeof APPEAL_KINDS)[number];
export const MIN_MESSAGE = 10;
export const MAX_MESSAGE = 1000;
export const DAILY_APPEALS = 3;

export function parseAppeal(data: unknown): { kind: AppealKind; message: string } {
  const d = (data ?? {}) as { kind?: unknown; message?: unknown };
  if (typeof d.kind !== 'string' || !APPEAL_KINDS.includes(d.kind as AppealKind)) {
    throw new HttpsError('invalid-argument', 'Unknown appeal type.');
  }
  const message = typeof d.message === 'string' ? d.message.trim() : '';
  if (message.length < MIN_MESSAGE || message.length > MAX_MESSAGE) {
    throw new HttpsError('invalid-argument', `Write ${MIN_MESSAGE} to ${MAX_MESSAGE} characters.`);
  }
  return { kind: d.kind as AppealKind, message };
}

export async function submitAppealCore(deps: Deps, uid: string, appeal: { kind: AppealKind; message: string }): Promise<{ id: string }> {
  await consumeDailyBudget(deps, uid, 'appeals', DAILY_APPEALS);
  const ref = deps.db.collection('appeals').doc();
  await ref.set({ uid, kind: appeal.kind, message: appeal.message, status: 'open', createdAt: FieldValue.serverTimestamp() });
  return { id: ref.id };
}

export const handleSubmitAppeal = async (r: CallableRequest<unknown>, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const appeal = parseAppeal(r.data);
  const correlationId = newCorrelationId();
  try {
    const res = await submitAppealCore(deps, uid, appeal);
    logEvent('appeal_submitted', { correlationId, outcome: 'ok' });
    return res;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('appeal_submitted', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('appeal_submitted', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const submitAppeal = onCall(buildCallableOptions(process.env), (r) => handleSubmitAppeal(r));
