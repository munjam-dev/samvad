import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  initializeTestEnvironment,
  type RulesTestEnvironment,
  type RulesTestContext,
} from '@firebase/rules-unit-testing';
import { HOSTS, PROJECT_ID } from './emulator';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

export async function createRulesEnv(): Promise<RulesTestEnvironment> {
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { ...HOSTS.firestore, rules: readFileSync(join(root, 'firestore.rules'), 'utf8') },
    storage: { ...HOSTS.storage, rules: readFileSync(join(root, 'storage.rules'), 'utf8') },
    database: { ...HOSTS.database, rules: readFileSync(join(root, 'database.rules.json'), 'utf8') },
  });
}

export function phoneContext(
  env: RulesTestEnvironment,
  uid: string,
  phone = '+919999900001',
): RulesTestContext {
  return env.authenticatedContext(uid, {
    phone_number: phone,
    firebase: { sign_in_provider: 'phone' },
  } as never);
}

export function otherProviderContext(
  env: RulesTestEnvironment,
  uid: string,
  provider: string,
): RulesTestContext {
  return env.authenticatedContext(uid, {
    phone_number: '+919999900001',
    firebase: { sign_in_provider: provider },
  } as never);
}
