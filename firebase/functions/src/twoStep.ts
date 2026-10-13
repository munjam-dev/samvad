import { randomBytes, scrypt as scryptCb, timingSafeEqual, type ScryptOptions } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';

// Phase 1.5: two-step verification. See docs/superpowers/specs/2026-10-13-phase1-two-step-verification.md.

export const FREE_ATTEMPTS = 5;
export const MAX_LOCK_MS = 24 * 60 * 60 * 1000;
export const RESET_WAIT_MS = 7 * 24 * 60 * 60 * 1000;
const SCRYPT: ScryptOptions = { N: 1 << 15, r: 8, p: 1, maxmem: 64 * 1024 * 1024 };
const KEY_BYTES = 32;

function scrypt(pin: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    scryptCb(pin, salt, KEY_BYTES, SCRYPT, (err, key) => (err ? reject(err) : resolve(key))),
  );
}

/** Exactly 6 digits, not one repeated digit, not a +1/-1 run such as 123456 or 987654. */
export function pinProblem(pin: unknown): 'format' | 'too-simple' | null {
  if (typeof pin !== 'string' || !/^[0-9]{6}$/.test(pin)) return 'format';
  const d = [...pin].map(Number);
  const steps = d.slice(1).map((v, i) => v - d[i]);
  if (steps.every((s) => s === 0) || steps.every((s) => s === 1) || steps.every((s) => s === -1)) return 'too-simple';
  return null;
}

function readPin(data: unknown, validateStrength: boolean): string {
  const pin = (data as { pin?: unknown } | null)?.pin;
  const problem = pinProblem(pin);
  if (problem === 'format') throw new HttpsError('invalid-argument', 'PIN must be 6 digits.');
  if (problem === 'too-simple' && validateStrength) throw new HttpsError('invalid-argument', 'pin-too-simple');
  return pin as string;
}

/** Lock after the free attempts: 1, 2, 4 … minutes, capped at 24 h. */
export function lockMsAfter(failedAttempts: number): number {
  if (failedAttempts < FREE_ATTEMPTS) return 0;
  const exp = Math.min(failedAttempts - FREE_ATTEMPTS, 20);
  return Math.min(60_000 * 2 ** exp, MAX_LOCK_MS);
}

async function setClaims(deps: Deps, uid: string, claims: { ts?: true; tsv?: string }): Promise<void> {
  const user = await deps.auth.getUser(uid);
  const next: Record<string, unknown> = { ...(user.customClaims ?? {}) };
  delete next.ts;
  delete next.tsv;
  await deps.auth.setCustomUserClaims(uid, { ...next, ...claims });
}

const docRef = (deps: Deps, uid: string) => deps.db.doc(`twoStep/${uid}`);

/**
 * Makes a new verified session: a random nonce becomes the account claim tsv and the per-session
 * claim pv of a custom token the app signs in with. Any previously verified session (another phone)
 * stops matching. The nonce is not a secret on its own: only this function can mint a token with it.
 */
async function newVerifiedSession(deps: Deps, uid: string): Promise<string> {
  const nonce = randomBytes(18).toString('base64url');
  await setClaims(deps, uid, { ts: true, tsv: nonce });
  return deps.auth.createCustomToken(uid, { pv: nonce });
}

export async function setPinCore(deps: Deps, uid: string, pin: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await scrypt(pin, salt);
  await docRef(deps, uid).set({
    hash: hash.toString('base64'),
    salt: salt.toString('base64'),
    failedAttempts: 0,
    lockedUntil: null,
    resetRequestedAt: null,
    updatedAt: FieldValue.serverTimestamp(),
  });
  return newVerifiedSession(deps, uid);
}

/**
 * Checks [pin] against the stored hash with the lockout. Each attempt is counted as a failure in a
 * transaction *before* hashing, so parallel guesses cannot slip past the lock; a correct PIN then
 * clears the count. Throws resource-exhausted while locked and permission-denied (details:
 * attemptsLeft, lockedUntilMs) on a wrong PIN. Returns false when two-step is not enabled.
 */
