import { onDocumentCreated, onDocumentDeleted } from 'firebase-functions/v2/firestore';
import { Timestamp } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

export const MAX_VISIBLE = 1000;
export const STATUS_LIFETIME_MS = 24 * 60 * 60 * 1000;

export type Audience = 'all' | 'only' | 'except';

/**
 * Who may see a status. Everyone the author has a direct chat with, narrowed by the audience choice and
 * minus anyone blocked in either direction. The number is capped so a status document stays small.
 */
export async function computeVisibleTo(deps: Deps, author: string, audience: Audience, list: string[]): Promise<string[]> {
  const chats = await deps.db.collection('conversations').where('members', 'array-contains', author).limit(MAX_VISIBLE).get();
  const partners = new Set<string>();
  for (const c of chats.docs) {
    if (c.get('type') !== 'direct') continue;
    for (const m of (c.get('members') as string[]) ?? []) if (m !== author) partners.add(m);
  }
  const iBlocked = await deps.db.collection(`users/${author}/blocked`).get();
  for (const b of iBlocked.docs) partners.delete(b.id);

  const candidates = [...partners];
  const blockedMe = await Promise.all(candidates.map((u) => deps.db.doc(`users/${u}/blocked/${author}`).get()));
  const allowed = candidates.filter((_, i) => !blockedMe[i].exists);

  const chosen = new Set(list);
  const visible =
    audience === 'all' ? allowed : audience === 'only' ? allowed.filter((u) => chosen.has(u)) : allowed.filter((u) => !chosen.has(u));
  return visible.slice(0, MAX_VISIBLE);
}

/** Writes visibleTo on a new status. Running it twice gives the same result. */
export async function applyStatusCreated(deps: Deps, author: string, sid: string): Promise<void> {
  const ref = deps.db.doc(`statuses/${author}/items/${sid}`);
  const snap = await ref.get();
  if (!snap.exists) return;
  const audience = snap.get('audience') as Audience;
  const list = (snap.get('list') as string[]) ?? [];
  const visibleTo = await computeVisibleTo(deps, author, audience, list);
  // The server alone sets the lifetime and the audience; clients cannot (rules). Both land in one write.
  const created = snap.get('createdAt') as Timestamp;
  await ref.update({ visibleTo, expiresAt: Timestamp.fromMillis(created.toMillis() + STATUS_LIFETIME_MS) });
  logEvent('status_published', { count: visibleTo.length, outcome: 'ok' });
}

/** Removes a status's files, views and reactions once the status itself is gone (deleted early or expired). */
export async function cleanupStatus(deps: Deps, author: string, sid: string): Promise<void> {
  await deps.deleteFiles(`statuses/${author}/${sid}/`);
  const base = `statuses/${author}/items/${sid}`;
  await deps.db.recursiveDelete(deps.db.collection(`${base}/views`));
  await deps.db.recursiveDelete(deps.db.collection(`${base}/reactions`));
  logEvent('status_cleanup', { count: 1, outcome: 'ok' });
}

export const onStatusCreated = onDocumentCreated({ document: 'statuses/{author}/items/{sid}', region: REGION }, async (event) => {
  await applyStatusCreated(defaultDeps(), event.params.author, event.params.sid);
});

export const onStatusDeleted = onDocumentDeleted({ document: 'statuses/{author}/items/{sid}', region: REGION }, async (event) => {
  await cleanupStatus(defaultDeps(), event.params.author, event.params.sid);
});
