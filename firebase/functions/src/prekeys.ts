import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';

/**
 * End-to-end encryption, key directory (docs/security/E2EE_DESIGN.md).
 *
 * The server stores and hands out PUBLIC keys only: the identity key, one signed pre-key, one "last resort" Kyber
 * pre-key and a stock of one-time pre-keys. Private keys never leave the phone. Everything lives under
 * e2eeKeys/{uid} (and its oneTime subcollection), which the security rules deny to every client; these callables
 * are the only way in or out.
 */

export const MAX_ONE_TIME_PER_UPLOAD = 100;
/** Stored one-time keys per person; a replenishing client uploads 100 when fewer than 25 are left. */
export const MAX_ONE_TIME_STORED = 200;
export const DAILY_PUBLISHES = 30;
export const DAILY_FETCHES = 200;
/** Bundles one person may take of the same other person per day: stops one account draining someone's one-time keys. */
export const DAILY_FETCHES_PER_PAIR = 6;
export const DAILY_UPGRADES = 50;

const WINDOW_MS = 24 * 60 * 60 * 1000;
const UID = /^[A-Za-z0-9]{1,128}$/;
const CID = /^[A-Za-z0-9]{1,128}_[A-Za-z0-9]{1,128}$/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Decoded lengths: X25519 keys with their type byte, XEdDSA signatures, Kyber-1024 public keys with their type byte. */
const LEN = { ec: 33, sig: 64, kyber: 1569 } as const;

export interface SignedKey {
  keyId: number;
  publicKey: string;
  signature: string;
}
export interface OneTimeKey {
  keyId: number;
  publicKey: string;
}
export interface PublishInput {
  registrationId: number;
  identityKey: string;
  signedPreKey: SignedKey;
  kyberPreKey: SignedKey;
  oneTimePreKeys: OneTimeKey[];
}

function badInput(what: string): never {
  throw new HttpsError('invalid-argument', `Invalid ${what}.`);
}

function b64(value: unknown, bytes: number, what: string): string {
  if (typeof value !== 'string' || value.length > 4096 || !BASE64.test(value)) badInput(what);
  if (Buffer.from(value, 'base64').length !== bytes) badInput(what);
  return value;
}

function keyId(value: unknown, what: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 0xffffff) badInput(what);
  return value;
}

function obj(value: unknown, what: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) badInput(what);
  return value as Record<string, unknown>;
}

function signedKey(value: unknown, pubBytes: number, what: string): SignedKey {
  const o = obj(value, what);
  return {
    keyId: keyId(o.keyId, what),
    publicKey: b64(o.publicKey, pubBytes, what),
    signature: b64(o.signature, LEN.sig, what),
  };
}

/** Strict parse of what a phone uploads. The uid is never part of the input: it always comes from the sign-in. */
export function parsePublish(data: unknown): PublishInput {
  const d = obj(data, 'request');
  const reg = d.registrationId;
  if (typeof reg !== 'number' || !Number.isInteger(reg) || reg < 1 || reg > 16380) badInput('registration id');
  const list = d.oneTimePreKeys;
  if (!Array.isArray(list) || list.length > MAX_ONE_TIME_PER_UPLOAD) badInput('one-time keys');
  const seen = new Set<number>();
  const oneTime = list.map((raw) => {
    const o = obj(raw, 'one-time key');
    const id = keyId(o.keyId, 'one-time key');
    if (seen.has(id)) badInput('one-time key');
    seen.add(id);
    return { keyId: id, publicKey: b64(o.publicKey, LEN.ec, 'one-time key') };
  });
  return {
    registrationId: reg,
    identityKey: b64(d.identityKey, LEN.ec, 'identity key'),
    signedPreKey: signedKey(d.signedPreKey, LEN.ec, 'signed pre-key'),
    kyberPreKey: signedKey(d.kyberPreKey, LEN.kyber, 'Kyber pre-key'),
    oneTimePreKeys: oneTime,
  };
}

/**
 * Stores a phone's public keys. A different identity key than the one on file means a new install (new phone, or
 * the app was reset): the old bundle and every old one-time key are replaced, which makes other people's apps
 * notice the change (their sends are refused until they fetch the new bundle, and they warn their user).
 */
