import { randomUUID } from 'node:crypto';
import { onCall, HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { onDocumentDeleted } from 'firebase-functions/v2/firestore';
import { FieldValue, Timestamp, type DocumentReference, type Transaction } from 'firebase-admin/firestore';
import { buildCallableOptions, REGION } from './config';
import { defaultDeps, type Deps } from './deps';
import { consumeDailyBudget } from './lib/budget';
import { requirePhoneAuth } from './lib/guards';
import { linkFields, readGroups, withoutGroup, type LinkedGroup } from './lib/communityLinks';
import { logEvent } from './lib/logger';
import { DAILY_JOINS, eligible, INVITE_ALPHABET, loadGroup, MAX_GROUP_MEMBERS, memberDoc, mirror, newInviteCode, run, systemMessage } from './groups';
import { cleanPhoto, parsePhoto } from './setProfilePhoto';

// Phase 6: communities. A community is a named space with an Announcements channel (a conversation of type
// community_announcements that only community admins can post to) and up to 10 linked groups its members
// can discover and join. Community documents, the announcements conversation's member list and every
// group link are written only here; the rules let members read them. See docs/COMMUNITIES.md.

export const MAX_COMMUNITY_MEMBERS = 1000;
export const MAX_COMMUNITY_GROUPS = 10;
export const MAX_COMMUNITY_ADMINS = 10;
export const MAX_COMMUNITY_NAME = 60;
export const MAX_COMMUNITY_DESCRIPTION = 300;
export const DAILY_COMMUNITIES = 5;
export const DAILY_COMMUNITY_ADDS = 200;
export const ANNOUNCEMENTS_TYPE = 'community_announcements';
/** Group admins checked for a block when someone joins a linked group (bounded so a join stays cheap). */
const BLOCK_CHECK_ADMINS = 10;

const UID = /^[A-Za-z0-9]{1,128}$/;
const ID = /^[A-Za-z0-9]{20}$/;
const INVITE = new RegExp(`^[${INVITE_ALPHABET}]{12}$`);

// ---- input --------------------------------------------------------------------------------------

export function communityName(raw: unknown): string {
  const name = typeof raw === 'string' ? raw.trim() : '';
  if (name.length < 1 || name.length > MAX_COMMUNITY_NAME) throw new HttpsError('invalid-argument', 'Community name must be 1–60 characters.');
  return name;
}

export function communityDescription(raw: unknown): string {
  if (typeof raw !== 'string' || raw.length > MAX_COMMUNITY_DESCRIPTION) {
    throw new HttpsError('invalid-argument', 'Description must be at most 300 characters.');
  }
  return raw.trim();
}

function idOf(data: unknown, key = 'id'): string {
  const id = (data as Record<string, unknown> | null)?.[key];
  if (typeof id !== 'string' || !ID.test(id)) throw new HttpsError('invalid-argument', 'Unknown community.');
  return id;
}

function uidOf(data: unknown): string {
  const uid = (data as { uid?: unknown } | null)?.uid;
  if (typeof uid !== 'string' || !UID.test(uid)) throw new HttpsError('invalid-argument', 'Unknown member.');
  return uid;
}

function uidList(raw: unknown, max: number): string[] {
  if (!Array.isArray(raw) || raw.length === 0 || raw.length > max || !raw.every((u) => typeof u === 'string' && UID.test(u))) {
    throw new HttpsError('invalid-argument', 'Invalid member list.');
  }
  return [...new Set(raw as string[])];
}

function groupIds(raw: unknown): string[] {
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw) || raw.length > MAX_COMMUNITY_GROUPS || !raw.every((c) => typeof c === 'string' && ID.test(c))) {
    throw new HttpsError('invalid-argument', 'Invalid group list.');
  }
  return [...new Set(raw as string[])];
}

function inviteCode(data: unknown): string {
  const code = (data as { code?: unknown } | null)?.code;
  const normal = typeof code === 'string' ? code.trim().toUpperCase() : '';
  if (!INVITE.test(normal)) throw new HttpsError('invalid-argument', 'invalid-code');
  return normal;
}

