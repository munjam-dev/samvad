import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, Timestamp } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { requirePhoneAuth } from './lib/guards';
import { consumeDailyBudget } from './lib/budget';
import { logEvent, newCorrelationId } from './lib/logger';
import { LIVEKIT_SECRETS, TOKEN_TTL_SECONDS, defaultLiveKit, requireLiveKit, type LiveKitService } from './lib/livekit';

// Phase 7: group meetings (many-to-many calls). A meeting opens 30 minutes before its scheduled start
// and stays joinable for 4 hours after, caps at 50 participants, and the host can end it for everyone.

export const MEETING_OPEN_BEFORE_MS = 30 * 60 * 1000;
export const MEETING_OPEN_AFTER_MS = 4 * 60 * 60 * 1000;
export const MAX_MEETING_PARTICIPANTS = 50;
export const MAX_TITLE = 120;
export const DAILY_MEETINGS = 10;

const GID = /^[A-Za-z0-9]{20}$/;
const MEETING_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export type MeetingKind = 'audio' | 'video';

function meetingKind(raw: unknown): MeetingKind {
  if (raw !== 'audio' && raw !== 'video') throw new HttpsError('invalid-argument', 'kind must be audio or video.');
  return raw;
}

function meetingTitle(raw: unknown): string {
  const title = typeof raw === 'string' ? raw.trim() : '';
  if (title.length < 1 || title.length > MAX_TITLE) throw new HttpsError('invalid-argument', 'Title must be 1–120 characters.');
  return title;
}

function meetingIdOf(data: unknown): string {
  const id = (data as { meetingId?: unknown } | null)?.meetingId;
  if (typeof id !== 'string' || !MEETING_ID.test(id)) throw new HttpsError('invalid-argument', 'Unknown meeting.');
  return id;
}

/** Pure window check: open from [MEETING_OPEN_BEFORE_MS] before start to [MEETING_OPEN_AFTER_MS] after. */
export function isMeetingWindowOpen(startsAtMs: number, nowMs: number): boolean {
  return nowMs >= startsAtMs - MEETING_OPEN_BEFORE_MS && nowMs <= startsAtMs + MEETING_OPEN_AFTER_MS;
}

export async function scheduleMeetingCore(
  deps: Deps,
  actor: string,
  groupId: string,
  title: string,
  startsAtMs: number,
  kind: MeetingKind,
): Promise<{ meetingId: string }> {
  const conv = await deps.db.doc(`conversations/${groupId}`).get();
  const admins: string[] = conv.exists ? conv.get('admins') ?? [] : [];
  if (!conv.exists || conv.get('type') !== 'group' || !admins.includes(actor)) {
    throw new HttpsError('permission-denied', 'Only group admins can schedule a meeting.');
  }
  const meetingId = randomUUID();
  const roomName = `meeting-${randomUUID()}`;
  const startsAt = Timestamp.fromMillis(startsAtMs);
  await deps.db.doc(`meetings/${meetingId}`).set({
    groupId,
    hostUid: actor,
    title,
    kind,
    startsAt,
    status: 'scheduled',
    roomName,
    participants: [],
    createdAt: FieldValue.serverTimestamp(),
  });
  await deps.db.doc(`conversations/${groupId}/messages/${randomUUID()}`).set({
    senderId: actor,
    kind: 'meeting',
    text: '',
    replyTo: null,
    createdAt: FieldValue.serverTimestamp(),
    deleted: false,
    reactions: {},
    meeting: { meetingId, title, startsAt, kind },
  });
  return { meetingId };
}

async function loadOpenMeeting(deps: Deps, meetingId: string, uid: string) {
  const snap = await deps.db.doc(`meetings/${meetingId}`).get();
  if (!snap.exists) throw new HttpsError('not-found', 'Unknown meeting.');
  const groupId = snap.get('groupId') as string;
  const conv = await deps.db.doc(`conversations/${groupId}`).get();
  const members: string[] = conv.exists ? conv.get('members') ?? [] : [];
  if (!conv.exists || !members.includes(uid)) throw new HttpsError('permission-denied', 'Not allowed.');
  if (snap.get('status') !== 'scheduled') throw new HttpsError('failed-precondition', 'meeting-ended');
  const startsAt = snap.get('startsAt') as Timestamp;
  if (!isMeetingWindowOpen(startsAt.toMillis(), deps.nowMs())) throw new HttpsError('failed-precondition', 'meeting-not-open');
  return snap;
}

