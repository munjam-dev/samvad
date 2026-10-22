import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// GIFs and stickers: kinds `gif` / `sticker` with a `gif` map {url, previewUrl, width, height, provider, id}.
let env: RulesTestEnvironment;
const CID = 'alice_bob';
const fsOf = (uid: string) => phoneContext(env, uid).firestore();

const gif = (over: Record<string, unknown> = {}) => ({
  url: 'https://media2.giphy.com/media/abc123XYZ/200w.gif?cid=x&ep=v1_gifs_search&rid=200w.gif&ct=g',
  previewUrl: 'https://media2.giphy.com/media/abc123XYZ/100w.gif',
  width: 200,
  height: 150,
  provider: 'giphy',
  id: 'abc123XYZ',
  ...over,
});

const gifMessage = (g: unknown = gif(), over: Record<string, unknown> = {}) => ({
  senderId: 'alice',
  kind: 'gif',
  text: '',
  replyTo: null,
  createdAt: serverTimestamp(),
  gif: g,
  ...over,
});

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
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

const at = (uid = 'alice', mid = randomUUID()) => doc(fsOf(uid), `conversations/${CID}/messages/${mid}`);

describe('gif and sticker messages', () => {
  it('a member can send a GIF, with or without a caption, and a sticker', async () => {
    await assertSucceeds(setDoc(at(), gifMessage()));
    await assertSucceeds(setDoc(at(), gifMessage(gif(), { text: 'so true' })));
    await assertSucceeds(setDoc(at(), gifMessage(gif(), { kind: 'sticker' })));
    await assertSucceeds(setDoc(at(), gifMessage(gif({ url: 'https://i.giphy.com/abc123XYZ.gif' }))));
    await assertSucceeds(setDoc(at(), gifMessage(gif({ url: 'https://media.giphy.com/media/abc123XYZ/200w.gif' }))));
  });

  it('only members, as themselves, and not when blocked', async () => {
    await assertFails(setDoc(at('mallory'), gifMessage(gif(), { senderId: 'mallory' })));
    await assertFails(setDoc(at('bob'), gifMessage()));
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/bob/blocked/alice'), { blockedAt: Timestamp.now() });
    });
    await assertFails(setDoc(at(), gifMessage()));
  });

  it('rejects hosts that are not allow-listed, and non-https links', async () => {
    for (const bad of [
      'https://evil.example.com/a.gif',
      'https://media2.giphy.com.evil.com/a.gif',
      'https://evilgiphy.com/a.gif',
      'https://media2.giphy.com@evil.com/a.gif',
      'http://media2.giphy.com/media/a/200w.gif',
      'https://api.giphy.com/v1/gifs',
      'https://media2.giphy.com:8443/media/a/200w.gif',
      'javascript:alert(1)',
      'data:image/gif;base64,R0lGOD',
      'https://media2.giphy.com/',
      `https://media2.giphy.com/${'a'.repeat(500)}`,
    ]) {
      await assertFails(setDoc(at(), gifMessage(gif({ url: bad }))));
      await assertFails(setDoc(at(), gifMessage(gif({ previewUrl: bad }))));
    }
  });

  it('rejects a bad provider, id, size or shape', async () => {
    await assertFails(setDoc(at(), gifMessage(gif({ provider: 'other' }))));
    await assertFails(setDoc(at(), gifMessage(gif({ id: '' }))));
    await assertFails(setDoc(at(), gifMessage(gif({ id: 'a/b' }))));
    await assertFails(setDoc(at(), gifMessage(gif({ width: 0 }))));
    await assertFails(setDoc(at(), gifMessage(gif({ width: 1001 }))));
    await assertFails(setDoc(at(), gifMessage(gif({ height: -1 }))));
    await assertFails(setDoc(at(), gifMessage(gif({ height: 10.5 }))));
    await assertFails(setDoc(at(), gifMessage(gif({ width: '200' }))));
    await assertFails(setDoc(at(), gifMessage(gif({ extra: 'x' }))));
    const { id: _omit, ...withoutId } = gif();
    await assertFails(setDoc(at(), gifMessage(withoutId)));
    await assertFails(setDoc(at(), gifMessage(null)));
    await assertFails(setDoc(at(), gifMessage('https://media2.giphy.com/a.gif')));
    const noGif = gifMessage();
    delete (noGif as any).gif;
    await assertFails(setDoc(at(), noGif));
  });

  it('keeps text injection out: a sticker has no caption, a GIF caption is short, the gif map is only for these kinds', async () => {
    await assertFails(setDoc(at(), gifMessage(gif(), { kind: 'sticker', text: 'hi' })));
    await assertFails(setDoc(at(), gifMessage(gif(), { text: 'x'.repeat(1001) })));
    await assertFails(setDoc(at(), gifMessage(gif(), { text: 5 })));
    await assertFails(setDoc(at(), gifMessage(gif(), { kind: 'text', text: 'hello' })));
    await assertFails(setDoc(at(), gifMessage(gif(), { media: { path: 'x' } })));
    await assertFails(setDoc(at(), gifMessage(gif(), { kind: 'image' })));
  });
});