// ---- shared steps -------------------------------------------------------------------------------

const communityRef = (deps: Deps, id: string) => deps.db.doc(`communities/${id}`);
const convRef = (deps: Deps, cid: string) => deps.db.doc(`conversations/${cid}`);
const secretRef = (deps: Deps, id: string) => deps.db.doc(`communitySecrets/${id}`);

interface Community {
  ref: DocumentReference;
  /** The announcements conversation. */
  ann: DocumentReference;
  members: string[];
  admins: string[];
  name: string;
  groups: LinkedGroup[];
}

/**
 * Reads the community inside [tx]; the caller must be a member (and an admin when [admin]).
 * Same answer for "no such community" and "not yours", so IDs cannot be probed.
 */
async function loadCommunity(tx: Transaction, deps: Deps, id: string, actor: string, admin: boolean): Promise<Community> {
  const ref = communityRef(deps, id);
  const snap = await tx.get(ref);
  const members: string[] = snap.get('members') ?? [];
  if (!snap.exists || !members.includes(actor)) throw new HttpsError('not-found', 'Unknown community.');
  const admins: string[] = snap.get('admins') ?? [];
  if (admin && !admins.includes(actor)) throw new HttpsError('permission-denied', 'Only community admins can do that.');
  return {
    ref,
    ann: convRef(deps, String(snap.get('announcementsCid'))),
    members,
    admins,
    name: String(snap.get('name') ?? ''),
    groups: readGroups(snap.get('groups')),
  };
}

/** A member of the announcements conversation. lastReadAt starts at "now" so joining never shows old posts as unread. */
function announcementMember(uid: string) {
  return { ...memberDoc(uid), lastReadAt: FieldValue.serverTimestamp() };
}

/** True when [uid] has blocked, or is blocked by, any of the first [BLOCK_CHECK_ADMINS] of [others]. */
async function blockedByAny(tx: Transaction, deps: Deps, uid: string, others: string[]): Promise<boolean> {
  const checks = await Promise.all(
    others
      .filter((o) => o !== uid)
      .slice(0, BLOCK_CHECK_ADMINS)
      .map(async (o) => {
        const [a, b] = await Promise.all([
          tx.get(deps.db.doc(`users/${uid}/blocked/${o}`)),
          tx.get(deps.db.doc(`users/${o}/blocked/${uid}`)),
        ]);
        return a.exists || b.exists;
      }),
  );
  return checks.some(Boolean);
}

/** Writes the changed member and admin lists to the community and its announcements conversation together. */
function writeRoster(tx: Transaction, c: Community, members: string[], admins: string[], extra: Record<string, unknown> = {}) {
  tx.update(c.ref, { members, admins, ...extra });
  tx.update(c.ann, { members, admins });
}

// ---- operations ---------------------------------------------------------------------------------

export async function createCommunityCore(
  deps: Deps,
  actor: string,
  name: string,
  description: string,
  groups: string[],
): Promise<{ id: string; announcementsCid: string }> {
  await consumeDailyBudget(deps, actor, 'communities', DAILY_COMMUNITIES);
  const ref = deps.db.collection('communities').doc();
  const ann = deps.db.collection('conversations').doc();
  await deps.db.runTransaction(async (tx) => {
    if (!(await tx.get(deps.db.doc(`profiles/${actor}`))).exists) {
      throw new HttpsError('failed-precondition', 'Create your profile first.');
    }
    const linked: LinkedGroup[] = [];
    const refs: DocumentReference[] = [];
    for (const cid of groups) {
      const g = await loadGroup(tx, deps, cid, actor, true); // not-found if not a member, permission-denied if not an admin
      if (g.communityId) throw new HttpsError('failed-precondition', 'already-linked');
      linked.push({ cid, name: g.name, linkedBy: actor, linkedAt: Timestamp.fromMillis(deps.nowMs()) });
      refs.push(g.ref);
    }
    tx.create(ref, {
      name,
      description,
      photoUrl: null,
      createdBy: actor,
      createdAt: FieldValue.serverTimestamp(),
      members: [actor],
      admins: [actor],
      announcementsCid: ann.id,
      ...linkFields(linked),
    });
    tx.create(ann, {
      type: ANNOUNCEMENTS_TYPE,
      communityId: ref.id,
      members: [actor],
      admins: [actor],
      createdBy: actor,
      createdAt: FieldValue.serverTimestamp(),
      name,
      description: '',
      photoUrl: null,
      lastMessage: null,
      lastMessageAt: FieldValue.serverTimestamp(),
    });
    tx.set(ann.collection('members').doc(actor), announcementMember(actor));
    for (const r of refs) tx.update(r, { communityId: ref.id });
  });
  return { id: ref.id, announcementsCid: ann.id };
}

