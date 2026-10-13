import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';
import { getDatabaseWithUrl, type Database } from 'firebase-admin/database';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';

/** Sends one data message to many FCM tokens; reports tokens FCM says are dead. */
export interface PushSender {
  send(tokens: string[], data: Record<string, string>): Promise<{ invalidTokens: string[] }>;
}

export interface Deps {
  db: Firestore;
  auth: Auth;
  rtdb: Database;
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

/** The project's default Realtime Database URL (FIREBASE_CONFIG in Functions, derived elsewhere). */
export function databaseUrl(env: NodeJS.ProcessEnv = process.env): string {
  const cfg = env.FIREBASE_CONFIG ? JSON.parse(env.FIREBASE_CONFIG) : {};
  if (cfg.databaseURL) return cfg.databaseURL;
  const project = cfg.projectId ?? env.GCLOUD_PROJECT ?? 'demo-samvad';
  return `https://${project}-default-rtdb.firebaseio.com`;
}

export function defaultDeps(): Deps {
  ensureAdminApp();
  return {
    db: getFirestore(),
    auth: getAuth(),
    rtdb: getDatabaseWithUrl(databaseUrl()),
    nowMs: () => Date.now(),
    push: fcmPushSender,
  };
}
