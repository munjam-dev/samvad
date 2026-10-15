import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, Timestamp } from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Moments files (photo, video, voice) live at statuses/{author}/{id}/{file}.
let env: RulesTestEnvironment;
const A = 'gita'; // author
const B = 'hari'; // can see
const C = 'indu'; // cannot see
const st = (uid: string) => phoneContext(env, uid).storage();
const bytes = (n: number) => new Uint8Array(n).fill(7);
const meta = (contentType: string) => ({ contentType });
const hour = 3_600_000;

async function seedItem(sid: string, extra: Record<string, unknown> = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `statuses/${A}/items/${sid}`), {
      kind: 'image', text: '', audience: 'all', list: [], createdAt: Timestamp.now(),
      expiresAt: Timestamp.fromMillis(Date.now() + 20 * hour), visibleTo: [B], ...extra,
    });
  });
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
});
afterAll(async () => {
  await env.cleanup();
});

describe('uploading a moment file', () => {
  it('the author can upload a photo, a video and a voice note of the allowed sizes', async () => {
    const sid = randomUUID();
    await assertSucceeds(uploadBytes(ref(st(A), `statuses/${A}/${sid}/photo.jpg`), bytes(1000), meta('image/jpeg')));
    await assertSucceeds(uploadBytes(ref(st(A), `statuses/${A}/${sid}/video.mp4`), bytes(1000), meta('video/mp4')));
    await assertSucceeds(uploadBytes(ref(st(A), `statuses/${A}/${sid}/voice.m4a`), bytes(1000), meta('audio/mp4')));
  });

  it('nobody can upload into someone else\'s moments folder', async () => {
    const sid = randomUUID();
    await assertFails(uploadBytes(ref(st(B), `statuses/${A}/${sid}/photo.jpg`), bytes(1000), meta('image/jpeg')));
  });

  it('rejects oversized files: photo over 8 MB, video over 16 MB, voice over 4 MB', async () => {
    const sid = randomUUID();
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/photo.jpg`), bytes(8 * 1024 * 1024 + 1), meta('image/jpeg')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/video.mp4`), bytes(16 * 1024 * 1024 + 1), meta('video/mp4')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/voice.m4a`), bytes(4 * 1024 * 1024 + 1), meta('audio/mp4')));
  });

  it('rejects the wrong content type for each file name, and any other file name', async () => {
    const sid = randomUUID();
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/photo.jpg`), bytes(100), meta('video/mp4')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/video.mp4`), bytes(100), meta('image/jpeg')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/voice.m4a`), bytes(100), meta('image/png')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/app.apk`), bytes(100), meta('application/vnd.android.package-archive')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/video.avi`), bytes(100), meta('video/x-msvideo')));
  });

  it('rejects an empty file and a malformed id', async () => {
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${randomUUID()}/photo.jpg`), bytes(0), meta('image/jpeg')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/not-a-uuid/photo.jpg`), bytes(100), meta('image/jpeg')));
  });

  it('a file cannot be overwritten, and not after its post exists', async () => {
    const sid = randomUUID();
    await assertSucceeds(uploadBytes(ref(st(A), `statuses/${A}/${sid}/photo.jpg`), bytes(100), meta('image/jpeg')));
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid}/photo.jpg`), bytes(200), meta('image/jpeg')));
    const sid2 = randomUUID();
    await seedItem(sid2);
    await assertFails(uploadBytes(ref(st(A), `statuses/${A}/${sid2}/photo.jpg`), bytes(100), meta('image/jpeg')));
  });
});

describe('reading a moment file', () => {
  const sid = randomUUID();
  const path = `statuses/${A}/${sid}/photo.jpg`;
  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), path), bytes(100), meta('image/jpeg'));
    });
  });

  it('the author can always read it', async () => {
    await assertSucceeds(getBytes(ref(st(A), path)));
  });

  it('a person the server marked as able to see it can read it', async () => {
    await seedItem(sid);
    await assertSucceeds(getBytes(ref(st(B), path)));
  });

  it('a person who cannot see it cannot read it', async () => {
    await seedItem(sid);
    await assertFails(getBytes(ref(st(C), path)));
  });

  it('nobody but the author can read before the server has said who can see it', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `statuses/${A}/items/${sid}`), { kind: 'image', text: '', audience: 'all', list: [], createdAt: Timestamp.now() });
    });
    await assertFails(getBytes(ref(st(B), path)));
    await assertSucceeds(getBytes(ref(st(A), path)));
  });

  it('an expired moment can no longer be read by others', async () => {
    await seedItem(sid, { expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await assertFails(getBytes(ref(st(B), path)));
    await assertSucceeds(getBytes(ref(st(A), path)));
  });

  it('blocking hides the file in either direction at once', async () => {
    await seedItem(sid);
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `users/${A}/blocked/${B}`), { createdAt: Timestamp.now() });
    });
    await assertFails(getBytes(ref(st(B), path)));
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `users/${B}/blocked/${A}`), { createdAt: Timestamp.now() });
    });
    await assertFails(getBytes(ref(st(B), path)));
  });
});
