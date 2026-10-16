import { onDocumentUpdated } from 'firebase-functions/v2/firestore';
import { FieldValue } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';
import { removePinnedMessage } from './pinned';

/**
 * When a sender deletes a media message for everyone, remove its file(s) from Storage and drop
 * the media description, so nothing of the attachment remains on the server.
 */
export async function cleanupDeletedMedia(
  deps: Deps,
  cid: string,
  mid: string,
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): Promise<boolean> {
  if (!before || !after) return false;
  if (before.deleted === true || after.deleted !== true || !after.media) return false;
  await deps.deleteFiles(`conversations/${cid}/${mid}/`);
  try {
    await deps.db.doc(`conversations/${cid}/messages/${mid}`).update({ media: FieldValue.delete() });
  } catch (err: any) {
    if (err?.code !== 5) throw err; // the message itself is already gone
  }
  logEvent('media_deleted', { count: 1, outcome: 'ok' });
  return true;
}

/** A message deleted for everyone can no longer be pinned: take it out of the chat's pins. */
export async function dropPinOfDeleted(
  deps: Deps,
  cid: string,
  mid: string,
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): Promise<boolean> {
  if (!before || !after || before.deleted === true || after.deleted !== true) return false;
  return removePinnedMessage(deps, cid, mid);
}

export const onMessageUpdated = onDocumentUpdated(
  { document: 'conversations/{cid}/messages/{mid}', region: REGION },
  async (event) => {
    const deps = defaultDeps();
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    await cleanupDeletedMedia(deps, event.params.cid, event.params.mid, before, after);
    await dropPinOfDeleted(deps, event.params.cid, event.params.mid, before, after);
  },
);
