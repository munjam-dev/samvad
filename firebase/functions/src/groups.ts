import { randomInt, randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { FieldValue, type DocumentReference, type Transaction } from 'firebase-admin/firestore';
import { buildCallableOptions } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
import { logEvent, newCorrelationId } from './lib/logger';
import { directCid, isEngagedPartner, parsePrivacy, privacyPath } from './lib/privacy';
import { cleanPhoto, parsePhoto } from './setProfilePhoto';

// Phase 2: groups. See docs/superpowers/specs/2026-10-13-phase2-groups.md. Group conversation
// documents are written only here; the rules let members read them and post messages.

export const MAX_GROUP_MEMBERS = 256;
export const MAX_NAME = 64;
export const MAX_DESCRIPTION = 512;
export const DAILY_GROUPS = 10;
export const DAILY_JOINS = 20;
const UID = /^[A-Za-z0-9]{1,128}$/;
const CID = /^[A-Za-z0-9]{20}$/;
export const INVITE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const INVITE = new RegExp(`^[${INVITE_ALPHABET}]{12}$`);

type SystemEvent = 'created' | 'added' | 'removed' | 'left' | 'joined' | 'promoted' | 'demoted' | 'renamed' | 'photo';

// ---- input --------------------------------------------------------------------------------------

export function groupName(raw: unknown): string {
  const name = typeof raw === 'string' ? raw.trim() : '';
  if (name.length < 1 || name.length > MAX_NAME) throw new HttpsError('invalid-argument', 'Group name must be 1–64 characters.');
  return name;
}

function uidList(raw: unknown, max: number): string[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > max || !raw.every((u) => typeof u === 'string' && UID.test(u))) {
    throw new HttpsError('invalid-argument', 'Invalid member list.');
  }
  return [...new Set(raw as string[])];
}

function cidOf(data: unknown): string {
  const cid = (data as { cid?: unknown } | null)?.cid;
  if (typeof cid !== 'string' || !CID.test(cid)) throw new HttpsError('invalid-argument', 'Unknown group.');
  return cid;
}

function uidOf(data: unknown): string {
  const uid = (data as { uid?: unknown } | null)?.uid;
  if (typeof uid !== 'string' || !UID.test(uid)) throw new HttpsError('invalid-argument', 'Unknown member.');
  return uid;
}

export function newInviteCode(): string {
  return Array.from({ length: 12 }, () => INVITE_ALPHABET[randomInt(INVITE_ALPHABET.length)]).join('');
}

// ---- shared steps -------------------------------------------------------------------------------

const convRef = (deps: Deps, cid: string) => deps.db.doc(`conversations/${cid}`);

interface Group {
  ref: DocumentReference;
  members: string[];
  admins: string[];
  name: string;
}

/** Reads the group inside [tx]; the caller must be a member (and an admin when [admin]). */
async function loadGroup(tx: Transaction, deps: Deps, cid: string, actor: string, admin: boolean): Promise<Group> {
  const ref = convRef(deps, cid);
  const snap = await tx.get(ref);
  const members: string[] = snap.get('members') ?? [];
  // Same answer for "no such group" and "not yours", so IDs cannot be probed.
  if (!snap.exists || snap.get('type') !== 'group' || !members.includes(actor)) {
    throw new HttpsError('not-found', 'Unknown group.');
  }
  const admins: string[] = snap.get('admins') ?? [];
  if (admin && !admins.includes(actor)) throw new HttpsError('permission-denied', 'Only group admins can do that.');
  return { ref, members, admins, name: String(snap.get('name') ?? '') };
}

/**
 * Which of [candidates] may be put in a group by [actor]: they need a profile, an undeleted account,
 * no block in either direction with the actor (so nobody is pulled into a group by someone they blocked),
 * and a "Who can add me to groups" setting (Phase 3) that lets the actor in: Everyone, People I chat with
 * (the person started a chat with the actor or has written to them), or Nobody (invite link only).
 * Everyone else is returned in [notEligible] without saying why, so the answer reveals neither a block nor a setting.
 */
