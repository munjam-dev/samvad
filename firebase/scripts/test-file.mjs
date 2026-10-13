// Runs one test file against the emulators: npm run test:file -- <path>
import { execSync } from 'node:child_process';

const file = process.argv[2];
if (!file) {
  console.error('usage: npm run test:file -- <path>');
  process.exit(2);
}
execSync('npm run build:functions', { stdio: 'inherit' });
execSync(
  `firebase emulators:exec --only auth,firestore,database,storage,functions --project demo-samvad "vitest run ${file}"`,
  { stdio: 'inherit' },
);
