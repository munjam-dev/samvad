import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { isVerifiedSession, requirePhoneAuth } from '../src/lib/guards';
import {
  FREE_ATTEMPTS,
  handleCompleteTwoStepReset,
  handleDisableTwoStep,
  handleRequestTwoStepReset,
  handleSetTwoStepPin,
  handleTwoStepStatus,
  handleVerifyTwoStepPin,
  lockMsAfter,
  MAX_LOCK_MS,
  pinProblem,
  RESET_WAIT_MS,
} from '../src/twoStep';
import { handleDeleteAccount } from '../src/deleteAccount';
import { handleLookupPhoneNumbers } from '../src/lookupPhoneNumbers';
import { handleReportContact } from '../src/reportContact';
import { handleSignOutAllDevices } from '../src/signOutAllDevices';
import { fakePush, testDeps, phoneRequest } from '../../test-support/deps';
import {
  callCallable,
  decodeClaims,
  refreshIdToken,
  resetEmulators,
  signInWithCustomToken,
  signInWithPhone,
} from '../../test-support/auth';

const deps = testDeps();
const UID = 'meera';
const errorOf = async (p: Promise<unknown>) =>
  p.then(
    () => ({ code: 'ok', message: '', details: undefined as any }),
    (e: any) => ({ code: e.code, message: e.message, details: e.details }),
  );

const claims = async () => (await deps.auth.getUser(UID)).customClaims ?? {};
const record = async () => deps.db.doc(`twoStep/${UID}`).get();

/** A plain SMS sign-in. With two-step on, the account claims ride along on every session. */
const phone = async (data: unknown = {}) => phoneRequest(UID, { data, claims: await claims() });
/** A session created from a PIN check, carrying per-session claim pv. */
const pinSession = async (pv: string, data: unknown = {}) =>
  phoneRequest(UID, { provider: 'custom', data, claims: { ...(await claims()), pv } });
/** The currently verified session (pv == tsv). */
const verified = async (data: unknown = {}) => pinSession(String((await claims()).tsv), data);

async function enable(pin = '482915') {
  await deps.auth.createUser({ uid: UID }).catch(() => undefined);
  return handleSetTwoStepPin(await phone({ pin }), deps);
}

beforeEach(resetEmulators);

describe('PIN rules and session checks', () => {
  it('accepts 6 digits that are not trivial', () => {
    for (const ok of ['482915', '120000', '135790', '111112']) expect(pinProblem(ok)).toBeNull();
    for (const bad of ['12345', '1234567', '12a456', '', null, 123456]) expect(pinProblem(bad)).toBe('format');
    for (const simple of ['000000', '999999', '123456', '345678', '987654', '654321']) expect(pinProblem(simple)).toBe('too-simple');
  });

  it('locks after five free attempts, doubling from one minute up to a day', () => {
    expect([1, 2, 3, 4].map(lockMsAfter)).toEqual([0, 0, 0, 0]);
    expect(lockMsAfter(FREE_ATTEMPTS)).toBe(60_000);
    expect(lockMsAfter(FREE_ATTEMPTS + 1)).toBe(120_000);
    expect(lockMsAfter(FREE_ATTEMPTS + 3)).toBe(480_000);
    expect(lockMsAfter(FREE_ATTEMPTS + 30)).toBe(MAX_LOCK_MS);
  });

  it('only phone sessions without two-step, or PIN sessions matching tsv, are verified', () => {
    const phoneTok = (extra = {}) => ({ firebase: { sign_in_provider: 'phone' }, ...extra });
    const custom = (extra = {}) => ({ firebase: { sign_in_provider: 'custom' }, ...extra });
    expect(isVerifiedSession(phoneTok())).toBe(true);
    expect(isVerifiedSession(phoneTok({ ts: true, tsv: 'a' }))).toBe(false);
    expect(isVerifiedSession(custom({ ts: true, tsv: 'a', pv: 'a' }))).toBe(true);
    expect(isVerifiedSession(custom({ ts: true, tsv: 'b', pv: 'a' }))).toBe(false);
    expect(isVerifiedSession(custom({ pv: 'a' }))).toBe(true); // two-step later turned off
    expect(isVerifiedSession(custom())).toBe(false); // custom tokens without pv are never ours
    expect(isVerifiedSession({ firebase: { sign_in_provider: 'anonymous' } })).toBe(false);
    expect(() => requirePhoneAuth({ auth: { uid: 'x', token: custom() } })).toThrow('Phone sign-in required.');
  });
});

