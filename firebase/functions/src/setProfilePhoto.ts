import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { JpegError, stripJpegMetadata } from './lib/jpeg';
import { logEvent, newCorrelationId } from './lib/logger';

// Phase 1.6: profile photos. Only this function writes avatars/{uid}/…; clients can only read them.

export const MAX_PHOTO_BYTES = 512 * 1024;
export const MAX_PHOTO_EDGE = 1024;
export const DAILY_PHOTO_CHANGES = 20;
const WINDOW_MS = 24 * 60 * 60 * 1000;

/** null removes the photo; otherwise base64 of a JPEG. */
export function parsePhoto(data: unknown): Buffer | null {
  const raw = (data as { jpegBase64?: unknown } | null)?.jpegBase64;
  if (raw === null) return null;
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > Math.ceil(MAX_PHOTO_BYTES / 3) * 4 + 4) {
    throw new HttpsError('invalid-argument', 'photo-too-large');
  }
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(raw)) throw new HttpsError('invalid-argument', 'Photo must be base64.');
  const bytes = Buffer.from(raw, 'base64');
  if (bytes.length === 0 || bytes.length > MAX_PHOTO_BYTES) throw new HttpsError('invalid-argument', 'photo-too-large');
  return bytes;
}

async function consumeBudget(deps: Deps, uid: string): Promise<void> {
  const ref = deps.db.doc(`rateLimits/${uid}`);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = deps.nowMs();
    const start: Timestamp | undefined = snap.get('photoWindowStart');
    const fresh = !start || now - start.toMillis() >= WINDOW_MS;
    const used = fresh ? 0 : Number(snap.get('photoCount') ?? 0);
    if (used + 1 > DAILY_PHOTO_CHANGES) throw new HttpsError('resource-exhausted', 'Too many photo changes today.');
    tx.set(ref, { photoWindowStart: fresh ? Timestamp.fromMillis(now) : start, photoCount: used + 1 }, { merge: true });
  });
}

/** Validates a JPEG photo and returns it without metadata; throws invalid-argument otherwise. */
export function cleanPhoto(photo: Buffer): Buffer {
  let clean;
  try {
    clean = stripJpegMetadata(photo);
  } catch (e) {
    if (e instanceof JpegError) throw new HttpsError('invalid-argument', 'not-a-jpeg');
    throw e;
  }
  if (clean.width > MAX_PHOTO_EDGE || clean.height > MAX_PHOTO_EDGE) {
    throw new HttpsError('invalid-argument', 'photo-too-large');
  }
  return clean.bytes;
}

/** Sets (or with null removes) the caller's photo; returns the new Storage path or null. */
export async function setPhotoCore(deps: Deps, uid: string, photo: Buffer | null): Promise<string | null> {
  const profileRef = deps.db.doc(`profiles/${uid}`);
  if (!(await profileRef.get()).exists) throw new HttpsError('failed-precondition', 'Create your profile first.');

  let path: string | null = null;
  if (photo) {
    const clean = cleanPhoto(photo);
    await consumeBudget(deps, uid);
    path = `avatars/${uid}/${randomUUID()}.jpg`;
    await deps.putFile(path, clean, 'image/jpeg');
  } else {
    await consumeBudget(deps, uid);
  }

  let previous: string | null = null;
  try {
    await deps.db.runTransaction(async (tx) => {
      const snap = await tx.get(profileRef);
      if (!snap.exists) throw new HttpsError('failed-precondition', 'Create your profile first.');
      const old = snap.get('photoUrl');
      previous = typeof old === 'string' ? old : null;
      tx.update(profileRef, { photoUrl: path, updatedAt: FieldValue.serverTimestamp() });
    });
  } catch (err) {
    if (path) await deps.deleteFile(path).catch(() => undefined);
    throw err;
  }
  // Only ever delete inside the caller's own folder.
  if (previous && previous !== path && String(previous).startsWith(`avatars/${uid}/`)) {
    await deps.deleteFile(previous).catch(() => undefined);
  }
  return path;
}

export async function handleSetProfilePhoto(
  request: CallableRequest<unknown>,
  deps: Deps = defaultDeps(),
): Promise<{ photoUrl: string | null }> {
  const { uid } = requirePhoneAuth(request);
  const photo = parsePhoto(request.data);
  const correlationId = newCorrelationId();
  try {
    const photoUrl = await setPhotoCore(deps, uid, photo);
    logEvent('profile_photo_set', { correlationId, outcome: photoUrl ? 'set' : 'removed' });
    return { photoUrl };
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('profile_photo_rejected', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('profile_photo_failed', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Could not update the photo. Please retry.');
  }
}

export const setProfilePhoto = onCall(buildCallableOptions(process.env), (request) =>
  handleSetProfilePhoto(request),
);