export async function checkPin(deps: Deps, uid: string, pin: string): Promise<boolean> {
  const ref = docRef(deps, uid);
  const now = deps.nowMs();
  const attempt = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return null;
    const lockedUntil = (snap.get('lockedUntil') as Timestamp | null)?.toMillis() ?? 0;
    if (lockedUntil > now) return { lockedUntil };
    const failed = Number(snap.get('failedAttempts') ?? 0) + 1;
    const lockMs = lockMsAfter(failed);
    tx.update(ref, { failedAttempts: failed, lockedUntil: lockMs > 0 ? Timestamp.fromMillis(now + lockMs) : null });
    return { hash: String(snap.get('hash')), salt: String(snap.get('salt')), failed, lockMs };
  });
  if (attempt === null) return false;
  if ('lockedUntil' in attempt) {
    throw new HttpsError('resource-exhausted', 'pin-locked', { lockedUntilMs: attempt.lockedUntil });
  }
  const expected = Buffer.from(attempt.hash, 'base64');
  const actual = await scrypt(pin, Buffer.from(attempt.salt, 'base64'));
  if (expected.length === actual.length && timingSafeEqual(expected, actual)) {
    await ref.update({ failedAttempts: 0, lockedUntil: null, resetRequestedAt: null });
    return true;
  }
  throw new HttpsError('permission-denied', 'wrong-pin', {
    attemptsLeft: Math.max(FREE_ATTEMPTS - attempt.failed, 0),
    lockedUntilMs: attempt.lockMs > 0 ? now + attempt.lockMs : null,
  });
}

/**
 * For PIN management, check against server state rather than claims alone: a token minted before
 * two-step was turned on has no ts claim for up to an hour, but must not be able to change the PIN.
 */
async function requireVerifiedAgainstServer(deps: Deps, uid: string, token: Record<string, any>): Promise<void> {
  const enabled = (await docRef(deps, uid).get()).exists;
  if (enabled && !(token.ts === true && typeof token.pv === 'string' && token.pv === token.tsv)) {
    throw new HttpsError('permission-denied', 'two-step-required');
  }
}

/** Returns a custom token for a new verified session, or null when two-step is off (claims cleared). */
export async function verifyCore(deps: Deps, uid: string, pin: string): Promise<string | null> {
  if (await checkPin(deps, uid, pin)) return newVerifiedSession(deps, uid);
  await setClaims(deps, uid, {});
  return null;
}

export async function disableCore(deps: Deps, uid: string, pin: string): Promise<void> {
  if (await checkPin(deps, uid, pin)) await docRef(deps, uid).delete();
  await setClaims(deps, uid, {});
}

export interface TwoStepStatus {
  enabled: boolean;
  verified: boolean;
  lockedUntilMs: number | null;
  resetAvailableAtMs: number | null;
}

export async function statusCore(deps: Deps, uid: string, verified: boolean): Promise<TwoStepStatus> {
  const snap = await docRef(deps, uid).get();
  if (!snap.exists) return { enabled: false, verified: true, lockedUntilMs: null, resetAvailableAtMs: null };
  const lockedUntil = (snap.get('lockedUntil') as Timestamp | null)?.toMillis() ?? null;
  const requested = (snap.get('resetRequestedAt') as Timestamp | null)?.toMillis() ?? null;
  return {
    enabled: true,
    verified,
    lockedUntilMs: lockedUntil && lockedUntil > deps.nowMs() ? lockedUntil : null,
    resetAvailableAtMs: requested === null ? null : requested + RESET_WAIT_MS,
  };
}

/** Tokens of the owner's registered (verified) devices. */
async function ownerTokens(deps: Deps, uid: string): Promise<string[]> {
  const devices = await deps.db.collection(`users/${uid}/devices`).where('fcmToken', '!=', null).get();
  return devices.docs
    .filter((d) => !d.get('revokedAt') && typeof d.get('fcmToken') === 'string')
    .map((d) => d.get('fcmToken') as string);
}

export async function requestResetCore(deps: Deps, uid: string): Promise<number | null> {
  const ref = docRef(deps, uid);
  const now = deps.nowMs();
  const result = await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) return { requestedAt: null as number | null, isNew: false };
    const existing = (snap.get('resetRequestedAt') as Timestamp | null)?.toMillis();
    if (existing) return { requestedAt: existing, isNew: false };
    tx.update(ref, { resetRequestedAt: Timestamp.fromMillis(now) });
    return { requestedAt: now, isNew: true };
  });
  if (result.requestedAt === null) return null;
  if (result.isNew) {
    // Best effort: a failed alert must not block the legitimate owner's reset.
    try {
      const tokens = await ownerTokens(deps, uid);
      if (tokens.length > 0) await deps.push.send(tokens, { type: 'security', event: 'two_step_reset_requested' });
    } catch {
      logEvent('security_alert_failed', { outcome: 'error' });
    }
  }
  return result.requestedAt + RESET_WAIT_MS;
}