export async function updateCommunityInfoCore(deps: Deps, actor: string, id: string, data: unknown): Promise<void> {
  const d = (data ?? {}) as { name?: unknown; description?: unknown };
  const update: Record<string, string> = {};
  if (d.name !== undefined) update.name = communityName(d.name);
  if (d.description !== undefined) update.description = communityDescription(d.description);
  if (Object.keys(update).length === 0) throw new HttpsError('invalid-argument', 'Nothing to change.');
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    tx.update(c.ref, update);
    if (update.name !== undefined && update.name !== c.name) tx.update(c.ann, { name: update.name });
  });
}

export async function setCommunityPhotoCore(deps: Deps, actor: string, id: string, photo: Buffer | null): Promise<string | null> {
  const clean = photo ? cleanPhoto(photo) : null;
  // Check membership and admin rights before writing anything to Storage.
  await deps.db.runTransaction(async (tx) => {
    await loadCommunity(tx, deps, id, actor, true);
  });
  const path = clean ? `communityPhotos/${id}/${randomUUID()}.jpg` : null;
  if (path && clean) await deps.putFile(path, clean, 'image/jpeg');
  let previous: string | null = null;
  try {
    await deps.db.runTransaction(async (tx) => {
      const c = await loadCommunity(tx, deps, id, actor, true);
      const old = (await tx.get(c.ref)).get('photoUrl');
      previous = typeof old === 'string' ? old : null;
      tx.update(c.ref, { photoUrl: path });
    });
  } catch (err) {
    if (path) await deps.deleteFile(path).catch(() => undefined);
    throw err;
  }
  if (previous && previous !== path && String(previous).startsWith(`communityPhotos/${id}/`)) {
    await deps.deleteFile(previous).catch(() => undefined);
  }
  return path;
}

export async function linkGroupCore(deps: Deps, actor: string, id: string, cid: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    const g = await loadGroup(tx, deps, cid, actor, true); // you can only link a group you admin
    if (g.communityId === id && c.groups.some((x) => x.cid === cid)) return;
    if (g.communityId) throw new HttpsError('failed-precondition', 'already-linked');
    if (c.groups.length >= MAX_COMMUNITY_GROUPS) throw new HttpsError('resource-exhausted', 'community-groups-full');
    const next = [...c.groups, { cid, name: g.name, linkedBy: actor, linkedAt: Timestamp.fromMillis(deps.nowMs()) }];
    tx.update(c.ref, linkFields(next));
    tx.update(g.ref, { communityId: id });
  });
}

/** A community admin removes a group they also admin (or one that is gone or empty) from the list. Leaves the group's chat untouched. */
export async function unlinkGroupCore(deps: Deps, actor: string, id: string, cid: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    if (!c.groups.some((g) => g.cid === cid)) throw new HttpsError('not-found', 'Unknown group.');
    const g = await tx.get(convRef(deps, cid));
    const members: string[] = g.get('members') ?? [];
    const admins: string[] = g.get('admins') ?? [];
    const gone = !g.exists || g.get('type') !== 'group' || members.length === 0;
    if (!gone && !admins.includes(actor)) throw new HttpsError('permission-denied', 'Only group admins can do that.');
    tx.update(c.ref, linkFields(withoutGroup(c.groups, cid)));
    if (!gone && g.get('communityId') === id) tx.update(g.ref, { communityId: FieldValue.delete() });
  });
}

