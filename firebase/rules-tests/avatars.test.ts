import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, Timestamp } from 'firebase/firestore';
import { getBytes, ref, uploadBytes } from 'firebase/storage';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

// Phase 1.6: profile photos are written only by setProfilePhoto and hidden from blocked people.
let env: RulesTestEnvironment;
const PATH = 'avatars/omar/7f1c2b3a-0000-4000-8000-000000000001.jpg';
const jpegMeta = { contentType: 'image/jpeg' };

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await uploadBytes(ref(ctx.storage(), PATH), new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), jpegMeta);
  });
});
afterAll(async () => {
  await env.cleanup();
});

describe('avatars (Storage)', () => {
  it('signed-in users can see a photo; signed-out users cannot', async () => {
    await assertSucceeds(getBytes(ref(phoneContext(env, 'omar').storage(), PATH)));
    await assertSucceeds(getBytes(ref(phoneContext(env, 'priya').storage(), PATH)));
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), PATH)));
  });

  it('people the owner blocked cannot see it', async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'users/omar/blocked/priya'), { createdAt: Timestamp.now() });
    });
    await assertFails(getBytes(ref(phoneContext(env, 'priya').storage(), PATH)));
    await assertSucceeds(getBytes(ref(phoneContext(env, 'quinn').storage(), PATH)));
  });

  it('clients can never write avatars, not even their own', async () => {
    const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
    await assertFails(uploadBytes(ref(phoneContext(env, 'omar').storage(), 'avatars/omar/new.jpg'), bytes, jpegMeta));
    await assertFails(uploadBytes(ref(phoneContext(env, 'priya').storage(), 'avatars/omar/x.jpg'), bytes, jpegMeta));
  });
});
