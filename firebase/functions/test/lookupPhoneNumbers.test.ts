import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { handleLookupPhoneNumbers, DAILY_LOOKUP_LIMIT } from '../src/lookupPhoneNumbers';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { callCallable, resetEmulators, signInWithPhone } from '../../test-support/auth';

const deps = testDeps();
const codeOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => e.code);

async function seedUser(uid: string, phoneE164: string) {
  await deps.db.doc(`users/${uid}`).set({ phoneE164, createdAt: Timestamp.now() });
}

beforeEach(resetEmulators);

describe('lookupPhoneNumbers', () => {
  it('requires a phone sign-in', async () => {
    expect(await codeOf(handleLookupPhoneNumbers({ data: { phoneNumbers: ['+919999900001'] } } as any, deps))).toBe(
      'unauthenticated',
    );
    expect(
      await codeOf(
        handleLookupPhoneNumbers(phoneRequest('alice', { provider: 'anonymous', data: { phoneNumbers: ['+919999900001'] } }), deps),
      ),
    ).toBe('permission-denied');
  });

  it('validates input: 1 to 50 E.164 numbers', async () => {
    for (const data of [{}, { phoneNumbers: [] }, { phoneNumbers: ['98765'] }, { phoneNumbers: Array(51).fill('+919999900001') }]) {
      expect(await codeOf(handleLookupPhoneNumbers(phoneRequest('alice', { data }), deps))).toBe('invalid-argument');
    }
  });

  it('returns registered users, never the caller, and ignores unknown numbers', async () => {
    await seedUser('alice', '+919999900001');
    await seedUser('bob', '+919999900002');
    const res = await handleLookupPhoneNumbers(
      phoneRequest('alice', { data: { phoneNumbers: ['+919999900001', '+919999900002', '+919999900099'] } }),
      deps,
    );
    expect(res.matches).toEqual([{ phoneE164: '+919999900002', uid: 'bob' }]);
  });

  it('enforces the rolling daily budget, then resets after 24 h', async () => {
    const batch = Array.from({ length: 50 }, (_, i) => `+9199999${String(10000 + i)}`);
    for (let i = 0; i < DAILY_LOOKUP_LIMIT / 50; i++) {
      await handleLookupPhoneNumbers(phoneRequest('alice', { data: { phoneNumbers: batch } }), deps);
    }
    expect(await codeOf(handleLookupPhoneNumbers(phoneRequest('alice', { data: { phoneNumbers: ['+919999900002'] } }), deps))).toBe(
      'resource-exhausted',
    );
    const tomorrow = testDeps({ nowMs: () => Date.now() + 24 * 60 * 60 * 1000 + 1000 });
    expect(
      await codeOf(handleLookupPhoneNumbers(phoneRequest('alice', { data: { phoneNumbers: ['+919999900002'] } }), tomorrow)),
    ).toBe('ok');
  });

  it('works over HTTP for a phone-signed-in caller', async () => {
    await seedUser('bob', '+919999900002');
    const { idToken } = await signInWithPhone('+919999900051');
    const res = await callCallable('lookupPhoneNumbers', idToken, { phoneNumbers: ['+919999900002'] });
    expect(res.body).toEqual({ result: { matches: [{ phoneE164: '+919999900002', uid: 'bob' }] } });
  });
});
