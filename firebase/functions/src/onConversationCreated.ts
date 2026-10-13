import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

/**
 * Mirrors conversation membership into the Realtime Database (members/{cid}/{uid} = true),
 * because RTDB rules for typing cannot read Firestore. Clients can never write the mirror.
 * Setting the whole node makes the trigger idempotent.
 */
export async function mirrorMembers(deps: Deps, cid: string, members: string[]): Promise<void> {
  const valid = members.filter((m) => typeof m === 'string' && m.length > 0);
  if (valid.length === 0) return;
  await deps.rtdb.ref(`members/${cid}`).set(Object.fromEntries(valid.map((m) => [m, true])));
  logEvent('members_mirrored', { count: valid.length, outcome: 'ok' });
}

export const onConversationCreated = onDocumentCreated(
  { document: 'conversations/{cid}', region: REGION },
  async (event) => {
    const members = (event.data?.get('members') as string[] | undefined) ?? [];
    await mirrorMembers(defaultDeps(), event.params.cid, members);
  },
);
