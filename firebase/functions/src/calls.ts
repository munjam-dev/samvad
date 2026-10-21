import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { REGION, buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';
import { LIVEKIT_SECRETS, TOKEN_TTL_SECONDS, defaultLiveKit, requireLiveKit, type LiveKitService } from './lib/livekit';

// Phase 7: 1:1 voice and video calls. Media never touches this server: calls/{callId} only coordinates
// ringing and who may join, and getCallToken mints a short-lived LiveKit room token. See docs/CALLING_SETUP.md.

export const RING_SECONDS = 45;
export const DAILY_CALLS = 40;
export type CallKind = 'audio' | 'video';
export type CallStatus = 'ringing' | 'active' | 'ended' | 'declined' | 'missed';

const CID = /^[A-Za-z0-9_-]{3,128}$/;
const CALL_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const BUSY_STATUSES = ['ringing', 'active'];

function callKind(raw: unknown): CallKind {
  if (raw !== 'audio' && raw !== 'video') throw new HttpsError('invalid-argument', 'kind must be audio or video.');
  return raw;
}

function callIdOf(data: unknown): string {
  const id = (data as { callId?: unknown } | null)?.callId;
  if (typeof id !== 'string' || !CALL_ID.test(id)) throw new HttpsError('invalid-argument', 'Unknown call.');
  return id;
}

/** True when either member of the direct chat has blocked the other. */
async function isBlockedPair(deps: Deps, a: string, b: string): Promise<boolean> {
  const [ab, ba] = await Promise.all([
    deps.db.doc(`users/${a}/blocked/${b}`).get(),
    deps.db.doc(`users/${b}/blocked/${a}`).get(),
  ]);
  return ab.exists || ba.exists;
}

/** Whether [uid] is a participant of a call that is currently ringing or active (ineligible to start/receive another). */
async function isBusy(deps: Deps, uid: string): Promise<boolean> {
  const busy = await deps.db.collection('calls')
    .where('participants', 'array-contains', uid)
    .where('status', 'in', BUSY_STATUSES)
    .limit(1)
    .get();
  return !busy.empty;
}

/**
 * Starts a 1:1 call: the caller must be a member of the direct conversation, with no block either way
 * and the callee not already on another call. Rings for RING_SECONDS; a data-only HIGH-priority push
 * wakes the callee's devices (no message content in it).
 */
export async function startCallCore(
  deps: Deps,
  callerUid: string,
  conversationId: string,
  kind: CallKind,
): Promise<{ callId: string; roomName: string }> {
  const conv = await deps.db.doc(`conversations/${conversationId}`).get();
  const members: string[] = conv.exists ? conv.get('members') ?? [] : [];
  if (!conv.exists || conv.get('type') !== 'direct' || !members.includes(callerUid)) {
    throw new HttpsError('permission-denied', 'Not allowed.');
  }
  const calleeUid = members.find((m) => m !== callerUid);
  if (!calleeUid) throw new HttpsError('not-found', 'No one to call.');
  if (await isBlockedPair(deps, callerUid, calleeUid)) throw new HttpsError('permission-denied', 'Not allowed.');
  if (await isBusy(deps, calleeUid)) throw new HttpsError('failed-precondition', 'callee-busy');
  if (await isBusy(deps, callerUid)) throw new HttpsError('failed-precondition', 'already-in-call');

  const callId = randomUUID();
  const roomName = `call-${randomUUID()}`;
  const now = deps.nowMs();
  await deps.db.doc(`calls/${callId}`).set({
    conversationId,
    callerUid,
    participants: [callerUid, calleeUid],
    kind,
    status: 'ringing' as CallStatus,
    roomName,
    createdAt: Timestamp.fromMillis(now),
    expiresAt: Timestamp.fromMillis(now + RING_SECONDS * 1000),
    answeredAt: null,
    endedAt: null,
    endedBy: null,
  });

  const caller = await deps.db.doc(`profiles/${callerUid}`).get();
  const callerName = (caller.get('displayName') as string | undefined) ?? 'Samvad';
  const devices = await deps.db.collection(`users/${calleeUid}/devices`).where('fcmToken', '!=', null).get();
  const tokenOwners = new Map<string, FirebaseFirestore.DocumentReference>();
  for (const d of devices.docs) {
    if (d.get('revokedAt')) continue;
    const token = d.get('fcmToken');
    if (typeof token === 'string' && token.length > 0) tokenOwners.set(token, d.ref);
  }
  if (tokenOwners.size > 0) {
    const { invalidTokens } = await deps.push.send([...tokenOwners.keys()], {
      type: 'incoming_call',
      callId,
      callerName,
      kind,
    });
    for (const t of invalidTokens) await tokenOwners.get(t)?.update({ fcmToken: null });
  }
  return { callId, roomName };
}

/** Loads a call and checks [uid] is one of its participants. */
async function loadCall(deps: Deps, callId: string, uid: string) {
  const snap = await deps.db.doc(`calls/${callId}`).get();
  const participants: string[] = snap.exists ? snap.get('participants') ?? [] : [];
  if (!snap.exists || !participants.includes(uid)) throw new HttpsError('not-found', 'Unknown call.');
  return snap;
}

export async function answerCallCore(deps: Deps, uid: string, callId: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`calls/${callId}`);
    const snap = await tx.get(ref);
    const participants: string[] = snap.exists ? snap.get('participants') ?? [] : [];
    if (!snap.exists || !participants.includes(uid)) throw new HttpsError('not-found', 'Unknown call.');
    if (snap.get('callerUid') === uid) throw new HttpsError('permission-denied', 'Only the callee can answer.');
    const expiresAt = snap.get('expiresAt') as Timestamp | undefined;
    if (snap.get('status') !== 'ringing' || (expiresAt && expiresAt.toMillis() <= deps.nowMs())) {
      throw new HttpsError('failed-precondition', 'call-ended');
    }
    tx.update(ref, { status: 'active', answeredAt: Timestamp.fromMillis(deps.nowMs()) });
  });
}