export async function setCommunityAdminCore(deps: Deps, actor: string, id: string, target: string, admin: boolean): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    if (!c.members.includes(target)) throw new HttpsError('not-found', 'Unknown member.');
    if (c.admins.includes(target) === admin) return;
    if (!admin && c.admins.length === 1) throw new HttpsError('failed-precondition', 'last-admin');
    if (admin && c.admins.length >= MAX_COMMUNITY_ADMINS) throw new HttpsError('resource-exhausted', 'admin-limit');
    writeRoster(tx, c, c.members, admin ? [...c.admins, target] : c.admins.filter((a) => a !== target));
  });
}

export async function removeCommunityMemberCore(deps: Deps, actor: string, id: string, target: string): Promise<void> {
  if (target === actor) throw new HttpsError('invalid-argument', 'Use leave instead.');
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    if (!c.members.includes(target)) throw new HttpsError('not-found', 'Unknown member.');
    writeRoster(tx, c, c.members.filter((m) => m !== target), c.admins.filter((a) => a !== target));
    tx.delete(c.ann.collection('members').doc(target));
  });
}

/** Admins invite people directly. Anyone without a profile, with a deleted account or blocked either way with the admin is skipped. */
export async function addCommunityMembersCore(deps: Deps, actor: string, id: string, invited: string[]): Promise<{ added: string[] }> {
  await consumeDailyBudget(deps, actor, 'communityAdds', DAILY_COMMUNITY_ADDS, invited.length);
  let added: string[] = [];
  await deps.db.runTransaction(async (tx) => {
    const c = await loadCommunity(tx, deps, id, actor, true);
    const { ok } = await eligible(tx, deps, actor, invited.filter((u) => !c.members.includes(u)));
    if (c.members.length + ok.length > MAX_COMMUNITY_MEMBERS) throw new HttpsError('resource-exhausted', 'community-full');
    added = ok;
    if (ok.length === 0) return;
    writeRoster(tx, c, [...c.members, ...ok], c.admins);
    for (const m of ok) tx.set(c.ann.collection('members').doc(m), announcementMember(m));
  });
  return { added };
}

/** Leaving keeps you in the community's groups. If you were the last admin the earliest member takes over; the last person out closes the community. */
export async function leaveCommunityCore(deps: Deps, actor: string, id: string): Promise<void> {
  let empty = false;
  await deps.db.runTransaction(async (tx) => {
    empty = false;
    const c = await loadCommunity(tx, deps, id, actor, false);
    const members = c.members.filter((m) => m !== actor);
    const admins = c.admins.filter((a) => a !== actor);
    if (admins.length === 0 && members.length > 0) admins.push(members[0]);
    tx.delete(c.ann.collection('members').doc(actor));
    if (members.length === 0) {
      empty = true;
      return;
    }
    writeRoster(tx, c, members, admins);
  });
  if (empty) await destroyCommunity(deps, id);
}

export async function deleteCommunityCore(deps: Deps, actor: string, id: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    await loadCommunity(tx, deps, id, actor, true);
  });
  await destroyCommunity(deps, id);
}

/** Removes the community, its announcements conversation and files, its invite code, and the link on each group. Idempotent. */
export async function destroyCommunity(deps: Deps, id: string): Promise<void> {
  let annCid: string | null = null;
  await deps.db.runTransaction(async (tx) => {
    const ref = communityRef(deps, id);
    const [snap, secret] = await Promise.all([tx.get(ref), tx.get(secretRef(deps, id))]);
    const groups = readGroups(snap.get('groups'));
    const groupSnaps = await Promise.all(groups.map((g) => tx.get(convRef(deps, g.cid))));
    annCid = snap.exists ? String(snap.get('announcementsCid')) : null;
    for (const g of groupSnaps) {
      if (g.exists && g.get('communityId') === id) tx.update(g.ref, { communityId: FieldValue.delete() });
    }
    const code = secret.get('inviteCode');
    if (typeof code === 'string') tx.delete(deps.db.doc(`communityInvites/${code}`));
    if (secret.exists) tx.delete(secret.ref);
    if (snap.exists) tx.delete(ref);
  });
  if (annCid) {
    await deps.db.recursiveDelete(convRef(deps, annCid));
    await deps.deleteFiles(`conversations/${annCid}/`);
  }
  await deps.deleteFiles(`communityPhotos/${id}/`);
}