/** Whether the owner's devices showed activity (presence) at or after [sinceMs]. */
async function ownerActiveSince(deps: Deps, uid: string, sinceMs: number): Promise<boolean> {
  const presence = (await deps.rtdb.ref(`presence/${uid}`).get()).val() as { online?: boolean; lastSeen?: number } | null;
  if (!presence) return false;
  return presence.online === true || Number(presence.lastSeen ?? 0) >= sinceMs;
}

export async function completeResetCore(deps: Deps, uid: string): Promise<void> {
  const ref = docRef(deps, uid);
  const snap = await ref.get();
  if (!snap.exists) {
    await setClaims(deps, uid, {});
    return;
  }
  const requested = (snap.get('resetRequestedAt') as Timestamp | null)?.toMillis();
  if (!requested) throw new HttpsError('failed-precondition', 'reset-not-requested');
  if (deps.nowMs() < requested + RESET_WAIT_MS) {
    throw new HttpsError('failed-precondition', 'reset-not-ready', { resetAvailableAtMs: requested + RESET_WAIT_MS });
  }
  if (await ownerActiveSince(deps, uid, requested)) {
    await ref.update({ resetRequestedAt: null });
    throw new HttpsError('failed-precondition', 'reset-cancelled');
  }
  await ref.delete();
  await setClaims(deps, uid, {});
}

// ---- handlers -----------------------------------------------------------------------------------

async function run<T>(event: string, work: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await work();
    logEvent(event, { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent(event, { correlationId, outcome: err.message === 'wrong-pin' ? 'wrong_pin' : err.code });
      throw err;
    }
    logEvent(event, { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
}

export async function handleSetTwoStepPin(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid } = requirePhoneAuth(request);
  const pin = readPin(request.data, true);
  return run('two_step_set', async () => {
    await requireVerifiedAgainstServer(deps, uid, request.auth!.token);
    // The caller signs in with this token; its phone session no longer counts once two-step is on.
    return { enabled: true, token: await setPinCore(deps, uid, pin) };
  });
}

export async function handleVerifyTwoStepPin(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid } = requirePhoneAuth(request, { allowUnverified: true });
  const pin = readPin(request.data, false);
  return run('two_step_verify', async () => ({ verified: true, token: await verifyCore(deps, uid, pin) }));
}

export async function handleDisableTwoStep(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid } = requirePhoneAuth(request);
  const pin = readPin(request.data, false);
  return run('two_step_disable', async () => {
    await requireVerifiedAgainstServer(deps, uid, request.auth!.token);
    await disableCore(deps, uid, pin);
    return { enabled: false };
  });
}

export async function handleTwoStepStatus(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid, verified } = requirePhoneAuth(request, { allowUnverified: true });
  return run('two_step_status', () => statusCore(deps, uid, verified));
}

export async function handleRequestTwoStepReset(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid } = requirePhoneAuth(request, { allowUnverified: true });
  return run('two_step_reset_requested', async () => ({ resetAvailableAtMs: await requestResetCore(deps, uid) }));
}

export async function handleCompleteTwoStepReset(request: CallableRequest<unknown>, deps: Deps = defaultDeps()) {
  const { uid } = requirePhoneAuth(request, { allowUnverified: true });
  return run('two_step_reset', async () => {
    await completeResetCore(deps, uid);
    return { enabled: false };
  });
}

const options = buildCallableOptions(process.env);
export const setTwoStepPin = onCall(options, (r) => handleSetTwoStepPin(r));
export const verifyTwoStepPin = onCall(options, (r) => handleVerifyTwoStepPin(r));
export const disableTwoStep = onCall(options, (r) => handleDisableTwoStep(r));
export const twoStepStatus = onCall(options, (r) => handleTwoStepStatus(r));
export const requestTwoStepReset = onCall(options, (r) => handleRequestTwoStepReset(r));
export const completeTwoStepReset = onCall(options, (r) => handleCompleteTwoStepReset(r));
