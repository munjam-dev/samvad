import { FieldValue } from 'firebase-admin/firestore';
import type { Deps } from './deps';
import { logEvent } from './lib/logger';

/**
 * Phase 3 "People I chat with". In a direct conversation, a person is engaged once they started it or have
 * sent a message in it (conversations/{cid}.engagedBy, written only by functions). The privacy rules read
 * this to decide who may see someone's last seen, photo or About when they chose "People I chat with".
 * It is not enough that a conversation exists: anyone can create one with anybody who has a profile, so
 * a stranger gets no access to the other person's details until the other person writes back.
 *
 * The Realtime Database copy (engaged/{cid}/{uid}) exists for the presence rules, which cannot read Firestore.
 */

/** Adds [uids] to a direct conversation's engagedBy and the RTDB copy. Groups are ignored. */
export async function markEngaged(deps: Deps, cid: string, uids: string[]): Promise<void> {
  const ref = deps.db.doc(`conversations/${cid}`);
  let added: string[] = [];
  await deps.db.runTransaction(async (tx) => {
    added = [];
    const snap = await tx.get(ref);
    if (!snap.exists || snap.get('type') !== 'direct') return;
    const members: string[] = snap.get('members') ?? [];
    const have: string[] = snap.get('engagedBy') ?? [];
    added = uids.filter((u) => members.includes(u) && !have.includes(u));
    if (added.length > 0) tx.update(ref, { engagedBy: FieldValue.arrayUnion(...added) });
  });
  if (added.length > 0) await mirrorEngaged(deps, cid, added);
}

export async function mirrorEngaged(deps: Deps, cid: string, uids: string[]): Promise<void> {
  const update: Record<string, true> = {};
  for (const u of uids) update[`engaged/${cid}/${u}`] = true;
  await deps.rtdb.ref().update(update);
}

/**
 * Conversations that predate this feature have no engagedBy. The creator counts, and so does anyone who has
 * sent a message. Safe to run more than once.
 */
export async function backfillEngagement(deps: Deps, cid: string): Promise<string[]> {
  const snap = await deps.db.doc(`conversations/${cid}`).get();
  if (!snap.exists || snap.get('type') !== 'direct') return [];
  const members: string[] = snap.get('members') ?? [];
  const have: string[] = snap.get('engagedBy') ?? [];
  const engaged = new Set<string>(have);
  const creator = snap.get('createdBy');
  if (typeof creator === 'string' && members.includes(creator)) engaged.add(creator);
  for (const m of members) {
    if (engaged.has(m)) continue;
    const sent = await snap.ref.collection('messages').where('senderId', '==', m).limit(1).get();
    if (!sent.empty) engaged.add(m);
  }
  const added = [...engaged].filter((u) => !have.includes(u));
  if (added.length > 0) {
    await snap.ref.update({ engagedBy: FieldValue.arrayUnion(...added) });
  }
  // Always refresh the RTDB copy: it is what the presence rules read.
  if (engaged.size > 0) await mirrorEngaged(deps, cid, [...engaged]);
  return added;
}

/** One-off migration over every direct conversation (see scripts/migrate-privacy.mjs). Returns how many were visited. */
export async function backfillAllEngagement(deps: Deps): Promise<number> {
  let visited = 0;
  let last: FirebaseFirestore.QueryDocumentSnapshot | undefined;
  for (;;) {
    let q = deps.db.collection('conversations').where('type', '==', 'direct').orderBy('__name__').limit(200);
    if (last) q = q.startAfter(last);
    const page = await q.get();
    if (page.empty) break;
    for (const d of page.docs) {
      await backfillEngagement(deps, d.id);
      visited++;
    }
    last = page.docs[page.docs.length - 1];
  }
  logEvent('engagement_backfilled', { count: visited, outcome: 'ok' });
  return visited;
}
