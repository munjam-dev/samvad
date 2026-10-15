import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, collectionGroup, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp, setDoc, Timestamp, updateDoc, where, type Firestore,
} from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Status: text or photo posts for 24 hours. visibleTo is written only by the server.
let env: RulesTestEnvironment;
const A = 'gita'; // author
const B = 'hari'; // a chat partner who can see it
const C = 'indu'; // a stranger who cannot
const SID = '7b2d1f0a-1111-4222-8333-944455556666';
const SID2 = '7b2d1f0a-1111-4222-8333-944455557777';

const db = (uid: string) => phoneContext(env, uid).firestore() as unknown as Firestore;
const item = (uid: string, author = A, sid = SID) => doc(db(uid), `statuses/${author}/items/${sid}`);
const future = (h = 20) => Timestamp.fromMillis(Date.now() + h * 3_600_000);

const newText = (extra: Record<string, unknown> = {}) => ({
  kind: 'text', text: 'Good morning', style: 'forest', audience: 'all', list: [],
  createdAt: serverTimestamp(), ...extra,
});

async function seed(fn: (d: Firestore) => Promise<void>) {
  await env.withSecurityRulesDisabled(async (ctx) => fn(ctx.firestore() as unknown as Firestore));
}
const seedItem = (sid: string, extra: Record<string, unknown> = {}) =>
  seed(async (d) => {
    await setDoc(doc(d, `statuses/${A}/items/${sid}`), {
      kind: 'text', text: 'hello', audience: 'all', list: [], createdAt: Timestamp.now(), expiresAt: future(), visibleTo: [B], ...extra,
    });
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
  });
});

describe('creating a status', () => {
  it('lets the author create a text status', async () => {
    await assertSucceeds(setDoc(item(A), newText()));
  });

  it('lets the author create an image status with media under their own folder', async () => {
    await assertSucceeds(
      setDoc(item(A), newText({ kind: 'image', text: 'Beach', media: { path: `statuses/${A}/${SID}/photo.jpg`, width: 800, height: 600, size: 90_000 } })),
    );
  });

  it('lets the author share with only chosen people, or with everyone except some', async () => {
    await assertSucceeds(setDoc(item(A), newText({ audience: 'only', list: [B] })));
    await assertSucceeds(setDoc(item(A, A, SID2), newText({ audience: 'except', list: [C] })));
  });

  it('rejects an expiry the client chose', async () => {
    await assertFails(setDoc(item(A), newText({ expiresAt: future(100) })));
    await assertFails(setDoc(item(A), newText({ expiresAt: future(1) })));
    await assertFails(setDoc(item(A), newText({ expiresAt: Timestamp.fromMillis(0) })));
  });

  it('rejects creating for someone else', async () => {
    await assertFails(setDoc(item(B, A), newText()));
  });

  it('rejects a client writing who can see it', async () => {
    await assertFails(setDoc(item(A), newText({ visibleTo: [C] })));
  });

  it('rejects bad shapes: empty text, huge text, unknown audience, missing list, extra fields', async () => {
    await assertFails(setDoc(item(A), newText({ text: '' })));
    await assertFails(setDoc(item(A), newText({ text: 'x'.repeat(701) })));
    await assertFails(setDoc(item(A), newText({ audience: 'everyone' })));
    await assertFails(setDoc(item(A), newText({ audience: 'only', list: [] })));
    await assertFails(setDoc(item(A), newText({ extra: 1 })));
  });

  it('rejects an image whose media path is not under the author and status id', async () => {
    await assertFails(
      setDoc(item(A), newText({ kind: 'image', text: '', media: { path: `statuses/${C}/${SID}/photo.jpg`, width: 10, height: 10, size: 100 } })),
    );
  });

  it('rejects a malformed status id', async () => {
    await assertFails(setDoc(doc(db(A), `statuses/${A}/items/not-a-uuid`), newText()));
  });
});

