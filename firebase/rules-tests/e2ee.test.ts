import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, collectionGroup, deleteField, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp, updateDoc,
} from 'firebase/firestore';
import { ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// End-to-end encrypted chats: the rules require a Signal envelope and refuse plaintext, malformed envelopes,
// envelopes addressed to a replaced install, and server-side scheduling. Key bundles are unreachable for clients.
let env: RulesTestEnvironment;
const ALICE_REG = 111;
const BOB_REG = 222;
const E2EE = 'alice_bob';
const PLAIN = 'alice_carol';
const fs = (uid: string) => phoneContext(env, uid).firestore();
const st = (uid: string) => phoneContext(env, uid).storage();

const CIPHER = 'QUJD'.repeat(40);
const enc = (reg: number, over: Record<string, unknown> = {}) => ({ v: 1, type: 'whisper', ciphertext: CIPHER, registrationId: reg, ...over });
const encMessage = (reg: number, over: Record<string, unknown> = {}, sender = 'alice') => ({
  senderId: sender, kind: 'text', text: '', replyTo: null, createdAt: serverTimestamp(), enc: enc(reg), ...over,
});
const mediaMessage = (mid: string, over: Record<string, unknown> = {}, cid = E2EE) => ({
  ...encMessage(BOB_REG, { kind: 'image' }),
  media: { path: `conversations/${cid}/${mid}/blob.bin`, size: 5000 },
  ...over,
});

const msgPath = (cid: string, mid: string) => `conversations/${cid}/messages/${mid}`;

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.firestore();
    for (const u of ['alice', 'bob', 'carol']) {
      await setDoc(doc(d, `profiles/${u}`), { displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
    }
    await setDoc(doc(d, 'e2eeKeys/alice'), { registrationId: ALICE_REG, identityKey: 'x', oneTimeCount: 1 });
    await setDoc(doc(d, 'e2eeKeys/bob'), { registrationId: BOB_REG, identityKey: 'y', oneTimeCount: 1 });
    await setDoc(doc(d, 'e2eeKeys/bob/oneTime/1'), { publicKey: 'k' });
    await setDoc(doc(d, `conversations/${E2EE}`), {
      type: 'direct', members: ['alice', 'bob'], createdBy: 'alice', createdAt: Timestamp.now(),
      lastMessage: null, lastMessageAt: Timestamp.now(), e2ee: true,
    });
    await setDoc(doc(d, `conversations/${PLAIN}`), {
      type: 'direct', members: ['alice', 'carol'], createdBy: 'alice', createdAt: Timestamp.now(),
      lastMessage: null, lastMessageAt: Timestamp.now(),
    });
  });
});

