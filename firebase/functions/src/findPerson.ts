import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';
import { directCid, isEngagedPartner, parsePrivacy, privacyPath } from './lib/privacy';
import { normalizeUsername, usernameProblem } from './username';

/** Exact lookups of one person per call (typed phone number or username), per caller per rolling day. */
export const DAILY_FIND_LIMIT = 60;
const E164 = /^\+[1-9][0-9]{6,14}$/;

export type FindPersonResult =
  | { found: false }
  | { found: true; uid: string; displayName: string; photoPath: string | null };

/**
 * Finds exactly one person by an exact phone number or an exact username. This is not a directory: it answers only for
 * the one value sent, never lists or prefix-matches, and people who turned off phone discovery are not found by number.
 * The photo follows the owner's "Profile photo" setting.
 */
export async function findPersonCore(deps: Deps, uid: string, data: unknown): Promise<FindPersonResult> {
  const d = (data ?? {}) as { phoneE164?: unknown; username?: unknown };
  const hasPhone = d.phoneE164 !== undefined && d.phoneE164 !== null;
  const hasName = d.username !== undefined && d.username !== null;
  if (hasPhone === hasName) throw new HttpsError('invalid-argument', 'Send a phone number or a username.');

  let target: string | null = null;
  const byPhone = hasPhone;
  if (hasPhone) {
    if (typeof d.phoneE164 !== 'string' || !E164.test(d.phoneE164)) {
      throw new HttpsError('invalid-argument', 'Phone numbers must be in E.164 format.');
    }
    await consumeDailyBudget(deps, uid, 'findPerson', DAILY_FIND_LIMIT);
    const snap = await deps.db.collection('users').where('phoneE164', '==', d.phoneE164).limit(1).get();
    target = snap.docs[0]?.id ?? null;
  } else {
    const name = normalizeUsername(d.username);
    if (usernameProblem(name)) throw new HttpsError('invalid-argument', 'Not a valid username.');
    await consumeDailyBudget(deps, uid, 'findPerson', DAILY_FIND_LIMIT);
    target = (await deps.db.doc(`usernames/${name}`).get()).get('uid') ?? null;
  }
  if (!target || target === uid) return { found: false };

  const [profile, settingsSnap] = await Promise.all([
    deps.db.doc(`profiles/${target}`).get(),
    deps.db.doc(privacyPath(target)).get(),
  ]);
  const settings = parsePrivacy(settingsSnap.data());
  if (!profile.exists) return { found: false };
  if (byPhone && !settings.phoneDiscovery) return { found: false };

  let mayShowPhoto = settings.photoVisibility === 'everyone';
  if (settings.photoVisibility === 'partners') {
    const conv = await deps.db.doc(`conversations/${directCid(uid, target)}`).get();
    mayShowPhoto = isEngagedPartner(conv.data(), target);
  }
  let photoPath: string | null = null;
  if (mayShowPhoto) {
    const photo = await deps.db.doc(`profiles/${target}/private/photo`).get();
    photoPath = (photo.get('photoUrl') as string | undefined) ?? null;
  }
  return { found: true, uid: target, displayName: (profile.get('displayName') as string) ?? '', photoPath };
}

export async function handleFindPerson(request: CallableRequest<unknown>, deps: Deps = defaultDeps()): Promise<FindPersonResult> {
  const { uid } = requirePhoneAuth(request);
  const correlationId = newCorrelationId();
  try {
    const result = await findPersonCore(deps, uid, request.data);
    logEvent('find_person', { correlationId, outcome: result.found ? 'found' : 'none' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logEvent('find_person_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Lookup failed. Please retry.');
  }
}

export const findPerson = onCall(buildCallableOptions(process.env), (request) => handleFindPerson(request));
