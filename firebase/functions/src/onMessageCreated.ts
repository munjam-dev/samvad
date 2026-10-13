import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { logEvent } from './lib/logger';

export const PREVIEW_LENGTH = 120;

export interface CreatedMessage {
  cid: string;
  mid: string;
  eventId: string;
  data: { senderId: string; kind: string; text: string; createdAt: Timestamp };
}

/**
 * Denormalises the newest message onto the conversation and bumps every other member's unread count.
 * Runs once per event ID, so a redelivered trigger changes nothing.
 */
export async function applyMessageCreated(deps: Deps, msg: CreatedMessage): Promise<void> {
  const { db } = deps;
  const convRef = db.doc(`conversations/${msg.cid}`);
  const markerRef = db.doc(`processedEvents/${msg.eventId}`);

  await db.runTransaction(async (tx) => {
    const [marker, conv] = await Promise.all([tx.get(markerRef), tx.get(convRef)]);
    if (marker.exists || !conv.exists) return;

    const members: string[] = conv.get('members') ?? [];
    const others = members.filter((m) => m !== msg.data.senderId);
    const memberSnaps = await Promise.all(others.map((m) => tx.get(convRef.collection('members').doc(m))));

    const current = conv.get('lastMessage');
    const currentAt: Timestamp | undefined = current?.createdAt;
    if (!currentAt || msg.data.createdAt.toMillis() >= currentAt.toMillis()) {
      tx.update(convRef, {
        lastMessage: {
          id: msg.mid,
          senderId: msg.data.senderId,
          preview: (msg.data.text ?? '').slice(0, PREVIEW_LENGTH),
          kind: msg.data.kind,
          createdAt: msg.data.createdAt,
        },
        lastMessageAt: msg.data.createdAt,
      });
    }

    memberSnaps.forEach((snap, i) => {
      if (snap.exists) {
        tx.update(snap.ref, { unreadCount: FieldValue.increment(1) });
      } else {
        tx.set(snap.ref, {
          uid: others[i],
          lastReadAt: null,
          lastDeliveredAt: null,
          unreadCount: 1,
          muted: false,
          pinned: false,
          archived: false,
        });
      }
    });

    tx.set(markerRef, { at: FieldValue.serverTimestamp() });
  });
  logEvent('message_fanout', { count: 1, outcome: 'ok' });
}

export const onMessageCreated = onDocumentCreated(
  { document: 'conversations/{cid}/messages/{mid}', region: REGION },
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    await applyMessageCreated(defaultDeps(), {
      cid: event.params.cid,
      mid: event.params.mid,
      eventId: event.id,
      data: snap.data() as CreatedMessage['data'],
    });
  },
);
