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
  // B2: leave every conversation (sent messages stay so the other member's history is intact).
  const memberships = await db.collectionGroup('members').where('uid', '==', uid).get();
  // Phase 1.1/1.3: clear presence, privacy, the block mirror and the RTDB membership mirror for this user.
  const rtdbCleanup: Record<string, null> = {
    [`presence/${uid}`]: null,
    [`privacy/${uid}`]: null,
    [`blocks/${uid}`]: null,
  };
  for (const d of memberships.docs) {
    const cid = d.ref.parent.parent?.id;
    if (cid) rtdbCleanup[`members/${cid}/${uid}`] = null;
  }
  await deps.rtdb.ref().update(rtdbCleanup);
  for (let i = 0; i < memberships.docs.length; i += 400) {
    const batch = db.batch();
    memberships.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  await db.doc(`rateLimits/${uid}`).delete();
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
