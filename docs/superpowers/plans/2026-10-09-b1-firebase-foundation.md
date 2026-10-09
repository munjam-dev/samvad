# B1 Firebase Foundation + Auth Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `firebase/` project: deny-by-default Firestore/Storage/Realtime Database rules, the `users`/`profiles`/`devices`/`deletedAccounts` data model, the `deleteAccount` and `signOutAllDevices` callables, and a test suite that proves them on the Emulator Suite.

**Architecture:** Clients write profile data directly to Firestore under security rules; trusted work (account deletion, token revocation) runs in two TypeScript callable Cloud Functions using the Admin SDK. Function logic is split into a pure-ish `*Core(deps, uid)` function (tested against the emulators with injected failures) and a thin `handle*` wrapper that applies guards (auth, provider, recency), so each layer is testable alone.

**Tech Stack:** Firebase Emulator Suite (Auth, Firestore, Realtime Database, Storage, Functions), Cloud Functions for Firebase v2 (`onCall`), TypeScript, vitest, `@firebase/rules-unit-testing`.

**Spec:** `docs/superpowers/specs/2026-10-09-foundation-auth-design.md` (revision 3.1, including the plan-time amendments in §0.1). Read it before starting; this plan does not repeat its rule tables.

## Global Constraints

- **Additive only.** Create files under `firebase/` and `docs/` only. Do not modify or delete any existing file. The 73 pre-existing uncommitted changes stay byte-for-byte unchanged.
- **Commits:** explicit paths only: `git add -- <paths>` then `git commit -m "<msg>" -- <paths>`. Never `git add -A`, `git commit -a`, reset, stash, amend or force-push. Every commit message ends with the line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- **Emulator project id:** `demo-samvad` (the `demo-` prefix guarantees no live project is touched). Application ID reference: `com.samvad.messenger`. Default function region: `asia-south1`.
- **Toolchain:** JDK 21 (Temurin) and Node 24 LTS (24.12 or newer). Pinned versions, exact (no `^`): `firebase-tools` 15.33.0, `firebase-functions` 7.4.0, `firebase-admin` 14.5.0, `firebase` 13.0.0, `@firebase/rules-unit-testing` 6.0.0, `typescript` 5.9.3, `vitest` 5.0.3, `@types/node` 24.19.1. Commit lockfiles. `firebase-tools` is a devDependency, never global.
- **Rules:** deny by default; every allow requires `request.auth != null && request.auth.token.firebase.sign_in_provider == 'phone'`. No client delete on `users`, `profiles`. `revokedAt`, `tokensRevokedAt` and everything under `deletedAccounts` are server-only. No composite indexes (`firestore.indexes.json` is `{"indexes":[],"fieldOverrides":[]}`).
- **Field values:** `displayName` 1–64 chars with a non-whitespace char; `about` ≤ 140; `photoUrl` must be null; `appVersion`/`osVersion` ≤ 32; `platform` == `"android"`; `phoneE164` matches `^\+[1-9][0-9]{6,14}$` and equals `request.auth.token.phone_number`; `deviceId` is a lowercase UUID.
- **Callables:** target uid is always `request.auth.uid`, never a payload field. `deleteAccount` requires `auth_time` ≤ 300 s old. `signOutAllDevices` cooldown is 60 s. Error codes: `unauthenticated`, `permission-denied` (non-phone provider), `failed-precondition` (stale `auth_time`, cooldown), `internal`. `enforceAppCheck` and `consumeAppCheckToken` are true unless `process.env.FUNCTIONS_EMULATOR === 'true'`.
- **Secrets and PII:** no service-account JSON, `.env` secrets or `google-services.json` anywhere. Logs never contain phone numbers, tokens, OTPs or document bodies.
- **No E2EE wording** in any file.

## Review Focus

Failure modes the spec implies but no obvious test covers; each has a pinning test in the task named.

1. A deleted user's still-valid token re-creates their documents (Task 3 rules, Task 7 callable).
2. `signOutAllDevices` by a user who has no `users/{uid}` document yet must not create a partial one, because that would make the client's later profile `create` fail (Task 8).
3. `deleteAccount` retried after the Auth user is already gone must succeed, not error (Task 7).
4. A user with more than 500 device documents exceeds Firestore's batch limit (Task 8).
5. Non-ASCII and blank display names: 64 Devanagari characters pass, 65 fail, whitespace-only fails (Task 4).

