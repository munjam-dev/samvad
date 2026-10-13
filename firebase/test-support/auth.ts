// Helpers that talk to the Auth, Firestore and Functions emulators over REST.
import { HOSTS, PROJECT_ID, REGION } from './emulator';

const AUTH = `http://${HOSTS.auth}`;
const IDT = `${AUTH}/identitytoolkit.googleapis.com/v1`;

async function json(res: Response): Promise<any> {
  const body = await res.json();
  if (!res.ok) throw new Error(`emulator request failed: ${res.status} ${JSON.stringify(body?.error?.message ?? '')}`);
  return body;
}

export async function signInWithPhone(
  phoneNumber: string,
): Promise<{ uid: string; idToken: string; refreshToken: string }> {
  const { sessionInfo } = await json(
    await fetch(`${IDT}/accounts:sendVerificationCode?key=fake-api-key`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ phoneNumber, recaptchaToken: 'ignored' }),
    }),
  );
  const { verificationCodes } = await json(
    await fetch(`${AUTH}/emulator/v1/projects/${PROJECT_ID}/verificationCodes`),
  );
  const match = (verificationCodes as any[]).filter((c) => c.sessionInfo === sessionInfo).pop();
  if (!match) throw new Error('verification code not found');
  const signIn = await json(
    await fetch(`${IDT}/accounts:signInWithPhoneNumber?key=fake-api-key`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionInfo, code: match.code }),
    }),
  );
  return { uid: signIn.localId, idToken: signIn.idToken, refreshToken: signIn.refreshToken };
}

export function decodeClaims(idToken: string): Record<string, any> {
  const payload = idToken.split('.')[1];
  return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
}

export async function listAuthUserIds(): Promise<string[]> {
  const res = await json(
    await fetch(`${IDT}/projects/${PROJECT_ID}/accounts:query`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer owner' },
      body: JSON.stringify({ returnUserInfo: true }),
    }),
  );
  return ((res.userInfo ?? []) as any[]).map((u) => u.localId);
}

export async function resetEmulators(): Promise<void> {
  await fetch(`${AUTH}/emulator/v1/projects/${PROJECT_ID}/accounts`, { method: 'DELETE' });
  const fs = HOSTS.firestore;
  await fetch(
    `http://${fs.host}:${fs.port}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: 'DELETE' },
  );
}

export async function callCallable(
  name: string,
  idToken: string | null,
  data: unknown = {},
): Promise<{ status: number; body: any }> {
  const fn = HOSTS.functions;
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (idToken) headers.authorization = `Bearer ${idToken}`;
  const res = await fetch(`http://${fn.host}:${fn.port}/${PROJECT_ID}/${REGION}/${name}`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ data }),
  });
  return { status: res.status, body: await res.json().catch(() => null) };
}
