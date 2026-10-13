// Runs the e2e flow on the emulators, captures all output, then checks it for leaked secrets.
import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

mkdirSync('.tmp', { recursive: true });
execSync('npm run build:functions', { stdio: 'inherit' });
let output = '';
try {
  output = execSync(
    'firebase emulators:exec --only auth,firestore,database,storage,functions --project demo-samvad "vitest run functions/test/e2e.flow.test.ts"',
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 },
  );
} catch (err) {
  output = `${err.stdout ?? ''}${err.stderr ?? ''}`;
  writeFileSync('.tmp/emulator-output.log', output);
  console.error('e2e flow failed; see .tmp/emulator-output.log');
  process.exit(1);
}
writeFileSync('.tmp/emulator-output.log', output);
execSync('node scripts/check-logs.mjs .tmp/emulator-output.log', { stdio: 'inherit' });
