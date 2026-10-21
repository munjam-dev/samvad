import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';
import { parsePrivacy, privacyPath } from './lib/privacy';

export const MAX_NUMBERS_PER_CALL = 50;
export const DAILY_LOOKUP_LIMIT = 500;
const WINDOW_MS = 24 * 60 * 60 * 1000;
const E164 = /^\+[1-9][0-9]{6,14}$/;

export function parseNumbers(data: unknown): string[] {
  const raw = (data as { phoneNumbers?: unknown })?.phoneNumbers;
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > MAX_NUMBERS_PER_CALL) {
    throw new HttpsError('invalid-argument', `Send 1 to ${MAX_NUMBERS_PER_CALL} phone numbers.`);
  }
  if (!raw.every((n) => typeof n === 'string' && E164.test(n))) {
    throw new HttpsError('invalid-argument', 'Phone numbers must be in E.164 format.');
  }
  return [...new Set(raw as string[])];
}

/** Rolling 24-hour budget per caller, stored server-side in rateLimits/{uid}. */
async function consumeBudget(deps: Deps, uid: string, count: number): Promise<void> {
  const ref = deps.db.doc(`rateLimits/${uid}`);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = deps.nowMs();
    const windowStart: Timestamp | undefined = snap.get('lookupWindowStart');
    const fresh = !windowStart || now - windowStart.toMillis() >= WINDOW_MS;
    const used = fresh ? 0 : Number(snap.get('lookupCount') ?? 0);
    if (used + count > DAILY_LOOKUP_LIMIT) {
      throw new HttpsError('resource-exhausted', 'Lookup limit reached. Try again later.');
    }
    tx.set(
      ref,
      {
        lookupWindowStart: fresh ? Timestamp.fromMillis(now) : windowStart,
        lookupCount: used + count,
      },
      { merge: true },
    );
  });
}

export async function lookupCore(
  deps: Deps,
  uid: string,
  numbers: string[],
): Promise<Array<{ phoneE164: string; uid: string }>> {
  await consumeBudget(deps, uid, numbers.length);
  const found: Array<{ phoneE164: string; uid: string }> = [];
  for (let i = 0; i < numbers.length; i += 30) {
    const chunk = numbers.slice(i, i + 30);
    const snap = await deps.db.collection('users').where('phoneE164', 'in', chunk).get();
    const others = snap.docs.filter((d) => d.id !== uid);
    if (others.length === 0) continue;
    // Phase 3: people who switched off "Let people find me by my phone number" are never returned.
    const settings = await deps.db.getAll(...others.map((d) => deps.db.doc(privacyPath(d.id))));
    others.forEach((d, i) => {
      if (parsePrivacy(settings[i].data()).phoneDiscovery) found.push({ phoneE164: d.get('phoneE164'), uid: d.id });
    });
  }
  return found;
}

export async function handleLookupPhoneNumbers(
  request: CallableRequest<unknown>,
  deps: Deps = defaultDeps(),
): Promise<{ matches: Array<{ phoneE164: string; uid: string }> }> {
  const { uid } = requirePhoneAuth(request);
  const numbers = parseNumbers(request.data);
  const correlationId = newCorrelationId();
  try {
    const matches = await lookupCore(deps, uid, numbers);
    logEvent('phone_lookup', { correlationId, count: numbers.length, outcome: 'ok' });
    return { matches };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logEvent('phone_lookup_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Lookup failed. Please retry.');
  }
}

export const lookupPhoneNumbers = onCall(buildCallableOptions(process.env), (request) =>
  handleLookupPhoneNumbers(request),
);
