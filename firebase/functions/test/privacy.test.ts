import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { handleAddGroupMembers, handleCreateGroup, handleJoinGroupWithCode, handleCreateGroupInvite } from '../src/groups';
import { handleLookupPhoneNumbers } from '../src/lookupPhoneNumbers';
import { applyMessageCreated } from '../src/onMessageCreated';
import { backfillAllEngagement, backfillEngagement, markEngaged } from '../src/engagement';
import { migrateAllProfiles, migrateProfileCore } from '../src/profilePrivacy';
import { deleteAccountCore } from '../src/deleteAccount';
import { DEFAULT_PRIVACY, directCid, parsePrivacy } from '../src/lib/privacy';
import { testDeps, phoneRequest } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const call = <T>(fn: (r: any, d: any) => Promise<T>, uid: string, data: unknown) => fn(phoneRequest(uid, { data }), deps);
const errorOf = async (p: Promise<unknown>) =>
  p.then(() => 'ok', (e: any) => (['no-eligible-members'].includes(e.message) ? e.message : e.code));

async function profiles(...uids: string[]) {
  for (const u of uids) await deps.db.doc(`profiles/${u}`).set({ displayName: u, updatedAt: Timestamp.now() });
}
const settings = (uid: string, values: Record<string, unknown>) =>
  deps.db.doc(`users/${uid}/settings/privacy`).set({ ...values, updatedAt: Timestamp.now() });
async function directChat(a: string, b: string, createdBy: string, engagedBy?: string[]) {
  const members = [a, b].sort();
  await deps.db.doc(`conversations/${directCid(a, b)}`).set({
    type: 'direct', members, createdBy, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    ...(engagedBy ? { engagedBy } : {}),
  });
}
const engagedBy = async (cid: string) => (await deps.db.doc(`conversations/${cid}`).get()).get('engagedBy');
const mirror = async (cid: string) => (await deps.rtdb.ref(`engaged/${cid}`).get()).val();

beforeEach(async () => {
  await resetEmulators();
  await profiles('anil', 'bina', 'chetan', 'dev');
});

describe('privacy settings parsing', () => {
  it('falls back to open defaults for missing or unknown values', () => {
    expect(parsePrivacy(undefined)).toEqual(DEFAULT_PRIVACY);
    expect(parsePrivacy({ aboutVisibility: 'friends', groupAdds: 5, phoneDiscovery: 'no' })).toEqual(DEFAULT_PRIVACY);
    expect(parsePrivacy({ photoVisibility: 'nobody', groupAdds: 'partners', phoneDiscovery: false })).toEqual({
      aboutVisibility: 'everyone', photoVisibility: 'nobody', groupAdds: 'partners', phoneDiscovery: false,
    });
  });

  it('builds the direct chat id in sorted order', () => {
    expect(directCid('zed', 'amy')).toBe('amy_zed');
    expect(directCid('amy', 'zed')).toBe('amy_zed');
  });
});

