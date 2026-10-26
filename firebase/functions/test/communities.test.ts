import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_COMMUNITIES,
  handleAddCommunityMembers,
  handleCreateCommunity,
  handleCreateCommunityInvite,
  handleDeleteCommunity,
  handleJoinCommunityGroup,
  handleJoinCommunityWithCode,
  handleLeaveCommunity,
  handleLinkCommunityGroup,
  handlePreviewCommunityInvite,
  handleRemoveCommunityMember,
  handleRevokeCommunityInvite,
  handleSetCommunityAdmin,
  handleSetCommunityPhoto,
  handleUnlinkCommunityGroup,
  handleUpdateCommunityInfo,
  MAX_COMMUNITY_ADMINS,
  MAX_COMMUNITY_GROUPS,
  MAX_COMMUNITY_MEMBERS,
  unlinkDeletedGroupCore,
} from '../src/communities';
import { handleCreateGroup, handleLeaveGroup, handleUpdateGroupInfo, INVITE_ALPHABET, MAX_GROUP_MEMBERS } from '../src/groups';
import { setTimerCore } from '../src/disappearing';
import { sendDueScheduled } from '../src/scheduled';
import { applyMessageCreated } from '../src/onMessageCreated';
import { deleteAccountCore } from '../src/deleteAccount';
import { chunkTokens, FCM_MULTICAST_LIMIT } from '../src/deps';
import { fakePush, testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const KNOWN = ['community-full', 'community-groups-full', 'already-linked', 'admin-limit', 'last-admin', 'invalid-code', 'group-full', 'not-allowed'];
const errorOf = async (p: Promise<unknown>) => p.then(() => 'ok', (e: any) => (KNOWN.includes(e.message) ? e.message : e.code));
const call = <T>(fn: (r: any, d: any) => Promise<T>, uid: string, data: unknown, d = deps) => fn(phoneRequest(uid, { data }), d);
const comm = async (id: string) => (await deps.db.doc(`communities/${id}`).get()).data()!;
const conv = async (cid: string) => (await deps.db.doc(`conversations/${cid}`).get()).data()!;
const exists = async (path: string) => (await deps.db.doc(path).get()).exists;
const systemEvents = async (cid: string) =>
  (await deps.db.collection(`conversations/${cid}/messages`).where('kind', '==', 'system').get()).docs.map((d) => d.get('system.event')).sort();

async function profiles(...uids: string[]) {
  for (const u of uids) await deps.db.doc(`profiles/${u}`).set({ displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
}
async function group(admin: string, name: string, members: string[]): Promise<string> {
  return (await call(handleCreateGroup, admin, { name, members })).cid;
}
async function community(creator = 'anil', extra: Record<string, unknown> = {}) {
  return (await call(handleCreateCommunity, creator, { name: 'Neighbours', description: 'Our lane', ...extra })) as { id: string; announcementsCid: string };
}
async function joined(id: string, ...uids: string[]) {
  const { code } = await call(handleCreateCommunityInvite, 'anil', { id });
  for (const u of uids) await call(handleJoinCommunityWithCode, u, { code });
  return code as string;
}

beforeEach(async () => {
  await resetEmulators();
  await profiles('anil', 'bina', 'chetan', 'dev', 'esha', 'fatima');
});

describe('createCommunity', () => {
  it('creates the community and its announcements conversation with the creator as admin and member', async () => {
    const res = await community('anil');
    expect(await comm(res.id)).toMatchObject({
      name: 'Neighbours', description: 'Our lane', photoUrl: null, createdBy: 'anil',
      members: ['anil'], admins: ['anil'], announcementsCid: res.announcementsCid, groups: [], linkedGroupIds: [],
    });
    expect(await conv(res.announcementsCid)).toMatchObject({
      type: 'community_announcements', communityId: res.id, members: ['anil'], admins: ['anil'], name: 'Neighbours',
    });
    const member = await deps.db.doc(`conversations/${res.announcementsCid}/members/anil`).get();
    expect(member.get('joinedAt')).toBeInstanceOf(Timestamp);
    expect(member.get('lastReadAt')).toBeInstanceOf(Timestamp);
  });

  it('links groups the creator admins in the same step', async () => {
    const g1 = await group('anil', 'Cricket', ['bina']);
    const g2 = await group('anil', 'Book club', ['chetan']);
    const res = await community('anil', { groups: [g1, g2, g1] });
    const c = await comm(res.id);
    expect(c.linkedGroupIds).toEqual([g1, g2]);
    expect(c.groups.map((g: any) => g.name)).toEqual(['Cricket', 'Book club']);
    expect((await conv(g1)).communityId).toBe(res.id);
  });

  it('refuses a group you do not admin, a group you are not in, and a group already in a community', async () => {
    const mine = await group('bina', 'Bina group', ['anil']); // anil is a member, not an admin
    const theirs = await group('bina', 'Private', ['chetan']); // anil is not a member
    expect(await errorOf(call(handleCreateCommunity, 'anil', { name: 'C', groups: [mine] }))).toBe('permission-denied');
    expect(await errorOf(call(handleCreateCommunity, 'anil', { name: 'C', groups: [theirs] }))).toBe('not-found');
    const own = await group('anil', 'Own', ['bina']);
    await community('anil', { groups: [own] });
    expect(await errorOf(call(handleCreateCommunity, 'anil', { name: 'C2', groups: [own] }))).toBe('already-linked');
    expect(await systemEvents(mine)).toEqual(['created']); // the failed attempts changed nothing
    expect((await conv(mine)).communityId).toBeUndefined();
  });

  it('validates input, needs a profile, and limits communities per day', async () => {
    for (const data of [
      { name: '' }, { name: '   ' }, { name: 'x'.repeat(61) }, { name: 'C', description: 'x'.repeat(301) },
      { name: 'C', groups: 'nope' }, { name: 'C', groups: ['short'] },
      { name: 'C', groups: Array.from({ length: 11 }, () => 'a'.repeat(20)) },
    ]) {
      expect(await errorOf(call(handleCreateCommunity, 'anil', data))).toBe('invalid-argument');
    }
    expect(await errorOf(call(handleCreateCommunity, 'ghost', { name: 'C' }))).toBe('failed-precondition');
    await deps.db.doc('rateLimits/anil').set({ communitiesWindowStart: Timestamp.now(), communitiesCount: DAILY_COMMUNITIES });
    expect(await errorOf(call(handleCreateCommunity, 'anil', { name: 'C' }))).toBe('resource-exhausted');
    expect(await errorOf(handleCreateCommunity({ data: { name: 'C' } } as any, deps))).toBe('unauthenticated');
  });
});

describe('editing', () => {
  it('only admins rename and describe; the announcements conversation follows the name', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina');
    expect(await errorOf(call(handleUpdateCommunityInfo, 'bina', { id, name: 'Hacked' }))).toBe('permission-denied');
    expect(await errorOf(call(handleUpdateCommunityInfo, 'esha', { id, name: 'Hacked' }))).toBe('not-found');
    await call(handleUpdateCommunityInfo, 'anil', { id, name: '  Lane 4  ', description: 'Updated' });
    expect(await comm(id)).toMatchObject({ name: 'Lane 4', description: 'Updated' });
    expect((await conv(announcementsCid)).name).toBe('Lane 4');
    expect(await errorOf(call(handleUpdateCommunityInfo, 'anil', { id }))).toBe('invalid-argument');
    expect(await errorOf(call(handleUpdateCommunityInfo, 'anil', { id, name: 'x'.repeat(61) }))).toBe('invalid-argument');
  });

  it('only admins change the photo', async () => {
    const { id } = await community();
    await joined(id, 'bina');
    expect(await errorOf(call(handleSetCommunityPhoto, 'bina', { id, jpegBase64: null }))).toBe('permission-denied');
    expect(await errorOf(call(handleSetCommunityPhoto, 'esha', { id, jpegBase64: null }))).toBe('not-found');
    expect(await call(handleSetCommunityPhoto, 'anil', { id, jpegBase64: null })).toEqual({ photoUrl: null });
    expect(await errorOf(call(handleSetCommunityPhoto, 'anil', { id, jpegBase64: 'not base64!' }))).toBe('invalid-argument');
  });
});

describe('linked groups', () => {
  it('links and unlinks a group the admin also admins, leaving the group chat alone', async () => {
    const { id } = await community();
    const g = await group('anil', 'Cricket', ['bina']);
    await call(handleLinkCommunityGroup, 'anil', { id, cid: g });
    await call(handleLinkCommunityGroup, 'anil', { id, cid: g }); // idempotent
    expect((await comm(id)).linkedGroupIds).toEqual([g]);
    expect((await conv(g)).communityId).toBe(id);
    await call(handleUnlinkCommunityGroup, 'anil', { id, cid: g });
    expect((await comm(id)).linkedGroupIds).toEqual([]);
    expect((await conv(g)).communityId).toBeUndefined();
    expect((await conv(g)).members).toEqual(['anil', 'bina']);
  });

  it('refuses linking by a non-admin of the community, or of a group you do not admin', async () => {
    const { id } = await community();
    await joined(id, 'bina');
    const bg = await group('bina', 'Bina group', ['anil']);
    const ag = await group('anil', 'Anil group', ['bina']);
    expect(await errorOf(call(handleLinkCommunityGroup, 'bina', { id, cid: ag }))).toBe('permission-denied'); // member, not community admin
    expect(await errorOf(call(handleLinkCommunityGroup, 'anil', { id, cid: bg }))).toBe('permission-denied'); // anil is not admin of bg
    expect(await errorOf(call(handleLinkCommunityGroup, 'anil', { id, cid: 'z'.repeat(20) }))).toBe('not-found');
    expect(await errorOf(call(handleLinkCommunityGroup, 'esha', { id, cid: ag }))).toBe('not-found');
    expect((await comm(id)).linkedGroupIds).toEqual([]);
    expect((await conv(bg)).communityId).toBeUndefined();
  });

  it('a group belongs to one community at a time', async () => {
    const a = await community('anil');
    const b = await community('anil', { name: 'Other' });
    const g = await group('anil', 'Shared', ['bina']);
    await call(handleLinkCommunityGroup, 'anil', { id: a.id, cid: g });
    expect(await errorOf(call(handleLinkCommunityGroup, 'anil', { id: b.id, cid: g }))).toBe('already-linked');
  });

  it('holds at most 10 groups', async () => {
    const { id } = await community();
    for (let i = 0; i < MAX_COMMUNITY_GROUPS; i++) {
      await deps.db.doc('rateLimits/anil').delete();
      await call(handleLinkCommunityGroup, 'anil', { id, cid: await group('anil', `G${i}`, ['bina']) });
    }
    await deps.db.doc('rateLimits/anil').delete();
    const extra = await group('anil', 'Eleventh', ['bina']);
    expect(await errorOf(call(handleLinkCommunityGroup, 'anil', { id, cid: extra }))).toBe('community-groups-full');
    expect((await comm(id)).linkedGroupIds).toHaveLength(10);
  });

  it('a community admin cannot unlink a group they do not admin; an emptied group drops out by itself', async () => {
    const { id } = await community();
    await joined(id, 'bina');
    await call(handleSetCommunityAdmin, 'anil', { id, uid: 'bina', admin: true });
    const g = await group('anil', 'Cricket', ['bina']);
    await call(handleLinkCommunityGroup, 'anil', { id, cid: g });
    expect(await errorOf(call(handleUnlinkCommunityGroup, 'bina', { id, cid: g }))).toBe('permission-denied'); // bina admins the community, not the group
    expect(await errorOf(call(handleUnlinkCommunityGroup, 'anil', { id, cid: 'z'.repeat(20) }))).toBe('not-found');
    await call(handleLeaveGroup, 'anil', { cid: g });
    await call(handleLeaveGroup, 'bina', { cid: g });
    expect((await comm(id)).linkedGroupIds).toEqual([]);
    expect((await conv(g)).communityId).toBeUndefined();
  });

  it('keeps the group name on the card in step with a rename, and drops a deleted group', async () => {
    const { id } = await community();
    const g = await group('anil', 'Cricket', ['bina']);
    await call(handleLinkCommunityGroup, 'anil', { id, cid: g });
    await call(handleUpdateGroupInfo, 'anil', { cid: g, name: 'Cricket Club' });
    expect((await comm(id)).groups[0].name).toBe('Cricket Club');
    await unlinkDeletedGroupCore(deps, g, id);
    expect((await comm(id)).groups).toEqual([]);
  });
});

describe('members and roles', () => {
  it('only admins invite people; blocked, unknown and existing members are skipped; both member lists stay equal', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina');
    await deps.db.doc('users/chetan/blocked/anil').set({ createdAt: Timestamp.now() });
    await deps.db.doc('users/anil/blocked/dev').set({ createdAt: Timestamp.now() });
    expect(await errorOf(call(handleAddCommunityMembers, 'bina', { id, uids: ['esha'] }))).toBe('permission-denied');
    expect(await errorOf(call(handleAddCommunityMembers, 'esha', { id, uids: ['fatima'] }))).toBe('not-found');
    expect(await call(handleAddCommunityMembers, 'anil', { id, uids: ['bina', 'chetan', 'dev', 'ghost', 'esha'] })).toEqual({ added: ['esha'] });
    expect((await comm(id)).members).toEqual(['anil', 'bina', 'esha']);
    expect((await conv(announcementsCid)).members).toEqual(['anil', 'bina', 'esha']);
    expect(await exists(`conversations/${announcementsCid}/members/esha`)).toBe(true);
    expect(await errorOf(call(handleAddCommunityMembers, 'anil', { id, uids: [] }))).toBe('invalid-argument');
  });

  it('never grows past 1000 members', async () => {
    const { id, announcementsCid } = await community();
    const filler = Array.from({ length: MAX_COMMUNITY_MEMBERS - 1 }, (_, i) => `m${i}`);
    await deps.db.doc(`communities/${id}`).update({ members: ['anil', ...filler] });
    await deps.db.doc(`conversations/${announcementsCid}`).update({ members: ['anil', ...filler] });
    expect(await errorOf(call(handleAddCommunityMembers, 'anil', { id, uids: ['bina'] }))).toBe('community-full');
    const { code } = await call(handleCreateCommunityInvite, 'anil', { id });
    expect(await errorOf(call(handleJoinCommunityWithCode, 'bina', { code }))).toBe('community-full');
    expect((await comm(id)).members).toHaveLength(MAX_COMMUNITY_MEMBERS);
  });

  it('admins promote and demote; the last admin cannot step down; the admin cap holds', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina', 'chetan');
    expect(await errorOf(call(handleSetCommunityAdmin, 'bina', { id, uid: 'chetan', admin: true }))).toBe('permission-denied');
    expect(await errorOf(call(handleSetCommunityAdmin, 'anil', { id, uid: 'anil', admin: false }))).toBe('last-admin');
    expect(await errorOf(call(handleSetCommunityAdmin, 'anil', { id, uid: 'esha', admin: true }))).toBe('not-found');
    expect(await errorOf(call(handleSetCommunityAdmin, 'anil', { id, uid: 'bina', admin: 'yes' }))).toBe('invalid-argument');
    await call(handleSetCommunityAdmin, 'anil', { id, uid: 'bina', admin: true });
    expect((await comm(id)).admins).toEqual(['anil', 'bina']);
    expect((await conv(announcementsCid)).admins).toEqual(['anil', 'bina']);
    await call(handleSetCommunityAdmin, 'bina', { id, uid: 'anil', admin: false });
    expect((await comm(id)).admins).toEqual(['bina']);
    expect((await conv(announcementsCid)).admins).toEqual(['bina']);

    const many = Array.from({ length: MAX_COMMUNITY_ADMINS }, (_, i) => `a${i}`);
    await deps.db.doc(`communities/${id}`).update({ members: ['anil', 'bina', 'chetan', ...many], admins: many });
    expect(await errorOf(call(handleSetCommunityAdmin, 'a0', { id, uid: 'chetan', admin: true }))).toBe('admin-limit');
  });

  it('admins remove members, who lose the community, the announcements and their member doc; groups are untouched', async () => {
    const g = await group('anil', 'Cricket', ['bina']);
    const { id, announcementsCid } = await community('anil', { groups: [g] });
    await joined(id, 'bina', 'chetan');
    await call(handleJoinCommunityGroup, 'chetan', { id, cid: g });
    expect(await errorOf(call(handleRemoveCommunityMember, 'bina', { id, uid: 'chetan' }))).toBe('permission-denied');
    expect(await errorOf(call(handleRemoveCommunityMember, 'anil', { id, uid: 'anil' }))).toBe('invalid-argument');
    expect(await errorOf(call(handleRemoveCommunityMember, 'anil', { id, uid: 'esha' }))).toBe('not-found');
    await call(handleRemoveCommunityMember, 'anil', { id, uid: 'chetan' });
    expect((await comm(id)).members).toEqual(['anil', 'bina']);
    expect((await conv(announcementsCid)).members).toEqual(['anil', 'bina']);
    expect(await exists(`conversations/${announcementsCid}/members/chetan`)).toBe(false);
    expect((await conv(g)).members).toContain('chetan');
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: g }))).toBe('not-found');
  });

  it('leaving keeps you in the groups; the last admin leaving hands over; the last person out closes the community', async () => {
    const g = await group('anil', 'Cricket', ['bina']);
    const { id, announcementsCid } = await community('anil', { groups: [g] });
    await joined(id, 'bina');
    await call(handleLeaveCommunity, 'anil', { id });
    expect((await conv(g)).members).toContain('anil');
    expect(await comm(id)).toMatchObject({ members: ['bina'], admins: ['bina'] });
    expect(await errorOf(call(handleLeaveCommunity, 'anil', { id }))).toBe('not-found');

    await call(handleLeaveCommunity, 'bina', { id });
    expect(await exists(`communities/${id}`)).toBe(false);
    expect(await exists(`conversations/${announcementsCid}`)).toBe(false);
    expect((await conv(g)).communityId).toBeUndefined();
    expect(await exists(`communitySecrets/${id}`)).toBe(false);
  });

  it('only admins delete a community, which clears its invite, announcements and links', async () => {
    const g = await group('anil', 'Cricket', ['bina']);
    const { id, announcementsCid } = await community('anil', { groups: [g] });
    const code = await joined(id, 'bina');
    await deps.db.doc(`conversations/${announcementsCid}/messages/${randomUUID()}`).set({ senderId: 'anil', kind: 'text', text: 'hi', createdAt: Timestamp.now() });
    expect(await errorOf(call(handleDeleteCommunity, 'bina', { id }))).toBe('permission-denied');
    await call(handleDeleteCommunity, 'anil', { id });
    expect(await exists(`communities/${id}`)).toBe(false);
    expect(await exists(`communityInvites/${code}`)).toBe(false);
    expect((await deps.db.collection(`conversations/${announcementsCid}/messages`).get()).size).toBe(0);
    expect((await conv(g)).communityId).toBeUndefined();
    expect((await conv(g)).members).toEqual(['anil', 'bina']);
  });
});

