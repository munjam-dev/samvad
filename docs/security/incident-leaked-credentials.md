# Incident runbook: credentials committed to git

Status: open. Created 2026-10-09. Contains no secret values and must stay that way.

## What was found

Read-only inspection of the repository (names and patterns only; values were not copied anywhere):

| Item | Evidence | Severity |
|---|---|---|
| `backend/.env` is tracked and contains a MongoDB connection setting and a JWT signing secret | tracked file; added in commit `b4d56c4` (2026-03-27) | High: database access and the ability to forge login tokens |
| `android/app/google-services.json` and `android/app/src/google-services (1).json` are tracked | both added in `b4d56c4` | Medium: contains the Firebase project/app IDs and an API key. Firebase API keys are identifiers, not secrets by themselves, but an unrestricted key can be abused |
| `lib/samvad_app.dart` matches a Supabase URL/key pattern | one match | Check manually: a Supabase `anon` key is designed to be public, a `service_role` key is not |
| `PRODUCTION_SETUP.md`, `backend/server.js`, `backend/.env` match credential-like patterns | pattern scan | Check manually: may be placeholders or defaults |

The git remote is `https://github.com/munjam-dev/zingify-mobile.git`. Whether it is public was not determined (the GitHub CLI is not logged in). **Assume the values are compromised** if the repo is or ever was public, was forked, or was cloned by anyone outside your control. Merged pull request #1 means GitHub also holds extra `refs/pull/*` refs.

The staged deletion of `google-services.json` in the working tree does not remove it from history.

## Order of operations

Rotate first, clean history second. Removing a file from history does nothing for copies that already exist (clones, forks, caches), so rotation is what actually closes the exposure.

### 1. Contain (today)

1. **MongoDB:** in the Atlas (or other host) console, create a new database user with a new strong password, update any running deployment (Heroku/Render/etc.) to use it, then delete the old user. Review the cluster's access logs and IP access list for connections you do not recognize. If the cluster is no longer needed, delete it.
2. **JWT secret:** generate a new random secret (for example `openssl rand -base64 48`), set it only in the host's secret/environment settings, redeploy. All tokens signed with the old secret become invalid, which is intended. If the Node backend is not running anywhere, record that and skip.
3. **Firebase config / API key:** in Google Cloud Console → APIs & Services → Credentials, restrict the old project's API key (Android apps: package name + SHA-1; API restrictions to only the APIs used), or delete and recreate it. Check usage and billing for anomalies. If the old Firebase project is abandoned, delete it. The native app uses a new project, so none of this config is reused.
4. **Supabase:** if any `service_role` key or database password appears anywhere in the repo, rotate it in the Supabase dashboard (Project Settings → API / Database). If only the public URL and `anon` key appear, rotating is optional but recommended once the Flutter app is retired.
5. **Check the pattern hits** in `PRODUCTION_SETUP.md`, `backend/server.js` and `lib/samvad_app.dart` by eye. Treat anything that is a real credential as above.
6. Search for other leaks: run a secret scanner (for example `gitleaks detect --source . --log-opts="--all"` or `trufflehog git file://.`) over the full history, and rotate whatever it finds.

### 2. Stop new leaks

1. Stop tracking the files without deleting your local copy: `git rm --cached backend/.env` and, if still present in the index, the two `google-services.json` paths. This changes the index only, so do it as its own deliberate commit and review `git status` first, because the working tree currently has 73 other pending changes.
2. Add ignore rules to the root `.gitignore`: `.env`, `.env.*`, `**/google-services.json`, `**/GoogleService-Info.plist`, `*.jks`, `*.keystore`, `keystore.properties`, `serviceAccount*.json`, `*-adminsdk-*.json`, `local.properties`.
3. Add a pre-commit secret scan (gitleaks) and enable GitHub secret scanning and push protection on the repository.
4. Keep `.env.example` files with placeholder values only.

### 3. Clean history (only after rotation)

Do this in a **fresh clone**, so your working directory and its 73 uncommitted changes are never touched.

1. Make a backup mirror: `git clone --mirror <remote> samvad-backup.git` and keep it offline.
2. Make a second fresh mirror clone to rewrite.
3. Install `git-filter-repo` and run it in that clone, listing each path to purge:
   `git filter-repo --invert-paths --path backend/.env --path android/app/google-services.json --path "android/app/src/google-services (1).json"`
   If the manual check in step 1.5 found credentials inside other files, replace those strings with `git filter-repo --replace-text <file>` (the replacement file holds the strings and must itself never be committed).
4. Verify: rerun the secret scanner across all refs and confirm the paths are gone from `git log --all -- <path>`.
5. Coordinate with every collaborator, then force-push all branches and tags (`git push --force --all` and `git push --force --tags`, using `--force-with-lease` where practical). Everyone must re-clone; old clones still contain the secrets and must not be pushed back.
6. GitHub keeps cached views, forks and `refs/pull/*` refs: contact GitHub Support to purge cached data and pull-request refs for the repository, and ask owners of forks to delete them. Because these copies cannot be fully removed, rotation in step 1 remains the real fix.
7. The repository belongs to `munjam-dev`; the account that runs the force-push needs admin rights. Branch protection may have to be lifted briefly and restored afterwards.

### 4. Record

Keep this file (without values) updated with dates for each step, who performed it, and what was found in access logs.

## Not done

No rotation, history rewrite, force-push or index change has been performed by Claude. All of the above is for the repository owner to carry out.
