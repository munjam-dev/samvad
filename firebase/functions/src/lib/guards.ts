import { HttpsError } from 'firebase-functions/v2/https';
import { MAX_AUTH_AGE_SECONDS } from '../config';

interface AuthLike {
  auth?: { uid: string; token: Record<string, any> };
}

/**
 * Two-step verification (Phase 1.5). Accounts with claim ts on accept only sessions that came from a
 * PIN check: the server mints a custom token whose per-session claim pv equals the account claim tsv.
 * Phone sessions of such accounts (a fresh SMS sign-in, possibly by someone else) are unverified.
 * Mirrors verifiedSession() in the security rules.
 */
export function isVerifiedSession(token: Record<string, any> | undefined): boolean {
  const provider = token?.firebase?.sign_in_provider;
  if (provider === 'phone') return token?.ts !== true;
  if (provider === 'custom' && typeof token?.pv === 'string') return token.ts !== true || token.pv === token.tsv;
  return false;
}

export function requirePhoneAuth(
  request: AuthLike,
  options: { allowUnverified?: boolean } = {},
): { uid: string; authTimeSeconds: number; verified: boolean } {
  const auth = request.auth;
  if (!auth || !auth.uid) {
    throw new HttpsError('unauthenticated', 'Sign in required.');
  }
  const provider = auth.token?.firebase?.sign_in_provider;
  const pinSession = provider === 'custom' && typeof auth.token?.pv === 'string';
  if (provider !== 'phone' && !pinSession) {
    throw new HttpsError('permission-denied', 'Phone sign-in required.');
  }
  const verified = isVerifiedSession(auth.token);
  if (!verified && !options.allowUnverified) {
    throw new HttpsError('permission-denied', 'two-step-required');
  }
  return { uid: auth.uid, authTimeSeconds: Number(auth.token.auth_time ?? 0), verified };
}

export function requireRecentAuth(
  authTimeSeconds: number,
  nowSeconds: number,
  maxAgeSeconds = MAX_AUTH_AGE_SECONDS,
): void {
  if (!(authTimeSeconds > 0) || nowSeconds - authTimeSeconds > maxAgeSeconds) {
    throw new HttpsError('failed-precondition', 'recent-login-required');
  }
}