describe('sending in an end-to-end encrypted chat', () => {
  it('accepts a well-formed envelope addressed to the recipient current keys, in both directions', async () => {
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG)));
    await assertSucceeds(setDoc(doc(fs('bob'), msgPath(E2EE, randomUUID())), encMessage(ALICE_REG, {}, 'bob')));
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { enc: enc(BOB_REG, { type: 'prekey' }) })));
  });

  it('rejects plaintext text, and any text next to an envelope', async () => {
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), {
      senderId: 'alice', kind: 'text', text: 'hello server', replyTo: null, createdAt: serverTimestamp(),
    }));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { text: 'hello server' })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { text: ' ' })));
  });

  it('rejects a malformed envelope', async () => {
    const bad: Array<Record<string, unknown>> = [
      { enc: undefined },
      { enc: null },
      { enc: 'AAAA' },
      { enc: enc(BOB_REG, { v: 2 }) },
      { enc: enc(BOB_REG, { v: '1' }) },
      { enc: enc(BOB_REG, { type: 'plain' }) },
      { enc: enc(BOB_REG, { ciphertext: '' }) },
      { enc: enc(BOB_REG, { ciphertext: 'short' }) },
      { enc: enc(BOB_REG, { ciphertext: 'hello world, this is not base64!' }) },
      { enc: enc(BOB_REG, { ciphertext: 'A'.repeat(32_772) }) },
      { enc: enc(BOB_REG, { ciphertext: 42 }) },
      { enc: enc(BOB_REG, { registrationId: '222' }) },
      { enc: enc(BOB_REG, { registrationId: 0 }) },
      { enc: enc(BOB_REG, { registrationId: 16381 }) },
      { enc: { ...enc(BOB_REG), extra: 'plaintext smuggled here' } },
      { enc: { v: 1, type: 'whisper', registrationId: BOB_REG } },
    ];
    for (const over of bad) {
      const data = encMessage(BOB_REG);
      Object.assign(data, over);
      if (over.enc === undefined) delete (data as Record<string, unknown>).enc;
      await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), data));
    }
  });

  it('accepts the largest allowed ciphertext', async () => {
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { enc: enc(BOB_REG, { ciphertext: 'A'.repeat(32_768) }) })));
  });

  it('rejects an envelope addressed to a replaced install (stale registration id)', async () => {
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG + 1)));
    // Bob signs in on a new phone: new keys, new registration id. The old address is now refused.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'e2eeKeys/bob'), { registrationId: 333, identityKey: 'z', oneTimeCount: 0 });
    });
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG)));
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(333)));
  });

  it('cannot send to someone whose keys were removed', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), `conversations/${E2EE}`), { e2ee: true });
      await setDoc(doc(ctx.firestore(), 'e2eeKeys/bob'), {});
    });
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG)));
  });

  it('keeps the usual checks: sender, membership, timestamps, blocks, id format', async () => {
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { senderId: 'bob' })));
    await assertFails(setDoc(doc(fs('carol'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, {}, 'carol')));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { createdAt: Timestamp.fromMillis(1) })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, 'not-a-uuid')), encMessage(BOB_REG)));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { deleted: true })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { reactions: { alice: '👍' } })));
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/bob/blocked/alice'), { createdAt: Timestamp.now() });
    });
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG)));
  });

  it('refuses content that would give the server something to read', async () => {
    const other = '7b2d1f0a-1111-4222-8333-944455556666';
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { replyTo: other })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { forwarded: true })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { mentions: ['bob'] })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { kind: 'poll', poll: { question: 'q', options: ['a', 'b'], ids: ['0', '1'], multi: false } })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { kind: 'contact', contact: { name: 'Asha', phones: ['+919999900001'] } })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { kind: 'system' })));
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { momentRef: { author: 'bob', sid: randomUUID() } })));
    // A contact card is allowed as a kind: its content is inside the envelope.
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, randomUUID())), encMessage(BOB_REG, { kind: 'contact' })));
  });

  it('accepts encrypted attachments and refuses plaintext media descriptions', async () => {
    const mid = randomUUID();
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, mid)), mediaMessage(mid)));
    const withThumb = randomUUID();
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(E2EE, withThumb)), mediaMessage(withThumb, {
      kind: 'video', media: { path: `conversations/${E2EE}/${withThumb}/blob.bin`, size: 9000, thumbPath: `conversations/${E2EE}/${withThumb}/thumb.bin` },
    })));

    const plain = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, plain)), mediaMessage(plain, {
      media: { path: `conversations/${E2EE}/${plain}/photo.jpg`, name: 'photo.jpg', size: 5000, mime: 'image/jpeg' },
    })));
    const wrongName = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, wrongName)), mediaMessage(wrongName, {
      media: { path: `conversations/${E2EE}/${wrongName}/photo.jpg`, size: 5000 },
    })));
    const leaksName = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, leaksName)), mediaMessage(leaksName, {
      media: { path: `conversations/${E2EE}/${leaksName}/blob.bin`, size: 5000, name: 'holiday.jpg', mime: 'image/jpeg' },
    })));
    const elsewhere = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, elsewhere)), mediaMessage(elsewhere, {
      media: { path: `conversations/${PLAIN}/${elsewhere}/blob.bin`, size: 5000 },
    })));
    const tooBig = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, tooBig)), mediaMessage(tooBig, {
      media: { path: `conversations/${E2EE}/${tooBig}/blob.bin`, size: 16 * 1024 * 1024 + 65 },
    })));
    const missing = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, missing)), encMessage(BOB_REG, { kind: 'image' })));
    const textWithMedia = randomUUID();
    await assertFails(setDoc(doc(fs('alice'), msgPath(E2EE, textWithMedia)), mediaMessage(textWithMedia, { kind: 'text' })));
  });

  it('does not allow an envelope in a chat that is not end-to-end encrypted', async () => {
    await assertFails(setDoc(doc(fs('alice'), msgPath(PLAIN, randomUUID())), encMessage(BOB_REG)));
    await assertSucceeds(setDoc(doc(fs('alice'), msgPath(PLAIN, randomUUID())), {
      senderId: 'alice', kind: 'text', text: 'ordinary message', replyTo: null, createdAt: serverTimestamp(),
    }));
  });
});