export async function publishPreKeysCore(deps: Deps, uid: string, input: PublishInput): Promise<{ oneTimeRemaining: number; identityChanged: boolean }> {
  await consumeDailyBudget(deps, uid, 'prekeyPublish', DAILY_PUBLISHES);
  const main = deps.db.doc(`e2eeKeys/${uid}`);
  const oneTimeCol = main.collection('oneTime');
  return deps.db.runTransaction(async (tx) => {
    const [existing, stored] = await Promise.all([tx.get(main), tx.get(oneTimeCol.limit(MAX_ONE_TIME_STORED + MAX_ONE_TIME_PER_UPLOAD))]);
    const identityChanged = existing.exists && existing.get('identityKey') !== input.identityKey;
    const keep = existing.exists && !identityChanged;
    const storedIds = new Set(keep ? stored.docs.map((d) => d.id) : []);
    if (!keep) stored.docs.forEach((d) => tx.delete(d.ref));
    const added = input.oneTimePreKeys.filter((k) => !storedIds.has(String(k.keyId))).length;
    const total = storedIds.size + added;
    if (total > MAX_ONE_TIME_STORED) throw new HttpsError('resource-exhausted', 'Too many one-time keys on file.');
    for (const k of input.oneTimePreKeys) tx.set(oneTimeCol.doc(String(k.keyId)), { publicKey: k.publicKey });
    const now = Timestamp.fromMillis(deps.nowMs());
    tx.set(main, {
      registrationId: input.registrationId,
      identityKey: input.identityKey,
      signedPreKey: input.signedPreKey,
      kyberPreKey: input.kyberPreKey,
      oneTimeCount: total,
      updatedAt: now,
      // When the identity key last changed (a new install); unchanged by routine top-ups and rotations.
      identityUpdatedAt: keep ? existing.get('identityUpdatedAt') ?? now : now,
    });
    return { oneTimeRemaining: total, identityChanged };
  });
}

export interface Bundle {
  registrationId: number;
  identityKey: string;
  signedPreKey: SignedKey;
  kyberPreKey: SignedKey;
  oneTimePreKey: OneTimeKey | null;
}

/**
 * Hands out one person's public bundle with at most one one-time pre-key, removed from the server in the same
 * transaction so it can never be given out twice. When none are left the bundle is still valid (the key agreement
 * simply has one fewer input). Blocked pairs, missing bundles and deleted accounts all look the same: not-found.
 */
export async function fetchPreKeyBundleCore(deps: Deps, caller: string, target: string): Promise<Bundle> {
  if (target === caller) throw new HttpsError('invalid-argument', 'Choose someone else.');
  await consumeDailyBudget(deps, caller, 'prekeyFetch', DAILY_FETCHES);
  const main = deps.db.doc(`e2eeKeys/${target}`);
  const pairRef = deps.db.doc(`e2eeFetchLimits/${caller}_${target}`);
  return deps.db.runTransaction(async (tx) => {
    const [bundle, a, b, gone, pair, oneTime] = await Promise.all([
      tx.get(main),
      tx.get(deps.db.doc(`users/${caller}/blocked/${target}`)),
      tx.get(deps.db.doc(`users/${target}/blocked/${caller}`)),
      tx.get(deps.db.doc(`deletedAccounts/${target}`)),
      tx.get(pairRef),
      tx.get(main.collection('oneTime').limit(1)),
    ]);
    if (!bundle.exists || a.exists || b.exists || gone.exists) throw new HttpsError('not-found', 'No keys for this person.');
    const now = deps.nowMs();
    const start: Timestamp | undefined = pair.get('windowStart');
    const fresh = !start || now - start.toMillis() >= WINDOW_MS;
    const used = fresh ? 0 : Number(pair.get('count') ?? 0);
    if (used + 1 > DAILY_FETCHES_PER_PAIR) throw new HttpsError('resource-exhausted', 'Daily limit reached. Try again later.');
    tx.set(pairRef, { windowStart: fresh ? Timestamp.fromMillis(now) : start, count: used + 1, expireAt: Timestamp.fromMillis(now + 2 * WINDOW_MS) });

    let oneTimePreKey: OneTimeKey | null = null;
    const first = oneTime.docs[0];
    if (first) {
      oneTimePreKey = { keyId: Number(first.id), publicKey: String(first.get('publicKey')) };
      tx.delete(first.ref);
      tx.update(main, { oneTimeCount: Math.max(0, Number(bundle.get('oneTimeCount') ?? 1) - 1) });
    }
    return {
      registrationId: Number(bundle.get('registrationId')),
      identityKey: String(bundle.get('identityKey')),
      signedPreKey: bundle.get('signedPreKey') as SignedKey,
      kyberPreKey: bundle.get('kyberPreKey') as SignedKey,
      oneTimePreKey,
    };
    // Several people can ask for the same bundle at once; each retry sees the keys the winners removed.
  }, { maxAttempts: 15 });
}

