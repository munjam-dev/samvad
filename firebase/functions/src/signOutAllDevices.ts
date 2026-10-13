import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions, REVOKE_COOLDOWN_SECONDS } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

const BATCH_LIMIT = 400;

export async function signOutAllDevicesCore(deps: Deps, uid: string): Promise<void> {
  const { db, auth } = deps;
  const userRef = db.doc(`users/${uid}`);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    if (!snap.exists) return; // never create a partial users doc
    const last = snap.get('tokensRevokedAt');
    if (last instanceof Timestamp && deps.nowMs() - last.toMillis() < REVOKE_COOLDOWN_SECONDS * 1000) {
      throw new HttpsError('failed-precondition', 'cooldown');
    }
    tx.update(userRef, { tokensRevokedAt: FieldValue.serverTimestamp() });
  });

  await auth.revokeRefreshTokens(uid);

  const devices = await userRef.collection('devices').get();
  for (let i = 0; i < devices.docs.length; i += BATCH_LIMIT) {
    const batch = db.batch();
    for (const d of devices.docs.slice(i, i + BATCH_LIMIT)) {
      batch.update(d.ref, { revokedAt: FieldValue.serverTimestamp() });
    }
    await batch.commit();
  }
}

export async function handleSignOutAllDevices(
  request: CallableRequest<unknown>,
  deps: Deps = defaultDeps(),
): Promise<{ revoked: true }> {
  const { uid } = requirePhoneAuth(request);
  const correlationId = newCorrelationId();
  try {
    await signOutAllDevicesCore(deps, uid);
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logEvent('sign_out_all_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Sign-out failed. Please retry.');
  }
  logEvent('signed_out_all_devices', { correlationId, outcome: 'ok' });
  return { revoked: true };
}

export const signOutAllDevices = onCall(buildCallableOptions(process.env), (request) =>
  handleSignOutAllDevices(request),
);
