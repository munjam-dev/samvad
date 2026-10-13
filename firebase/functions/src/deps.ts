import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';

export interface Deps {
  db: Firestore;
  auth: Auth;
  nowMs: () => number;
}

/**
 * Initialises the default Admin app once. Must run at module load: Firestore triggers decode
 * their event snapshot with the default app before handler code runs.
 * Uses the runtime service account (Application Default Credentials); never a key file.
 */
export function ensureAdminApp(): void {
  if (getApps().length === 0) initializeApp();
}

export function defaultDeps(): Deps {
  ensureAdminApp();
  return { db: getFirestore(), auth: getAuth(), nowMs: () => Date.now() };
}
