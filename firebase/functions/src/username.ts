import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';

export const MIN_LENGTH = 3;
export const MAX_LENGTH = 20;
/** Changes of username per day, and availability checks per day, for one account. */
export const DAILY_CHANGES = 10;
export const DAILY_CHECKS = 120;

/** Words nobody may take, so no account can pass as the app or its staff. */
export const RESERVED = new Set([
  'admin', 'administrator', 'samvad', 'support', 'help', 'official', 'staff', 'team', 'moderator', 'mod', 'root',
  'system', 'security', 'safety', 'privacy', 'legal', 'contact', 'info', 'service', 'noreply', 'billing',
  'null', 'undefined', 'me', 'you', 'everyone', 'all', 'here', 'status', 'moments', 'settings', 'api', 'www',
]);

export type UsernameProblem = 'too-short' | 'too-long' | 'bad-start' | 'bad-chars' | 'underscores' | 'reserved';

/** Lower-case, without a leading "@" or surrounding spaces. Usernames are not case sensitive. */
export function normalizeUsername(raw: unknown): string {
  return typeof raw === 'string' ? raw.trim().replace(/^@/, '').toLowerCase() : '';
}

/** The format rules. Kept identical to the app's `Usernames` in :core. Returns null when the name is acceptable. */
export function usernameProblem(name: string): UsernameProblem | null {
  if (name.length < MIN_LENGTH) return 'too-short';
  if (name.length > MAX_LENGTH) return 'too-long';
  if (!/^[a-z]/.test(name)) return 'bad-start';
  if (!/^[a-z0-9_]+$/.test(name)) return 'bad-chars';
  if (name.includes('__') || name.endsWith('_')) return 'underscores';
  if (RESERVED.has(name)) return 'reserved';
  return null;
}

function requireValid(raw: unknown): string {
  const name = normalizeUsername(raw);
  const problem = usernameProblem(name);
  if (problem) throw new HttpsError('invalid-argument', problem);
  return name;
}

/**
 * Gives the account this username, or removes it when [raw] is empty. The reservation document and the profile
 * change in one transaction, so two people asking for the same name at the same moment cannot both get it, and
 * the old name is freed in the same step.
 */
export async function setUsernameCore(deps: Deps, uid: string, raw: unknown): Promise<{ username: string | null }> {
  const clearing = normalizeUsername(raw) === '';
  const name = clearing ? null : requireValid(raw);
  await consumeDailyBudget(deps, uid, 'username', DAILY_CHANGES);
  await deps.db.runTransaction(async (tx) => {
    const profileRef = deps.db.doc(`profiles/${uid}`);
    const profile = await tx.get(profileRef);
    if (!profile.exists) throw new HttpsError('failed-precondition', 'profile-required');
    const current: string | null = profile.get('username') ?? null;
    if (name === current) return;
    let taken = false;
    if (name) {
      const target = await tx.get(deps.db.doc(`usernames/${name}`));
      taken = target.exists && target.get('uid') !== uid;
    }
    if (taken) throw new HttpsError('already-exists', 'username-taken');
    if (current) tx.delete(deps.db.doc(`usernames/${current}`));
    if (name) tx.set(deps.db.doc(`usernames/${name}`), { uid, createdAt: FieldValue.serverTimestamp() });
    tx.update(profileRef, { username: name ?? FieldValue.delete() });
  });
  return { username: name };
}

/** Whether a name can be taken right now. A name the caller already owns counts as available. */
export async function checkUsernameCore(deps: Deps, uid: string, raw: unknown): Promise<{ available: boolean; reason?: UsernameProblem | 'taken' }> {
  await consumeDailyBudget(deps, uid, 'usernameCheck', DAILY_CHECKS);
  const name = normalizeUsername(raw);
  const problem = usernameProblem(name);
  if (problem) return { available: false, reason: problem };
  const snap = await deps.db.doc(`usernames/${name}`).get();
  if (snap.exists && snap.get('uid') !== uid) return { available: false, reason: 'taken' };
  return { available: true };
}

/** Called when an account is deleted: frees the name. Safe to run twice. */
export async function releaseUsername(deps: Deps, uid: string): Promise<void> {
  const profile = await deps.db.doc(`profiles/${uid}`).get();
  const name = profile.get('username') as string | undefined;
  if (!name) return;
  const ref = deps.db.doc(`usernames/${name}`);
  const snap = await ref.get();
  if (snap.exists && snap.get('uid') === uid) await ref.delete();
}

type Req = CallableRequest<unknown>;

async function run<T>(event: string, work: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await work();
    logEvent(event, { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent(event, { correlationId, outcome: err.code });
      throw err;
    }
    logEvent(event, { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
}

export const handleSetUsername = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { username?: unknown };
  return run('username_set', () => setUsernameCore(deps, uid, d.username));
};

export const handleCheckUsername = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { username?: unknown };
  return run('username_checked', () => checkUsernameCore(deps, uid, d.username));
};

export const setUsername = onCall(buildCallableOptions(process.env), (r) => handleSetUsername(r));
export const checkUsername = onCall(buildCallableOptions(process.env), (r) => handleCheckUsername(r));