describe('turning it on', () => {
  it('stores only a salted hash, sets the claims and returns a session token', async () => {
    const res = await enable('482915');
    const doc = await record();
    expect(JSON.stringify(doc.data())).not.toContain('482915');
    expect(String(doc.get('salt')).length).toBeGreaterThan(10);
    const c = await claims();
    expect(c.ts).toBe(true);
    expect(typeof c.tsv).toBe('string');
    expect(decodeClaims(res.token).claims?.pv ?? decodeClaims(res.token).pv).toBe(c.tsv);
  });

  it('rejects malformed and too-simple PINs', async () => {
    await deps.auth.createUser({ uid: UID });
    expect((await errorOf(handleSetTwoStepPin(await phone({ pin: '12345' }), deps))).code).toBe('invalid-argument');
    expect((await errorOf(handleSetTwoStepPin(await phone({ pin: '123456' }), deps))).message).toBe('pin-too-simple');
  });

  it('changing the PIN needs the verified session, checked against the server record', async () => {
    await enable();
    expect((await errorOf(handleSetTwoStepPin(await phone({ pin: '777123' }), deps))).message).toBe('two-step-required');
    // A phone token minted before two-step was on (no ts claim yet) is refused as well.
    const oldToken = phoneRequest(UID, { data: { pin: '777123' } });
    expect((await errorOf(handleSetTwoStepPin(oldToken, deps))).message).toBe('two-step-required');
    expect((await errorOf(handleSetTwoStepPin(await verified({ pin: '777123' }), deps))).code).toBe('ok');
  });
});

describe('verifying a new sign-in', () => {
  it('a wrong PIN reports attempts left; the right one starts a new verified session', async () => {
    await enable('482915');
    const before = (await claims()).tsv;
    const wrong = await errorOf(handleVerifyTwoStepPin(await phone({ pin: '000111' }), deps));
    expect(wrong).toMatchObject({ code: 'permission-denied', message: 'wrong-pin' });
    expect(wrong.details.attemptsLeft).toBe(FREE_ATTEMPTS - 1);
    const ok = await handleVerifyTwoStepPin(await phone({ pin: '482915' }), deps);
    expect(typeof ok.token).toBe('string');
    const after = (await claims()).tsv;
    expect(after).not.toBe(before); // the previously verified phone is now stale
    expect((await record()).get('failedAttempts')).toBe(0);
  });

  it('locks after five wrong PINs, even with the right PIN, until the lock expires', async () => {
    await enable('482915');
    for (let i = 0; i < FREE_ATTEMPTS; i++) await errorOf(handleVerifyTwoStepPin(await phone({ pin: '000111' }), deps));
    const locked = await errorOf(handleVerifyTwoStepPin(await phone({ pin: '482915' }), deps));
    expect(locked).toMatchObject({ code: 'resource-exhausted', message: 'pin-locked' });
    expect(locked.details.lockedUntilMs).toBeGreaterThan(Date.now());
    const later = testDeps({ nowMs: () => Date.now() + 61_000 });
    expect((await errorOf(handleVerifyTwoStepPin(await phone({ pin: '482915' }), later))).code).toBe('ok');
  });

  it('parallel guesses cannot get past the lock', async () => {
    await enable('482915');
    const request = await phone({ pin: '000111' });
    const results = await Promise.all(Array.from({ length: 9 }, () => errorOf(handleVerifyTwoStepPin(request, deps))));
    expect(results.filter((r) => r.message === 'wrong-pin')).toHaveLength(FREE_ATTEMPTS);
    expect(results.filter((r) => r.message === 'pin-locked')).toHaveLength(9 - FREE_ATTEMPTS);
  });

  it('verifying when two-step is off clears stale claims and returns no token', async () => {
    await deps.auth.createUser({ uid: UID });
    await deps.auth.setCustomUserClaims(UID, { ts: true, tsv: 'old' });
    expect(await handleVerifyTwoStepPin(await phone({ pin: '482915' }), deps)).toEqual({ verified: true, token: null });
    expect(await claims()).toEqual({});
  });
});

describe('status and turning it off', () => {
  it('reports status, and turning off needs the verified session and the PIN', async () => {
    await enable('482915');
    expect(await handleTwoStepStatus(await phone(), deps)).toEqual({
      enabled: true, verified: false, lockedUntilMs: null, resetAvailableAtMs: null,
    });
    expect((await handleTwoStepStatus(await verified(), deps)).verified).toBe(true);
    expect((await errorOf(handleDisableTwoStep(await phone({ pin: '482915' }), deps))).message).toBe('two-step-required');
    expect((await errorOf(handleDisableTwoStep(await verified({ pin: '000111' }), deps))).message).toBe('wrong-pin');
    const session = await verified({ pin: '482915' });
    expect((await errorOf(handleDisableTwoStep(session, deps))).code).toBe('ok');
    expect((await record()).exists).toBe(false);
    expect(await claims()).toEqual({});
    expect((await handleTwoStepStatus(await phone(), deps)).enabled).toBe(false);
  });
});

