import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';

/** Sends one data message to many FCM tokens; reports tokens FCM says are dead. */
export interface PushSender {
  send(tokens: string[], data: Record<string, string>): Promise<{ invalidTokens: string[] }>;
}

export interface Deps {
  db: Firestore;
  auth: Auth;
  nowMs: () => number;
  push: PushSender;
}

/**
 * Initialises the default Admin app once. Must run at module load: Firestore triggers decode
 * their event snapshot with the default app before handler code runs.
 * Uses the runtime service account (Application Default Credentials); never a key file.
 */
export function ensureAdminApp(): void {
  if (getApps().length === 0) initializeApp();
}

const DEAD_TOKEN_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

export const fcmPushSender: PushSender = {
  async send(tokens, data) {
    if (tokens.length === 0) return { invalidTokens: [] };
    const res = await getMessaging().sendEachForMulticast({
      tokens,
      data,
      android: { priority: 'high', collapseKey: data.cid },
    });
    const invalidTokens = res.responses
      .map((r, i) => (!r.success && r.error && DEAD_TOKEN_CODES.has(r.error.code) ? tokens[i] : null))
      .filter((t): t is string => t !== null);
    return { invalidTokens };
  },
};

export function defaultDeps(): Deps {
  ensureAdminApp();
  return { db: getFirestore(), auth: getAuth(), nowMs: () => Date.now(), push: fcmPushSender };
}
