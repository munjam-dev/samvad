// Fails when function log output leaks the e2e phone number or tokens, or lacks a real function log line.
// Only function output is scanned (lines the emulator prefixes with '>' or tags 'functions:'): the Auth
// emulator itself prints test verification codes and phone numbers, which is test-only tooling.
import { readFileSync } from 'node:fs';

const outputFile = process.argv[2];
// Strip ANSI colour codes first: CI forces colour, so a function line may start with ESC[90m>.
const ANSI = /\u001b\[[0-9;]*m/g;
const output = readFileSync(outputFile, 'utf8')
  .replace(ANSI, '')
  .split('\n')
  .map((line) => line.trimStart())
  .filter((line) => line.startsWith('>') || line.includes('functions:'))
  .join('\n');
const secrets = JSON.parse(readFileSync('.tmp/e2e-secrets.json', 'utf8'));

const needles = [secrets.phone, secrets.phone.replace('+', ''), ...secrets.idTokens];
const leaked = needles.filter((n) => n && output.includes(n));
if (leaked.length > 0) {
  console.error(`log hygiene: ${leaked.length} secret value(s) found in emulator output`);
  process.exit(1);
}

// Positive control: a structured line emitted by the deleteAccount function itself.
const functionLine = output
  .split('\n')
  .some((line) => line.includes('"event":"account_deleted"') && line.includes('"severity":"INFO"'));
if (!functionLine) {
  console.error('log hygiene: no account_deleted function log line captured; the check would be vacuous');
  process.exit(1);
}
console.log('log hygiene: OK (no phone number or token in output; function log line present)');
