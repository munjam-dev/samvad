import { HttpsError } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import type { Deps } from '../deps';

const WINDOW_MS = 24 * 60 * 60 * 1000;

/**
 * Rolling 24-hour budget per user and action, kept in rateLimits/{uid} as {key}WindowStart/{key}Count.
 * Throws resource-exhausted once [limit] is used up.
 */
export async function consumeDailyBudget(deps: Deps, uid: string, key: string, limit: number, amount = 1): Promise<void> {
  const ref = deps.db.doc(`rateLimits/${uid}`);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = deps.nowMs();
    const start: Timestamp | undefined = snap.get(`${key}WindowStart`);
    const fresh = !start || now - start.toMillis() >= WINDOW_MS;
    const used = fresh ? 0 : Number(snap.get(`${key}Count`) ?? 0);
    if (used + amount > limit) throw new HttpsError('resource-exhausted', 'Daily limit reached. Try again later.');
    tx.set(ref, { [`${key}WindowStart`]: fresh ? Timestamp.fromMillis(now) : start, [`${key}Count`]: used + amount }, { merge: true });
  });
}