describe('reading a status', () => {
  beforeEach(() => seedItem(SID));

  it('the author reads their own', async () => {
    await assertSucceeds(getDoc(item(A)));
  });

  it('the author can list their own statuses', async () => {
    await assertSucceeds(getDocs(collection(db(A), `statuses/${A}/items`)));
  });

  it('nobody else can list someone else\'s statuses folder', async () => {
    await assertFails(getDocs(collection(db(B), `statuses/${A}/items`)));
    await assertFails(getDocs(collection(db(C), `statuses/${A}/items`)));
  });

  it('a person in visibleTo can read it', async () => {
    await assertSucceeds(getDoc(item(B)));
  });

  it('a person not in visibleTo cannot', async () => {
    await assertFails(getDoc(item(C)));
  });

  it('an expired status cannot be read by a viewer, but the author still can', async () => {
    await seedItem(SID2, { expiresAt: Timestamp.fromMillis(Date.now() - 1000) });
    await assertFails(getDoc(item(B, A, SID2)));
    await assertSucceeds(getDoc(item(A, A, SID2)));
  });

  it('blocking hides it at once, in either direction', async () => {
    await seed(async (d) => setDoc(doc(d, `users/${A}/blocked/${B}`), { createdAt: Timestamp.now() }));
    await assertFails(getDoc(item(B)));
    await seed(async (d) => {
      await updateDoc(doc(d, `users/${A}/blocked/${B}`), { createdAt: Timestamp.now() });
    });
    await env.clearFirestore();
    await seed(async (d) => {
      for (const u of [A, B]) await setDoc(doc(d, `profiles/${u}`), { displayName: u, about: '', photoUrl: null, updatedAt: Timestamp.now() });
      await setDoc(doc(d, `statuses/${A}/items/${SID}`), { kind: 'text', text: 'hi', audience: 'all', list: [], createdAt: Timestamp.now(), expiresAt: future(), visibleTo: [B] });
      await setDoc(doc(d, `users/${B}/blocked/${A}`), { createdAt: Timestamp.now() });
    });
    await assertFails(getDoc(item(B)));
  });

  it('the feed query returns only statuses visible to me', async () => {
    await seedItem(SID2, { visibleTo: [C] });
    const feed = (uid: string) => getDocs(query(collectionGroup(db(uid), 'items'), where('visibleTo', 'array-contains', uid)));
    await assertSucceeds(feed(B));
    await assertSucceeds(feed(C));
  });

  it('a feed query that is not limited to me is refused', async () => {
    await assertFails(getDocs(collectionGroup(db(B), 'items')));
    await assertFails(getDocs(query(collectionGroup(db(B), 'items'), where('visibleTo', 'array-contains', C))));
  });

  it('a client cannot edit a status', async () => {
    await assertFails(updateDoc(item(A), { text: 'changed' }));
    await assertFails(updateDoc(item(A), { visibleTo: [C] }));
  });

  it('only the author can delete it', async () => {
    await assertFails(deleteDoc(item(B)));
    await assertSucceeds(deleteDoc(item(A)));
  });
});

describe('views and reactions', () => {
  beforeEach(() => seedItem(SID));
  const view = (uid: string, viewer = uid) => doc(db(uid), `statuses/${A}/items/${SID}/views/${viewer}`);
  const reaction = (uid: string, viewer = uid) => doc(db(uid), `statuses/${A}/items/${SID}/reactions/${viewer}`);

  it('a visible viewer can record that they saw it, and the author can read it', async () => {
    await assertSucceeds(setDoc(view(B), { viewedAt: serverTimestamp() }));
    await assertSucceeds(getDoc(view(A, B)));
  });

  it('nobody else can read who viewed', async () => {
    await seed(async (d) => setDoc(doc(d, `statuses/${A}/items/${SID}/views/${B}`), { viewedAt: Timestamp.now() }));
    await assertFails(getDoc(view(C, B)));
  });

  it('a stranger cannot record a view, and nobody can record one for someone else', async () => {
    await assertFails(setDoc(view(C), { viewedAt: serverTimestamp() }));
    await assertFails(setDoc(view(B, C), { viewedAt: serverTimestamp() }));
  });

  it('the author cannot view their own status as a viewer', async () => {
    await assertFails(setDoc(view(A), { viewedAt: serverTimestamp() }));
  });

  it('a visible viewer can react once, change it, and remove it', async () => {
    await assertSucceeds(setDoc(reaction(B), { emoji: '❤️', at: serverTimestamp() }));
    await assertSucceeds(setDoc(reaction(B), { emoji: '😂', at: serverTimestamp() }));
    await assertSucceeds(deleteDoc(reaction(B)));
  });

  it('a stranger cannot react, and an oversized or empty emoji is refused', async () => {
    await assertFails(setDoc(reaction(C), { emoji: '❤️', at: serverTimestamp() }));
    await assertFails(setDoc(reaction(B), { emoji: '', at: serverTimestamp() }));
    await assertFails(setDoc(reaction(B), { emoji: 'x'.repeat(17), at: serverTimestamp() }));
  });
});