describe('who can add me to groups', () => {
  it('Everyone (the default) can be added', async () => {
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['bina'] });
    expect(res).toMatchObject({ added: 1, notEligible: [] });
  });

  it('Nobody: skipped when creating a group, and reported as not eligible', async () => {
    await settings('bina', { groupAdds: 'nobody' });
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['bina', 'chetan'] });
    expect(res.added).toBe(1);
    expect(res.notEligible).toEqual(['bina']);
    const conv = (await deps.db.doc(`conversations/${res.cid}`).get()).data()!;
    expect(conv.members).toEqual(['anil', 'chetan']);
  });

  it('Nobody for everyone invited: no group is created', async () => {
    await settings('bina', { groupAdds: 'nobody' });
    expect(await errorOf(call(handleCreateGroup, 'anil', { name: 'G', members: ['bina'] }))).toBe('no-eligible-members');
  });

  it('Nobody: skipped when adding to an existing group, with the others still added', async () => {
    const { cid } = await call(handleCreateGroup, 'anil', { name: 'G', members: ['chetan'] });
    await settings('bina', { groupAdds: 'nobody' });
    const res = await call(handleAddGroupMembers, 'anil', { cid, uids: ['bina', 'dev'] });
    expect(res).toEqual({ added: ['dev'], notEligible: ['bina'] });
    expect((await deps.db.doc(`conversations/${cid}`).get()).get('members')).not.toContain('bina');
  });

  it('Nobody still lets the person join with an invite link', async () => {
    const { cid } = await call(handleCreateGroup, 'anil', { name: 'G', members: ['chetan'] });
    const { code } = await call(handleCreateGroupInvite, 'anil', { cid });
    await settings('bina', { groupAdds: 'nobody' });
    await call(handleJoinGroupWithCode, 'bina', { code });
    expect((await deps.db.doc(`conversations/${cid}`).get()).get('members')).toContain('bina');
  });

  it('People I chat with: allowed only when the person started the chat or wrote in it', async () => {
    await settings('bina', { groupAdds: 'partners' });
    await settings('chetan', { groupAdds: 'partners' });
    await settings('dev', { groupAdds: 'partners' });
    await directChat('anil', 'bina', 'bina', ['bina']); // bina started a chat with anil
    await directChat('anil', 'chetan', 'anil', ['anil']); // anil started it, chetan never wrote
    // dev has no chat with anil at all
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['bina', 'chetan', 'dev'] });
    expect(res.added).toBe(1);
    expect([...res.notEligible].sort()).toEqual(['chetan', 'dev']);
  });

  it('a block still wins over the setting and is reported the same way', async () => {
    await deps.db.doc('users/bina/blocked/anil').set({ createdAt: Timestamp.now() });
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['bina', 'chetan'] });
    expect(res.notEligible).toEqual(['bina']);
  });

  it('adding oneself is not reported as a person who declined', async () => {
    const res = await call(handleCreateGroup, 'anil', { name: 'G', members: ['anil', 'bina'] });
    expect(res.notEligible).toEqual([]);
  });
});

describe('contact discovery', () => {
  const lookup = (uid: string, numbers: string[]) => handleLookupPhoneNumbers(phoneRequest(uid, { data: { phoneNumbers: numbers } }), deps);
  beforeEach(async () => {
    await deps.db.doc('users/bina').set({ phoneE164: '+919999900002', createdAt: Timestamp.now() });
    await deps.db.doc('users/chetan').set({ phoneE164: '+919999900003', createdAt: Timestamp.now() });
  });

  it('finds people by default and when the switch is on', async () => {
    await settings('chetan', { phoneDiscovery: true });
    const res = await lookup('anil', ['+919999900002', '+919999900003']);
    expect(res.matches.map((m) => m.uid).sort()).toEqual(['bina', 'chetan']);
  });

  it('never returns an account that switched it off, and still returns the others in the same call', async () => {
    await settings('bina', { phoneDiscovery: false });
    const res = await lookup('anil', ['+919999900002', '+919999900003']);
    expect(res.matches).toEqual([{ phoneE164: '+919999900003', uid: 'chetan' }]);
    expect((await lookup('anil', ['+919999900002'])).matches).toEqual([]);
  });

  it('other privacy settings do not hide a person from number lookup', async () => {
    await settings('bina', { aboutVisibility: 'nobody', photoVisibility: 'nobody', groupAdds: 'nobody' });
    expect((await lookup('anil', ['+919999900002'])).matches).toEqual([{ phoneE164: '+919999900002', uid: 'bina' }]);
  });

  it('turning it back on makes the account findable again', async () => {
    await settings('bina', { phoneDiscovery: false });
    await settings('bina', { phoneDiscovery: true });
    expect((await lookup('anil', ['+919999900002'])).matches).toHaveLength(1);
  });
});

