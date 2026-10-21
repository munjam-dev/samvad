import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  writeBatch,
  deleteDoc,
} from 'firebase/firestore';
import { get, ref as dbRef, set } from 'firebase/database';
import { getBytes, ref as stRef, uploadBytes, listAll } from 'firebase/storage';
import { createRulesEnv, otherProviderContext, phoneContext } from '../test-support/rulesEnv';

// Phase 3: profile visibility (About, photo, last seen), group-add and discovery settings, revoked devices, appeals.
let env: RulesTestEnvironment;

const OWNER = 'zed'; // sorts after 'amy' and 'bob', so the pair id is built in both orders below
const PARTNER = 'amy';
const STRANGER = 'bob';
const PHOTO = `avatars/${OWNER}/7f1c2b3a-0000-4000-8000-000000000001.jpg`;
const jpegMeta = { contentType: 'image/jpeg' };

const fs = (uid: string) => phoneContext(env, uid).firestore();

async function seed(fn: (ctx: { fs: any; db: any; st: any }) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn({ fs: ctx.firestore(), db: ctx.database(), st: ctx.storage() }));
}

async function seedWorld() {
  await seed(async ({ fs: f, st }) => {
    for (const u of [OWNER, PARTNER, STRANGER]) {
      await setDoc(doc(f, `profiles/${u}`), { displayName: u, updatedAt: Timestamp.now() });
    }
    await setDoc(doc(f, `profiles/${OWNER}/private/about`), { about: 'Hello there', updatedAt: Timestamp.now() });
    await setDoc(doc(f, `profiles/${OWNER}/private/photo`), { photoUrl: PHOTO, updatedAt: Timestamp.now() });
    // OWNER started a chat with PARTNER (engaged). STRANGER started one with OWNER, who never wrote back.
    await setDoc(doc(f, `conversations/${PARTNER}_${OWNER}`), {
      type: 'direct', members: [PARTNER, OWNER], createdBy: OWNER, engagedBy: [OWNER], createdAt: Timestamp.now(), lastMessageAt: Timestamp.now(),
    });
    await setDoc(doc(f, `conversations/${STRANGER}_${OWNER}`), {
      type: 'direct', members: [STRANGER, OWNER], createdBy: STRANGER, engagedBy: [STRANGER], createdAt: Timestamp.now(), lastMessageAt: Timestamp.now(),
    });
    await uploadBytes(stRef(st, PHOTO), new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), jpegMeta);
  });
}

async function setSettings(values: Record<string, unknown>) {
  await seed(async ({ fs: f }) => {
    await setDoc(doc(f, `users/${OWNER}/settings/privacy`), { ...values, updatedAt: Timestamp.now() });
  });
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearDatabase();
  await env.clearStorage();
  await seedWorld();
});
afterAll(async () => {
  await env.cleanup();
});

describe('privacy settings document', () => {
  const path = `users/${OWNER}/settings/privacy`;
  const valid = (over: Record<string, unknown> = {}) => ({
    aboutVisibility: 'partners',
    photoVisibility: 'nobody',
    groupAdds: 'partners',
    phoneDiscovery: false,
    updatedAt: serverTimestamp(),
    ...over,
  });

  it('the owner can create, read and update it', async () => {
    const db = fs(OWNER);
    await assertSucceeds(setDoc(doc(db, path), valid()));
    await assertSucceeds(getDoc(doc(db, path)));
    await assertSucceeds(setDoc(doc(db, path), valid({ aboutVisibility: 'everyone' })));
  });

  it('rejects unknown values, unknown fields, a wrong type and a forged updatedAt', async () => {
    const db = fs(OWNER);
    await assertFails(setDoc(doc(db, path), valid({ aboutVisibility: 'friends' })));
    await assertFails(setDoc(doc(db, path), valid({ groupAdds: true })));
    await assertFails(setDoc(doc(db, path), valid({ phoneDiscovery: 'no' })));
    await assertFails(setDoc(doc(db, path), valid({ admin: true })));
    await assertFails(setDoc(doc(db, path), valid({ updatedAt: Timestamp.now() })));
  });

  it('nobody else can read or write it, and signed-out and other providers cannot', async () => {
    await setSettings({ aboutVisibility: 'nobody' });
    await assertFails(getDoc(doc(fs(STRANGER), path)));
    await assertFails(getDoc(doc(fs(PARTNER), path)));
    await assertFails(setDoc(doc(fs(STRANGER), path), valid()));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), path)));
    await assertFails(setDoc(doc(otherProviderContext(env, OWNER, 'anonymous').firestore(), path), valid()));
  });

  it('cannot be deleted by a client', async () => {
    await setSettings({});
    await assertFails(deleteDoc(doc(fs(OWNER), path)));
  });
});