async function eligible(tx: Transaction, deps: Deps, actor: string, candidates: string[]): Promise<{ ok: string[]; notEligible: string[] }> {
  const db = deps.db;
  const checks = await Promise.all(
    candidates.map(async (u) => {
      if (u === actor) return false;
      const [profile, deleted, blockedActor, actorBlocked, settings] = await Promise.all([
        tx.get(db.doc(`profiles/${u}`)),
        tx.get(db.doc(`deletedAccounts/${u}`)),
        tx.get(db.doc(`users/${u}/blocked/${actor}`)),
        tx.get(db.doc(`users/${actor}/blocked/${u}`)),
        tx.get(db.doc(privacyPath(u))),
      ]);
      if (!profile.exists || deleted.exists || blockedActor.exists || actorBlocked.exists) return false;
      const rule = parsePrivacy(settings.data()).groupAdds;
      if (rule === 'everyone') return true;
      if (rule === 'nobody') return false;
      return isEngagedPartner((await tx.get(db.doc(`conversations/${directCid(actor, u)}`))).data(), u);
    }),
  );
  return {
    ok: candidates.filter((_, i) => checks[i]),
    notEligible: candidates.filter((u, i) => !checks[i] && u !== actor),
  };
}

function memberDoc(uid: string) {
  return {
    uid,
    lastReadAt: null,
    lastDeliveredAt: null,
    unreadCount: 0,
    muted: false,
    pinned: false,
    archived: false,
    joinedAt: FieldValue.serverTimestamp(),
  };
}

/** A server-written "Asha added Ravi" line; clients render it in their own language. */
function systemMessage(tx: Transaction, ref: DocumentReference, actor: string, event: SystemEvent, extra: { targets?: string[]; name?: string } = {}) {
  tx.set(ref.collection('messages').doc(randomUUID()), {
    senderId: actor,
    kind: 'system',
    text: '',
    replyTo: null,
    createdAt: FieldValue.serverTimestamp(),
    deleted: false,
    reactions: {},
    system: { event, ...(extra.targets ? { targets: extra.targets } : {}), ...(extra.name ? { name: extra.name } : {}) },
  });
}

/** Keeps the RTDB membership mirror (used by the typing rules) in step. */
async function mirror(deps: Deps, cid: string, add: string[], remove: string[]): Promise<void> {
  const update: Record<string, true | null> = {};
  for (const u of add) update[`members/${cid}/${u}`] = true;
  for (const u of remove) {
    update[`members/${cid}/${u}`] = null;
    update[`typing/${cid}/${u}`] = null;
  }
  if (Object.keys(update).length > 0) await deps.rtdb.ref().update(update);
}

// ---- operations ---------------------------------------------------------------------------------

export async function createGroupCore(deps: Deps, actor: string, name: string, invited: string[]): Promise<{ cid: string; added: number; notEligible: string[] }> {
  await consumeDailyBudget(deps, actor, 'groups', DAILY_GROUPS);
  const ref = deps.db.collection('conversations').doc();
  let members: string[] = [];
  let notEligible: string[] = [];
  await deps.db.runTransaction(async (tx) => {
    if (!(await tx.get(deps.db.doc(`profiles/${actor}`))).exists) {
      throw new HttpsError('failed-precondition', 'Create your profile first.');
    }
    const { ok: others, notEligible: skipped } = await eligible(tx, deps, actor, invited.slice(0, MAX_GROUP_MEMBERS - 1));
    notEligible = skipped;
    if (others.length === 0) throw new HttpsError('failed-precondition', 'no-eligible-members');
    members = [actor, ...others];
    tx.create(ref, {
      type: 'group',
      members,
      admins: [actor],
      createdBy: actor,
      createdAt: FieldValue.serverTimestamp(),
      name,
      description: '',
      photoUrl: null,
      lastMessage: null,
      lastMessageAt: FieldValue.serverTimestamp(),
    });
    for (const m of members) tx.set(ref.collection('members').doc(m), memberDoc(m));
    systemMessage(tx, ref, actor, 'created', { name });
  });
  await mirror(deps, ref.id, members, []);
  return { cid: ref.id, added: members.length - 1, notEligible };
}

