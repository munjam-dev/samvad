// One-off migration for the Phase 3 privacy features. Run once after deploying the rules and functions:
//
//   npm run build:functions
//   GOOGLE_CLOUD_PROJECT=<project id> node scripts/migrate-privacy.mjs
//
// (uses Application Default Credentials: `gcloud auth application-default login` with an account that can
// write to Firestore and the Realtime Database). Against the emulators, set FIRESTORE_EMULATOR_HOST and
// FIREBASE_DATABASE_EMULATOR_HOST first. Safe to run more than once.
//
// 1. Moves profiles/{uid}.about and .photoUrl into profiles/{uid}/private/about and /photo and removes them
//    from the public document, so the visibility settings can apply to them.
// 2. Fills conversations/{cid}.engagedBy (and the RTDB copy engaged/{cid}) for direct chats that existed before,
//    so "People I chat with" works for old chats: the chat's creator, and anyone who has sent a message in it.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { defaultDeps } = require('../functions/lib/deps.js');
const { migrateAllProfiles } = require('../functions/lib/profilePrivacy.js');
const { backfillAllEngagement } = require('../functions/lib/engagement.js');

const deps = defaultDeps();
const profiles = await migrateAllProfiles(deps);
console.log(`profiles moved: ${profiles}`);
const chats = await backfillAllEngagement(deps);
console.log(`direct chats checked: ${chats}`);
