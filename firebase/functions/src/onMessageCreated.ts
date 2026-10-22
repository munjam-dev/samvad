import { onDocumentCreated } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { mirrorEngaged } from './engagement';
import { logEvent } from './lib/logger';

export const PREVIEW_LENGTH = 120;
const RECIPIENT_PARALLELISM = 25;

export interface CreatedMessage {
  cid: string;
  mid: string;
  eventId: string;
  data: {
    senderId: string;
    kind: string;
    text: string;
    createdAt: Timestamp;
    media?: { name?: string } | null;
    /** Groups: UIDs mentioned with @; they are notified even when the group is muted. */
    mentions?: string[];
    /** Server-written group events ("added", "left"…); never pushed. */
    system?: Record<string, unknown>;
    poll?: { question?: string };
    /** Group events (Phase 6): the card in the timeline, written only by createGroupEvent. */
    eventId?: string;
    eventTitle?: string;
    /** Shared contact card; only its name is used in previews (never the phone numbers). */
    contact?: { name?: string };
    meeting?: { title?: string };
  };
}

/** Chat-list and notification preview: media kinds get a short label, then the caption. */
export function previewOf(data: CreatedMessage['data']): string {
  const caption = (data.text ?? '').trim();
  const label = (() => {
    switch (data.kind) {
      case 'image': return '📷 Photo';
      case 'voice': return '🎤 Voice message';
      case 'file': return `📄 ${data.media?.name ?? 'File'}`;
      case 'poll': return `📊 ${data.poll?.question ?? 'Poll'}`;
      case 'event': return `📅 ${data.eventTitle ?? 'Event'}`;
      case 'video': return '🎬 Video';
      case 'gif': return 'GIF';
      case 'sticker': return 'Sticker';
      case 'contact': return `👤 ${data.contact?.name ?? 'Contact'}`;
      case 'meeting': return `📅 ${data.meeting?.title ?? 'Meeting'}`;
      default: return '';
    }
  })();
  const text = label && caption ? `${label} · ${caption}` : label || caption;
  return text.slice(0, PREVIEW_LENGTH);
}

/**
 * Denormalises the newest message onto the conversation and, in direct chats, bumps the other member's
 * unread count (groups count unread on the device instead: a counter per member would cost up to 256
 * writes per message). Runs once per event ID, so a redelivered trigger changes nothing.
 */
