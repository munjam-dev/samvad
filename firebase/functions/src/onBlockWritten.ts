import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

/**
 * Mirrors users/{uid}/blocked/{otherUid} into RTDB blocks/{uid}/{otherUid}, because RTDB rules
 * (presence) cannot read Firestore. Reads the current Firestore state rather than trusting the
 * event payload, so out-of-order or retried events still converge.
 */
export async function mirrorBlock(deps: Deps, uid: string, otherUid: string): Promise<void> {
  const blocked = (await deps.db.doc(`users/${uid}/blocked/${otherUid}`).get()).exists;
  await deps.rtdb.ref(`blocks/${uid}/${otherUid}`).set(blocked ? true : null);
  logEvent('block_mirrored', { outcome: blocked ? 'blocked' : 'unblocked' });
}

export const onBlockWritten = onDocumentWritten(
  { document: 'users/{uid}/blocked/{otherUid}', region: REGION },
  async (event) => {
    await mirrorBlock(defaultDeps(), event.params.uid, event.params.otherUid);
  },
);