/** A group that no longer exists drops out of its community's list. */
export async function unlinkDeletedGroupCore(deps: Deps, cid: string, id: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    const snap = await tx.get(communityRef(deps, id));
    if (!snap.exists) return;
    tx.update(snap.ref, linkFields(withoutGroup(readGroups(snap.get('groups')), cid)));
  });
}

/** Account deletion: leave every community (their groups are handled separately). */
export async function leaveAllCommunities(deps: Deps, uid: string): Promise<void> {
  const mine = await deps.db.collection('communities').where('members', 'array-contains', uid).get();
  for (const d of mine.docs) await leaveCommunityCore(deps, uid, d.id).catch(() => undefined);
}

// ---- invite codes -------------------------------------------------------------------------------

/** Returns the current code, creating one if there is none. [regenerate] replaces it, so the old code stops working. */
export async function createCommunityInviteCore(deps: Deps, actor: string, id: string, regenerate: boolean): Promise<string> {
  return deps.db.runTransaction(async (tx) => {
    await loadCommunity(tx, deps, id, actor, true);
    const secret = await tx.get(secretRef(deps, id));
    const existing = secret.get('inviteCode');
    if (typeof existing === 'string' && !regenerate) return existing;
    const code = newInviteCode();
    if (typeof existing === 'string') tx.delete(deps.db.doc(`communityInvites/${existing}`));
    tx.set(deps.db.doc(`communityInvites/${code}`), { id, createdBy: actor, createdAt: FieldValue.serverTimestamp() });
    tx.set(secretRef(deps, id), { inviteCode: code }, { merge: true });
    return code;
  });
}

export async function revokeCommunityInviteCore(deps: Deps, actor: string, id: string): Promise<void> {
  await deps.db.runTransaction(async (tx) => {
    await loadCommunity(tx, deps, id, actor, true);
    const secret = await tx.get(secretRef(deps, id));
    const code = secret.get('inviteCode');
    if (typeof code === 'string') tx.delete(deps.db.doc(`communityInvites/${code}`));
    tx.set(secretRef(deps, id), { inviteCode: null }, { merge: true });
  });
}

export interface CommunityPreview {
  name: string;
  description: string;
  memberCount: number;
  groupCount: number;
}

/** What the person is about to join: name, description and sizes only, never the member list. */
export async function previewCommunityInviteCore(deps: Deps, uid: string, code: string): Promise<CommunityPreview> {
  await consumeDailyBudget(deps, uid, 'joins', DAILY_JOINS);
  const invite = await deps.db.doc(`communityInvites/${code}`).get();
  if (!invite.exists) throw new HttpsError('not-found', 'invalid-code');
  const c = await communityRef(deps, String(invite.get('id'))).get();
  if (!c.exists) throw new HttpsError('not-found', 'invalid-code');
  return {
    name: String(c.get('name') ?? ''),
    description: String(c.get('description') ?? ''),
    memberCount: (c.get('members') ?? []).length,
    groupCount: readGroups(c.get('groups')).length,
  };
}

