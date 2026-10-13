import { beforeEach, describe, expect, it } from 'vitest';
import { decodeClaims, listAuthUserIds, resetEmulators, signInWithPhone } from '../../test-support/auth';

beforeEach(resetEmulators);

describe('auth emulator helper', () => {
  it('signs in with a phone number and yields phone-provider claims', async () => {
    const { uid, idToken } = await signInWithPhone('+919999900011');
    const claims = decodeClaims(idToken);
    expect(claims.phone_number).toBe('+919999900011');
    expect(claims.firebase.sign_in_provider).toBe('phone');
    expect(await listAuthUserIds()).toContain(uid);
  });
});
