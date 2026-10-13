import { afterAll, beforeAll, describe, it } from 'vitest';
import { assertFails, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { ref as storageRef, uploadString, getBytes } from 'firebase/storage';
import { ref as dbRef, get, set } from 'firebase/database';
import { createRulesEnv, phoneContext } from '../test-support/rulesEnv';

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await createRulesEnv();
});
afterAll(async () => {
  await env.cleanup();
});

const contexts = () => [phoneContext(env, 'alice'), env.unauthenticatedContext()];

describe('default deny', () => {
  it('firestore denies reads and writes to an unnamed collection', async () => {
    for (const ctx of contexts()) {
      await assertFails(getDoc(doc(ctx.firestore(), 'unknown/x')));
      await assertFails(setDoc(doc(ctx.firestore(), 'unknown/x'), { a: 1 }));
    }
  });

  it('storage denies read and write of any path', async () => {
    for (const ctx of contexts()) {
      await assertFails(uploadString(storageRef(ctx.storage(), 'anything.txt'), 'hi'));
      await assertFails(getBytes(storageRef(ctx.storage(), 'anything.txt')));
    }
  });

  it('realtime database denies read and write of any path', async () => {
    for (const ctx of contexts()) {
      await assertFails(get(dbRef(ctx.database(), 'unknown/x')));
      await assertFails(set(dbRef(ctx.database(), 'unknown/x'), true));
    }
  });
});
