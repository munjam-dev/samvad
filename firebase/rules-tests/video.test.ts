import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp } from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Chat video: kind `video`, media {path .../video.mp4, mime video/mp4, durationMs <= 60 s, thumbPath .../thumb.jpg}.
let env: RulesTestEnvironment;
const CID = 'alice_bob';
const MB = 1024 * 1024;
const fsOf = (uid: string) => phoneContext(env, uid).firestore();
const stOf = (uid: string) => phoneContext(env, uid).storage();
const bytes = (n: number) => new Uint8Array(n).fill(7);

const video = (mid: string, over: Record<string, unknown> = {}) => ({
  path: `conversations/${CID}/${mid}/video.mp4`,
  name: 'Video',
  size: 5 * MB,
  mime: 'video/mp4',
  width: 1280,
  height: 720,
  durationMs: 30_000,
  thumbPath: `conversations/${CID}/${mid}/thumb.jpg`,
  ...over,
});

const videoMessage = (m: unknown, kind = 'video', text = '') => ({
  senderId: 'alice', kind, text, replyTo: null, createdAt: serverTimestamp(), media: m,
});

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `conversations/${CID}`), {
      type: 'direct', members: ['alice', 'bob'], createdBy: 'alice',
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
  });
});
afterAll(async () => {
  await env.cleanup();
});

describe('video messages (Firestore)', () => {
  const send = (uid: string, mid: string, data: unknown) => setDoc(doc(fsOf(uid), `conversations/${CID}/messages/${mid}`), data as any);

  it('a member can send a video of up to 60 seconds and 16 MB, with a caption', async () => {
    const a = randomUUID();
    await assertSucceeds(send('alice', a, videoMessage(video(a))));
    const b = randomUUID();
    await assertSucceeds(send('alice', b, videoMessage(video(b, { durationMs: 60_000, size: 16 * MB }), 'video', 'Look')));
  });

  it('rejects a video longer than 60 s, with no or bad duration', async () => {
    const m = randomUUID();
    await assertFails(send('alice', m, videoMessage(video(m, { durationMs: 60_001 }))));
    await assertFails(send('alice', m, videoMessage(video(m, { durationMs: 0 }))));
    await assertFails(send('alice', m, videoMessage(video(m, { durationMs: '30000' }))));
    const noDuration: any = video(m);
    delete noDuration.durationMs;
    await assertFails(send('alice', m, videoMessage(noDuration)));
  });

  it('rejects an oversize video, the wrong content type and a wrong file name', async () => {
    const m = randomUUID();
    await assertFails(send('alice', m, videoMessage(video(m, { size: 16 * MB + 1 }))));
    await assertFails(send('alice', m, videoMessage(video(m, { size: 0 }))));
    await assertFails(send('alice', m, videoMessage(video(m, { mime: 'video/webm' }))));
    await assertFails(send('alice', m, videoMessage(video(m, { mime: 'image/jpeg' }))));
    await assertFails(send('alice', m, videoMessage(video(m, { path: `conversations/${CID}/${m}/movie.mp4` }))));
  });

  it('requires the thumbnail to be thumb.jpg in the same message folder', async () => {
    const m = randomUUID();
    const noThumb: any = video(m);
    delete noThumb.thumbPath;
    await assertFails(send('alice', m, videoMessage(noThumb)));
    await assertFails(send('alice', m, videoMessage(video(m, { thumbPath: `conversations/${CID}/${randomUUID()}/thumb.jpg` }))));
    await assertFails(send('alice', m, videoMessage(video(m, { thumbPath: `conversations/other_chat/${m}/thumb.jpg` }))));
    await assertFails(send('alice', m, videoMessage(video(m, { thumbPath: `conversations/${CID}/${m}/other.jpg` }))));
    await assertFails(send('alice', m, videoMessage(video(m, { thumbPath: 5 }))));
  });

  it('rejects a video path in another message or chat, extra keys and a non-member', async () => {
    const m = randomUUID();
    await assertFails(send('alice', m, videoMessage(video(randomUUID()))));
    await assertFails(send('alice', m, videoMessage(video(m, { path: `conversations/other_chat/${m}/video.mp4` }))));
    await assertFails(send('alice', m, videoMessage(video(m, { downloadUrl: 'https://x' }))));
    await assertFails(send('carol', m, { ...videoMessage(video(m)), senderId: 'carol' }));
  });

  it('keeps video.mp4 to the video kind and thumbPath to videos', async () => {
    const m = randomUUID();
    await assertFails(send('alice', m, videoMessage(video(m), 'file')));
    await assertFails(send('alice', m, videoMessage(video(m), 'image')));
    await assertFails(send('alice', m, videoMessage(video(m, { mime: 'video/mp4', durationMs: 1000 }), 'voice')));
    const photo = { path: `conversations/${CID}/${m}/photo.jpg`, name: 'photo.jpg', size: 1000, mime: 'image/jpeg', thumbPath: `conversations/${CID}/${m}/thumb.jpg` };
    await assertFails(send('alice', m, videoMessage(photo, 'image')));
  });
});

describe('video files (Storage)', () => {
  const meta = (uid: string, contentType: string) => ({ contentType, customMetadata: { senderId: uid } });
  const path = (mid: string, file: string) => `conversations/${CID}/${mid}/${file}`;

  it('a member uploads video.mp4 and thumb.jpg; both members read, outsiders cannot', async () => {
    const mid = randomUUID();
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path(mid, 'video.mp4')), bytes(1024), meta('alice', 'video/mp4')));
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path(mid, 'thumb.jpg')), bytes(1024), meta('alice', 'image/jpeg')));
    await assertSucceeds(getBytes(ref(stOf('bob'), path(mid, 'video.mp4'))));
    await assertSucceeds(getBytes(ref(stOf('bob'), path(mid, 'thumb.jpg'))));
    await assertFails(getBytes(ref(stOf('carol'), path(mid, 'video.mp4'))));
  });

  it('accepts exactly 16 MB and rejects larger', async () => {
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(16 * MB), meta('alice', 'video/mp4')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(16 * MB + 1), meta('alice', 'video/mp4')));
  });

  it('rejects other video types, video bytes under another name, and a wrong type for video.mp4', async () => {
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(10), meta('alice', 'video/webm')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(10), meta('alice', 'video/quicktime')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(10), meta('alice', 'application/pdf')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'clip.mp4')), bytes(10), meta('alice', 'video/mp4')));
  });

  it('limits the thumbnail to a small JPEG', async () => {
    await assertSucceeds(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'thumb.jpg')), bytes(256 * 1024), meta('alice', 'image/jpeg')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'thumb.jpg')), bytes(256 * 1024 + 1), meta('alice', 'image/jpeg')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'thumb.jpg')), bytes(100), meta('alice', 'image/png')));
  });

  it('a non-member, a spoofed sender and an existing message are rejected', async () => {
    await assertFails(uploadBytes(ref(stOf('carol'), path(randomUUID(), 'video.mp4')), bytes(10), meta('carol', 'video/mp4')));
    await assertFails(uploadBytes(ref(stOf('alice'), path(randomUUID(), 'video.mp4')), bytes(10), meta('bob', 'video/mp4')));
    const mid = randomUUID();
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `conversations/${CID}/messages/${mid}`), {
        senderId: 'bob', kind: 'text', text: 'hi', replyTo: null, createdAt: Timestamp.now(),
      });
    });
    await assertFails(uploadBytes(ref(stOf('alice'), path(mid, 'video.mp4')), bytes(10), meta('alice', 'video/mp4')));
  });
});