describe('invite codes', () => {
  it('admins create one code; anyone can preview and join; regenerating replaces it; revoking stops it', async () => {
    const g = await group('anil', 'Cricket', ['bina']);
    const { id, announcementsCid } = await community('anil', { groups: [g] });
    await joined(id, 'bina');
    expect(await errorOf(call(handleCreateCommunityInvite, 'bina', { id }))).toBe('permission-denied');
    expect(await errorOf(call(handleCreateCommunityInvite, 'esha', { id }))).toBe('not-found');
    const { code } = await call(handleCreateCommunityInvite, 'anil', { id });
    expect(code).toMatch(new RegExp(`^[${INVITE_ALPHABET}]{12}$`));
    expect((await call(handleCreateCommunityInvite, 'anil', { id })).code).toBe(code);

    expect(await call(handlePreviewCommunityInvite, 'dev', { code: code.toLowerCase() })).toEqual({
      name: 'Neighbours', description: 'Our lane', memberCount: 2, groupCount: 1,
    });
    expect(await call(handleJoinCommunityWithCode, 'dev', { code })).toEqual({ id });
    expect(await call(handleJoinCommunityWithCode, 'dev', { code })).toEqual({ id }); // already a member
    expect((await comm(id)).members).toEqual(['anil', 'bina', 'dev']);
    expect((await conv(announcementsCid)).members).toEqual(['anil', 'bina', 'dev']);
    expect(await exists(`conversations/${announcementsCid}/members/dev`)).toBe(true);

    const fresh = (await call(handleCreateCommunityInvite, 'anil', { id, regenerate: true })).code;
    expect(fresh).not.toBe(code);
    expect(await errorOf(call(handleJoinCommunityWithCode, 'esha', { code }))).toBe('invalid-code');
    expect(await exists(`communityInvites/${code}`)).toBe(false);
    await call(handleJoinCommunityWithCode, 'esha', { code: fresh });

    expect(await errorOf(call(handleRevokeCommunityInvite, 'bina', { id }))).toBe('permission-denied');
    await call(handleRevokeCommunityInvite, 'anil', { id });
    expect(await errorOf(call(handleJoinCommunityWithCode, 'fatima', { code: fresh }))).toBe('invalid-code');
    expect(await errorOf(call(handleJoinCommunityWithCode, 'fatima', { code: 'short' }))).toBe('invalid-code');
    expect(await errorOf(call(handlePreviewCommunityInvite, 'fatima', { code: fresh }))).toBe('invalid-code');
  });

  it('turns away someone who blocked, or is blocked by, an admin, with the generic answer', async () => {
    const { id } = await community();
    const { code } = await call(handleCreateCommunityInvite, 'anil', { id });
    await deps.db.doc('users/anil/blocked/dev').set({ createdAt: Timestamp.now() });
    await deps.db.doc('users/esha/blocked/anil').set({ createdAt: Timestamp.now() });
    expect(await errorOf(call(handleJoinCommunityWithCode, 'dev', { code }))).toBe('invalid-code');
    expect(await errorOf(call(handleJoinCommunityWithCode, 'esha', { code }))).toBe('invalid-code');
    expect(await errorOf(call(handleJoinCommunityWithCode, 'ghost', { code }))).toBe('failed-precondition');
    expect((await comm(id)).members).toEqual(['anil']);
  });
});

