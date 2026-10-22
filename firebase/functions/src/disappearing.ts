import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { onDocumentDeleted } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { REGION, buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { previewOf } from './onMessageCreated';
import { removePinnedMessage } from './pinned';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';

/** Off, 24 hours, 7 days, 90 days. */
export const TIMER_CHOICES = [0, 86_400, 604_800, 7_776_000] as const;
export const DAILY_TIMER_CHANGES = 20;
const CID = /^[A-Za-z0-9_-]{3,128}$/;

export function timerSeconds(raw: unknown): number {
  if (typeof raw !== 'number' || !(TIMER_CHOICES as readonly number[]).includes(raw)) {
    throw new HttpsError('invalid-argument', 'Choose off, 24 hours, 7 days or 90 days.');
  }
  return raw;
}

/**
 * Sets the chat's disappearing-message timer. Direct chats: either member. Groups: admins only. Clients can
 * never write the field (the rules have no update path for conversations), so this is the only way.
 * Writes a visible system line naming who changed it. Only messages sent afterwards are affected.
 */
export async function setTimerCore(deps: Deps, actor: string, cid: string, seconds: number): Promise<void> {
  await consumeDailyBudget(deps, actor, 'timer', DAILY_TIMER_CHANGES);
  await deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`conversations/${cid}`);
    const snap = await tx.get(ref);
    const members: string[] = snap.exists ? snap.get('members') ?? [] : [];
    if (!snap.exists || !members.includes(actor)) throw new HttpsError('permission-denied', 'Not allowed.');
    if (snap.get('type') !== 'direct' && !(snap.get('admins') ?? []).includes(actor)) {
      throw new HttpsError('permission-denied', 'Only admins can change this.');
    }
    if (Number(snap.get('disappearAfterSec') ?? 0) === seconds) return;
    tx.update(ref, { disappearAfterSec: seconds });
    tx.set(ref.collection('messages').doc(randomUUID()), {
      senderId: actor,
      kind: 'system',
      text: '',
      replyTo: null,
      createdAt: FieldValue.serverTimestamp(),
      deleted: false,
      reactions: {},
      system: { event: 'disappearing', seconds },
    });
  });
}

/**
 * Removes everything that belonged to a message once it is gone (by expiry or any other deletion): its files,
 * its poll votes, its pin and every starred reference to it. Safe to run twice.
 */
export async function cleanupGoneMessage(deps: Deps, cid: string, mid: string): Promise<void> {
  await deps.deleteFiles(`conversations/${cid}/${mid}/`);
  await deps.db.recursiveDelete(deps.db.collection(`conversations/${cid}/messages/${mid}/votes`));
  const saved = await deps.db.collectionGroup('savedMessages').where('messageId', '==', mid).get();
  const writer = deps.db.bulkWriter();
  for (const d of saved.docs) if (d.get('conversationId') === cid) void writer.delete(d.ref);
  await writer.close();
  await removePinnedMessage(deps, cid, mid);
  await refreshLastMessage(deps, cid, mid);
  logEvent('message_cleanup', { count: 1, outcome: 'ok' });
}

/**
 * The chat list reads the newest message from a copy on the conversation. When that message is gone, replace
 * the copy with the newest message that is left (or clear it), so its text does not outlive the message.
 */
async function refreshLastMessage(deps: Deps, cid: string, gone: string): Promise<void> {
  const ref = deps.db.doc(`conversations/${cid}`);
  const conv = await ref.get();
  if (!conv.exists || conv.get('lastMessage.id') !== gone) return;
  const newest = await ref.collection('messages').orderBy('createdAt', 'desc').limit(1).get();
  const next = newest.docs[0];
  if (!next) {
    await ref.update({ lastMessage: null });
    return;
  }
  const data = next.data() as Record<string, any>;
  const timer = data.expiresAt as Timestamp | undefined;
  await ref.update({
    lastMessage: {
      id: next.id,
      senderId: data.senderId,
      preview: data.deleted ? '' : previewOf({ ...data, createdAt: data.createdAt } as any),
      kind: data.kind,
      ...(data.enc ? { encrypted: true } : {}),
      createdAt: data.createdAt,
      ...(data.kind === 'system' && data.system ? { system: data.system } : {}),
      ...(timer ? { expiresAt: timer } : {}),
    },
  });
}

type Req = CallableRequest<unknown>;

export const handleSetDisappearingTimer = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { conversationId?: unknown; seconds?: unknown };
  if (typeof d.conversationId !== 'string' || !CID.test(d.conversationId)) throw new HttpsError('invalid-argument', 'Unknown chat.');
  const seconds = timerSeconds(d.seconds);
  const correlationId = newCorrelationId();
  try {
    await setTimerCore(deps, uid, d.conversationId, seconds);
    logEvent('disappearing_timer_set', { correlationId, outcome: 'ok' });
    return { ok: true };
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('disappearing_timer_set', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('disappearing_timer_set', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const setDisappearingTimer = onCall(buildCallableOptions(process.env), (r) => handleSetDisappearingTimer(r));

export const onMessageDeleted = onDocumentDeleted({ document: 'conversations/{cid}/messages/{mid}', region: REGION }, async (event) => {
  await cleanupGoneMessage(defaultDeps(), event.params.cid, event.params.mid);
});