---

### Task 1: Toolchain prerequisites (needs owner OK before running)

**Files:** none (machine setup only).

- [ ] **Step 1: Ask the owner for permission** to install Temurin JDK 21 and Node 24 LTS (`winget install EclipseAdoptium.Temurin.21.JDK`, `winget install OpenJS.NodeJS.LTS`). Do not proceed without a yes. This does not touch the repository or the JDK 8 install.
- [ ] **Step 2: Install, then open a fresh shell.** If `java -version` still reports 1.8, put the JDK 21 `bin` directory ahead of JDK 8 on PATH (or set `JAVA_HOME`) with the owner's approval.
- [ ] **Step 3: Verify.** Run `java -version` (expect `21.x`) and `node -v` (expect `v24.12` or newer). Run `npm view firebase-tools@15.33.0 version` (expect `15.33.0`).

No commit.

### Task 2: `firebase/` scaffold with deny-all rules

**Files:**
- Create: `firebase/{.gitignore,.firebaserc.example,firebase.json,package.json,vitest.config.ts,tsconfig.json,firestore.rules,firestore.indexes.json,storage.rules,database.rules.json}` and `firebase/scripts/test-file.mjs`
- Create: `firebase/test-support/emulator.ts`, `firebase/test-support/rulesEnv.ts`
- Test: `firebase/rules-tests/default-deny.test.ts`

**Interfaces:**
- Produces (`test-support/emulator.ts`): `PROJECT_ID = 'demo-samvad'`, `REGION = 'asia-south1'`, `HOSTS = { auth: '127.0.0.1:9099', firestore: {host:'127.0.0.1', port:8080}, database: {host:'127.0.0.1', port:9000}, storage: {host:'127.0.0.1', port:9199}, functions: {host:'127.0.0.1', port:5001} }`.
- Produces (`test-support/rulesEnv.ts`): `createRulesEnv(): Promise<RulesTestEnvironment>` (loads `firestore.rules`, `storage.rules`, `database.rules.json` from disk); `phoneContext(env: RulesTestEnvironment, uid: string, phone = '+919999900001')` returning an authenticated context whose token has `phone_number: phone` and `firebase: { sign_in_provider: 'phone' }`; `otherProviderContext(env, uid, provider: string)` for the same uid with a different provider.
- Produces (`package.json` scripts): `test` = build functions, then `firebase emulators:exec --only auth,firestore,database,storage,functions --project demo-samvad "vitest run"`; `emulators` = build functions, then `firebase emulators:start --only auth,firestore,database,storage,functions --project demo-samvad`; `test:file` = `node scripts/test-file.mjs`, which builds functions and runs `firebase emulators:exec` with `vitest run <path-argument>` (use it as `npm run test:file -- <path>`, because arguments to `npm test` would not reach vitest).

- [ ] **Step 1: Write the failing test** `default-deny.test.ts` with these cases, each using an authenticated phone context and an unauthenticated context unless stated:
  - `firestore denies reads and writes to an unnamed collection` (assert `assertFails` for `getDoc` and `setDoc` on `unknown/x`, both contexts);
  - `storage denies read and write of any path` (`ref('anything.txt')` upload and `getBytes` fail for both contexts);
  - `realtime database denies read and write of any path` (`ref('presence/x')` get and set fail for both contexts).