export async function getMeetingTokenCore(
  deps: Deps,
  uid: string,
  meetingId: string,
  livekit: LiveKitService | null,
): Promise<{ url: string; token: string; roomName: string }> {
  const kit = requireLiveKit(livekit);
  await loadOpenMeeting(deps, meetingId, uid);
  const ref = deps.db.doc(`meetings/${meetingId}`);
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const participants: string[] = snap.get('participants') ?? [];
    if (participants.includes(uid)) return;
    if (participants.length >= MAX_MEETING_PARTICIPANTS) throw new HttpsError('resource-exhausted', 'meeting-full');
    tx.update(ref, { participants: FieldValue.arrayUnion(uid) });
  });
  const snap = await ref.get();
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

export async function endMeetingCore(deps: Deps, uid: string, meetingId: string, livekit: LiveKitService | null): Promise<void> {
  let roomName: string | null = null;
  await deps.db.runTransaction(async (tx) => {
    const ref = deps.db.doc(`meetings/${meetingId}`);
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError('not-found', 'Unknown meeting.');
    if (snap.get('hostUid') !== uid) throw new HttpsError('permission-denied', 'Only the host can end this meeting.');
    if (snap.get('status') !== 'scheduled') return;
    roomName = snap.get('roomName') as string;
    tx.update(ref, { status: 'ended', endedAt: FieldValue.serverTimestamp() });
  });
  if (roomName && livekit) await livekit.closeRoom(roomName);
}

/** Account deletion: the person just drops off any meeting they had joined; hosting stays attributed (history). */
export async function cleanupMeetingsForDeletedAccount(deps: Deps, uid: string): Promise<void> {
  const joined = await deps.db.collection('meetings').where('participants', 'array-contains', uid).get();
  for (const d of joined.docs) {
    await d.ref.update({ participants: FieldValue.arrayRemove(uid) }).catch(() => undefined);
  }
}

// ---- callables ------------------------------------------------------------------------------------

type Req = CallableRequest<unknown>;

export const handleScheduleMeeting = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = (r.data ?? {}) as { groupId?: unknown; title?: unknown; startsAt?: unknown; kind?: unknown };
  if (typeof d.groupId !== 'string' || !GID.test(d.groupId)) throw new HttpsError('invalid-argument', 'Unknown group.');
  const title = meetingTitle(d.title);
  const kind = meetingKind(d.kind);
  const startsAtMs = typeof d.startsAt === 'number' ? d.startsAt : NaN;
  if (!Number.isFinite(startsAtMs) || startsAtMs <= deps.nowMs()) {
    throw new HttpsError('invalid-argument', 'startsAt must be a future time.');
  }
  await consumeDailyBudget(deps, uid, 'meeting', DAILY_MEETINGS);
  const correlationId = newCorrelationId();
  try {
    const result = await scheduleMeetingCore(deps, uid, d.groupId, title, startsAtMs, kind);
    logEvent('meeting_scheduled', { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('meeting_scheduled', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('meeting_scheduled', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const handleGetMeetingToken = async (
  r: Req,
  deps: Deps = defaultDeps(),
  livekit: LiveKitService | null = defaultLiveKit(),
) => {
  const { uid } = requirePhoneAuth(r);
  const meetingId = meetingIdOf(r.data);
  const correlationId = newCorrelationId();
  try {
    const result = await getMeetingTokenCore(deps, uid, meetingId, livekit);
    logEvent('meeting_token_minted', { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent('meeting_token_minted', { correlationId, outcome: err.code });
      throw err;
    }
    logEvent('meeting_token_minted', { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
};

export const handleEndMeeting = async (
  r: Req,
  deps: Deps = defaultDeps(),
  livekit: LiveKitService | null = defaultLiveKit(),
) => {
  const { uid } = requirePhoneAuth(r);
  const meetingId = meetingIdOf(r.data);
  await endMeetingCore(deps, uid, meetingId, livekit);
  return { ok: true };
};

const callableOpts = () => ({ ...buildCallableOptions(process.env), secrets: LIVEKIT_SECRETS });

export const scheduleMeeting = onCall(buildCallableOptions(process.env), (r) => handleScheduleMeeting(r));
export const getMeetingToken = onCall(callableOpts(), (r) => handleGetMeetingToken(r));
export const endMeeting = onCall(callableOpts(), (r) => handleEndMeeting(r));