export async function joinCommunityWithCodeCore(deps: Deps, uid: string, code: string): Promise<{ id: string }> {
  await consumeDailyBudget(deps, uid, 'joins', DAILY_JOINS);
  let id = '';
  await deps.db.runTransaction(async (tx) => {
    const invite = await tx.get(deps.db.doc(`communityInvites/${code}`));
    if (!invite.exists) throw new HttpsError('not-found', 'invalid-code');
    id = String(invite.get('id'));
    const ref = communityRef(deps, id);
    const [snap, profile] = await Promise.all([tx.get(ref), tx.get(deps.db.doc(`profiles/${uid}`))]);
    if (!snap.exists) throw new HttpsError('not-found', 'invalid-code');
    if (!profile.exists) throw new HttpsError('failed-precondition', 'Create your profile first.');
    const members: string[] = snap.get('members') ?? [];
    const admins: string[] = snap.get('admins') ?? [];
    if (members.includes(uid)) return;
    if (members.length >= MAX_COMMUNITY_MEMBERS) throw new HttpsError('resource-exhausted', 'community-full');
    // Anyone who is blocked by, or has blocked, one of the community's admins is turned away with the generic answer.
    if (await blockedByAny(tx, deps, uid, admins)) throw new HttpsError('not-found', 'invalid-code');
    const ann = convRef(deps, String(snap.get('announcementsCid')));
    tx.update(ref, { members: [...members, uid] });
    tx.update(ann, { members: [...members, uid] });
    tx.set(ann.collection('members').doc(uid), announcementMember(uid));
  });
  return { id };
}

/** A community member joins one of the community's linked groups, within that group's own cap and block rules. */
export async function joinCommunityGroupCore(deps: Deps, uid: string, id: string, cid: string): Promise<{ cid: string }> {
  await consumeDailyBudget(deps, uid, 'joins', DAILY_JOINS);
  let joined = false;
  await deps.db.runTransaction(async (tx) => {
    joined = false;
    const c = await loadCommunity(tx, deps, id, uid, false);
    if (!c.groups.some((g) => g.cid === cid)) throw new HttpsError('not-found', 'Unknown group.');
    const ref = convRef(deps, cid);
    const snap = await tx.get(ref);
    const members: string[] = snap.get('members') ?? [];
    const admins: string[] = snap.get('admins') ?? [];
    // A group that is gone, or has nobody left to run it, cannot be joined.
    if (!snap.exists || snap.get('type') !== 'group' || snap.get('communityId') !== id || members.length === 0) {
      throw new HttpsError('not-found', 'Unknown group.');
    }
    if (members.includes(uid)) return;
    if (members.length >= MAX_GROUP_MEMBERS) throw new HttpsError('resource-exhausted', 'group-full');
    if (await blockedByAny(tx, deps, uid, admins)) throw new HttpsError('permission-denied', 'not-allowed');
    tx.update(ref, { members: FieldValue.arrayUnion(uid) });
    tx.set(ref.collection('members').doc(uid), memberDoc(uid));
    systemMessage(tx, ref, uid, 'joinedFromCommunity');
    joined = true;
  });
  if (joined) await mirror(deps, cid, [uid], []);
  return { cid };
}

// ---- callables ----------------------------------------------------------------------------------

type Req = CallableRequest<unknown>;