export async function declineCallCore(deps: Deps, uid: string, callId: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`calls/${callId}`);
    const snap = await tx.get(ref);
    const participants: string[] = snap.exists ? snap.get('participants') ?? [] : [];
    if (!snap.exists || !participants.includes(uid)) throw new HttpsError('not-found', 'Unknown call.');
    if (snap.get('callerUid') === uid) throw new HttpsError('permission-denied', 'Only the callee can decline.');
    if (snap.get('status') !== 'ringing') throw new HttpsError('failed-precondition', 'call-ended');
    tx.update(ref, { status: 'declined', endedAt: Timestamp.fromMillis(deps.nowMs()), endedBy: uid });
  });
}

export async function endCallCore(deps: Deps, uid: string, callId: string, livekit: LiveKitService | null): Promise<void> {
  let roomName: string | null = null;
  let cancelled: FirebaseFirestore.DocumentData | null = null;
  await deps.db.runTransaction(async (tx) => {
    roomName = null;
    cancelled = null;
    const ref = deps.db.doc(`calls/${callId}`);
    const snap = await tx.get(ref);
    const participants: string[] = snap.exists ? snap.get('participants') ?? [] : [];
    if (!snap.exists || !participants.includes(uid)) throw new HttpsError('not-found', 'Unknown call.');
    const status = snap.get('status') as CallStatus;
    if (status === 'ended' || status === 'declined' || status === 'missed') return;
    roomName = snap.get('roomName') as string;
    const endedAt = Timestamp.fromMillis(deps.nowMs());
    if (status === 'ringing' && snap.get('callerUid') === uid) {
      // The caller gave up before an answer: the callee sees a missed call.
      tx.update(ref, { status: 'missed', endedAt, endedBy: uid });
      cancelled = snap.data() ?? null;
    } else if (status === 'ringing') {
      tx.update(ref, { status: 'declined', endedAt, endedBy: uid });
    } else {
      tx.update(ref, { status: 'ended', endedAt, endedBy: uid });
    }
  });
  if (cancelled) await notifyMissed(deps, callId, cancelled);
  if (roomName && livekit) await livekit.closeRoom(roomName);
}

export async function getCallTokenCore(
  deps: Deps,
  uid: string,
  callId: string,
  livekit: LiveKitService | null,
): Promise<{ url: string; token: string; roomName: string }> {
  const kit = requireLiveKit(livekit);
  const snap = await loadCall(deps, callId, uid);
  const status = snap.get('status') as CallStatus;
  if (status !== 'ringing' && status !== 'active') throw new HttpsError('failed-precondition', 'call-ended');
  const expiresAt = snap.get('expiresAt') as Timestamp | undefined;
  if (status === 'ringing' && expiresAt && expiresAt.toMillis() <= deps.nowMs()) {
    throw new HttpsError('failed-precondition', 'call-ended');
  }
  const profile = await deps.db.doc(`profiles/${uid}`).get();
  const name = (profile.get('displayName') as string | undefined) ?? 'Samvad';
  const roomName = snap.get('roomName') as string;
  const token = await kit.mintToken({
    identity: uid,
    name,
    room: roomName,
    ttlSeconds: TOKEN_TTL_SECONDS,
    video: snap.get('kind') === 'video',
  });
  return { url: kit.url, token, roomName };
}