export async function addMembersCore(deps: Deps, actor: string, cid: string, invited: string[]): Promise<{ added: string[]; notEligible: string[] }> {
  let added: string[] = [];
  let notEligible: string[] = [];
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, true);
    const fresh = invited.filter((u) => !g.members.includes(u));
    const { ok, notEligible: skipped } = await eligible(tx, deps, actor, fresh);
    notEligible = skipped;
    if (g.members.length + ok.length > MAX_GROUP_MEMBERS) throw new HttpsError('resource-exhausted', 'group-full');
    added = ok;
    if (ok.length === 0) return;
    tx.update(g.ref, { members: FieldValue.arrayUnion(...ok) });
    for (const m of ok) tx.set(g.ref.collection('members').doc(m), memberDoc(m));
    systemMessage(tx, g.ref, actor, 'added', { targets: ok });
  });
  await mirror(deps, cid, added, []);
  return { added, notEligible };
}

export async function removeMemberCore(deps: Deps, actor: string, cid: string, target: string): Promise<void> {
  if (target === actor) throw new HttpsError('invalid-argument', 'Use leave instead.');
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, true);
    if (!g.members.includes(target)) throw new HttpsError('not-found', 'Unknown member.');
    tx.update(g.ref, { members: FieldValue.arrayRemove(target), admins: FieldValue.arrayRemove(target) });
    tx.delete(g.ref.collection('members').doc(target));
    systemMessage(tx, g.ref, actor, 'removed', { targets: [target] });
  });
  await mirror(deps, cid, [], [target]);
}

/** Leaving (or deleting the account). If the last admin leaves, the earliest remaining member takes over. */
export async function leaveGroupCore(deps: Deps, actor: string, cid: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, false);
    const members = g.members.filter((m) => m !== actor);
    const admins = g.admins.filter((a) => a !== actor);
    if (admins.length === 0 && members.length > 0) admins.push(members[0]);
    tx.update(g.ref, { members, admins });
    tx.delete(g.ref.collection('members').doc(actor));
    systemMessage(tx, g.ref, actor, 'left');
    if (admins.length > 0 && !g.admins.includes(admins[admins.length - 1])) {
      systemMessage(tx, g.ref, admins[admins.length - 1], 'promoted', { targets: [admins[admins.length - 1]] });
    }
  });
  await mirror(deps, cid, [], [actor]);
}

export async function setAdminCore(deps: Deps, actor: string, cid: string, target: string, admin: boolean): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, true);
    if (!g.members.includes(target)) throw new HttpsError('not-found', 'Unknown member.');
    if (g.admins.includes(target) === admin) return;
    if (!admin && g.admins.length === 1) throw new HttpsError('failed-precondition', 'last-admin');
    tx.update(g.ref, { admins: admin ? FieldValue.arrayUnion(target) : FieldValue.arrayRemove(target) });
    systemMessage(tx, g.ref, actor, admin ? 'promoted' : 'demoted', { targets: [target] });
  });
}

export async function updateInfoCore(deps: Deps, actor: string, cid: string, data: unknown): Promise<void> {
  const d = (data ?? {}) as { name?: unknown; description?: unknown };
  const update: Record<string, string> = {};
  if (d.name !== undefined) update.name = groupName(d.name);
  if (d.description !== undefined) {
    if (typeof d.description !== 'string' || d.description.length > MAX_DESCRIPTION) {
      throw new HttpsError('invalid-argument', 'Description must be at most 512 characters.');
    }
    update.description = d.description.trim();
  }
  if (Object.keys(update).length === 0) throw new HttpsError('invalid-argument', 'Nothing to change.');
  await deps.db.runTransaction(async (tx) => {
    const g = await loadGroup(tx, deps, cid, actor, true);
    tx.update(g.ref, update);
    if (update.name !== undefined && update.name !== g.name) systemMessage(tx, g.ref, actor, 'renamed', { name: update.name });
  });
}

