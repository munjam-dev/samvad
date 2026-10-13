import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp, updateDoc } from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

let env: RulesTestEnvironment;
const CID = 'alice_bob';
const fsOf = (uid: string) => phoneContext(env, uid).firestore();
const stOf = (uid: string) => phoneContext(env, uid).storage();

const media = (mid: string, over: Record<string, unknown> = {}) => ({
  path: `conversations/${CID}/${mid}/photo.jpg`,
  name: 'photo.jpg',
  size: 120_000,
  mime: 'image/jpeg',
  width: 1200,
  height: 900,
  ...over,
});

const mediaMessage = (mid: string, kind = 'image', m: Record<string, unknown> = media(mid), text = '') => ({
  senderId: 'alice',
  kind,
  text,
  replyTo: null,
  createdAt: serverTimestamp(),
  media: m,
});

async function seedConversation() {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `conversations/${CID}`), {
      type: 'direct', members: ['alice', 'bob'], createdBy: 'alice',
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
  });
}

const bytes = (n: number) => new Uint8Array(n).fill(7);

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await seedConversation();
});
afterAll(async () => {
  await env.cleanup();
});

describe('media messages (Firestore)', () => {
  it('a member can send image, file and voice messages with a valid media description', async () => {
    const db = fsOf('alice');
    const a = randomUUID();
    await assertSucceeds(setDoc(doc(db, `conversations/${CID}/messages/${a}`), mediaMessage(a, 'image', media(a), 'Sunset')));
    const b = randomUUID();
    const file = { path: `conversations/${CID}/${b}/notes.pdf`, name: 'Trip notes.pdf', size: 5000, mime: 'application/pdf' };
    await assertSucceeds(setDoc(doc(db, `conversations/${CID}/messages/${b}`), mediaMessage(b, 'file', file)));
    const c = randomUUID();
    const voice = { path: `conversations/${CID}/${c}/voice.m4a`, name: 'voice.m4a', size: 40_000, mime: 'audio/mp4', durationMs: 9000, waveform: [1, 5, 9, 3] };
    await assertSucceeds(setDoc(doc(db, `conversations/${CID}/messages/${c}`), mediaMessage(c, 'voice', voice)));
  });

  it('rejects media pointing at another message, oversized, wrong types, extra keys and voice without duration', async () => {
    const db = fsOf('alice');
    const mid = randomUUID();
    const at = doc(db, `conversations/${CID}/messages/${mid}`);
    await assertFails(setDoc(at, mediaMessage(mid, 'image', media(randomUUID()))));
    await assertFails(setDoc(at, mediaMessage(mid, 'image', { ...media(mid), path: `conversations/other_chat/${mid}/x.jpg` })));
    await assertFails(setDoc(at, mediaMessage(mid, 'image', media(mid, { size: 16 * 1024 * 1024 + 1 }))));
    await assertFails(setDoc(at, mediaMessage(mid, 'image', media(mid, { mime: 'application/vnd.android.package-archive' }))));
    await assertFails(setDoc(at, mediaMessage(mid, 'image', media(mid, { downloadUrl: 'https://x' }))));
    await assertFails(setDoc(at, mediaMessage(mid, 'voice', { path: `conversations/${CID}/${mid}/v.m4a`, name: 'v.m4a', size: 10, mime: 'audio/mp4' })));
    await assertFails(setDoc(at, mediaMessage(mid, 'video', media(mid))));
    await assertFails(setDoc(at, { senderId: 'alice', kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), media: media(mid) }));
  });

  it('media messages cannot be edited, only deleted for everyone', async () => {
    const mid = randomUUID();
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `conversations/${CID}/messages/${mid}`), {
        senderId: 'alice', kind: 'image', text: '', replyTo: null, createdAt: Timestamp.now(), media: media(mid),
      });
    });
    const ref2 = doc(fsOf('alice'), `conversations/${CID}/messages/${mid}`);
    await assertFails(updateDoc(ref2, { text: 'new caption', editedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(ref2, { deleted: true, text: '' }));
  });
});

describe('media files (Storage)', () => {
  const meta = (uid: string, contentType = 'image/jpeg') => ({ contentType, customMetadata: { senderId: uid } });

  it('a member uploads once; both members can read; outsiders cannot', async () => {
    const mid = randomUUID();
    const path = `conversations/${CID}/${mid}/photo.jpg`;
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path), bytes(1024), meta('alice')));
    await assertSucceeds(getBytes(ref(stOf('bob'), path)));
    await assertFails(getBytes(ref(stOf('carol'), path)));
    await assertFails(uploadBytes(ref(stOf('carol'), `conversations/${CID}/${randomUUID()}/x.jpg`), bytes(10), meta('carol')));
  });

  it('files are immutable and cannot be deleted by clients', async () => {
    const mid = randomUUID();
    const path = `conversations/${CID}/${mid}/photo.jpg`;
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path), bytes(10), meta('alice')));
    await assertFails(uploadBytes(ref(stOf('alice'), path), bytes(20), meta('alice')));
  });

  it('rejects executables, unknown types, spoofed sender, bad names and oversize', async () => {
    const p = (name = 'f.bin') => `conversations/${CID}/${randomUUID()}/${name}`;
    await assertFails(uploadBytes(ref(stOf('alice'), p('app.apk')), bytes(10), meta('alice', 'application/vnd.android.package-archive')));
    await assertFails(uploadBytes(ref(stOf('alice'), p()), bytes(10), meta('alice', 'application/octet-stream')));
    await assertFails(uploadBytes(ref(stOf('alice'), p('a.jpg')), bytes(10), meta('bob')));
    await assertFails(uploadBytes(ref(stOf('alice'), p('../../x.jpg')), bytes(10), meta('alice')));
    await assertFails(uploadBytes(ref(stOf('alice'), `conversations/${CID}/not-a-uuid/a.jpg`), bytes(10), meta('alice')));
    await assertFails(uploadBytes(ref(stOf('alice'), p('big.jpg')), bytes(16 * 1024 * 1024 + 1), meta('alice')));
    await assertSucceeds(uploadBytes(ref(stOf('alice'), p('doc.pdf')), bytes(10), meta('alice', 'application/pdf')));
  });

  it('cannot attach a file to a message that already exists', async () => {
    const mid = randomUUID();
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `conversations/${CID}/messages/${mid}`), {
        senderId: 'bob', kind: 'text', text: 'hi', replyTo: null, createdAt: Timestamp.now(),
      });
    });
    await assertFails(uploadBytes(ref(stOf('alice'), `conversations/${CID}/${mid}/x.jpg`), bytes(10), meta('alice')));
  });
});
