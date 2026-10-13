import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth, requireRecentAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

/** Idempotent: every step tolerates having already run. */
export async function deleteAccountCore(deps: Deps, uid: string): Promise<void> {
  const { db, auth } = deps;
  await db.doc(`deletedAccounts/${uid}`).set({ deletedAt: FieldValue.serverTimestamp() });
  await db.recursiveDelete(db.doc(`users/${uid}`));
  await db.doc(`profiles/${uid}`).delete();
  try {
    await auth.deleteUser(uid);
  } catch (err: any) {
    if (err?.code !== 'auth/user-not-found') throw err;
  }
}

export async function handleDeleteAccount(
  request: CallableRequest<unknown>,
  deps: Deps = defaultDeps(),
): Promise<{ deleted: true }> {
  const { uid, authTimeSeconds } = requirePhoneAuth(request);
  requireRecentAuth(authTimeSeconds, Math.floor(deps.nowMs() / 1000));
  const correlationId = newCorrelationId();
  try {
    await deleteAccountCore(deps, uid);
  } catch {
    logEvent('account_delete_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Account deletion failed. Please retry.');
  }
  logEvent('account_deleted', { correlationId, outcome: 'ok' });
  return { deleted: true };
}

export const deleteAccount = onCall(buildCallableOptions(process.env), (request) =>
  handleDeleteAccount(request),
);