export async function setGroupPhotoCore(deps: Deps, actor: string, cid: string, photo: Buffer | null): Promise<string | null> {
  const clean = photo ? cleanPhoto(photo) : null;
  // Check membership and admin rights before writing anything to Storage.
  await deps.db.runTransaction(async (tx) => {
    await loadGroup(tx, deps, cid, actor, true);
  });
  const path = clean ? `groupPhotos/${cid}/${randomUUID()}.jpg` : null;
  if (path && clean) await deps.putFile(path, clean, 'image/jpeg');
  let previous: string | null = null;
  try {
    await deps.db.runTransaction(async (tx) => {
      const g = await loadGroup(tx, deps, cid, actor, true);
      const old = (await tx.get(g.ref)).get('photoUrl');
      previous = typeof old === 'string' ? old : null;
      tx.update(g.ref, { photoUrl: path });
      systemMessage(tx, g.ref, actor, 'photo');
    });
  } catch (err) {
    if (path) await deps.deleteFile(path).catch(() => undefined);
    throw err;
  }
  if (previous && previous !== path && String(previous).startsWith(`groupPhotos/${cid}/`)) {
    await deps.deleteFile(previous).catch(() => undefined);
  }
  return path;
}

const secretRef = (deps: Deps, cid: string) => deps.db.doc(`groupSecrets/${cid}`);

export async function createInviteCore(deps: Deps, actor: string, cid: string): Promise<string> {
  return deps.db.runTransaction(async (tx) => {
    await loadGroup(tx, deps, cid, actor, true);
    const secret = await tx.get(secretRef(deps, cid));
    const existing = secret.get('inviteCode');
    if (typeof existing === 'string') return existing;
    const code = newInviteCode();
    tx.set(deps.db.doc(`groupInvites/${code}`), { cid, createdBy: actor, createdAt: FieldValue.serverTimestamp() });
    tx.set(secretRef(deps, cid), { inviteCode: code }, { merge: true });
    return code;
  });
}

export async function revokeInviteCore(deps: Deps, actor: string, cid: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    await loadGroup(tx, deps, cid, actor, true);
    const secret = await tx.get(secretRef(deps, cid));
    const code = secret.get('inviteCode');
    if (typeof code === 'string') tx.delete(deps.db.doc(`groupInvites/${code}`));
    tx.set(secretRef(deps, cid), { inviteCode: null }, { merge: true });
  });
}

function inviteCode(data: unknown): string {
  const code = (data as { code?: unknown } | null)?.code;
  const normal = typeof code === 'string' ? code.trim().toUpperCase() : '';
  if (!INVITE.test(normal)) throw new HttpsError('invalid-argument', 'invalid-code');
  return normal;
}

/** What the person is about to join: name and size only, never the member list. */
export async function previewInviteCore(deps: Deps, uid: string, code: string): Promise<{ name: string; memberCount: number }> {
  await consumeDailyBudget(deps, uid, 'joins', DAILY_JOINS);
  const invite = await deps.db.doc(`groupInvites/${code}`).get();
  if (!invite.exists) throw new HttpsError('not-found', 'invalid-code');
  const g = await convRef(deps, String(invite.get('cid'))).get();
  if (!g.exists) throw new HttpsError('not-found', 'invalid-code');
  return { name: String(g.get('name') ?? ''), memberCount: (g.get('members') ?? []).length };
}

export async function joinWithCodeCore(deps: Deps, uid: string, code: string): Promise<{ cid: string }> {
  await consumeDailyBudget(deps, uid, 'joins', DAILY_JOINS);
  let cid = '';
  let joined = false;
  await deps.db.runTransaction(async (tx) => {
    joined = false;
    const invite = await tx.get(deps.db.doc(`groupInvites/${code}`));
    if (!invite.exists) throw new HttpsError('not-found', 'invalid-code');
    cid = String(invite.get('cid'));
    const ref = convRef(deps, cid);
    const [snap, profile] = await Promise.all([tx.get(ref), tx.get(deps.db.doc(`profiles/${uid}`))]);
    if (!snap.exists) throw new HttpsError('not-found', 'invalid-code');
    if (!profile.exists) throw new HttpsError('failed-precondition', 'Create your profile first.');
    const members: string[] = snap.get('members') ?? [];
    if (members.includes(uid)) return;
    if (members.length >= MAX_GROUP_MEMBERS) throw new HttpsError('resource-exhausted', 'group-full');
    tx.update(ref, { members: FieldValue.arrayUnion(uid) });
    tx.set(ref.collection('members').doc(uid), memberDoc(uid));
    systemMessage(tx, ref, uid, 'joined');
    joined = true;
  });
  if (joined) await mirror(deps, cid, [uid], []);
  return { cid };
}

