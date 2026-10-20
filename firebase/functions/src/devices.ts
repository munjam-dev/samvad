import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

// Phase 3: linked devices. Firebase cannot revoke the refresh token of one install, so signing out a single
// device works like this: its device document is deleted, a server-only marker (users/{uid}/revokedDevices/{id})
// is written, and a data message tells the phone to sign itself out. The rules refuse to register a device whose
// id has a marker, and the app checks the marker on start. A signed-out phone is therefore signed out the next
// time it opens the app or receives the message, not at the instant of the request.

export const DAILY_REVOKES = 20;
const DEVICE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function deviceIdOf(data: unknown): string {
  const id = (data as { deviceId?: unknown } | null)?.deviceId;
  if (typeof id !== 'string' || !DEVICE_ID.test(id)) throw new HttpsError('invalid-argument', 'Unknown device.');
  return id;
}

export async function revokeDeviceCore(deps: Deps, uid: string, deviceId: string): Promise<{ revoked: true; notified: boolean }> {
  await consumeDailyBudget(deps, uid, 'deviceRevokes', DAILY_REVOKES);
  const ref = deps.db.doc(`users/${uid}/devices/${deviceId}`);
  const marker = deps.db.doc(`users/${uid}/revokedDevices/${deviceId}`);
  let token: string | null = null;
  await deps.db.runTransaction(async (tx) => {
    token = null;
    const [device, done] = await Promise.all([tx.get(ref), tx.get(marker)]);
    // The path holds the caller's own uid, so another person's device is just "unknown" here.
    if (!device.exists && !done.exists) throw new HttpsError('not-found', 'Unknown device.');
    const t = device.get('fcmToken');
    token = typeof t === 'string' && t.length > 0 ? t : null;
    if (device.exists) tx.delete(ref);
    if (!done.exists) tx.set(marker, { revokedAt: FieldValue.serverTimestamp() });
  });

  let notified = false;
  if (token) {
    try {
      await deps.push.send([token], { type: 'device_revoked', deviceId });
      notified = true;
    } catch {
      logEvent('device_revoke_push_failed', { outcome: 'error' });
    }
  }
  return { revoked: true, notified };
}

async function run<T>(event: string, work: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await work();
    logEvent(event, { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent(event, { correlationId, outcome: err.code });
      throw err;
    }
    logEvent(event, { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
}

export const handleRevokeDevice = async (r: CallableRequest<unknown>, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const deviceId = deviceIdOf(r.data);
  return run('device_revoked', () => revokeDeviceCore(deps, uid, deviceId));
};

export const revokeDevice = onCall(buildCallableOptions(process.env), (r) => handleRevokeDevice(r));