describe('About (profiles/{uid}/private/about)', () => {
  const about = `profiles/${OWNER}/private/about`;

  it('everyone can read it until the owner says otherwise (also with no settings document)', async () => {
    await assertSucceeds(getDoc(doc(fs(STRANGER), about)));
    await setSettings({ aboutVisibility: 'everyone' });
    await assertSucceeds(getDoc(doc(fs(STRANGER), about)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), about)));
  });

  it('Nobody: only the owner can read it', async () => {
    await setSettings({ aboutVisibility: 'nobody' });
    await assertFails(getDoc(doc(fs(PARTNER), about)));
    await assertFails(getDoc(doc(fs(STRANGER), about)));
    await assertSucceeds(getDoc(doc(fs(OWNER), about)));
  });

  it('People I chat with: only people in a chat the owner started or wrote in; a stranger who made a chat is not one', async () => {
    await setSettings({ aboutVisibility: 'partners' });
    await assertSucceeds(getDoc(doc(fs(PARTNER), about))); // pair id built as amy_zed
    await assertFails(getDoc(doc(fs(STRANGER), about))); // started the chat, the owner never wrote
    await assertFails(getDoc(doc(fs('carol'), about))); // no chat at all
    await assertSucceeds(getDoc(doc(fs(OWNER), about)));
    // Once the owner writes in the chat the stranger started, they count.
    await seed(async ({ fs: f }) => {
      await updateDoc(doc(f, `conversations/${STRANGER}_${OWNER}`), { engagedBy: [STRANGER, OWNER] });
    });
    await assertSucceeds(getDoc(doc(fs(STRANGER), about)));
  });

  it('the pair id is built the same way in both orders', async () => {
    // Here the reader sorts before the owner; swap roles: 'amy' owns the data, 'zed' reads.
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, 'profiles/amy/private/about'), { about: 'amy about', updatedAt: Timestamp.now() });
      await setDoc(doc(f, 'users/amy/settings/privacy'), { aboutVisibility: 'partners', updatedAt: Timestamp.now() });
      await updateDoc(doc(f, `conversations/${PARTNER}_${OWNER}`), { engagedBy: [OWNER, PARTNER] });
    });
    await assertSucceeds(getDoc(doc(fs(OWNER), 'profiles/amy/private/about')));
    await assertFails(getDoc(doc(fs(STRANGER), 'profiles/amy/private/about')));
  });

  it('people the owner blocked never see it, whatever the setting', async () => {
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, `users/${OWNER}/blocked/${PARTNER}`), { createdAt: Timestamp.now() });
    });
    await setSettings({ aboutVisibility: 'partners' });
    await assertFails(getDoc(doc(fs(PARTNER), about)));
    await setSettings({ aboutVisibility: 'everyone' });
    await assertFails(getDoc(doc(fs(PARTNER), about)));
    await assertSucceeds(getDoc(doc(fs(STRANGER), about)));
  });

  it('the public profile no longer carries it', async () => {
    await assertSucceeds(getDoc(doc(fs(STRANGER), `profiles/${OWNER}`)));
  });

  it('only the owner writes it, at most 140 characters, with a server timestamp, and only with a profile', async () => {
    const mine = fs(OWNER);
    await assertSucceeds(setDoc(doc(mine, about), { about: 'New about', updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(mine, about), { about: 'a'.repeat(141), updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(mine, about), { about: 5, updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(mine, about), { about: 'x', updatedAt: serverTimestamp(), extra: 1 }));
    await assertFails(setDoc(doc(mine, about), { about: 'x', updatedAt: Timestamp.now() }));
    await assertFails(setDoc(doc(fs(STRANGER), about), { about: 'defaced', updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs('newbie'), 'profiles/newbie/private/about'), { about: 'x', updatedAt: serverTimestamp() }));
  });

  it('can be created in the same batch as the profile', async () => {
    const db = fs('dana');
    const batch = writeBatch(db);
    batch.set(doc(db, 'profiles/dana'), { displayName: 'Dana', updatedAt: serverTimestamp() });
    batch.set(doc(db, 'profiles/dana/private/about'), { about: 'Hi', updatedAt: serverTimestamp() });
    await assertSucceeds(batch.commit());
  });
});

describe('profile photo (profiles/{uid}/private/photo and Storage)', () => {
  const photoDoc = `profiles/${OWNER}/private/photo`;
  const read = (uid: string) => getBytes(stRef(phoneContext(env, uid).storage(), PHOTO));

  it('everyone signed in can read the path and the file by default', async () => {
    await assertSucceeds(getDoc(doc(fs(STRANGER), photoDoc)));
    await assertSucceeds(read(STRANGER));
  });

  it('signed-out users cannot read it', async () => {
    await assertFails(getBytes(stRef(env.unauthenticatedContext().storage(), PHOTO)));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), photoDoc)));
  });

  it('Nobody: the path and the file are hidden from everyone but the owner', async () => {
    await setSettings({ photoVisibility: 'nobody' });
    await assertFails(getDoc(doc(fs(PARTNER), photoDoc)));
    await assertFails(read(PARTNER));
    await assertSucceeds(getDoc(doc(fs(OWNER), photoDoc)));
    await assertSucceeds(read(OWNER));
  });

  it('People I chat with: only engaged chat partners; the stranger who started a chat is refused', async () => {
    await setSettings({ photoVisibility: 'partners' });
    await assertSucceeds(getDoc(doc(fs(PARTNER), photoDoc)));
    await assertSucceeds(read(PARTNER));
    await assertFails(getDoc(doc(fs(STRANGER), photoDoc)));
    await assertFails(read(STRANGER));
    await assertFails(read('carol'));
  });

  it('a blocked person is refused whatever the setting', async () => {
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, `users/${OWNER}/blocked/${PARTNER}`), { createdAt: Timestamp.now() });
    });
    await setSettings({ photoVisibility: 'everyone' });
    await assertFails(getDoc(doc(fs(PARTNER), photoDoc)));
    await assertFails(read(PARTNER));
  });

  it('the About setting does not hide the photo, and the photo setting does not hide About', async () => {
    await setSettings({ photoVisibility: 'nobody', aboutVisibility: 'everyone' });
    await assertSucceeds(getDoc(doc(fs(STRANGER), `profiles/${OWNER}/private/about`)));
    await assertFails(getDoc(doc(fs(STRANGER), photoDoc)));
  });

  it('clients can never write the photo document or the files, not even the owner', async () => {
    await assertFails(setDoc(doc(fs(OWNER), photoDoc), { photoUrl: PHOTO, updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(fs(STRANGER), photoDoc), { photoUrl: 'x', updatedAt: serverTimestamp() }));
    await assertFails(uploadBytes(stRef(phoneContext(env, OWNER).storage(), `avatars/${OWNER}/new.jpg`), new Uint8Array([1]), jpegMeta));
  });

  it('the avatar folder cannot be listed', async () => {
    await assertFails(listAll(stRef(phoneContext(env, STRANGER).storage(), `avatars/${OWNER}`)));
    await assertFails(listAll(stRef(phoneContext(env, OWNER).storage(), `avatars/${OWNER}`)));
  });
});

