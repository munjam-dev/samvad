import { describe, expect, it } from 'vitest';
import { requirePhoneAuth, requireRecentAuth } from '../src/lib/guards';

const code = (fn: () => unknown) => {
  try {
    fn();
  } catch (e: any) {
    return e.code;
  }
  return 'no-error';
};

describe('guards', () => {
  it('rejects missing auth with unauthenticated', () => {
    expect(code(() => requirePhoneAuth({}))).toBe('unauthenticated');
  });

  it('rejects anonymous and custom providers with permission-denied', () => {
    for (const p of ['anonymous', 'custom']) {
      expect(code(() => requirePhoneAuth({ auth: { uid: 'u', token: { firebase: { sign_in_provider: p } } } }))).toBe(
        'permission-denied',
      );
    }
  });

  it('returns uid and auth_time for a phone token', () => {
    expect(
      requirePhoneAuth({ auth: { uid: 'u1', token: { auth_time: 1000, firebase: { sign_in_provider: 'phone' } } } }),
    ).toEqual({ uid: 'u1', authTimeSeconds: 1000, verified: true });
  });

  it('requireRecentAuth passes at exactly 300 s and throws failed-precondition at 301 s', () => {
    expect(code(() => requireRecentAuth(1000, 1300))).toBe('no-error');
    expect(code(() => requireRecentAuth(1000, 1301))).toBe('failed-precondition');
  });
});
