import { onDocumentUpdated } from 'firebase-functions/v2/firestore';
import { FieldValue } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

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

export const onMessageUpdated = onDocumentUpdated(
  { document: 'conversations/{cid}/messages/{mid}', region: REGION },
  async (event) => {
    await cleanupDeletedMedia(
      defaultDeps(),
      event.params.cid,
      event.params.mid,
      event.data?.before.data(),
      event.data?.after.data(),
    );
  },
);
