import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { assertFails, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, getDocs } from 'firebase/firestore';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});

describe('indexes and list access', () => {
  it('declares exactly the chat-list and scheduled-message indexes and the members.uid override', () => {
    const json = JSON.parse(readFileSync(join(root, 'firestore.indexes.json'), 'utf8'));
    expect(json.indexes).toEqual([
      {
        collectionGroup: 'conversations',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'members', arrayConfig: 'CONTAINS' },
          { fieldPath: 'lastMessageAt', order: 'DESCENDING' },
        ],
      },
      // Phase 3: the sender function's due query, and the sender's own list.
      {
        collectionGroup: 'scheduledMessages',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'status', order: 'ASCENDING' },
          { fieldPath: 'sendAt', order: 'ASCENDING' },
        ],
      },
      {
        collectionGroup: 'scheduledMessages',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'senderId', order: 'ASCENDING' },
          { fieldPath: 'sendAt', order: 'ASCENDING' },
        ],
      },
      // Phase 7: the busy check (array-contains + status) and the missed-call sweep.
      {
        collectionGroup: 'calls',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'participants', arrayConfig: 'CONTAINS' },
          { fieldPath: 'status', order: 'ASCENDING' },
        ],
      },
      {
        collectionGroup: 'calls',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'participants', arrayConfig: 'CONTAINS' },
          { fieldPath: 'createdAt', order: 'DESCENDING' },
        ],
      },
      {
        collectionGroup: 'calls',
        queryScope: 'COLLECTION',
        fields: [
          { fieldPath: 'status', order: 'ASCENDING' },
          { fieldPath: 'expiresAt', order: 'ASCENDING' },
        ],
      },
    ]);
    expect(json.fieldOverrides.map((o: any) => `${o.collectionGroup}.${o.fieldPath}`)).toEqual(['members.uid', 'messages.expiresAt', 'savedMessages.messageId', 'items.expiresAt', 'items.visibleTo']);
  });

  it('users, profiles and deletedAccounts cannot be listed; conversations only via a members filter', async () => {
    const db = phoneContext(env, 'alice').firestore();
    for (const name of ['users', 'profiles', 'deletedAccounts', 'conversations']) {
      await assertFails(getDocs(collection(db, name)));
    }
  });
});
