import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, getDoc, serverTimestamp, setDoc, Timestamp } from 'firebase/firestore';
import { get, ref as dbRef, serverTimestamp as rtdbNow, set } from 'firebase/database';
import { getBytes, ref as stRef, uploadBytes } from 'firebase/storage';
import { createRulesEnv } from '../test-support/rulesEnv';

// Phase 1.5: with claim ts on, only a PIN-minted custom-token session whose pv equals tsv may act.
let env: RulesTestEnvironment;
const U = 'kiran';
const P = 'leela';
const CID = `${U}_${P}`;

type Session = 'off' | 'verified' | 'unverified' | 'stale' | 'custom-no-pv';
function ctx(uid: string, session: Session) {
  const base = { phone_number: '+919999900071', auth_time: 1_700_000_000 };
  const token = {
    // Two-step off: a plain phone sign-in.
    off: { ...base, firebase: { sign_in_provider: 'phone' } },
    // Two-step on, PIN entered on this session.
    verified: { ...base, firebase: { sign_in_provider: 'custom' }, ts: true, tsv: 'n2', pv: 'n2' },
    // Two-step on, fresh SMS sign-in (for example after a SIM swap).
    unverified: { ...base, firebase: { sign_in_provider: 'phone' }, ts: true, tsv: 'n2' },
    // Two-step on, PIN entered on an older phone, since replaced by a newer verification.
    stale: { ...base, firebase: { sign_in_provider: 'custom' }, ts: true, tsv: 'n2', pv: 'n1' },
    'custom-no-pv': { ...base, firebase: { sign_in_provider: 'custom' } },
  }[session];
  return env.authenticatedContext(uid, token as never);
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async (c) => {
    const db = c.firestore();
    await setDoc(doc(db, `users/${U}`), { phoneE164: '+919999900071', createdAt: Timestamp.now(), consent: { version: 1, acceptedAt: Timestamp.now() } });
    await setDoc(doc(db, `profiles/${P}`), { displayName: 'Leela', about: '', photoUrl: null, updatedAt: Timestamp.now() });
    await setDoc(doc(db, `conversations/${CID}`), {
      type: 'direct', members: [U, P], createdBy: U, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    await setDoc(doc(db, `twoStep/${U}`), { hash: 'x', salt: 'y', failedAttempts: 0 });
  });
});
afterAll(async () => {
  await env.cleanup();
});

describe('Firestore', () => {
  it('a verified session and an account without two-step can read; an unverified session cannot', async () => {
    await assertSucceeds(getDoc(doc(ctx(U, 'verified').firestore(), `users/${U}`)));
    await assertSucceeds(getDoc(doc(ctx(U, 'off').firestore(), `users/${U}`)));
    await assertFails(getDoc(doc(ctx(U, 'unverified').firestore(), `users/${U}`)));
    await assertFails(getDoc(doc(ctx(U, 'unverified').firestore(), `profiles/${P}`)));
    await assertFails(getDoc(doc(ctx(U, 'stale').firestore(), `users/${U}`)));
    await assertFails(getDoc(doc(ctx(U, 'custom-no-pv').firestore(), `users/${U}`)));
  });

  it('an unverified session cannot send messages or register a device', async () => {
    const msg = { senderId: U, kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp() };
    await assertFails(setDoc(doc(ctx(U, 'unverified').firestore(), `conversations/${CID}/messages/${randomUUID()}`), msg));
    await assertSucceeds(setDoc(doc(ctx(U, 'verified').firestore(), `conversations/${CID}/messages/${randomUUID()}`), msg));
    const device = { platform: 'android', appVersion: '1', osVersion: '14', createdAt: serverTimestamp(), lastSeenAt: serverTimestamp() };
    await assertFails(setDoc(doc(ctx(U, 'unverified').firestore(), `users/${U}/devices/${randomUUID()}`), device));
  });

  it('the PIN record is never readable, even by a verified owner', async () => {
    await assertFails(getDoc(doc(ctx(U, 'verified').firestore(), `twoStep/${U}`)));
    await assertFails(setDoc(doc(ctx(U, 'verified').firestore(), `twoStep/${U}`), { hash: 'mine' }));
  });
});

describe('Realtime Database', () => {
  it('an unverified session cannot write presence or read others', async () => {
    await assertFails(set(dbRef(ctx(U, 'unverified').database(), `presence/${U}`), { online: true, lastSeen: rtdbNow() }));
    await assertSucceeds(set(dbRef(ctx(U, 'verified').database(), `presence/${U}`), { online: true, lastSeen: rtdbNow() }));
    await assertFails(get(dbRef(ctx(U, 'unverified').database(), `presence/${U}`)));
    await assertFails(get(dbRef(ctx(U, 'stale').database(), `presence/${U}`)));
    await assertSucceeds(get(dbRef(ctx(P, 'off').database(), `presence/${U}`)));
  });
});

describe('Storage', () => {
  it('an unverified session cannot upload or download chat media', async () => {
    const path = `conversations/${CID}/${randomUUID()}/photo.jpg`;
    const meta = { contentType: 'image/jpeg', customMetadata: { senderId: U } };
    const bytes = new Uint8Array(64);
    await assertFails(uploadBytes(stRef(ctx(U, 'unverified').storage(), path), bytes, meta));
    await assertSucceeds(uploadBytes(stRef(ctx(U, 'verified').storage(), path), bytes, meta));
    await assertFails(getBytes(stRef(ctx(U, 'unverified').storage(), path)));
    await assertFails(getBytes(stRef(ctx(U, 'stale').storage(), path)));
    await assertSucceeds(getBytes(stRef(ctx(P, 'off').storage(), path)));
  });
});
