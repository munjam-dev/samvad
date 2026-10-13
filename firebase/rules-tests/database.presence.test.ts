import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { get, ref, remove, serverTimestamp, set } from 'firebase/database';
import { createRulesEnv, otherProviderContext, phoneContext } from '../test-support/rulesEnv';

let env: RulesTestEnvironment;
const now = () => Date.now();

async function seed(path: string, value: unknown) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await set(ref(ctx.database(), path), value);
  });
}

beforeAll(async () => {
  env = await createRulesEnv();
});
beforeEach(async () => {
  await env.clearDatabase();
});
afterAll(async () => {
  await env.cleanup();
});

describe('presence', () => {
  it('the owner can write a valid presence; others and other providers cannot', async () => {
    const mine = ref(phoneContext(env, 'alice').database(), 'presence/alice');
    await assertSucceeds(set(mine, { online: true, lastSeen: serverTimestamp() }));
    await assertFails(set(ref(phoneContext(env, 'bob').database(), 'presence/alice'), { online: false, lastSeen: now() }));
    await assertFails(set(ref(otherProviderContext(env, 'alice', 'anonymous').database(), 'presence/alice'), { online: true, lastSeen: now() }));
    await assertFails(set(ref(env.unauthenticatedContext().database(), 'presence/alice'), { online: true, lastSeen: now() }));
  });

  it('rejects malformed presence', async () => {
    const mine = ref(phoneContext(env, 'alice').database(), 'presence/alice');
    await assertFails(set(mine, { online: 'yes', lastSeen: now() }));
    await assertFails(set(mine, { online: true }));
    await assertFails(set(mine, { online: true, lastSeen: now(), phone: '+919999900001' }));
    await assertFails(set(mine, { online: true, lastSeen: now() + 10 * 60 * 1000 }));
  });

  it('others can read presence unless the owner hides last seen; the owner always can', async () => {
    await seed('presence/alice', { online: true, lastSeen: now() });
    await assertSucceeds(get(ref(phoneContext(env, 'bob').database(), 'presence/alice')));
    await assertFails(get(ref(env.unauthenticatedContext().database(), 'presence/alice')));
    await seed('privacy/alice', { hideLastSeen: true });
    await assertFails(get(ref(phoneContext(env, 'bob').database(), 'presence/alice')));
    await assertSucceeds(get(ref(phoneContext(env, 'alice').database(), 'presence/alice')));
  });

  it('privacy settings are owner-only and boolean', async () => {
    await assertSucceeds(set(ref(phoneContext(env, 'alice').database(), 'privacy/alice/hideLastSeen'), true));
    await assertFails(set(ref(phoneContext(env, 'alice').database(), 'privacy/alice/hideLastSeen'), 'yes'));
    await assertFails(set(ref(phoneContext(env, 'alice').database(), 'privacy/alice/other'), true));
    await assertFails(get(ref(phoneContext(env, 'bob').database(), 'privacy/alice')));
    await assertFails(set(ref(phoneContext(env, 'bob').database(), 'privacy/alice/hideLastSeen'), false));
  });
});

describe('typing', () => {
  const CID = 'alice_bob';
  beforeEach(async () => {
    await seed(`members/${CID}`, { alice: true, bob: true });
  });

  it('members can mark themselves typing and read the conversation', async () => {
    await assertSucceeds(set(ref(phoneContext(env, 'alice').database(), `typing/${CID}/alice`), serverTimestamp()));
    await assertSucceeds(get(ref(phoneContext(env, 'bob').database(), `typing/${CID}`)));
    await assertSucceeds(remove(ref(phoneContext(env, 'alice').database(), `typing/${CID}/alice`)));
  });

  it('nobody can write another member, non-members can neither read nor write, values must be numbers', async () => {
    await assertFails(set(ref(phoneContext(env, 'alice').database(), `typing/${CID}/bob`), now()));
    await assertFails(set(ref(phoneContext(env, 'carol').database(), `typing/${CID}/carol`), now()));
    await assertFails(get(ref(phoneContext(env, 'carol').database(), `typing/${CID}`)));
    await assertFails(set(ref(phoneContext(env, 'alice').database(), `typing/${CID}/alice`), 'typing'));
  });

  it('membership mirror is server-only; members can read their own conversation entry', async () => {
    await assertFails(set(ref(phoneContext(env, 'carol').database(), `members/${CID}/carol`), true));
    await assertFails(set(ref(phoneContext(env, 'alice').database(), `members/${CID}/carol`), true));
    await assertSucceeds(get(ref(phoneContext(env, 'alice').database(), `members/${CID}`)));
    await assertFails(get(ref(phoneContext(env, 'carol').database(), `members/${CID}`)));
  });
});