describe('joining a linked group from the community', () => {
  async function setup() {
    const g = await group('anil', 'Cricket', ['bina']);
    const { id, announcementsCid } = await community('anil', { groups: [g] });
    await joined(id, 'chetan');
    return { g, id, announcementsCid };
  }

  it('a member joins, with a system line, a member doc and the typing mirror', async () => {
    const { g, id } = await setup();
    expect(await call(handleJoinCommunityGroup, 'chetan', { id, cid: g })).toEqual({ cid: g });
    await call(handleJoinCommunityGroup, 'chetan', { id, cid: g }); // idempotent
    expect((await conv(g)).members).toEqual(['anil', 'bina', 'chetan']);
    expect((await deps.db.doc(`conversations/${g}/members/chetan`).get()).get('joinedAt')).toBeInstanceOf(Timestamp);
    expect(await systemEvents(g)).toEqual(['created', 'joinedFromCommunity']);
    expect((await deps.rtdb.ref(`members/${g}/chetan`).get()).val()).toBe(true);
  });

  it('refuses people outside the community, groups that are not linked, and tampered ids', async () => {
    const { g, id } = await setup();
    const other = await group('esha', 'Other', ['fatima']);
    expect(await errorOf(call(handleJoinCommunityGroup, 'dev', { id, cid: g }))).toBe('not-found'); // not in the community
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: other }))).toBe('not-found'); // not linked
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: 'z'.repeat(20) }))).toBe('not-found');
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id: 'bad', cid: g }))).toBe('invalid-argument');
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: 'a/b' }))).toBe('invalid-argument');
    expect((await conv(other)).members).not.toContain('chetan');
  });

  it("respects the group's own cap", async () => {
    const { g, id } = await setup();
    const filler = Array.from({ length: MAX_GROUP_MEMBERS - 2 }, (_, i) => `m${i}`);
    await deps.db.doc(`conversations/${g}`).update({ members: ['anil', 'bina', ...filler] });
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: g }))).toBe('group-full');
  });

  it('respects blocks with the group admins', async () => {
    const { g, id } = await setup();
    await deps.db.doc('users/anil/blocked/chetan').set({ createdAt: Timestamp.now() });
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: g }))).toBe('not-allowed');
    expect((await conv(g)).members).not.toContain('chetan');
  });

  it('is limited per day like other joins', async () => {
    const { g, id } = await setup();
    await deps.db.doc('rateLimits/chetan').set({ joinsWindowStart: Timestamp.now(), joinsCount: 20 });
    expect(await errorOf(call(handleJoinCommunityGroup, 'chetan', { id, cid: g }))).toBe('resource-exhausted');
  });
});

