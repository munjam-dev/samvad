import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, serverTimestamp, setDoc, Timestamp, updateDoc } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Contact cards: kind `contact` with a `contact` map {name, phones[1..3] in E.164, organisation?}.
let env: RulesTestEnvironment;
const CID = 'alice_bob';
const fsOf = (uid: string) => phoneContext(env, uid).firestore();

const card = (over: Record<string, unknown> = {}) => ({
  name: 'Asha Rao',
  phones: ['+919876543210'],
  ...over,
});

const contactMessage = (c: unknown = card(), over: Record<string, unknown> = {}) => ({
  senderId: 'alice',
  kind: 'contact',
  text: '',
  replyTo: null,
  createdAt: serverTimestamp(),
  contact: c,
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

describe('contact messages', () => {
  it('a member can share a contact card with one to three numbers and an organisation', async () => {
    await assertSucceeds(setDoc(at(), contactMessage()));
    await assertSucceeds(setDoc(at(), contactMessage(card({ organisation: 'Rao & Sons' }))));
    await assertSucceeds(setDoc(at(), contactMessage(card({ phones: ['+919876543210', '+14155550123', '+442071838750'] }))));
  });

  it('rejects a non-member, a spoofed sender and a blocked pair', async () => {
    await assertFails(setDoc(at('carol'), contactMessage(card(), { senderId: 'carol' })));
    await assertFails(setDoc(at('alice'), contactMessage(card(), { senderId: 'bob' })));
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/bob/blocked/alice'), { blockedAt: Timestamp.now() });
    });
    await assertFails(setDoc(at(), contactMessage()));
  });

  it('rejects malformed phone numbers', async () => {
    for (const bad of ['9876543210', '+0123456789', '+91 98765 43210', '+91-9876543210', '+123456', '+1234567890123456', 'abc', '']) {
      await assertFails(setDoc(at(), contactMessage(card({ phones: [bad] }))));
    }
    await assertFails(setDoc(at(), contactMessage(card({ phones: [12345678901] }))));
    await assertFails(setDoc(at(), contactMessage(card({ phones: ['+919876543210', 'nope'] }))));
  });

  it('rejects too many, no or duplicate numbers', async () => {
    await assertFails(setDoc(at(), contactMessage(card({ phones: [] }))));
    await assertFails(setDoc(at(), contactMessage(card({ phones: ['+919876543210', '+14155550123', '+442071838750', '+61291234567'] }))));
    await assertFails(setDoc(at(), contactMessage(card({ phones: ['+919876543210', '+919876543210'] }))));
    await assertFails(setDoc(at(), contactMessage(card({ phones: '+919876543210' }))));
  });

  it('rejects a bad name or organisation, extra keys and a wrong shape', async () => {
    await assertFails(setDoc(at(), contactMessage(card({ name: '' }))));
    await assertFails(setDoc(at(), contactMessage(card({ name: 'x'.repeat(101) }))));
    await assertFails(setDoc(at(), contactMessage(card({ name: 7 }))));
    await assertFails(setDoc(at(), contactMessage(card({ organisation: 'o'.repeat(101) }))));
    await assertFails(setDoc(at(), contactMessage(card({ organisation: 5 }))));
    await assertFails(setDoc(at(), contactMessage(card({ email: 'a@b.c' }))));
    await assertFails(setDoc(at(), contactMessage({ phones: ['+919876543210'] })));
    await assertFails(setDoc(at(), contactMessage(null)));
    await assertFails(setDoc(at(), contactMessage('Asha')));
    const noContact = contactMessage();
    delete (noContact as any).contact;
    await assertFails(setDoc(at(), noContact));
  });

  it('keeps the contact field to contact messages and the text empty', async () => {
    await assertFails(setDoc(at(), contactMessage(card(), { text: 'here you go' })));
    await assertFails(setDoc(at(), contactMessage(card(), { media: { path: 'x' } })));
    await assertFails(setDoc(at(), { senderId: 'alice', kind: 'text', text: 'hi', replyTo: null, createdAt: serverTimestamp(), contact: card() }));
  });

  it('a contact card cannot be edited, only deleted for everyone', async () => {
    const mid = randomUUID();
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), `conversations/${CID}/messages/${mid}`), {
        senderId: 'alice', kind: 'contact', text: '', replyTo: null, createdAt: Timestamp.now(), contact: card(),
      });
    });
    const ref = at('alice', mid);
    await assertFails(updateDoc(ref, { contact: card({ name: 'Someone else' }) }));
    await assertFails(updateDoc(ref, { text: 'edited', editedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(ref, { deleted: true, text: '' }));
  });
});
