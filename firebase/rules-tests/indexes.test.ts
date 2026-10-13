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
  it('firestore.indexes.json declares no composite indexes', () => {
    const json = JSON.parse(readFileSync(join(root, 'firestore.indexes.json'), 'utf8'));
    expect(json).toEqual({ indexes: [], fieldOverrides: [] });
  });

  it('no collection other than users/{uid}/devices allows list', async () => {
    const db = phoneContext(env, 'alice').firestore();
    for (const name of ['users', 'profiles', 'deletedAccounts']) {
      await assertFails(getDocs(collection(db, name)));
    }
  });
});