/** What the caller's own bundle looks like on the server, so the app can top up one-time keys or republish. */
export async function preKeyStatusCore(deps: Deps, uid: string): Promise<{ published: boolean; oneTimeRemaining: number; identityKey: string | null; registrationId: number | null }> {
  const snap = await deps.db.doc(`e2eeKeys/${uid}`).get();
  if (!snap.exists) return { published: false, oneTimeRemaining: 0, identityKey: null, registrationId: null };
  return {
    published: true,
    oneTimeRemaining: Number(snap.get('oneTimeCount') ?? 0),
    identityKey: String(snap.get('identityKey')),
    registrationId: Number(snap.get('registrationId')),
  };
}

/**
 * Turns on end-to-end encryption for an existing direct chat. Only NEW messages are protected; history stays as it
 * is. Both people must have published keys. Irreversible: the chat refuses plaintext from then on.
 */
export async function upgradeConversationCore(deps: Deps, uid: string, cid: string): Promise<{ e2ee: true }> {
  await consumeDailyBudget(deps, uid, 'e2eeUpgrade', DAILY_UPGRADES);
  const ref = deps.db.doc(`conversations/${cid}`);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const members: string[] = snap.exists ? snap.get('members') ?? [] : [];
    if (!snap.exists || snap.get('type') !== 'direct' || !members.includes(uid)) throw new HttpsError('permission-denied', 'Not allowed.');
    if (snap.get('e2ee') === true) return;
    const [a, b] = members;
    const [ka, kb, ab, ba] = await Promise.all([
      tx.get(deps.db.doc(`e2eeKeys/${a}`)),
      tx.get(deps.db.doc(`e2eeKeys/${b}`)),
      tx.get(deps.db.doc(`users/${a}/blocked/${b}`)),
      tx.get(deps.db.doc(`users/${b}/blocked/${a}`)),
    ]);
    if (ab.exists || ba.exists) throw new HttpsError('permission-denied', 'Not allowed.');
    if (!ka.exists || !kb.exists) throw new HttpsError('failed-precondition', 'keys-missing');
    tx.update(ref, { e2ee: true, e2eeSince: Timestamp.fromMillis(deps.nowMs()) });
  });
  return { e2ee: true };
}

type Req = CallableRequest<unknown>;

async function guarded<T>(event: string, run: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await run();
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

export const handlePublishPreKeys = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const input = parsePublish(r.data);
  return guarded('prekeys_published', () => publishPreKeysCore(deps, uid, input));
};

export const handleFetchPreKeyBundle = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const target = (r.data as { uid?: unknown } | null)?.uid;
  if (typeof target !== 'string' || !UID.test(target)) throw new HttpsError('invalid-argument', 'Unknown person.');
  return guarded('prekey_bundle_fetched', () => fetchPreKeyBundleCore(deps, uid, target));
};

export const handlePreKeyStatus = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  return guarded('prekey_status', () => preKeyStatusCore(deps, uid));
};

export const handleUpgradeConversation = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = (r.data as { conversationId?: unknown } | null)?.conversationId;
  if (typeof cid !== 'string' || !CID.test(cid)) throw new HttpsError('invalid-argument', 'Unknown chat.');
  return guarded('e2ee_upgrade', () => upgradeConversationCore(deps, uid, cid));
};

export const publishPreKeys = onCall(buildCallableOptions(process.env), (r) => handlePublishPreKeys(r));
export const fetchPreKeyBundle = onCall(buildCallableOptions(process.env), (r) => handleFetchPreKeyBundle(r));
export const preKeyStatus = onCall(buildCallableOptions(process.env), (r) => handlePreKeyStatus(r));
export const upgradeConversationE2ee = onCall(buildCallableOptions(process.env), (r) => handleUpgradeConversation(r));