describe('editing, deleting and reacting in an encrypted chat', () => {
  const seedMessage = async (mid: string, over: Record<string, unknown> = {}) => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), msgPath(E2EE, mid)), {
        senderId: 'alice', kind: 'text', text: '', replyTo: null, createdAt: Timestamp.now(), deleted: false, reactions: {}, enc: enc(BOB_REG), ...over,
      });
    });
  };

  it('an edit replaces the envelope and nothing else', async () => {
    const mid = randomUUID();
    await seedMessage(mid);
    const d = doc(fs('alice'), msgPath(E2EE, mid));
    await assertSucceeds(updateDoc(d, { enc: enc(BOB_REG, { type: 'prekey' }), editedAt: serverTimestamp() }));
    await assertFails(updateDoc(d, { text: 'plain edit', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(d, { enc: enc(BOB_REG + 5), editedAt: serverTimestamp() }));
    await assertFails(updateDoc(d, { enc: enc(BOB_REG), text: 'also text', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(d, { enc: deleteField(), editedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(fs('bob'), msgPath(E2EE, mid)), { enc: enc(ALICE_REG), editedAt: serverTimestamp() }));
  });

  it('an edit is refused after 15 minutes', async () => {
    const mid = randomUUID();
    await seedMessage(mid, { createdAt: Timestamp.fromMillis(Date.now() - 16 * 60 * 1000) });
    await assertFails(updateDoc(doc(fs('alice'), msgPath(E2EE, mid)), { enc: enc(BOB_REG), editedAt: serverTimestamp() }));
  });

  it('a message from before the upgrade (plaintext) cannot be edited into the encrypted chat', async () => {
    const mid = randomUUID();
    await seedMessage(mid, { text: 'old plaintext' });
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(ctx.firestore(), msgPath(E2EE, mid)), { enc: deleteField() });
    });
    const d = doc(fs('alice'), msgPath(E2EE, mid));
    await assertFails(updateDoc(d, { text: 'new plaintext', editedAt: serverTimestamp() }));
    await assertFails(updateDoc(d, { enc: enc(BOB_REG), editedAt: serverTimestamp() }));
  });

  it('delete for everyone removes the ciphertext', async () => {
    const mid = randomUUID();
    await seedMessage(mid);
    const d = doc(fs('alice'), msgPath(E2EE, mid));
    await assertFails(updateDoc(d, { deleted: true, text: '' }));
    await assertFails(updateDoc(doc(fs('bob'), msgPath(E2EE, mid)), { deleted: true, text: '', enc: deleteField() }));
    await assertSucceeds(updateDoc(d, { deleted: true, text: '', enc: deleteField() }));
  });

  it('reactions work and stay visible to the server (documented)', async () => {
    const mid = randomUUID();
    await seedMessage(mid);
    await assertSucceeds(updateDoc(doc(fs('bob'), msgPath(E2EE, mid)), { 'reactions.bob': '👍' }));
    await assertFails(updateDoc(doc(fs('bob'), msgPath(E2EE, mid)), { 'reactions.alice': '👍' }));
  });
});

