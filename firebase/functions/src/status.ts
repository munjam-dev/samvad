import { onDocumentCreated, onDocumentDeleted } from 'firebase-functions/v2/firestore';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions, REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
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
export async function applyStatusCreated(deps: Deps, author: string, sid: string): Promise<string[]> {
  const ref = deps.db.doc(`statuses/${author}/items/${sid}`);
  const snap = await ref.get();
  if (!snap.exists) return [];
  const audience = snap.get('audience') as Audience;
  const list = (snap.get('list') as string[]) ?? [];
  const visibleTo = await computeVisibleTo(deps, author, audience, list);
  // The server alone sets the lifetime and the audience; clients cannot (rules). Both land in one write.
  const created = snap.get('createdAt') as Timestamp;
  await ref.update({ visibleTo, expiresAt: Timestamp.fromMillis(created.toMillis() + STATUS_LIFETIME_MS) });
  logEvent('status_published', { count: visibleTo.length, outcome: 'ok' });
  return visibleTo;
}

/** A viewer hears about a new moment from the same author at most once in this long. */
export const MOMENT_PING_WINDOW_MS = 6 * 60 * 60 * 1000;
/** Only the first this-many viewers are notified, so one post never causes a large fan-out. */
export const MOMENT_PING_CAP = 500;
const PING_PARALLELISM = 25;

/**
 * Tells the people who can see a new moment that there is one: a data push with the author's name and no content.
 * Skips the author, anyone with the one-to-one chat muted, blocked pairs, and anyone already pinged by this author in the
 * last 6 hours (the marker is momentPings/{viewer}_{author}, written only here). Returns how many viewers were pinged.
 */
export async function notifyMomentViewers(deps: Deps, author: string, visibleTo: string[]): Promise<number> {
  const viewers = [...new Set(visibleTo)].filter((v) => v !== author).slice(0, MOMENT_PING_CAP);
  if (viewers.length === 0) return 0;
  const { db } = deps;
  const profile = await db.doc(`profiles/${author}`).get();
  const authorName = (profile.get('displayName') as string | undefined) ?? '';
  const tokenOwners = new Map<string, FirebaseFirestore.DocumentReference>();
  let pinged = 0;

  for (let i = 0; i < viewers.length; i += PING_PARALLELISM) {
    await Promise.all(
      viewers.slice(i, i + PING_PARALLELISM).map(async (viewer) => {
        const cid = [author, viewer].sort().join('_');
        const [member, iBlocked, theyBlocked] = await Promise.all([
          db.doc(`conversations/${cid}/members/${viewer}`).get(),
          db.doc(`users/${author}/blocked/${viewer}`).get(),
          db.doc(`users/${viewer}/blocked/${author}`).get(),
        ]);
        if (member.get('muted') === true || iBlocked.exists || theyBlocked.exists) return;
        const devices = await db.collection(`users/${viewer}/devices`).where('fcmToken', '!=', null).get();
        const tokens: Array<[string, FirebaseFirestore.DocumentReference]> = [];
        for (const d of devices.docs) {
          const token = d.get('fcmToken');
          if (!d.get('revokedAt') && typeof token === 'string' && token.length > 0) tokens.push([token, d.ref]);
        }
        if (tokens.length === 0) return;
        // The marker is taken last, so a viewer who was skipped above can still be told next time.
        const marker = db.doc(`momentPings/${viewer}_${author}`);
        const took = await db.runTransaction(async (tx) => {
          const snap = await tx.get(marker);
          const at: Timestamp | undefined = snap.get('at');
          const now = deps.nowMs();
          if (at && now - at.toMillis() < MOMENT_PING_WINDOW_MS) return false;
          tx.set(marker, { at: Timestamp.fromMillis(now) });
          return true;
        });
        if (!took) return;
        pinged++;
        for (const [token, ref] of tokens) tokenOwners.set(token, ref);
      }),
    );
  }
  if (tokenOwners.size === 0) return pinged;
  const { invalidTokens } = await deps.push.send([...tokenOwners.keys()], {
    type: 'moment_new',
    authorId: author,
    title: authorName,
    momentAuthor: author,
  });
  for (const t of invalidTokens) await tokenOwners.get(t)?.update({ fcmToken: null });
  logEvent('moment_push_sent', { count: tokenOwners.size, outcome: 'ok' });
  return pinged;
}