describe('announcements: push and server-side protections', () => {
  async function announcement(announcementsCid: string, sender: string, push = fakePush(), extra: Record<string, unknown> = {}) {
    await applyMessageCreated(testDeps({ push: push.sender }), {
      cid: announcementsCid, mid: randomUUID(), eventId: randomUUID(),
      data: { senderId: sender, kind: 'text', text: 'Water cut at 10', createdAt: Timestamp.now(), ...extra },
    });
    return push.sent;
  }

  it('pushes to every member except the sender through the normal pipeline, titled with the community, without per-member counters', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina', 'chetan', 'dev');
    for (const [u, t] of [['anil', 'ta'], ['bina', 'tb'], ['chetan', 'tc'], ['dev', 'td']]) {
      await deps.db.doc(`users/${u}/devices/${randomUUID()}`).set({ fcmToken: t });
    }
    await deps.db.doc(`conversations/${announcementsCid}/members/dev`).update({ muted: true });
    const sent = await announcement(announcementsCid, 'anil');
    expect(sent).toHaveLength(1);
    expect(sent[0].tokens).toEqual(['tb', 'tc']);
    expect(sent[0].data).toMatchObject({ title: 'Neighbours', body: 'anil: Water cut at 10', group: '1', community: '1', communityId: id, cid: announcementsCid });
    expect((await conv(announcementsCid)).lastMessage.preview).toBe('Water cut at 10');
    expect((await deps.db.doc(`conversations/${announcementsCid}/members/bina`).get()).get('unreadCount')).toBe(0);
    expect(await announcement(announcementsCid, 'anil', fakePush(), { kind: 'system', system: { event: 'x' } })).toEqual([]);
  });

  it('splits large fan-outs into FCM-sized batches', () => {
    const tokens = Array.from({ length: 1201 }, (_, i) => `t${i}`);
    const parts = chunkTokens(tokens);
    expect(parts.map((p) => p.length)).toEqual([FCM_MULTICAST_LIMIT, FCM_MULTICAST_LIMIT, 201]);
    expect(parts.flat()).toEqual(tokens);
    expect(chunkTokens([])).toEqual([]);
  });

  it('a scheduled message from a non-admin never reaches the announcements', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina');
    const sendAt = Timestamp.fromMillis(Date.now() - 1000);
    const base = { conversationId: announcementsCid, text: 'sneaky', replyTo: null, sendAt, status: 'pending', createdAt: sendAt, updatedAt: sendAt };
    await deps.db.doc('scheduledMessages/s1').set({ ...base, senderId: 'bina' });
    await deps.db.doc('scheduledMessages/s2').set({ ...base, senderId: 'anil', text: 'official' });
    expect(await sendDueScheduled(deps)).toEqual({ sent: 1, failed: 1 });
    expect((await deps.db.doc('scheduledMessages/s1').get()).get('failure')).toBe('not_member');
    const texts = (await deps.db.collection(`conversations/${announcementsCid}/messages`).get()).docs.map((d) => d.get('text'));
    expect(texts).toEqual(['official']);
  });

  it('members cannot set the disappearing timer on the announcements', async () => {
    const { id, announcementsCid } = await community();
    await joined(id, 'bina');
    await expect(setTimerCore(deps, 'bina', announcementsCid, 3600)).rejects.toMatchObject({ code: 'permission-denied' });
    await setTimerCore(deps, 'anil', announcementsCid, 3600);
    expect((await conv(announcementsCid)).disappearAfterSec).toBe(3600);
  });
});

describe('deleting an account', () => {
  it('leaves its communities: admin rights are handed over, groups are left separately, and an empty community closes', async () => {
    const g = await group('anil', 'Cricket', ['bina']);
    const shared = await community('anil', { groups: [g] });
    await joined(shared.id, 'bina');
    const alone = await community('anil', { name: 'Solo' });
    await deps.auth.createUser({ uid: 'anil' });
    await deleteAccountCore(deps, 'anil');
    expect(await comm(shared.id)).toMatchObject({ members: ['bina'], admins: ['bina'] });
    expect((await conv(shared.announcementsCid)).members).toEqual(['bina']);
    expect(await exists(`conversations/${shared.announcementsCid}/members/anil`)).toBe(false);
    expect((await conv(g)).members).toEqual(['bina']);
    expect(await exists(`communities/${alone.id}`)).toBe(false);
    expect(await exists(`conversations/${alone.announcementsCid}`)).toBe(false);
  });
});