describe('People I chat with (engagement)', () => {
  const CID = directCid('anil', 'bina');
  const msg = (senderId: string, id: string, kind = 'text') => ({
    cid: CID, mid: id, eventId: `e-${id}`, data: { senderId, kind, text: 'hi', createdAt: Timestamp.now() },
  });
  beforeEach(async () => {
    await directChat('anil', 'bina', 'anil');
  });

  it('markEngaged records the creator in Firestore and the RTDB copy, once', async () => {
    await markEngaged(deps, CID, ['anil']);
    await markEngaged(deps, CID, ['anil']);
    expect(await engagedBy(CID)).toEqual(['anil']);
    expect(await mirror(CID)).toEqual({ anil: true });
  });

  it('ignores people who are not in the chat, and groups', async () => {
    await markEngaged(deps, CID, ['mallory']);
    expect(await engagedBy(CID)).toBeUndefined();
    await deps.db.doc('conversations/grp').set({ type: 'group', members: ['anil', 'bina'], admins: ['anil'], createdAt: Timestamp.now() });
    await markEngaged(deps, 'grp', ['anil']);
    expect((await deps.db.doc('conversations/grp').get()).get('engagedBy')).toBeUndefined();
  });

  it('a message makes its sender engaged, and only the sender', async () => {
    await applyMessageCreated(deps, msg('bina', 'm1'));
    expect(await engagedBy(CID)).toEqual(['bina']);
    expect(await mirror(CID)).toEqual({ bina: true });
    await applyMessageCreated(deps, msg('anil', 'm2'));
    expect((await engagedBy(CID)).sort()).toEqual(['anil', 'bina']);
  });

  it('a redelivered event changes nothing', async () => {
    await applyMessageCreated(deps, msg('bina', 'm1'));
    await deps.db.doc(`conversations/${CID}`).update({ engagedBy: [] });
    await applyMessageCreated(deps, msg('bina', 'm1'));
    expect(await engagedBy(CID)).toEqual([]);
  });

  it('group messages do not create engagement', async () => {
    await deps.db.doc('conversations/grp').set({
      type: 'group', members: ['anil', 'bina'], admins: ['anil'], createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    await applyMessageCreated(deps, { cid: 'grp', mid: 'g1', eventId: 'eg1', data: { senderId: 'bina', kind: 'text', text: 'x', createdAt: Timestamp.now() } });
    expect((await deps.db.doc('conversations/grp').get()).get('engagedBy')).toBeUndefined();
    expect(await mirror('grp')).toBeNull();
  });

  it('a system message does not make anyone engaged', async () => {
    await applyMessageCreated(deps, msg('bina', 'm3', 'system'));
    expect(await engagedBy(CID)).toBeUndefined();
  });

  it('backfill counts the creator and anyone who sent a message, and nobody else', async () => {
    await deps.db.doc(`conversations/${CID}/messages/m1`).set({ senderId: 'bina', kind: 'text', text: 'hi', createdAt: Timestamp.now() });
    expect((await backfillEngagement(deps, CID)).sort()).toEqual(['anil', 'bina']);
    await directChat('anil', 'chetan', 'chetan'); // chetan started, anil never wrote
    await backfillEngagement(deps, directCid('anil', 'chetan'));
    expect(await engagedBy(directCid('anil', 'chetan'))).toEqual(['chetan']);
    expect(await mirror(directCid('anil', 'chetan'))).toEqual({ chetan: true });
  });

  it('backfill of everything visits every direct chat and is repeatable', async () => {
    await directChat('anil', 'chetan', 'anil');
    await directChat('bina', 'dev', 'dev');
    await deps.db.doc('conversations/grp').set({ type: 'group', members: ['anil', 'bina'], createdAt: Timestamp.now() });
    expect(await backfillAllEngagement(deps)).toBe(3);
    expect(await backfillAllEngagement(deps)).toBe(3);
    expect(await engagedBy(directCid('bina', 'dev'))).toEqual(['dev']);
  });
});

describe('profile migration', () => {
  it('moves About and the photo path to the private documents and removes them from the public profile', async () => {
    await deps.db.doc('profiles/old').set({ displayName: 'Old', about: 'Hi there', photoUrl: 'avatars/old/x.jpg', username: 'old_one', updatedAt: Timestamp.now() });
    expect(await migrateProfileCore(deps, 'old')).toBe(true);
    const pub = (await deps.db.doc('profiles/old').get()).data()!;
    expect(pub).toMatchObject({ displayName: 'Old', username: 'old_one' });
    expect('about' in pub).toBe(false);
    expect('photoUrl' in pub).toBe(false);
    expect((await deps.db.doc('profiles/old/private/about').get()).get('about')).toBe('Hi there');
    expect((await deps.db.doc('profiles/old/private/photo').get()).get('photoUrl')).toBe('avatars/old/x.jpg');
    expect(await migrateProfileCore(deps, 'old')).toBe(false);
  });

  it('a null photo leaves no photo document, and a newer private value is not overwritten', async () => {
    await deps.db.doc('profiles/p1').set({ displayName: 'P', about: 'legacy', photoUrl: null, updatedAt: Timestamp.now() });
    await deps.db.doc('profiles/p1/private/about').set({ about: 'newer', updatedAt: Timestamp.now() });
    await migrateProfileCore(deps, 'p1');
    expect((await deps.db.doc('profiles/p1/private/photo').get()).exists).toBe(false);
    expect((await deps.db.doc('profiles/p1/private/about').get()).get('about')).toBe('newer');
  });

  it('migrates every profile that needs it and skips the rest', async () => {
    await deps.db.doc('profiles/a1').set({ displayName: 'A', about: 'x', photoUrl: null, updatedAt: Timestamp.now() });
    await deps.db.doc('profiles/a2').set({ displayName: 'B', updatedAt: Timestamp.now() });
    expect(await migrateAllProfiles(deps)).toBe(1);
    expect(await migrateAllProfiles(deps)).toBe(0);
  });

  it('does nothing for a missing profile', async () => {
    expect(await migrateProfileCore(deps, 'ghost')).toBe(false);
  });
});

describe('deleteAccount and privacy data', () => {
  it('removes the private profile documents, the settings, appeals and the RTDB engaged copy', async () => {
    await deps.auth.createUser({ uid: 'anil' });
    await deps.db.doc('profiles/anil/private/about').set({ about: 'x', updatedAt: Timestamp.now() });
    await deps.db.doc('profiles/anil/private/photo').set({ photoUrl: null, updatedAt: Timestamp.now() });
    await settings('anil', { groupAdds: 'nobody' });
    await deps.db.doc('appeals/z1').set({ uid: 'anil', kind: 'other', message: 'ten chars!!', status: 'open', createdAt: Timestamp.now() });
    await deps.db.doc('appeals/z2').set({ uid: 'bina', kind: 'other', message: 'ten chars!!', status: 'open', createdAt: Timestamp.now() });
    await directChat('anil', 'bina', 'anil');
    await deps.db.doc(`conversations/${directCid('anil', 'bina')}/members/anil`).set({ uid: 'anil' });
    await markEngaged(deps, directCid('anil', 'bina'), ['anil']);
    await deleteAccountCore(deps, 'anil');
    expect((await deps.db.doc('profiles/anil/private/about').get()).exists).toBe(false);
    expect((await deps.db.doc('profiles/anil/private/photo').get()).exists).toBe(false);
    expect((await deps.db.doc('users/anil/settings/privacy').get()).exists).toBe(false);
    expect((await deps.db.doc('appeals/z1').get()).exists).toBe(false);
    expect((await deps.db.doc('appeals/z2').get()).exists).toBe(true);
    expect(await mirror(directCid('anil', 'bina'))).toBeNull();
  });
});