export const DAILY_AUDIENCE_EDITS = 20;

function parseAudience(data: unknown): { statusId: string; audience: Audience; list: string[] } {
  const d = (data ?? {}) as { statusId?: unknown; audience?: unknown; list?: unknown };
  if (typeof d.statusId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(d.statusId)) {
    throw new HttpsError('invalid-argument', 'Unknown moment.');
  }
  if (d.audience !== 'all' && d.audience !== 'only' && d.audience !== 'except') throw new HttpsError('invalid-argument', 'Unknown audience.');
  const raw = d.list === undefined ? [] : d.list;
  if (!Array.isArray(raw) || raw.length > 200 || raw.some((x) => typeof x !== 'string' || x.length === 0 || x.length > 128)) {
    throw new HttpsError('invalid-argument', 'Invalid list.');
  }
  const list = [...new Set(raw as string[])];
  // Same shape the rules demand at creation: "all" has no list; "only" and "except" need someone.
  if (d.audience === 'all' ? list.length !== 0 : list.length < 1) throw new HttpsError('invalid-argument', 'Invalid list.');
  return { statusId: d.statusId, audience: d.audience, list };
}

/** Re-stamps visibleTo for a live moment exactly like creation (computeVisibleTo). Only the author's own live moment. */
export async function setStatusAudienceCore(
  deps: Deps,
  uid: string,
  input: { statusId: string; audience: Audience; list: string[] },
): Promise<{ count: number }> {
  const ref = deps.db.doc(`statuses/${uid}/items/${input.statusId}`);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError('not-found', 'That moment is gone.');
  const expires = snap.get('expiresAt') as Timestamp | undefined;
  const created = snap.get('createdAt') as Timestamp | undefined;
  const end = expires?.toMillis() ?? (created ? created.toMillis() + STATUS_LIFETIME_MS : 0);
  if (deps.nowMs() >= end) throw new HttpsError('failed-precondition', 'That moment has ended.');
  await consumeDailyBudget(deps, uid, 'statusAudience', DAILY_AUDIENCE_EDITS);
  const visibleTo = await computeVisibleTo(deps, uid, input.audience, input.list);
  await ref.update({ audience: input.audience, list: input.list, visibleTo });
  logEvent('status_audience_set', { count: visibleTo.length, outcome: 'ok' });
  return { count: visibleTo.length };
}

export const handleSetStatusAudience = async (r: CallableRequest<unknown>, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const input = parseAudience(r.data);
  try {
    return await setStatusAudienceCore(deps, uid, input);
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logEvent('status_audience_set', { outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const setStatusAudience = onCall(buildCallableOptions(process.env), (r) => handleSetStatusAudience(r));

/** Removes a status's files, views and reactions once the status itself is gone (deleted early or expired). */
export async function cleanupStatus(deps: Deps, author: string, sid: string): Promise<void> {
  await deps.deleteFiles(`statuses/${author}/${sid}/`);
  const base = `statuses/${author}/items/${sid}`;
  await deps.db.recursiveDelete(deps.db.collection(`${base}/views`));
  await deps.db.recursiveDelete(deps.db.collection(`${base}/reactions`));
  logEvent('status_cleanup', { count: 1, outcome: 'ok' });
}

export const onStatusCreated = onDocumentCreated({ document: 'statuses/{author}/items/{sid}', region: REGION }, async (event) => {
  const deps = defaultDeps();
  const visibleTo = await applyStatusCreated(deps, event.params.author, event.params.sid);
  try {
    await notifyMomentViewers(deps, event.params.author, visibleTo);
  } catch {
    logEvent('moment_push_failed', { outcome: 'error' });
  }
});

export const onStatusDeleted = onDocumentDeleted({ document: 'statuses/{author}/items/{sid}', region: REGION }, async (event) => {
  await cleanupStatus(defaultDeps(), event.params.author, event.params.sid);
});