/** Tells the callee's devices the ring is over (stops the ringing and leaves a missed-call line). */
async function notifyMissed(deps: Deps, callId: string, data: FirebaseFirestore.DocumentData): Promise<void> {
  const calleeUid = (data.participants as string[]).find((p) => p !== data.callerUid);
  if (!calleeUid) return;
  const caller = await deps.db.doc(`profiles/${data.callerUid}`).get();
  const callerName = (caller.get('displayName') as string | undefined) ?? 'Samvad';
  const devices = await deps.db.collection(`users/${calleeUid}/devices`).where('fcmToken', '!=', null).get();
  const tokens = devices.docs.map((dd) => dd.get('fcmToken')).filter((t): t is string => typeof t === 'string' && t.length > 0);
  if (tokens.length > 0) {
    await deps.push.send(tokens, { type: 'missed_call', callId, callerUid: String(data.callerUid), callerName, kind: String(data.kind) });
  }
}

/**
 * Marks every call still 'ringing' past its expiry as 'missed' and tells the callee. Runs every
 * minute, like the scheduled-message sender; each call is claimed once so a redelivered run is safe.
 */
export async function cleanupMissedCallsCore(deps: Deps, nowMs: number = deps.nowMs()): Promise<number> {
  const due = await deps.db.collection('calls')
    .where('status', '==', 'ringing')
    .where('expiresAt', '<=', Timestamp.fromMillis(nowMs))
    .limit(200)
    .get();
  let missed = 0;
  for (const d of due.docs) {
    const outcome = await deps.db.runTransaction(async (tx) => {
      const snap = await tx.get(d.ref);
      if (!snap.exists || snap.get('status') !== 'ringing') return false;
      tx.update(d.ref, { status: 'missed', endedAt: Timestamp.fromMillis(nowMs) });
      return true;
    });
    if (!outcome) continue;
    missed++;
    await notifyMissed(deps, d.id, d.data());
  }
  if (missed > 0) logEvent('calls_missed', { count: missed, outcome: 'ok' });
  return missed;
}

/** Account deletion: live calls involving the deleted user are ended; history is left as-is (like messages). */
export async function cleanupCallsForDeletedAccount(deps: Deps, uid: string): Promise<void> {
  const live = await deps.db.collection('calls')
    .where('participants', 'array-contains', uid)
    .where('status', 'in', BUSY_STATUSES)
    .get();
  const now = Timestamp.fromMillis(deps.nowMs());
  for (const d of live.docs) {
    await d.ref.update({ status: 'ended', endedAt: now, endedBy: uid }).catch(() => undefined);
  }
}

// ---- callables ------------------------------------------------------------------------------------

type Req = CallableRequest<unknown>;

export const handleStartCall = async (
  r: Req,
  deps: Deps = defaultDeps(),
): Promise<{ callId: string; roomName: string }> => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { conversationId?: unknown; kind?: unknown };
  if (typeof d.conversationId !== 'string' || !CID.test(d.conversationId)) throw new HttpsError('invalid-argument', 'Unknown chat.');
  const kind = callKind(d.kind);
  await consumeDailyBudget(deps, uid, 'call', DAILY_CALLS);
  const correlationId = newCorrelationId();
  try {
    const result = await startCallCore(deps, uid, d.conversationId, kind);
    logEvent('call_started', { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('call_started', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('call_started', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const handleAnswerCall = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const callId = callIdOf(r.data);
  await answerCallCore(deps, uid, callId);
  return { ok: true };
};

export const handleDeclineCall = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const callId = callIdOf(r.data);
  await declineCallCore(deps, uid, callId);
  return { ok: true };
};

export const handleEndCall = async (r: Req, deps: Deps = defaultDeps(), livekit: LiveKitService | null = defaultLiveKit()) => {
  const { uid } = requirePhoneAuth(r);
  const callId = callIdOf(r.data);
  await endCallCore(deps, uid, callId, livekit);
  return { ok: true };
};

export const handleGetCallToken = async (
  r: Req,
  deps: Deps = defaultDeps(),
  livekit: LiveKitService | null = defaultLiveKit(),
) => {
  const { uid } = requirePhoneAuth(r);
  const callId = callIdOf(r.data);
  const correlationId = newCorrelationId();
  try {
    const result = await getCallTokenCore(deps, uid, callId, livekit);
    logEvent('call_token_minted', { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('call_token_minted', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('call_token_minted', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

const callableOpts = () => ({ ...buildCallableOptions(process.env), secrets: LIVEKIT_SECRETS });

export const startCall = onCall(callableOpts(), (r) => handleStartCall(r));
export const answerCall = onCall(buildCallableOptions(process.env), (r) => handleAnswerCall(r));
export const declineCall = onCall(buildCallableOptions(process.env), (r) => handleDeclineCall(r));
export const endCall = onCall(callableOpts(), (r) => handleEndCall(r));
export const getCallToken = onCall(callableOpts(), (r) => handleGetCallToken(r));

export const cleanupMissedCalls = onSchedule(
  { schedule: 'every 1 minutes', region: REGION, timeZone: 'Asia/Kolkata', retryCount: 0, maxInstances: 1 },
  async () => {
    await cleanupMissedCallsCore(defaultDeps());
  },
);