- [ ] **Step 2: Run it to verify it fails** (`cd firebase && npm install && npm test`). Expected: FAIL because rules files are missing.
- [ ] **Step 3: Create the scaffold.** `firebase.json`: emulators on the ports in `HOSTS`, `ui.enabled=false`, `singleProjectMode=true`, firestore/storage/database rules and indexes paths, `functions` with `source: "functions"`, `runtime: "nodejs24"`, `predeploy` running the functions build, `ignore` of `node_modules`. `firestore.rules` contains only `rules_version = '2'` and a catch-all `allow read, write: if false`. `storage.rules` and `database.rules.json` deny everything. `.gitignore` ignores `node_modules`, `functions/lib`, `*.log`, `.firebaserc`, `.tmp/`, `functions/.env`, `functions/.env.*`, `*-adminsdk-*.json`, `serviceAccount*.json`. `.firebaserc.example` has placeholder aliases `dev`, `staging`, `prod`. `vitest.config.ts`: include `rules-tests/**/*.test.ts` and `functions/test/**/*.test.ts`, `fileParallelism: false`, `testTimeout: 30000`. `package.json` pins the exact devDependency versions from Global Constraints.
- [ ] **Step 4: Run `npm test` again.** Expected: PASS (3 tests), with all five emulators starting from the checked-in config.
- [ ] **Step 5: Commit** `firebase/` files (including `package-lock.json`) with message `feat(firebase): scaffold project with deny-all rules`.

### Task 3: Firestore rules — `users` and `deletedAccounts`

**Files:**
- Modify: `firebase/firestore.rules`
- Test: `firebase/rules-tests/firestore.users.test.ts`

**Interfaces:**
- Consumes: `createRulesEnv`, `phoneContext`, `otherProviderContext` (Task 2).
- Produces (rules helper functions reused by Tasks 4–5): `signedIn()`, `isOwner(uid)`, `notDeleted(uid)` (= `!exists(/databases/$(database)/documents/deletedAccounts/$(uid))`).

