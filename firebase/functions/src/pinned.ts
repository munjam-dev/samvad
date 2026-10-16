import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';

/** Pinned messages: at most this many per chat, shown in a banner at the top of the conversation. */
export const MAX_PINS = 3;
export const DAILY_PIN_CHANGES = 200;
const CID = /^[A-Za-z0-9_-]{3,128}$/;
const MID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export interface Pin {
  messageId: string;
  pinnedBy: string;
  pinnedAt: Timestamp;
}

/** The pins on a conversation document, oldest first; anything malformed is ignored. */
export function readPins(raw: unknown): Pin[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((p): p is Pin => !!p && typeof p.messageId === 'string');
}

/**
 * Pins or unpins one message. Direct chats: either person (not while either has blocked the other, for pinning).
 * Groups: admins only, the same model as every other group setting. Clients can never write the field (the rules
 * have no update path for conversations), so this is the only way. A pin needs a live message of this chat that
 * the caller can see; pinning twice does nothing; the fourth pin is refused. Writes no system line.
 */
export async function setPinnedCore(deps: Deps, actor: string, cid: string, mid: string, pinned: boolean): Promise<void> {
  await consumeDailyBudget(deps, actor, 'pin', DAILY_PIN_CHANGES);
  await deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`conversations/${cid}`);
    const snap = await tx.get(ref);
    const members: string[] = snap.exists ? snap.get('members') ?? [] : [];
    // Same answer for "no such chat" and "not yours", so ids cannot be probed.
    if (!snap.exists || !members.includes(actor)) throw new HttpsError('permission-denied', 'Not allowed.');
    const group = snap.get('type') === 'group';
    if (group && !(snap.get('admins') ?? []).includes(actor)) {
      throw new HttpsError('permission-denied', 'Only admins can change this.');
    }
    const pins = readPins(snap.get('pinnedMessages'));
    const index = pins.findIndex((p) => p.messageId === mid);

    if (!pinned) {
      if (index < 0) return;
      tx.update(ref, { pinnedMessages: pins.filter((p) => p.messageId !== mid) });
      return;
    }

    if (index >= 0) return;
    if (!group) {
      const [a, b] = members;
      const [ab, ba] = await Promise.all([tx.get(deps.db.doc(`users/${a}/blocked/${b}`)), tx.get(deps.db.doc(`users/${b}/blocked/${a}`))]);
      if (ab.exists || ba.exists) throw new HttpsError('permission-denied', 'Not allowed.');
    }
    const msgSnap = await tx.get(ref.collection('messages').doc(mid));
    const expires = msgSnap.get('expiresAt') as Timestamp | undefined;
    if (
      !msgSnap.exists ||
      msgSnap.get('deleted') === true ||
      msgSnap.get('kind') === 'system' ||
      (expires && expires.toMillis() <= deps.nowMs())
    ) {
      throw new HttpsError('not-found', 'Message not found.');
    }
    if (group) {
      // A group member sees only messages from their own join time onwards, so only those can be pinned.
      const member = await tx.get(ref.collection('members').doc(actor));
      const joined = member.get('joinedAt') as Timestamp | undefined;
      const created = msgSnap.get('createdAt') as Timestamp | undefined;
      if (joined && created && created.toMillis() < joined.toMillis()) throw new HttpsError('not-found', 'Message not found.');
    }
    if (pins.length >= MAX_PINS) throw new HttpsError('failed-precondition', 'pin-limit');
    tx.update(ref, { pinnedMessages: [...pins, { messageId: mid, pinnedBy: actor, pinnedAt: Timestamp.fromMillis(deps.nowMs()) }] });
  });
}

/**
 * Drops a message from the chat's pins once the message is gone (deleted for everyone, or expired).
 * Safe to run twice, and when the chat no longer exists.
 */
export async function removePinnedMessage(deps: Deps, cid: string, mid: string): Promise<boolean> {
  return deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`conversations/${cid}`);
    const snap = await tx.get(ref);
    if (!snap.exists) return false;
    const pins = readPins(snap.get('pinnedMessages'));
    if (!pins.some((p) => p.messageId === mid)) return false;
    tx.update(ref, { pinnedMessages: pins.filter((p) => p.messageId !== mid) });
    return true;
  });
}

type Req = CallableRequest<unknown>;

export const handleSetPinnedMessage = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { conversationId?: unknown; messageId?: unknown; pinned?: unknown };
  if (typeof d.conversationId !== 'string' || !CID.test(d.conversationId)) throw new HttpsError('invalid-argument', 'Unknown chat.');
  if (typeof d.messageId !== 'string' || !MID.test(d.messageId)) throw new HttpsError('invalid-argument', 'Unknown message.');
  if (typeof d.pinned !== 'boolean') throw new HttpsError('invalid-argument', 'Say whether to pin or unpin.');
  const correlationId = newCorrelationId();
  try {
    await setPinnedCore(deps, uid, d.conversationId, d.messageId, d.pinned);
    logEvent('message_pin_set', { correlationId, outcome: 'ok' });
    return { ok: true };
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('message_pin_set', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('message_pin_set', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const setPinnedMessage = onCall(buildCallableOptions(process.env), (r) => handleSetPinnedMessage(r));
