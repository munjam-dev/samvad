import { getApps, initializeApp } from 'firebase-admin/app';
import { getAuth, type Auth } from 'firebase-admin/auth';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';

export interface Deps {
  db: Firestore;
  auth: Auth;
  nowMs: () => number;
}

/** Uses the runtime service account (Application Default Credentials); never a key file. */
export function defaultDeps(): Deps {
  if (getApps().length === 0) initializeApp();
  return { db: getFirestore(), auth: getAuth(), nowMs: () => Date.now() };
}
