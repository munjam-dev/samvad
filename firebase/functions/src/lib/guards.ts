import { HttpsError } from 'firebase-functions/v2/https';
import { MAX_AUTH_AGE_SECONDS } from '../config';

interface AuthLike {
  auth?: { uid: string; token: Record<string, any> };
}

export function requirePhoneAuth(request: AuthLike): { uid: string; authTimeSeconds: number } {
  const auth = request.auth;
  if (!auth || !auth.uid) {
    throw new HttpsError('unauthenticated', 'Sign in required.');
  }
  if (auth.token?.firebase?.sign_in_provider !== 'phone') {
    throw new HttpsError('permission-denied', 'Phone sign-in required.');
  }
  return { uid: auth.uid, authTimeSeconds: Number(auth.token.auth_time ?? 0) };
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