describe('last seen (Realtime Database)', () => {
  const rtdb = (uid: string) => phoneContext(env, uid).database();
  const CID = `${PARTNER}_${OWNER}`;
  beforeEach(async () => {
    await seed(async ({ db }) => {
      await set(dbRef(db, `presence/${OWNER}`), { online: true, lastSeen: Date.now() });
    });
  });
  const privacy = (value: unknown) =>
    seed(async ({ db }) => {
      await set(dbRef(db, `privacy/${OWNER}`), value);
    });
  const engage = (cid: string, uid: string) =>
    seed(async ({ db }) => {
      await set(dbRef(db, `engaged/${cid}/${uid}`), true);
    });

  it('Everyone is the default, and the owner can always read it', async () => {
    await assertSucceeds(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    await privacy({ lastSeen: 'everyone' });
    await assertSucceeds(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    await assertFails(get(dbRef(env.unauthenticatedContext().database(), `presence/${OWNER}`)));
  });

  it('the old hide-last-seen switch keeps working, with or without the new setting', async () => {
    await privacy({ hideLastSeen: true });
    await assertFails(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    await assertSucceeds(get(dbRef(rtdb(OWNER), `presence/${OWNER}`)));
    // An older app turning it on later wins over a stale newer value.
    await privacy({ hideLastSeen: true, lastSeen: 'everyone' });
    await assertFails(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
  });

  it('Nobody hides it from everyone but the owner', async () => {
    await privacy({ lastSeen: 'nobody' });
    await engage(CID, OWNER);
    await assertFails(get(dbRef(rtdb(PARTNER), `presence/${OWNER}`)));
    await assertFails(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    await assertSucceeds(get(dbRef(rtdb(OWNER), `presence/${OWNER}`)));
  });

  it('People I chat with: engaged chat partners only, in both id orders', async () => {
    await privacy({ lastSeen: 'partners' });
    await assertFails(get(dbRef(rtdb(PARTNER), `presence/${OWNER}`)));
    await engage(CID, OWNER);
    await assertSucceeds(get(dbRef(rtdb(PARTNER), `presence/${OWNER}`)));
    await assertFails(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    // A chat the stranger started, where only the stranger is engaged, does not count.
    await engage(`${STRANGER}_${OWNER}`, STRANGER);
    await assertFails(get(dbRef(rtdb(STRANGER), `presence/${OWNER}`)));
    // The other id order: the reader sorts after the owner.
    await seed(async ({ db }) => {
      await set(dbRef(db, 'presence/amy'), { online: false, lastSeen: Date.now() });
      await set(dbRef(db, 'privacy/amy'), { lastSeen: 'partners' });
      await set(dbRef(db, `engaged/${CID}/amy`), true);
    });
    await assertSucceeds(get(dbRef(rtdb(OWNER), 'presence/amy')));
    await assertFails(get(dbRef(rtdb(STRANGER), 'presence/amy')));
  });

  it('a block hides it in both directions whatever the setting', async () => {
    await engage(CID, OWNER);
    await seed(async ({ db }) => {
      await set(dbRef(db, `blocks/${OWNER}/${PARTNER}`), true);
    });
    await assertFails(get(dbRef(rtdb(PARTNER), `presence/${OWNER}`)));
    await seed(async ({ db }) => {
      await set(dbRef(db, `blocks/${OWNER}/${PARTNER}`), null);
      await set(dbRef(db, `blocks/${PARTNER}/${OWNER}`), true);
    });
    await assertFails(get(dbRef(rtdb(PARTNER), `presence/${OWNER}`)));
  });

  it('the owner can write only the three known values, and nobody else can write or read the setting', async () => {
    const mine = rtdb(OWNER);
    for (const v of ['everyone', 'partners', 'nobody']) {
      await assertSucceeds(set(dbRef(mine, `privacy/${OWNER}/lastSeen`), v));
    }
    await assertFails(set(dbRef(mine, `privacy/${OWNER}/lastSeen`), 'contacts'));
    await assertFails(set(dbRef(mine, `privacy/${OWNER}/lastSeen`), true));
    await assertFails(set(dbRef(rtdb(STRANGER), `privacy/${OWNER}/lastSeen`), 'everyone'));
    await assertFails(get(dbRef(rtdb(STRANGER), `privacy/${OWNER}`)));
  });

  it('the engaged mirror is server-only: nobody can write or read it', async () => {
    await assertFails(set(dbRef(rtdb(OWNER), `engaged/${CID}/${OWNER}`), true));
    await assertFails(set(dbRef(rtdb(STRANGER), `engaged/${STRANGER}_${OWNER}/${OWNER}`), true));
    await assertFails(get(dbRef(rtdb(OWNER), `engaged/${CID}`)));
  });
});

describe('revoked devices', () => {
  const ID = '0b8d9d2e-5f0a-4d6b-9a33-5d1a4b7a1c11';
  const device = () => ({
    platform: 'android', appVersion: '1.0', osVersion: '14', createdAt: serverTimestamp(), lastSeenAt: serverTimestamp(),
  });

  it('the owner reads a marker but cannot write or delete it; others cannot read it', async () => {
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, `users/${OWNER}/revokedDevices/${ID}`), { revokedAt: Timestamp.now() });
    });
    await assertSucceeds(getDoc(doc(fs(OWNER), `users/${OWNER}/revokedDevices/${ID}`)));
    await assertFails(getDoc(doc(fs(STRANGER), `users/${OWNER}/revokedDevices/${ID}`)));
    await assertFails(setDoc(doc(fs(OWNER), `users/${OWNER}/revokedDevices/${ID}`), { revokedAt: serverTimestamp() }));
    await assertFails(deleteDoc(doc(fs(OWNER), `users/${OWNER}/revokedDevices/${ID}`)));
    await assertFails(setDoc(doc(fs(OWNER), `users/${OWNER}/revokedDevices/${ID.replace('0b8', '1b8')}`), { revokedAt: serverTimestamp() }));
  });

  it('a revoked device id cannot register again, while others still can', async () => {
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, `users/${OWNER}`), { phoneE164: '+919999900001', consent: { version: 1, acceptedAt: Timestamp.now() }, createdAt: Timestamp.now() });
      await setDoc(doc(f, `users/${OWNER}/revokedDevices/${ID}`), { revokedAt: Timestamp.now() });
    });
    await assertFails(setDoc(doc(fs(OWNER), `users/${OWNER}/devices/${ID}`), device()));
    await assertSucceeds(setDoc(doc(fs(OWNER), `users/${OWNER}/devices/1b8d9d2e-5f0a-4d6b-9a33-5d1a4b7a1c11`), device()));
  });
});