// ---- callables ----------------------------------------------------------------------------------

async function run<T>(event: string, work: () => Promise<T>): Promise<T> {
  const correlationId = newCorrelationId();
  try {
    const result = await work();
    logEvent(event, { correlationId, outcome: 'ok' });
    return result;
  } catch (err) {
    if (err instanceof HttpsError) {
      logEvent(event, { correlationId, outcome: err.code });
      throw err;
    }
    logEvent(event, { correlationId, outcome: 'error' });
    throw new HttpsError('internal', 'Please retry.');
  }
}

type Req = CallableRequest<unknown>;

export const handleCreateGroup = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = r.data as { name?: unknown; members?: unknown } | null;
  const name = groupName(d?.name);
  const members = uidList(d?.members, MAX_GROUP_MEMBERS - 1);
  return run('group_created', () => createGroupCore(deps, uid, name, members));
};
export const handleAddGroupMembers = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const members = uidList((r.data as { uids?: unknown } | null)?.uids, MAX_GROUP_MEMBERS - 1);
  return run('group_members_added', () => addMembersCore(deps, uid, cid, members));
};
export const handleRemoveGroupMember = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const target = uidOf(r.data);
  return run('group_member_removed', async () => (await removeMemberCore(deps, uid, cid, target), { ok: true }));
};
export const handleLeaveGroup = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  return run('group_left', async () => (await leaveGroupCore(deps, uid, cid), { ok: true }));
};
export const handleSetGroupAdmin = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const target = uidOf(r.data);
  const admin = (r.data as { admin?: unknown } | null)?.admin;
  if (typeof admin !== 'boolean') throw new HttpsError('invalid-argument', 'admin must be a boolean.');
  return run('group_admin_set', async () => (await setAdminCore(deps, uid, cid, target, admin), { ok: true }));
};
export const handleUpdateGroupInfo = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  return run('group_info_updated', async () => (await updateInfoCore(deps, uid, cid, r.data), { ok: true }));
};
export const handleSetGroupPhoto = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  const photo = parsePhoto(r.data);
  return run('group_photo_set', async () => ({ photoUrl: await setGroupPhotoCore(deps, uid, cid, photo) }));
};
export const handleCreateGroupInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  return run('group_invite_created', async () => ({ code: await createInviteCore(deps, uid, cid) }));
};
export const handleRevokeGroupInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const cid = cidOf(r.data);
  return run('group_invite_revoked', async () => (await revokeInviteCore(deps, uid, cid), { ok: true }));
};
export const handlePreviewGroupInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const code = inviteCode(r.data);
  return run('group_invite_previewed', () => previewInviteCore(deps, uid, code));
};
export const handleJoinGroupWithCode = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const code = inviteCode(r.data);
  return run('group_joined', () => joinWithCodeCore(deps, uid, code));
};

const options = buildCallableOptions(process.env);
export const createGroup = onCall(options, (r) => handleCreateGroup(r));
export const addGroupMembers = onCall(options, (r) => handleAddGroupMembers(r));
export const removeGroupMember = onCall(options, (r) => handleRemoveGroupMember(r));
export const leaveGroup = onCall(options, (r) => handleLeaveGroup(r));
export const setGroupAdmin = onCall(options, (r) => handleSetGroupAdmin(r));
export const updateGroupInfo = onCall(options, (r) => handleUpdateGroupInfo(r));
export const setGroupPhoto = onCall(options, (r) => handleSetGroupPhoto(r));
export const createGroupInvite = onCall(options, (r) => handleCreateGroupInvite(r));
export const revokeGroupInvite = onCall(options, (r) => handleRevokeGroupInvite(r));
export const previewGroupInvite = onCall(options, (r) => handlePreviewGroupInvite(r));
export const joinGroupWithCode = onCall(options, (r) => handleJoinGroupWithCode(r));