export const handleCreateCommunity = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const d = r.data as { name?: unknown; description?: unknown; groups?: unknown } | null;
  const name = communityName(d?.name);
  const description = d?.description === undefined ? '' : communityDescription(d.description);
  const groups = groupIds(d?.groups);
  return run('community_created', () => createCommunityCore(deps, uid, name, description, groups));
};
export const handleUpdateCommunityInfo = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  return run('community_info_updated', async () => (await updateCommunityInfoCore(deps, uid, id, r.data), { ok: true }));
};
export const handleSetCommunityPhoto = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const photo = parsePhoto(r.data);
  return run('community_photo_set', async () => ({ photoUrl: await setCommunityPhotoCore(deps, uid, id, photo) }));
};
export const handleLinkCommunityGroup = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const cid = idOf(r.data, 'cid');
  return run('community_group_linked', async () => (await linkGroupCore(deps, uid, id, cid), { ok: true }));
};
export const handleUnlinkCommunityGroup = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const cid = idOf(r.data, 'cid');
  return run('community_group_unlinked', async () => (await unlinkGroupCore(deps, uid, id, cid), { ok: true }));
};
export const handleSetCommunityAdmin = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const target = uidOf(r.data);
  const admin = (r.data as { admin?: unknown } | null)?.admin;
  if (typeof admin !== 'boolean') throw new HttpsError('invalid-argument', 'admin must be a boolean.');
  return run('community_admin_set', async () => (await setCommunityAdminCore(deps, uid, id, target, admin), { ok: true }));
};
export const handleRemoveCommunityMember = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const target = uidOf(r.data);
  return run('community_member_removed', async () => (await removeCommunityMemberCore(deps, uid, id, target), { ok: true }));
};
export const handleAddCommunityMembers = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const uids = uidList((r.data as { uids?: unknown } | null)?.uids, 50);
  return run('community_members_added', () => addCommunityMembersCore(deps, uid, id, uids));
};
export const handleLeaveCommunity = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  return run('community_left', async () => (await leaveCommunityCore(deps, uid, id), { ok: true }));
};
export const handleDeleteCommunity = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  return run('community_deleted', async () => (await deleteCommunityCore(deps, uid, id), { ok: true }));
};
export const handleCreateCommunityInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const regenerate = (r.data as { regenerate?: unknown } | null)?.regenerate === true;
  return run('community_invite_created', async () => ({ code: await createCommunityInviteCore(deps, uid, id, regenerate) }));
};
export const handleRevokeCommunityInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  return run('community_invite_revoked', async () => (await revokeCommunityInviteCore(deps, uid, id), { ok: true }));
};
export const handlePreviewCommunityInvite = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const code = inviteCode(r.data);
  return run('community_invite_previewed', () => previewCommunityInviteCore(deps, uid, code));
};
export const handleJoinCommunityWithCode = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const code = inviteCode(r.data);
  return run('community_joined', () => joinCommunityWithCodeCore(deps, uid, code));
};
export const handleJoinCommunityGroup = async (r: Req, deps: Deps = defaultDeps()) => {
  const { uid } = requirePhoneAuth(r);
  const id = idOf(r.data);
  const cid = idOf(r.data, 'cid');
  return run('community_group_joined', () => joinCommunityGroupCore(deps, uid, id, cid));
};

const options = buildCallableOptions(process.env);
export const createCommunity = onCall(options, (r) => handleCreateCommunity(r));
export const updateCommunityInfo = onCall(options, (r) => handleUpdateCommunityInfo(r));
export const setCommunityPhoto = onCall(options, (r) => handleSetCommunityPhoto(r));
export const linkCommunityGroup = onCall(options, (r) => handleLinkCommunityGroup(r));
export const unlinkCommunityGroup = onCall(options, (r) => handleUnlinkCommunityGroup(r));
export const setCommunityAdmin = onCall(options, (r) => handleSetCommunityAdmin(r));
export const removeCommunityMember = onCall(options, (r) => handleRemoveCommunityMember(r));
export const addCommunityMembers = onCall(options, (r) => handleAddCommunityMembers(r));
export const leaveCommunity = onCall(options, (r) => handleLeaveCommunity(r));
export const deleteCommunity = onCall(options, (r) => handleDeleteCommunity(r));
export const createCommunityInvite = onCall(options, (r) => handleCreateCommunityInvite(r));
export const revokeCommunityInvite = onCall(options, (r) => handleRevokeCommunityInvite(r));
export const previewCommunityInvite = onCall(options, (r) => handlePreviewCommunityInvite(r));
export const joinCommunityWithCode = onCall(options, (r) => handleJoinCommunityWithCode(r));
export const joinCommunityGroup = onCall(options, (r) => handleJoinCommunityGroup(r));

/** If a linked group is ever deleted, its community stops listing it. (Cheap: fires only on a conversation delete.) */
export const onGroupDeleted = onDocumentDeleted({ document: 'conversations/{cid}', region: REGION }, async (event) => {
  const id = event.data?.get('communityId');
  if (event.data?.get('type') !== 'group' || typeof id !== 'string') return;
  await unlinkDeletedGroupCore(defaultDeps(), event.params.cid, id);
  logEvent('community_group_removed', { outcome: 'ok' });
});
