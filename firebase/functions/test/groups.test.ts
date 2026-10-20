import { randomUUID } from 'node:crypto';
import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import {
  DAILY_GROUPS,
  handleAddGroupMembers,
  handleCreateGroup,
  handleCreateGroupInvite,
  handleJoinGroupWithCode,
  handleLeaveGroup,
  handlePreviewGroupInvite,
  handleRemoveGroupMember,
  handleRevokeGroupInvite,
  handleSetGroupAdmin,
  handleSetGroupPhoto,
  handleUpdateGroupInfo,
  INVITE_ALPHABET,
  MAX_GROUP_MEMBERS,
} from '../src/groups';
import { applyMessageCreated } from '../src/onMessageCreated';
import { deleteAccountCore } from '../src/deleteAccount';
import { fakePush, testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const errorOf = async (p: Promise<unknown>) =>
  p.then(() => 'ok', (e: any) => (['group-full', 'last-admin', 'no-eligible-members', 'invalid-code'].includes(e.message) ? e.message : e.code));
const call = <T>(fn: (r: any, d: any) => Promise<T>, uid: string, data: unknown, d = deps) => fn(phoneRequest(uid, { data }), d);
const conv = async (cid: string) => (await deps.db.doc(`conversations/${cid}`).get()).data()!;
const systemEvents = async (cid: string) =>
  (await deps.db.collection(`conversations/${cid}/messages`).where('kind', '==', 'system').get()).docs.map((d) => d.get('system.event')).sort();
const mirror = async (cid: string) => (await deps.rtdb.ref(`members/${cid}`).get()).val();

async function profiles(...uids: string[]) {
  for (const u of uids) await deps.db.doc(`profiles/${u}`).set({ displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
}
async function group(name = 'Family', members = ['bina', 'chetan']): Promise<string> {
  return (await call(handleCreateGroup, 'anil', { name, members })).cid;
}

beforeEach(async () => {
  await resetEmulators();
  await profiles('anil', 'bina', 'chetan', 'dev', 'esha');
});

describe('createGroup', () => {
  it('creates the group with the creator as admin, member docs with joinedAt, a system message and the RTDB mirror', async () => {
    const res = await call(handleCreateGroup, 'anil', { name: '  Family  ', members: ['bina', 'chetan', 'anil'] });
    expect(res.added).toBe(2);
    const c = await conv(res.cid);
    expect(c).toMatchObject({ type: 'group', name: 'Family', members: ['anil', 'bina', 'chetan'], admins: ['anil'], createdBy: 'anil' });
    const member = await deps.db.doc(`conversations/${res.cid}/members/bina`).get();
    expect(member.get('joinedAt')).toBeInstanceOf(Timestamp);
    expect(member.get('unreadCount')).toBe(0);
    expect(await systemEvents(res.cid)).toEqual(['created']);
    expect(await mirror(res.cid)).toEqual({ anil: true, bina: true, chetan: true });
  });

  it('skips people without a profile and people blocked in either direction', async () => {
    await deps.db.doc('users/bina/blocked/anil').set({ createdAt: Timestamp.now() });
    await deps.db.doc('users/anil/blocked/chetan').set({ createdAt: Timestamp.now() });
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['bina', 'chetan', 'ghost', 'dev'] });
    expect((await conv(res.cid)).members).toEqual(['anil', 'dev']);
    expect(await errorOf(call(handleCreateGroup, 'anil', { name: 'G', members: ['ghost'] }))).toBe('no-eligible-members');
  });

  it('validates input and limits groups per day', async () => {
    for (const data of [{ name: '', members: ['bina'] }, { name: 'x'.repeat(65), members: ['bina'] }, { name: 'G', members: [] }, { name: 'G', members: ['a/b'] }]) {
      expect(await errorOf(call(handleCreateGroup, 'anil', data))).toBe('invalid-argument');
    }
    await deps.db.doc('rateLimits/anil').set({ groupsWindowStart: Timestamp.now(), groupsCount: DAILY_GROUPS });
    expect(await errorOf(call(handleCreateGroup, 'anil', { name: 'G', members: ['bina'] }))).toBe('resource-exhausted');
  });
});

describe('membership', () => {
  it('only admins add members; added people get joinedAt and the mirror', async () => {
    const cid = await group();
    expect(await errorOf(call(handleAddGroupMembers, 'bina', { cid, uids: ['dev'] }))).toBe('permission-denied');
    expect(await errorOf(call(handleAddGroupMembers, 'esha', { cid, uids: ['dev'] }))).toBe('not-found');
    expect(await call(handleAddGroupMembers, 'anil', { cid, uids: ['dev', 'bina'] })).toEqual({ added: ['dev'], notEligible: [] });
    expect((await conv(cid)).members).toEqual(['anil', 'bina', 'chetan', 'dev']);
    expect((await mirror(cid)).dev).toBe(true);
    expect(await systemEvents(cid)).toEqual(['added', 'created']);
  });

  it('never grows past 256 members', async () => {
    const cid = await group();
    const filler = Array.from({ length: MAX_GROUP_MEMBERS - 3 }, (_, i) => `m${i}`);
    await deps.db.doc(`conversations/${cid}`).update({ members: ['anil', 'bina', 'chetan', ...filler] });
    expect(await errorOf(call(handleAddGroupMembers, 'anil', { cid, uids: ['dev'] }))).toBe('group-full');
  });

  it('admins remove members, who lose membership, their member doc and the mirror', async () => {
    const cid = await group();
    expect(await errorOf(call(handleRemoveGroupMember, 'bina', { cid, uid: 'chetan' }))).toBe('permission-denied');
    await call(handleRemoveGroupMember, 'anil', { cid, uid: 'chetan' });
    expect((await conv(cid)).members).toEqual(['anil', 'bina']);
    expect((await deps.db.doc(`conversations/${cid}/members/chetan`).get()).exists).toBe(false);
    expect((await mirror(cid)).chetan).toBeUndefined();
  });

  it('when the last admin leaves, the earliest remaining member becomes admin', async () => {
    const cid = await group();
    await call(handleLeaveGroup, 'anil', { cid });
    const c = await conv(cid);
    expect(c.members).toEqual(['bina', 'chetan']);
    expect(c.admins).toEqual(['bina']);
    expect(await systemEvents(cid)).toEqual(['created', 'left', 'promoted']);
  });

  it('admins promote and demote; the last admin cannot step down', async () => {
    const cid = await group();
    expect(await errorOf(call(handleSetGroupAdmin, 'anil', { cid, uid: 'anil', admin: false }))).toBe('last-admin');
    await call(handleSetGroupAdmin, 'anil', { cid, uid: 'bina', admin: true });
    expect((await conv(cid)).admins).toEqual(['anil', 'bina']);
    await call(handleSetGroupAdmin, 'bina', { cid, uid: 'anil', admin: false });
    expect((await conv(cid)).admins).toEqual(['bina']);
  });

  it('admins rename the group and change the description', async () => {
    const cid = await group();
    expect(await errorOf(call(handleUpdateGroupInfo, 'bina', { cid, name: 'New' }))).toBe('permission-denied');
    await call(handleUpdateGroupInfo, 'anil', { cid, name: 'Trip 2026', description: 'Goa plans' });
    expect(await conv(cid)).toMatchObject({ name: 'Trip 2026', description: 'Goa plans' });
    expect(await systemEvents(cid)).toEqual(['created', 'renamed']);
  });

  it('only admins set the group photo', async () => {
    const cid = await group();
    expect(await errorOf(call(handleSetGroupPhoto, 'bina', { cid, jpegBase64: null }))).toBe('permission-denied');
    expect(await call(handleSetGroupPhoto, 'anil', { cid, jpegBase64: null })).toEqual({ photoUrl: null });
  });
});

describe('invite codes', () => {
  it('admins create one code; anyone can preview and join; revoking stops it', async () => {
    const cid = await group();
    expect(await errorOf(call(handleCreateGroupInvite, 'bina', { cid }))).toBe('permission-denied');
    const { code } = await call(handleCreateGroupInvite, 'anil', { cid });
    expect(code).toMatch(new RegExp(`^[${INVITE_ALPHABET}]{12}$`));
    expect((await call(handleCreateGroupInvite, 'anil', { cid })).code).toBe(code);

    expect(await call(handlePreviewGroupInvite, 'dev', { code: code.toLowerCase() })).toEqual({ name: 'Family', memberCount: 3 });
    expect(await call(handleJoinGroupWithCode, 'dev', { code })).toEqual({ cid });
    expect(await call(handleJoinGroupWithCode, 'dev', { code })).toEqual({ cid }); // already a member
    expect((await conv(cid)).members).toContain('dev');
    expect(await systemEvents(cid)).toEqual(['created', 'joined']);

    await call(handleRevokeGroupInvite, 'anil', { cid });
    expect(await errorOf(call(handleJoinGroupWithCode, 'esha', { code }))).toBe('invalid-code');
    expect(await errorOf(call(handleJoinGroupWithCode, 'esha', { code: 'short' }))).toBe('invalid-code');
  });

  it('a full group cannot be joined', async () => {
    const cid = await group();
    const { code } = await call(handleCreateGroupInvite, 'anil', { cid });
    const filler = Array.from({ length: MAX_GROUP_MEMBERS - 3 }, (_, i) => `m${i}`);
    await deps.db.doc(`conversations/${cid}`).update({ members: ['anil', 'bina', 'chetan', ...filler] });
    expect(await errorOf(call(handleJoinGroupWithCode, 'dev', { code }))).toBe('group-full');
  });
});

describe('messages and notifications in groups', () => {
  async function message(cid: string, sender: string, extra: Record<string, unknown> = {}) {
    const push = fakePush();
    await applyMessageCreated(testDeps({ push: push.sender }), {
      cid, mid: randomUUID(), eventId: randomUUID(),
      data: { senderId: sender, kind: 'text', text: 'Dinner at 8?', createdAt: Timestamp.now(), ...extra },
    });
    return push.sent;
  }

  it('updates the preview, leaves unread counters alone, and pushes "Sender: text" titled with the group', async () => {
    const cid = await group();
    for (const [u, t] of [['bina', 'tb'], ['chetan', 'tc']]) await deps.db.doc(`users/${u}/devices/${randomUUID()}`).set({ fcmToken: t });
    const sent = await message(cid, 'anil');
    expect((await conv(cid)).lastMessage.preview).toBe('Dinner at 8?');
    expect((await deps.db.doc(`conversations/${cid}/members/bina`).get()).get('unreadCount')).toBe(0);
    expect(sent).toHaveLength(1);
    expect(sent[0].tokens).toEqual(['tb', 'tc']);
    expect(sent[0].data).toMatchObject({ title: 'Family', body: 'anil: Dinner at 8?', group: '1' });
  });

  it('muted members are skipped unless mentioned; system messages never push', async () => {
    const cid = await group();
    for (const [u, t] of [['bina', 'tb'], ['chetan', 'tc']]) await deps.db.doc(`users/${u}/devices/${randomUUID()}`).set({ fcmToken: t });
    await deps.db.doc(`conversations/${cid}/members/bina`).update({ muted: true });
    expect((await message(cid, 'anil'))[0].tokens).toEqual(['tc']);
    expect((await message(cid, 'anil', { mentions: ['bina'] }))[0].tokens).toEqual(['tb', 'tc']);
    expect(await message(cid, 'anil', { kind: 'system', system: { event: 'added' } })).toEqual([]);
  });
});

describe('deleting an account', () => {
  it('leaves its groups and hands over admin rights', async () => {
    const cid = await group();
    await deps.auth.createUser({ uid: 'anil' });
    await deleteAccountCore(deps, 'anil');
    const c = await conv(cid);
    expect(c.members).toEqual(['bina', 'chetan']);
    expect(c.admins).toEqual(['bina']);
  });
});
