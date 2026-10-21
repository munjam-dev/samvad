import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth, requireRecentAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';
import { checkPin, pinProblem } from './twoStep';
import { leaveGroupCore } from './groups';
import { leaveAllCommunities } from './communities';
import { releaseUsername } from './username';

/** Idempotent: every step tolerates having already run. */
export async function deleteAccountCore(deps: Deps, uid: string): Promise<void> {
  const { db, auth } = deps;
  await db.doc(`deletedAccounts/${uid}`).set({ deletedAt: FieldValue.serverTimestamp() });
  await releaseUsername(deps, uid); // before the profile goes: the profile says which name to free
  await db.recursiveDelete(db.doc(`users/${uid}`));
  await db.recursiveDelete(db.doc(`profiles/${uid}`)); // includes profiles/{uid}/private/*
  // Phase 3: the account's appeals go with it.
  const appeals = await db.collection('appeals').where('uid', '==', uid).get();
  for (let i = 0; i < appeals.docs.length; i += 400) {
    const batch = db.batch();
    appeals.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  // Phase 3: the account's scheduled messages, so nothing is sent after deletion.
  const scheduled = await db.collection('scheduledMessages').where('senderId', '==', uid).get();
  for (let i = 0; i < scheduled.docs.length; i += 400) {
    const batch = db.batch();
    scheduled.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  // Phase 6: leave every community first (admins hand over, an empty community is closed).
  await leaveAllCommunities(deps, uid);
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
    if (cid) {
      rtdbCleanup[`members/${cid}/${uid}`] = null;
      rtdbCleanup[`engaged/${cid}/${uid}`] = null;
    }
  }
  await deps.rtdb.ref().update(rtdbCleanup);
  // Phase 2: groups need a proper leave, so the member list and admins stay consistent.
  for (const d of memberships.docs) {
    const conv = d.ref.parent.parent;
    if (conv && (await conv.get()).get('type') === 'group') {
      await leaveGroupCore(deps, uid, conv.id).catch(() => undefined);
    }
  }
  for (let i = 0; i < memberships.docs.length; i += 400) {
    const batch = db.batch();
    memberships.docs.slice(i, i + 400).forEach((d) => batch.delete(d.ref));
    await batch.commit();
  }
  await db.doc(`rateLimits/${uid}`).delete();
  await db.doc(`twoStep/${uid}`).delete();
  await deps.deleteFiles(`avatars/${uid}/`);
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
  // With two-step on, the PIN replaces the fresh SMS sign-in: re-authenticating would start a new,
  // unverified session and lock the app halfway through.
  if ((await deps.db.doc(`twoStep/${uid}`).get()).exists) {
    const pin = (request.data as { pin?: unknown } | null)?.pin;
    if (pinProblem(pin) === 'format') throw new HttpsError('failed-precondition', 'pin-required');
    await checkPin(deps, uid, pin as string);
  } else {
    requireRecentAuth(authTimeSeconds, Math.floor(deps.nowMs() / 1000));
  }
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