describe('appeals', () => {
  beforeEach(async () => {
    await seed(async ({ fs: f }) => {
      await setDoc(doc(f, 'appeals/a1'), { uid: OWNER, kind: 'other', message: 'Please look again', status: 'open', createdAt: Timestamp.now() });
      await setDoc(doc(f, 'appeals/a2'), { uid: STRANGER, kind: 'other', message: 'Someone else here', status: 'open', createdAt: Timestamp.now() });
    });
  });

  it('a person reads and lists only their own appeals', async () => {
    await assertSucceeds(getDoc(doc(fs(OWNER), 'appeals/a1')));
    await assertFails(getDoc(doc(fs(OWNER), 'appeals/a2')));
    const mine = await assertSucceeds(getDocs(query(collection(fs(OWNER), 'appeals'), where('uid', '==', OWNER))));
    if (mine.size !== 1) throw new Error(`expected 1 appeal, got ${mine.size}`);
    await assertFails(getDocs(collection(fs(OWNER), 'appeals')));
    await assertFails(getDocs(query(collection(fs(OWNER), 'appeals'), where('uid', '==', STRANGER))));
  });

  it('signed-out users cannot read, and nobody can write, update or delete from a client', async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'appeals/a1')));
    const db = fs(OWNER);
    await assertFails(setDoc(doc(db, 'appeals/new'), { uid: OWNER, kind: 'other', message: 'Writing directly', status: 'open', createdAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db, 'appeals/a1'), { status: 'granted' }));
    await assertFails(deleteDoc(doc(db, 'appeals/a1')));
  });
});
