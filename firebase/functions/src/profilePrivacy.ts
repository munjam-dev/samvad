import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { FieldValue } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

/**
 * Phase 3: "About" and the photo path moved out of profiles/{uid}, which every signed-in person can read, into
 * profiles/{uid}/private/about and /photo, whose readers follow the owner's visibility setting. Profiles written
 * before that still carry the two fields on the public document. This moves them across and removes them, and is
 * safe to run any number of times. A newer value in the private document is never overwritten.
 */
export async function migrateProfileCore(deps: Deps, uid: string): Promise<boolean> {
  const profileRef = deps.db.doc(`profiles/${uid}`);
  let moved = false;
  await deps.db.runTransaction(async (tx) => {
    moved = false;
    const [profile, about, photo] = await Promise.all([
      tx.get(profileRef),
      tx.get(deps.db.doc(`profiles/${uid}/private/about`)),
      tx.get(deps.db.doc(`profiles/${uid}/private/photo`)),
    ]);
    if (!profile.exists) return;
    const legacy: Record<string, FirebaseFirestore.FieldValue> = {};
    if (profile.get('about') !== undefined) {
      if (!about.exists) {
        tx.set(about.ref, { about: String(profile.get('about') ?? '').slice(0, 140), updatedAt: FieldValue.serverTimestamp() });
      }
      legacy.about = FieldValue.delete();
    }
    if (profile.get('photoUrl') !== undefined) {
      const path = profile.get('photoUrl');
      if (!photo.exists && typeof path === 'string') {
        tx.set(photo.ref, { photoUrl: path, updatedAt: FieldValue.serverTimestamp() });
      }
      legacy.photoUrl = FieldValue.delete();
    }
    if (Object.keys(legacy).length === 0) return;
    tx.update(profileRef, legacy);
    moved = true;
  });
  return moved;
}

/** Migrates every profile (see scripts/migrate-privacy.mjs). Returns how many profiles changed. */
export async function migrateAllProfiles(deps: Deps): Promise<number> {
  let changed = 0;
  let last: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  for (;;) {
    let q = deps.db.collection('profiles').orderBy('__name__').limit(200);
    if (last) q = q.startAfter(last);
    const page = await q.get();
    if (page.empty) break;
    for (const d of page.docs) {
      if (d.get('about') !== undefined || d.get('photoUrl') !== undefined) {
        if (await migrateProfileCore(deps, d.id)) changed++;
      }
    }
    last = page.docs[page.docs.length - 1];
  }
  logEvent('profiles_migrated', { count: changed, outcome: 'ok' });
  return changed;
}

/** Cleans up a profile that still has the old fields (written by an older app version, or never migrated). */
export const onProfileWritten = onDocumentWritten({ document: 'profiles/{uid}', region: REGION }, async (event) => {
  const after = event.data?.after;
  if (!after?.exists) return;
  if (after.get('about') === undefined && after.get('photoUrl') === undefined) return;
  await migrateProfileCore(defaultDeps(), event.params.uid);
});