export async function applyMessageCreated(deps: Deps, msg: CreatedMessage): Promise<void> {
  const { db } = deps;
  const convRef = db.doc(`conversations/${msg.cid}`);
  const markerRef = db.doc(`processedEvents/${msg.eventId}`);
  let recipients: string[] = [];
  let group: { name: string; community?: boolean } | null = null;
  let newlyEngaged = false;

  await db.runTransaction(async (tx) => {
    recipients = [];
    group = null;
    newlyEngaged = false;
    const [marker, conv] = await Promise.all([tx.get(markerRef), tx.get(convRef)]);
    if (marker.exists || !conv.exists) return;

    const type = conv.get('type');
    const isGroup = type === 'group';
    // Announcements (up to 1,000 members) count unread on the device like groups: no counter per member.
    const isAnnouncements = type === 'community_announcements';
    // Disappearing messages: the timer in force right now decides this message's expiry (written below).
    const timer = Number(conv.get('disappearAfterSec') ?? 0);
    if (isGroup) group = { name: String(conv.get('name') ?? '') };
    if (isAnnouncements) group = { name: String(conv.get('name') ?? ''), community: true };
    const members: string[] = conv.get('members') ?? [];
    // Phase 3: a person who writes in a direct chat becomes one of its engaged people ("People I chat with").
    if (!isGroup && msg.data.kind !== 'system' && !((conv.get('engagedBy') ?? []) as string[]).includes(msg.data.senderId)) {
      newlyEngaged = true;
    }
    const others = members.filter((m) => m !== msg.data.senderId);
    recipients = msg.data.kind === 'system' ? [] : others;
    const memberSnaps = isGroup || isAnnouncements ? [] : await Promise.all(others.map((m) => tx.get(convRef.collection('members').doc(m))));

    if (newlyEngaged) tx.update(convRef, { engagedBy: FieldValue.arrayUnion(msg.data.senderId) });

    const current = conv.get('lastMessage');
    const currentAt: Timestamp | undefined = current?.createdAt;
    if (!currentAt || msg.data.createdAt.toMillis() >= currentAt.toMillis()) {
      tx.update(convRef, {
        lastMessage: {
          id: msg.mid,
          senderId: msg.data.senderId,
          preview: previewOf(msg.data),
          kind: msg.data.kind,
          createdAt: msg.data.createdAt,
          ...(msg.data.kind === 'system' && msg.data.system ? { system: msg.data.system } : {}),
          ...(timer > 0 ? { expiresAt: Timestamp.fromMillis(msg.data.createdAt.toMillis() + timer * 1000) } : {}),
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

    // The server alone stamps the expiry; clients cannot (rules). Written after all reads in this transaction.
    if (timer > 0) {
      tx.update(convRef.collection('messages').doc(msg.mid), {
        expiresAt: Timestamp.fromMillis(msg.data.createdAt.toMillis() + timer * 1000),
      });
    }

    tx.set(markerRef, { at: FieldValue.serverTimestamp() });
  });
  logEvent('message_fanout', { count: 1, outcome: 'ok' });
  if (newlyEngaged) await mirrorEngaged(deps, msg.cid, [msg.data.senderId]).catch(() => logEvent('engaged_mirror_failed', { outcome: 'error' }));

  // Push only when this delivery applied the event, so a redelivered trigger never re-notifies.
  if (recipients.length > 0) {
    try {
      await notifyRecipients(deps, msg, recipients, group);
    } catch {
      logEvent('push_failed', { outcome: 'error' });
    }
  }
}

/**
 * Sends a data-only push to every active device of each recipient who hasn't muted the chat.
 * The app builds the visible notification, so it can stay quiet while the chat is open.
 * Dead tokens are cleared so they aren't retried.
 */
export async function notifyRecipients(
  deps: Deps,
  msg: CreatedMessage,
  recipients: string[],
  group: { name: string; community?: boolean } | null = null,
): Promise<void> {
  const { db } = deps;
  const sender = await db.doc(`profiles/${msg.data.senderId}`).get();
  const senderName = (sender.get('displayName') as string | undefined) ?? 'New message';
  const title = group ? group.name : senderName;
  const body = group ? `${senderName}: ${previewOf(msg.data)}` : previewOf(msg.data);
  const mentioned = new Set(Array.isArray(msg.data.mentions) ? msg.data.mentions : []);
  const tokenOwners = new Map<string, FirebaseFirestore.DocumentReference>();

  // A few recipients at a time: an announcement can reach 1,000 members, which must not run one by one.
  for (let i = 0; i < recipients.length; i += RECIPIENT_PARALLELISM) {
    await Promise.all(
      recipients.slice(i, i + RECIPIENT_PARALLELISM).map(async (uid) => {
        const member = await db.doc(`conversations/${msg.cid}/members/${uid}`).get();
        if (member.get('muted') === true && !mentioned.has(uid)) return;
        const devices = await db.collection(`users/${uid}/devices`).where('fcmToken', '!=', null).get();
        for (const d of devices.docs) {
          if (d.get('revokedAt')) continue;
          const token = d.get('fcmToken');
          if (typeof token === 'string' && token.length > 0) tokenOwners.set(token, d.ref);
        }
      }),
    );
  }
  if (tokenOwners.size === 0) return;

  const { invalidTokens } = await deps.push.send([...tokenOwners.keys()], {
    type: 'message',
    cid: msg.cid,
    mid: msg.mid,
    senderId: msg.data.senderId,
    title,
    body: body.slice(0, PREVIEW_LENGTH + 66),
    ...(group ? { group: '1' } : {}),
    ...(group?.community ? { community: '1' } : {}),
  });
  for (const t of invalidTokens) {
    await tokenOwners.get(t)?.update({ fcmToken: null });
  }
  logEvent('push_sent', { count: tokenOwners.size, outcome: 'ok' });
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
