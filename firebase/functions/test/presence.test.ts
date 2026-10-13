import { beforeEach, describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { mirrorMembers } from '../src/onConversationCreated';
import { deleteAccountCore } from '../src/deleteAccount';
import { databaseUrl } from '../src/deps';
import { testDeps } from '../../test-support/deps';
import { resetEmulators } from '../../test-support/auth';

const deps = testDeps();
const val = async (path: string) => (await deps.rtdb.ref(path).get()).val();

beforeEach(resetEmulators);

describe('RTDB membership mirror', () => {
  it('derives the default database URL', () => {
    expect(databaseUrl({ GCLOUD_PROJECT: 'samvad-dev' })).toBe('https://samvad-dev-default-rtdb.firebaseio.com');
    expect(databaseUrl({ FIREBASE_CONFIG: JSON.stringify({ databaseURL: 'https://x.firebasedatabase.app' }) })).toBe(
      'https://x.firebasedatabase.app',
    );
  });

  it('uses the default instance in the Functions emulator, where clients and rules live', () => {
    // The emulator passes the legacy "<project>.firebaseio.com" URL in FIREBASE_CONFIG.
    const env = {
      FUNCTIONS_EMULATOR: 'true',
      FIREBASE_CONFIG: JSON.stringify({ projectId: 'demo-samvad', databaseURL: 'https://demo-samvad.firebaseio.com' }),
    };
    expect(databaseUrl(env)).toBe('https://demo-samvad-default-rtdb.firebaseio.com');
  });

  it('mirrors members and is idempotent', async () => {
    await mirrorMembers(deps, 'alice_bob', ['alice', 'bob']);
    await mirrorMembers(deps, 'alice_bob', ['alice', 'bob']);
    expect(await val('members/alice_bob')).toEqual({ alice: true, bob: true });
  });

  it('runs as a trigger when a conversation is created', async () => {
    await deps.db.doc('conversations/alice_carol').set({
      type: 'direct', members: ['alice', 'carol'], createdBy: 'alice',
      createdAt: Timestamp.now(), lastMessage: null, lastMessageAt: Timestamp.now(),
    });
    let mirrored: unknown = null;
    for (let i = 0; i < 40 && !mirrored; i++) {
      mirrored = await val('members/alice_carol');
      if (!mirrored) await new Promise((r) => setTimeout(r, 250));
    }
    expect(mirrored).toEqual({ alice: true, carol: true });
  });

  it('deleteAccount clears presence, privacy and the membership mirror', async () => {
    await deps.auth.createUser({ uid: 'alice' });
    await deps.db.doc('conversations/alice_bob/members/alice').set({ uid: 'alice' });
    await deps.rtdb.ref().update({
      'presence/alice': { online: true, lastSeen: Date.now() },
      'privacy/alice': { hideLastSeen: true },
      'members/alice_bob': { alice: true, bob: true },
    });
    await deleteAccountCore(deps, 'alice');
    expect(await val('presence/alice')).toBeNull();
    expect(await val('privacy/alice')).toBeNull();
    expect(await val('members/alice_bob')).toEqual({ bob: true });
  });
});
