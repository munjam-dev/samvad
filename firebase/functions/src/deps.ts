import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';
import { getDatabaseWithUrl, type Database } from 'firebase-admin/database';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { getStorage } from 'firebase-admin/storage';

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
  /** Deletes every Storage object under a prefix (used when a media message is deleted). */
  deleteFiles: (prefix: string) => Promise<void>;
  /** Writes one Storage object (profile photos are written only by the server). */
  putFile: (path: string, bytes: Buffer, contentType: string) => Promise<void>;
  /** Deletes one Storage object; a missing object is not an error. */
  deleteFile: (path: string) => Promise<void>;
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

/** FCM accepts at most 500 tokens per multicast; a community announcement can have more devices than that. */
export const FCM_MULTICAST_LIMIT = 500;

export function chunkTokens(tokens: string[], size = FCM_MULTICAST_LIMIT): string[][] {
  const chunks: string[][] = [];
  for (let i = 0; i < tokens.length; i += size) chunks.push(tokens.slice(i, i + size));
  return chunks;
}

export const fcmPushSender: PushSender = {
  async send(tokens, data) {
    const invalidTokens: string[] = [];
    for (const batch of chunkTokens(tokens)) {
      const res = await getMessaging().sendEachForMulticast({
        tokens: batch,
        data,
        android: { priority: 'high', collapseKey: data.cid },
      });
      res.responses.forEach((r, i) => {
        if (!r.success && r.error && DEAD_TOKEN_CODES.has(r.error.code)) invalidTokens.push(batch[i]);
      });
    }
    return { invalidTokens };
  },
};

/**
 * The project's default Realtime Database URL (FIREBASE_CONFIG in Functions, derived elsewhere).
 * Against the emulators (functions or tests), FIREBASE_CONFIG may carry the legacy
 * "<project>.firebaseio.com" URL, a different emulator namespace from the default instance that the
 * app and the rules use, so it is ignored there and everyone uses the default instance.
 */
export function databaseUrl(env: NodeJS.ProcessEnv = process.env): string {
  const cfg = env.FIREBASE_CONFIG ? JSON.parse(env.FIREBASE_CONFIG) : {};
  const emulated = env.FUNCTIONS_EMULATOR === 'true' || !!env.FIREBASE_DATABASE_EMULATOR_HOST;
  if (cfg.databaseURL && !emulated) return cfg.databaseURL;
  const project = cfg.projectId ?? env.GCLOUD_PROJECT ?? 'demo-samvad';
  return `https://${project}-default-rtdb.firebaseio.com`;
}

/** The project's default Storage bucket. */
export function bucketName(env: NodeJS.ProcessEnv = process.env): string {
  const cfg = env.FIREBASE_CONFIG ? JSON.parse(env.FIREBASE_CONFIG) : {};
  if (cfg.storageBucket) return cfg.storageBucket;
  const project = cfg.projectId ?? env.GCLOUD_PROJECT ?? 'demo-samvad';
  return `${project}.appspot.com`;
}

export function defaultDeps(): Deps {
  ensureAdminApp();
  return {
    db: getFirestore(),
    auth: getAuth(),
    rtdb: getDatabaseWithUrl(databaseUrl()),
    nowMs: () => Date.now(),
    push: fcmPushSender,
    deleteFiles: async (prefix) => {
      await getStorage().bucket(bucketName()).deleteFiles({ prefix, force: true });
    },
    putFile: async (path, bytes, contentType) => {
      await getStorage().bucket(bucketName()).file(path).save(bytes, { contentType, resumable: false });
    },
    deleteFile: async (path) => {
      await getStorage().bucket(bucketName()).file(path).delete({ ignoreNotFound: true });
    },
  };
}