describe('creating chats and the encryption flag', () => {
  const create = (uid: string, cid: string, members: string[], extra: Record<string, unknown> = {}) =>
    setDoc(doc(fs(uid), `conversations/${cid}`), {
      type: 'direct', members, createdBy: uid, createdAt: serverTimestamp(), lastMessage: null, lastMessageAt: serverTimestamp(), ...extra,
    });

  it('a new chat can be created encrypted only when both people have published keys', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'e2eeKeys/carol'), { registrationId: 7, identityKey: 'c', oneTimeCount: 0 });
      await setDoc(doc(ctx.firestore(), 'profiles/dave'), { displayName: 'dave', about: '', photoUrl: null, updatedAt: Timestamp.now() });
    });
    await assertSucceeds(create('bob', 'bob_carol', ['bob', 'carol'], { e2ee: true }));
    // dave has no keys (older app): a chat with e2ee = true is refused, a plaintext one is fine.
    await assertFails(create('bob', 'bob_dave', ['bob', 'dave'], { e2ee: true }));
    await assertSucceeds(create('bob', 'bob_dave', ['bob', 'dave']));
  });

  it('e2ee can only ever be true at creation', async () => {
    await assertFails(create('bob', 'bob_carol', ['bob', 'carol'], { e2ee: false }));
    await assertFails(create('bob', 'bob_carol', ['bob', 'carol'], { e2ee: 'yes' }));
  });

  it('nobody can switch the flag from the app', async () => {
    await assertFails(updateDoc(doc(fs('alice'), `conversations/${PLAIN}`), { e2ee: true }));
    await assertFails(updateDoc(doc(fs('alice'), `conversations/${E2EE}`), { e2ee: false }));
    await assertFails(updateDoc(doc(fs('alice'), `conversations/${E2EE}`), { e2eeSince: serverTimestamp() }));
  });

  it('scheduling is refused in an encrypted chat and still works elsewhere', async () => {
    const scheduled = (cid: string) => ({
      senderId: 'alice', conversationId: cid, text: 'later', replyTo: null,
      sendAt: Timestamp.fromMillis(Date.now() + 3_600_000), status: 'pending', createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    await assertFails(setDoc(doc(fs('alice'), `scheduledMessages/${randomUUID()}`), scheduled(E2EE)));
    await assertSucceeds(setDoc(doc(fs('alice'), `scheduledMessages/${randomUUID()}`), scheduled(PLAIN)));
  });
});

describe('public key bundles', () => {
  it('no client can read or write anyone\'s bundle, including their own', async () => {
    for (const uid of ['alice', 'bob', 'carol']) {
      const db = fs(uid);
      await assertFails(getDoc(doc(db, 'e2eeKeys/bob')));
      await assertFails(getDoc(doc(db, `e2eeKeys/${uid}`)));
      await assertFails(getDoc(doc(db, 'e2eeKeys/bob/oneTime/1')));
      await assertFails(getDocs(collection(db, 'e2eeKeys')));
      await assertFails(getDocs(collection(db, 'e2eeKeys/bob/oneTime')));
      await assertFails(getDocs(collectionGroup(db, 'oneTime')));
      await assertFails(setDoc(doc(db, `e2eeKeys/${uid}`), { registrationId: 1, identityKey: 'attacker' }));
      await assertFails(setDoc(doc(db, `e2eeKeys/${uid}/oneTime/9`), { publicKey: 'attacker' }));
      await assertFails(updateDoc(doc(db, 'e2eeKeys/bob'), { identityKey: 'attacker' }));
      await assertFails(setDoc(doc(db, `e2eeFetchLimits/${uid}_bob`), { count: 0 }));
      await assertFails(getDoc(doc(db, `e2eeFetchLimits/${uid}_bob`)));
    }
  });
});

describe('encrypted attachment files (Storage)', () => {
  const bytes = new Uint8Array(1000).fill(3);
  const meta = (type: string, uid = 'alice') => ({ contentType: type, customMetadata: { senderId: uid } });
  const upload = (cid: string, mid: string, file: string, type: string) =>
    uploadBytes(ref(st('alice'), `conversations/${cid}/${mid}/${file}`), bytes, meta(type));

  it('an encrypted chat accepts only ciphertext named blob.bin or thumb.bin', async () => {
    await assertSucceeds(upload(E2EE, randomUUID(), 'blob.bin', 'application/octet-stream'));
    const mid = randomUUID();
    await assertSucceeds(upload(E2EE, mid, 'blob.bin', 'application/octet-stream'));
    await assertSucceeds(upload(E2EE, mid, 'thumb.bin', 'application/octet-stream'));
    await assertFails(upload(E2EE, randomUUID(), 'photo.jpg', 'image/jpeg'));
    await assertFails(upload(E2EE, randomUUID(), 'blob.bin', 'image/jpeg'));
    await assertFails(upload(E2EE, randomUUID(), 'photo.jpg', 'application/octet-stream'));
    await assertFails(upload(E2EE, randomUUID(), 'video.mp4', 'video/mp4'));
  });

  it('a chat that is not encrypted keeps refusing octet-stream', async () => {
    await assertFails(upload(PLAIN, randomUUID(), 'blob.bin', 'application/octet-stream'));
    await assertSucceeds(upload(PLAIN, randomUUID(), 'photo.jpg', 'image/jpeg'));
  });

  it('outsiders cannot upload to an encrypted chat', async () => {
    await assertFails(uploadBytes(ref(st('carol'), `conversations/${E2EE}/${randomUUID()}/blob.bin`), bytes, meta('application/octet-stream', 'carol')));
  });
});