- [ ] **Step 1: Write the failing tests** (client SDK calls with `serverTimestamp()` where the spec says `== request.time`). Valid create body: `{ phoneE164: '+919999900001', consent: { version: 1, acceptedAt: serverTimestamp() }, createdAt: serverTimestamp() }`.
  - `owner can create own user doc`
  - `owner can get own user doc; list is denied`
  - `another user cannot get, create or update users/B`
  - `unauthenticated and anonymous and other-provider contexts are denied everywhere under users`
  - `create rejects phoneE164 different from the token's phone_number`
  - `create rejects a malformed phoneE164` (`'12345'`, `'+0123456789'`)
  - `create rejects unknown fields, wrong types, missing fields`
  - `create rejects client-set tokensRevokedAt`
  - `update may only raise consent.version, with acceptedAt == request.time` (equal version fails, lower fails, higher passes)
  - `update rejects changes to phoneE164 or createdAt`
  - `client delete is denied`
  - `client cannot read or write deletedAccounts/{uid}` (own uid and another's)
  - `create is denied once deletedAccounts/{uid} exists` (seed the tombstone with `env.withSecurityRulesDisabled`)
- [ ] **Step 2: Run** `npm run test:file -- rules-tests/firestore.users.test.ts`; expected FAIL (rules are still catch-all deny, so the "can" cases fail).
- [ ] **Step 3: Implement** the `users/{uid}` match in `firestore.rules`: `get` for owner; `create` requires owner, `notDeleted(uid)`, `keys().hasOnly/hasAll(['phoneE164','consent','createdAt'])`, the E.164 regex plus equality with the token claim, `consent` map with `version` int ≥ 1 and `acceptedAt == request.time`, `createdAt == request.time`; `update` requires owner and `diff().affectedKeys().hasOnly(['consent'])` with `consent.version > resource.data.consent.version` and `acceptedAt == request.time`; no `delete`, no `list`. Add a `deletedAccounts/{uid}` match with all access denied. Keep the final catch-all deny.
- [ ] **Step 4: Run the file's tests.** Expected: PASS. Then run `npm test` (full) to confirm Task 2 tests still pass.
- [ ] **Step 5: Commit** `firebase/firestore.rules` and the test file: `feat(firebase): users rules and deleted-account tombstone`.

### Task 4: Firestore rules — `profiles`

**Files:**
- Modify: `firebase/firestore.rules`
- Test: `firebase/rules-tests/firestore.profiles.test.ts`

**Interfaces:** Consumes the Task 3 helpers.

- [ ] **Step 1: Write the failing tests.** Valid create body: `{ displayName: 'Asha', about: '', photoUrl: null, updatedAt: serverTimestamp() }`.
  - `owner can create and update own profile`
  - `any phone-signed-in user can get another profile; list is denied for everyone`
  - `unauthenticated and other-provider contexts cannot get`
  - `another user cannot create or update profiles/B`
  - `rejects non-null photoUrl, including an https URL`
  - `rejects displayName of 0 chars, whitespace-only, and 65 chars; accepts 64 Devanagari chars` (use `'स'.repeat(64)` and `.repeat(65)`)
  - `rejects about longer than 140`
  - `rejects unknown fields and updatedAt not equal to request.time`
  - `update can change only displayName, about, updatedAt`
  - `client delete is denied`
  - `create is denied once deletedAccounts/{uid} exists`
- [ ] **Step 2: Run the file;** expected FAIL.
- [ ] **Step 3: Implement** the `profiles/{uid}` match: `get` for any `signedIn()`, no `list`; `create` owner + `notDeleted(uid)` + exact key set + validators; `update` owner + `affectedKeys().hasOnly(['displayName','about','updatedAt'])` + validators on the resulting data; non-blank check via `matches('.*\\S.*')`; no `delete`.
- [ ] **Step 4: Run the file, then the full suite.** Expected PASS.
- [ ] **Step 5: Commit:** `feat(firebase): profiles rules`.

### Task 5: Firestore rules — `devices`

**Files:**
- Modify: `firebase/firestore.rules`
- Test: `firebase/rules-tests/firestore.devices.test.ts`, `firebase/rules-tests/indexes.test.ts`

**Interfaces:** Consumes the Task 3 helpers.

- [ ] **Step 1: Write the failing tests.** `deviceId` fixture: `crypto.randomUUID()`. Valid create body: `{ platform: 'android', appVersion: '1.0.0', osVersion: '15', createdAt: serverTimestamp(), lastSeenAt: serverTimestamp() }` (optional `fcmToken: string | null`).
  - `owner can create, update, get, list and delete own devices`
  - `another user cannot read, list, create, update or delete users/A/devices/*`
  - `unauthenticated and other-provider contexts are denied`
  - `rejects deviceId that is not a lowercase UUID`
  - `rejects platform other than "android"`, `appVersion` or `osVersion` over 32 chars, unknown fields
  - `rejects client-set revokedAt on create and on update`, even when the server already set it on the doc (seed with rules disabled, then attempt an update that changes it)
  - `update may change only appVersion, osVersion, lastSeenAt, fcmToken, and lastSeenAt must equal request.time`
  - `create is denied once deletedAccounts/{uid} exists`
  - `indexes.test.ts`: `firestore.indexes.json deep-equals {"indexes":[],"fieldOverrides":[]}`, and `no collection other than users/{uid}/devices allows list` (assert `getDocs` on `users`, `profiles`, `deletedAccounts` fails for the owner).
- [ ] **Step 2: Run both files;** expected FAIL for the devices tests.
- [ ] **Step 3: Implement** the `users/{uid}/devices/{deviceId}` match inside the `users/{uid}` match: `read` (get and list) owner-only; `create` owner + `notDeleted(uid)` + `deviceId.matches('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')` + required keys `platform, appVersion, osVersion, createdAt, lastSeenAt` and optional `fcmToken` (string ≤ 4096 or null); `update` with the affected-keys allow-list; `delete` owner.
- [ ] **Step 4: Run the full suite.** Expected PASS.
- [ ] **Step 5: Commit:** `feat(firebase): devices rules and index assertions`.

### Task 6: Functions scaffold, guards, config, logger

**Files:**
- Create: `firebase/functions/{package.json,tsconfig.json}`, `src/{index.ts,config.ts,deps.ts}`, `src/lib/{guards.ts,logger.ts}`
- Test: `firebase/functions/test/guards.test.ts`, `config.test.ts`, `logger.test.ts`

**Interfaces:**
- Produces `config.ts`: `REGION: string = 'asia-south1'`; `MAX_AUTH_AGE_SECONDS = 300`; `REVOKE_COOLDOWN_SECONDS = 60`; `buildCallableOptions(env: NodeJS.ProcessEnv): { region: string; enforceAppCheck: boolean; consumeAppCheckToken: boolean }`.
- Produces `lib/guards.ts`: `requirePhoneAuth(request: { auth?: { uid: string; token: Record<string, any> } }): { uid: string; authTimeSeconds: number }` (throws `HttpsError('unauthenticated')` with no auth; `HttpsError('permission-denied')` when `token.firebase.sign_in_provider !== 'phone'`); `requireRecentAuth(authTimeSeconds: number, nowSeconds: number, maxAgeSeconds = MAX_AUTH_AGE_SECONDS): void` (throws `HttpsError('failed-precondition', 'recent-login-required')`).
- Produces `lib/logger.ts`: `newCorrelationId(): string`; `logEvent(event: string, fields?: Record<string, string | number | boolean>): void` which logs through `firebase-functions/logger` as `{ event, ...allowed }` and drops every field not in `['correlationId','count','outcome']`.
- Produces `deps.ts`: `interface Deps { db: Firestore; auth: Auth; nowMs: () => number }` and `defaultDeps(): Deps` (initializes the Admin app once, no credentials passed).
- `package.json`: `engines.node` `"24"`, `main` `lib/index.js`, `build` = `tsc`, pinned `firebase-admin`, `firebase-functions`, `typescript`, `@types/node`.

- [ ] **Step 1: Write the failing tests.**
  - guards: `rejects missing auth with unauthenticated`; `rejects sign_in_provider "anonymous" and "custom" with permission-denied`; `returns uid and auth_time for a phone token`; `requireRecentAuth passes at exactly 300 s and throws failed-precondition at 301 s`.
  - config: `buildCallableOptions({}) has enforceAppCheck true, consumeAppCheckToken true, region asia-south1`; `buildCallableOptions({ FUNCTIONS_EMULATOR: 'true' }) has both false`.
  - logger: `logEvent drops a phone field and a token field and keeps correlationId` (spy on the logger module).
- [ ] **Step 2: Run** `npm run test:file -- functions/test/guards.test.ts`, then the same for `config.test.ts` and `logger.test.ts`; expected FAIL (modules missing).
- [ ] **Step 3: Implement** the modules per the Interfaces block. `src/index.ts` is empty of exports for now (callables are added in Tasks 7–8). Run `npm install` in `firebase/functions` and commit its lockfile.
- [ ] **Step 4: Run** `npm test`; expected PASS (previous tests included), and `npm --prefix functions run build` succeeds.
- [ ] **Step 5: Commit:** `feat(functions): scaffold, auth guards, callable config, safe logger`.

### Task 7: `deleteAccount` callable

**Files:**
- Create: `firebase/functions/src/deleteAccount.ts`, `firebase/test-support/auth.ts`
- Modify: `firebase/functions/src/index.ts` (export `deleteAccount`)
- Test: `firebase/functions/test/auth-helper.test.ts`, `functions/test/deleteAccount.core.test.ts`, `functions/test/deleteAccount.handler.test.ts`, `functions/test/deleteAccount.http.test.ts`

**Interfaces:**
- Consumes: `Deps`, `defaultDeps`, `requirePhoneAuth`, `requireRecentAuth`, `buildCallableOptions`, `logEvent`, `newCorrelationId` (Task 6).
- Produces `test-support/auth.ts` (Auth emulator REST: `sendVerificationCode`, `GET /emulator/v1/projects/{id}/verificationCodes`, `signInWithPhoneNumber`): `signInWithPhone(phoneNumber: string): Promise<{ uid: string; idToken: string; refreshToken: string }>`; `decodeClaims(idToken: string): Record<string, any>`; `listAuthUserIds(): Promise<string[]>`; `resetEmulators(): Promise<void>` (clears Auth accounts and Firestore); `callCallable(name: string, idToken: string | null, data?: unknown): Promise<{ status: number; body: any }>` posting `{ data }` to `http://127.0.0.1:5001/demo-samvad/asia-south1/<name>`.
- Produces `deleteAccount.ts`: `deleteAccountCore(deps: Deps, uid: string): Promise<void>`; `handleDeleteAccount(request: CallableRequest<unknown>, deps?: Deps): Promise<{ deleted: true }>`; `deleteAccount` (the `onCall` export using `buildCallableOptions(process.env)`).
- Core order: (1) set `deletedAccounts/{uid}` = `{ deletedAt: serverTimestamp }`; (2) `db.recursiveDelete(users/{uid})`; (3) delete `profiles/{uid}`; (4) `auth.deleteUser(uid)`, treating code `auth/user-not-found` as success. Any other failure bubbles up (the handler maps it to `internal` and logs `account_delete_failed` with a correlation ID).

- [ ] **Step 1: Write the failing tests.**
  - `auth-helper`: `signInWithPhone returns a token whose claims have phone_number and firebase.sign_in_provider "phone"` and `a uid that listAuthUserIds contains`.
  - core (real Admin SDK against the emulators): `removes user doc, devices subcollection, profile, and the auth user, and writes the tombstone`; `leaves another user's documents and auth user untouched`; `is idempotent when run twice`; `completes on retry after auth.deleteUser failed once` (wrap `deps.auth.deleteUser` to throw once: first call rejects with data already deleted, second resolves and the user is gone); `succeeds when the auth user is already gone`.
  - handler (call `handleDeleteAccount` with fabricated requests): `rejects no auth (unauthenticated)`; `rejects provider "anonymous" (permission-denied)`; `rejects auth_time 301 s old (failed-precondition)`; `accepts auth_time 299 s old and returns {deleted:true}`; `ignores a uid supplied in request.data`.
  - http: `deleteAccount without an Authorization header returns an unauthenticated error`; `with a fresh phone token deletes the caller and returns deleted:true`; `a second identical call with the same token also succeeds` (Review Focus 3); `after deletion, the same token cannot create users/{uid} via the rules` (use `createRulesEnv`-style context with the same uid; Review Focus 1).
- [ ] **Step 2: Run** each new file; expected FAIL.
- [ ] **Step 3: Implement** `test-support/auth.ts`, then `deleteAccount.ts` per the Interfaces block; export from `index.ts`; run the functions build.
- [ ] **Step 4: Run** `npm test`; expected PASS. If the emulator's Auth REST endpoint names differ from the Interfaces block, adjust only `test-support/auth.ts` and note it in the commit message.
- [ ] **Step 5: Commit:** `feat(functions): deleteAccount callable with tombstone and idempotent retry`.

### Task 8: `signOutAllDevices` callable

**Files:**
- Create: `firebase/functions/src/signOutAllDevices.ts`
- Modify: `firebase/functions/src/index.ts`
- Test: `functions/test/signOutAllDevices.core.test.ts`, `signOutAllDevices.handler.test.ts`, `signOutAllDevices.http.test.ts`

**Interfaces:**
- Consumes: Task 6 modules and `test-support/auth.ts` (Task 7).
- Produces: `signOutAllDevicesCore(deps: Deps, uid: string): Promise<void>`; `handleSignOutAllDevices(request: CallableRequest<unknown>, deps?: Deps): Promise<{ revoked: true }>`; `signOutAllDevices` (`onCall` export).
- Core order: (1) Firestore transaction on `users/{uid}`: if the doc exists and `tokensRevokedAt` is within `REVOKE_COOLDOWN_SECONDS` of `deps.nowMs()`, throw `HttpsError('failed-precondition', 'cooldown')`; otherwise, if the doc exists, set `tokensRevokedAt` to the server timestamp (never create the doc); (2) `auth.revokeRefreshTokens(uid)`; (3) set `revokedAt` on every `users/{uid}/devices/*` doc in batches of at most 400 writes.

- [ ] **Step 1: Write the failing tests.**
  - core: `stamps tokensRevokedAt, revokes refresh tokens, and sets revokedAt on all devices` (check the Auth user's `tokensValidAfterTime` changed); `a second call within 60 s throws failed-precondition and changes nothing`; `a call 61 s later succeeds` (inject `nowMs`); `does not create users/{uid} when it does not exist, but still revokes tokens` (Review Focus 2); `handles 1000 device documents` (Review Focus 4); `does not touch another user's devices`.
  - handler: same guard cases as Task 7 (no auth, anonymous provider) and `ignores a uid supplied in request.data`.
  - http: `unauthenticated call is rejected`; `phone-signed-in call returns revoked:true`.
- [ ] **Step 2: Run;** expected FAIL.
- [ ] **Step 3: Implement** per the Interfaces block; export from `index.ts`; build.
- [ ] **Step 4: Run** `npm test`; expected PASS.
- [ ] **Step 5: Commit:** `feat(functions): signOutAllDevices callable with cooldown`.

### Task 9: End-to-end flow and log-hygiene check

**Files:**
- Create: `firebase/functions/test/e2e.flow.test.ts`, `firebase/scripts/check-logs.mjs`
- Modify: `firebase/package.json` (add script `test:e2e:logs`)

**Interfaces:**
- Consumes: `signInWithPhone`, `callCallable`, `resetEmulators`, `createRulesEnv`, `phoneContext`.
- Produces: `.tmp/e2e-secrets.json` written by the flow test with `{ phone, idTokens: string[] }` (gitignored); `scripts/check-logs.mjs <outputFile>` exits non-zero when the output contains any value from that file, or lacks a real function log line for `account_deleted`.
- `test:e2e:logs` script: run `firebase emulators:exec` for only `e2e.flow.test.ts`, redirect all output to `.tmp/emulator-output.log`, then run `node scripts/check-logs.mjs .tmp/emulator-output.log`.

- [ ] **Step 1: Write the failing flow test** `e2e flow: sign in with a test number, create profile and device through rules, sign out everywhere, then delete the account`: sign in via `signInWithPhone('+919999900042')`; create `users/{uid}`, `profiles/{uid}`, and a device doc through rules contexts built with `phoneContext(env, uid, phone)` for the uid that `signInWithPhone` returned; call `signOutAllDevices` (expect `revoked:true`); sign in again (fresh token); call `deleteAccount` (expect `deleted:true`); assert `users/{uid}`, `profiles/{uid}` and the devices are gone, the tombstone exists, the Auth user is gone, and a re-create attempt is denied. Test titles must not contain the phone number or tokens.
- [ ] **Step 2: Run it;** expected FAIL until the script and secrets file exist, and the log check fails for lack of captured output.
- [ ] **Step 3: Implement** `check-logs.mjs` and the npm script. Pattern for the positive control: a structured function log line containing `"event":"account_deleted"`. Run once and inspect `.tmp/emulator-output.log` to see how the emulator prints a function log line; adjust the pattern to match a real function log line (not vitest's own output, which must not be able to satisfy the control).
- [ ] **Step 4: Run** `npm run test:e2e:logs`; expected: exit 0, no phone number or token present, positive control found. Then run `npm test` (full) and expect all tests to pass.
- [ ] **Step 5: Commit:** `test(firebase): end-to-end auth flow and log-hygiene check`.

### Task 10: Documentation and final verification

**Files:**
- Create: `firebase/README.md`

- [ ] **Step 1: Write `firebase/README.md`** covering: prerequisites (JDK 21, Node 24 LTS); `npm install`, `npm test`, `npm run emulators`, `npm run test:e2e:logs`; the data model and rules summary (link to the spec); the owner checklist from spec §11 (projects, billing, Phone provider, SMS region allow-list, Firestore location matching `asia-south1`, API-key restriction, budget alerts); deployment per alias (`.firebaserc` from the example, `firebase deploy --only firestore:rules,functions --project <alias>`); the App Check rollout (monitor, then enforce; Debug and Play Integrity providers); what the emulator cannot prove (list from spec §10); the client contract pointer (spec §8); a warning that no secrets or service-account files may be committed.
- [ ] **Step 2: Verify the whole deliverable.**
  - `cd firebase && npm test` passes; `npm run test:e2e:logs` passes.
  - `git status --short | wc -l` prints `73` (nothing new is left uncommitted and nothing else changed).
  - `git log --stat --format=%h -- firebase docs/superpowers/plans` shows only paths under `firebase/` and `docs/`.
  - Search the new files for secrets: `git grep -n -I -E "AIza|-----BEGIN|private_key|mongodb(\+srv)?://|service_role" -- firebase` returns nothing.
  - `git ls-files firebase | grep -E "\.env|google-services|adminsdk|serviceAccount"` returns nothing.
- [ ] **Step 3: Commit:** `docs(firebase): setup, deployment and App Check guide`.
- [ ] **Step 4: Report to the owner** exactly what ran and passed, and list as "setup still required": real SMS delivery, Play Integrity, App Check enforcement, SMS region policy, deployed rules and functions, billing alerts.