describe('forgot PIN: 7-day reset', () => {
  it('alerts the owner once, waits 7 days, then turns two-step off', async () => {
    const push = fakePush();
    const d = testDeps({ push: push.sender });
    await enable();
    await deps.db.doc(`users/${UID}/devices/${randomUUID()}`).set({ fcmToken: 'owner-phone', revokedAt: null });
    const first = await handleRequestTwoStepReset(await phone(), d);
    const again = await handleRequestTwoStepReset(await phone(), d);
    expect(again.resetAvailableAtMs).toBe(first.resetAvailableAtMs);
    expect(push.sent).toEqual([{ tokens: ['owner-phone'], data: { type: 'security', event: 'two_step_reset_requested' } }]);

    expect((await errorOf(handleCompleteTwoStepReset(await phone(), d))).message).toBe('reset-not-ready');
    const week = testDeps({ nowMs: () => Date.now() + RESET_WAIT_MS + 1000 });
    expect((await errorOf(handleCompleteTwoStepReset(await phone(), week))).code).toBe('ok');
    expect((await record()).exists).toBe(false);
    expect(await claims()).toEqual({});
  });

  it('is cancelled when the owner uses Samvad after the request', async () => {
    await enable();
    const tsv = (await claims()).tsv;
    await handleRequestTwoStepReset(await phone(), deps);
    await deps.rtdb.ref(`presence/${UID}`).set({ online: false, lastSeen: Date.now() + 5000 });
    const week = testDeps({ nowMs: () => Date.now() + RESET_WAIT_MS + 10_000 });
    expect((await errorOf(handleCompleteTwoStepReset(await phone(), week))).message).toBe('reset-cancelled');
    expect((await record()).get('resetRequestedAt')).toBeNull();
    expect(await claims()).toMatchObject({ ts: true, tsv });
  });

  it('is cancelled by entering the right PIN', async () => {
    await enable('482915');
    await handleRequestTwoStepReset(await phone(), deps);
    await handleVerifyTwoStepPin(await phone({ pin: '482915' }), deps);
    expect((await record()).get('resetRequestedAt')).toBeNull();
  });
});

describe('every other callable refuses an unverified session', () => {
  it('lookup, report, sign out everywhere and delete', async () => {
    await enable();
    const stale = await pinSession('an-older-phone');
    const calls = [
      async () => handleLookupPhoneNumbers(await phone({ phoneNumbers: ['+919999900002'] }), deps),
      async () => handleReportContact(await phone({ requestId: randomUUID(), conversationId: 'a_b', reason: 'spam' }), deps),
      async () => handleSignOutAllDevices(await phone(), deps),
      async () => handleDeleteAccount(await phone({ pin: '482915' }), deps),
      async () => handleLookupPhoneNumbers({ ...stale, data: { phoneNumbers: ['+919999900002'] } }, deps),
    ];
    for (const call of calls) {
      expect(await errorOf(call())).toMatchObject({ code: 'permission-denied', message: 'two-step-required' });
    }
  });
});

describe('deleting the account with two-step on', () => {
  it('needs the PIN instead of a fresh sign-in, and removes the PIN record', async () => {
    await enable('482915');
    const old = Math.floor(Date.now() / 1000) - 3600; // outside the 5-minute recent-sign-in window
    const at = async (data: unknown) => {
      const request = await verified(data);
      request.auth.token.auth_time = old;
      return request;
    };
    expect((await errorOf(handleDeleteAccount(await at({}), deps))).message).toBe('pin-required');
    expect((await errorOf(handleDeleteAccount(await at({ pin: '000111' }), deps))).message).toBe('wrong-pin');
    expect((await errorOf(handleDeleteAccount(await at({ pin: '482915' }), deps))).code).toBe('ok');
    expect((await record()).exists).toBe(false);
  });
});

describe('over HTTP with real emulator sessions', () => {
  it('an SMS sign-in is locked out until the PIN; the PIN session works; the older PIN session goes stale', async () => {
    const number = '+919999900081';
    const lookup = { phoneNumbers: ['+919999900002'] };
    const firstPhone = await signInWithPhone(number);
    const enabled = await callCallable('setTwoStepPin', firstPhone.idToken, { pin: '482915' });
    expect(enabled.status).toBe(200);
    const firstPin = await signInWithCustomToken(enabled.body.result.token);
    expect((await callCallable('lookupPhoneNumbers', firstPin.idToken, lookup)).status).toBe(200);

    // Someone else gets the SMS code.
    const intruder = await signInWithPhone(number);
    expect((await callCallable('lookupPhoneNumbers', intruder.idToken, lookup)).body?.error?.message).toBe('two-step-required');
    const guess = await callCallable('verifyTwoStepPin', intruder.idToken, { pin: '000111' });
    expect(guess.body?.error?.message).toBe('wrong-pin');

    // The owner on a new phone enters the PIN; the first phone's session goes stale.
    const newPhone = await signInWithPhone(number);
    const verifiedRes = await callCallable('verifyTwoStepPin', newPhone.idToken, { pin: '482915' });
    const newPin = await signInWithCustomToken(verifiedRes.body.result.token);
    expect((await callCallable('lookupPhoneNumbers', newPin.idToken, lookup)).status).toBe(200);
    const firstRefreshed = await refreshIdToken(firstPin.refreshToken);
    expect((await callCallable('lookupPhoneNumbers', firstRefreshed, lookup)).body?.error?.message).toBe('two-step-required');
    // The intruder's session never becomes verified, whatever its auth_time.
    const intruderRefreshed = await refreshIdToken(intruder.refreshToken);
    expect((await callCallable('lookupPhoneNumbers', intruderRefreshed, lookup)).body?.error?.message).toBe('two-step-required');
  });
});
