import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp, updateDoc, type Firestore } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 8: repliesOff on a moment, and replies (text or short voice) that name the moment they answer.
let env: RulesTestEnvironment;
const A = 'gita'; // author
const B = 'hari'; // a chat partner who can see it
const C = 'indu'; // a chat partner who cannot (not in visibleTo)
const SID = '7b2d1f0a-1111-4222-8333-944455556666';
const SID_OFF = '7b2d1f0a-1111-4222-8333-944455557777';
const MID = '9c2d1f0a-1111-4222-8333-944455550001';

const cidOf = (x: string, y: string) => [x, y].sort().join('_');
const db = (uid: string) => phoneContext(env, uid).firestore() as unknown as Firestore;
const future = (h = 20) => Timestamp.fromMillis(Date.now() + h * 3_600_000);

async function seed(fn: (d: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}
const moment = (sid: string, extra: Record<string, unknown> = {}) => ({
  kind: 'text', text: 'hello', audience: 'all', list: [], createdAt: Timestamp.now(), expiresAt: future(), visibleTo: [B], ...extra,
});

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await seed(async (d) => {
    for (const u of [A, B, C]) await setDoc(doc(d, `profiles/${u}`), { displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
    for (const other of [B, C]) {
      const members = [A, other].sort();
      await setDoc(doc(d, `conversations/${cidOf(A, other)}`), {
        type: 'direct', members, createdBy: members[0], createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
      });
    }
    // A chat between two other people, to try a reply into the wrong chat.
    await setDoc(doc(d, `conversations/${cidOf(B, C)}`), {
      type: 'direct', members: [B, C].sort(), createdBy: B, createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    await setDoc(doc(d, `statuses/${A}/items/${SID}`), moment(SID));
    await setDoc(doc(d, `statuses/${A}/items/${SID_OFF}`), moment(SID_OFF, { repliesOff: true }));
  });
});

const newText = (extra: Record<string, unknown> = {}) => ({
  kind: 'text', text: 'Good morning', style: 'forest', audience: 'all', list: [], createdAt: serverTimestamp(), ...extra,
});
const statusDoc = (uid: string, sid: string) => doc(db(uid), `statuses/${A}/items/${sid}`);
const NEW_SID = '1a2d1f0a-1111-4222-8333-944455556666';

describe('repliesOff on a moment', () => {
  it('lets the author set repliesOff true or false at creation', async () => {
    await assertSucceeds(setDoc(statusDoc(A, NEW_SID), newText({ repliesOff: true })));
    await assertSucceeds(setDoc(statusDoc(A, '1a2d1f0a-1111-4222-8333-944455556667'), newText({ repliesOff: false })));
  });

  it('rejects a repliesOff that is not a boolean', async () => {
    await assertFails(setDoc(statusDoc(A, NEW_SID), newText({ repliesOff: 'yes' })));
    await assertFails(setDoc(statusDoc(A, NEW_SID), newText({ repliesOff: 1 })));
  });

  it('cannot be changed afterwards, not even by the author', async () => {
    await assertFails(updateDoc(statusDoc(A, SID_OFF), { repliesOff: false }));
    await assertFails(updateDoc(statusDoc(A, SID), { repliesOff: true }));
    await assertFails(updateDoc(statusDoc(B, SID), { repliesOff: true }));
  });

  it('a client cannot rewrite visibleTo or the audience either', async () => {
    await assertFails(updateDoc(statusDoc(A, SID), { visibleTo: [B, C] }));
    await assertFails(updateDoc(statusDoc(A, SID), { audience: 'only', list: [C] }));
  });
});

describe('replying to a moment', () => {
  const textReply = (extra: Record<string, unknown> = {}) => ({
    senderId: B, kind: 'text', text: 'Nice!', replyTo: null, createdAt: serverTimestamp(), momentRef: { author: A, sid: SID }, ...extra,
  });
  const voiceReply = (durationMs: number, extra: Record<string, unknown> = {}) => ({
    senderId: B, kind: 'voice', text: '', replyTo: null, createdAt: serverTimestamp(), momentRef: { author: A, sid: SID },
    media: { path: `conversations/${cidOf(A, B)}/${MID}/voice.m4a`, name: 'Voice message', size: 20_000, mime: 'audio/mp4', durationMs, waveform: [1, 2, 3] },
    ...extra,
  });
  const msg = (uid: string, cid: string) => doc(db(uid), `conversations/${cid}/messages/${MID}`);

  it('lets a viewer reply with text in the private chat', async () => {
    await assertSucceeds(setDoc(msg(B, cidOf(A, B)), textReply()));
  });

  it('lets a viewer reply with a voice note of 30 seconds or less', async () => {
    await assertSucceeds(setDoc(msg(B, cidOf(A, B)), voiceReply(30_000)));
  });

  it('refuses a voice reply longer than 30 seconds', async () => {
    await assertFails(setDoc(msg(B, cidOf(A, B)), voiceReply(30_001)));
    await assertFails(setDoc(msg(B, cidOf(A, B)), voiceReply(120_000)));
  });

  it('refuses any reply when the moment has replies off', async () => {
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ momentRef: { author: A, sid: SID_OFF } })));
    await assertFails(setDoc(msg(B, cidOf(A, B)), voiceReply(5_000, { momentRef: { author: A, sid: SID_OFF } })));
  });

  it('refuses a reply from someone who cannot see the moment (left out of the audience or blocked)', async () => {
    const cid = cidOf(A, C);
    await assertFails(setDoc(msg(C, cid), textReply({ senderId: C })));
    // Blocked after the moment was posted: the chat itself refuses the message.
    await seed(async (d) => { await setDoc(doc(d, `users/${A}/blocked/${B}`), { createdAt: Timestamp.now() }); });
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply()));
  });

  it('refuses a reply to an expired or missing moment', async () => {
    await seed(async (d) => { await setDoc(doc(d, `statuses/${A}/items/${SID}`), moment(SID, { expiresAt: Timestamp.fromMillis(Date.now() - 1000) })); });
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply()));
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ momentRef: { author: A, sid: '2b2d1f0a-1111-4222-8333-944455556666' } })));
  });

  it('refuses a reply that names a moment of someone who is not in this chat, or that names the sender itself', async () => {
    await assertFails(setDoc(msg(B, cidOf(B, C)), textReply({ senderId: B })));
    await assertFails(setDoc(msg(A, cidOf(A, B)), textReply({ senderId: A })));
  });

  it('refuses a spoofed sender and a bad reference shape', async () => {
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ senderId: A })));
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ momentRef: { author: A, sid: 'nope' } })));
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ momentRef: { author: A, sid: SID, extra: 1 } })));
    await assertFails(setDoc(msg(B, cidOf(A, B)), textReply({ momentRef: 'x' })));
  });

  it('refuses other message kinds as a moment reply', async () => {
    await assertFails(setDoc(msg(B, cidOf(A, B)), {
      senderId: B, kind: 'image', text: '', replyTo: null, createdAt: serverTimestamp(), momentRef: { author: A, sid: SID },
      media: { path: `conversations/${cidOf(A, B)}/${MID}/photo.jpg`, name: 'p.jpg', size: 1000, mime: 'image/jpeg' },
    }));
  });

  it('a plain message without a reference still works as before', async () => {
    const { momentRef: _omit, ...plain } = textReply();
    await assertSucceeds(setDoc(msg(B, cidOf(A, B)), plain));
  });
});
