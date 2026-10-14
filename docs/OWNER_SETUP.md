# Owner setup: what only you can do

These steps need your Google account, a payment method, or legal and business decisions, so the code
can't do them. Do them in order. After each step, reply in chat with the **"Tell Claude"** line shown.
**Never paste passwords, keys, keystores or card details into the chat.** I don't need them.

Project: **Samvad-Messenger** (`samvad-messenger`) ·
Console: https://console.firebase.google.com/project/samvad-messenger

Already done: Android apps registered (release and debug), debug fingerprints added, Firestore created
in Mumbai with rules and indexes deployed.

---

## Step 1: Create the Realtime Database (2 minutes, free)

Used for online status, last seen and typing.

1. Open https://console.firebase.google.com/project/samvad-messenger/database
2. Click **Create Database**.
3. **Location:** choose **Singapore (asia-southeast1)**. It is the closest region to India. This
   can't be changed later.
4. **Security rules:** choose **Start in locked mode**. I'll deploy the real rules.
5. Click **Enable**.

**Tell Claude:** `RTDB done`

---

## Step 2: Upgrade to the Blaze plan (5 minutes, needs a card)

Cloud Functions (sign-out everywhere, groups, two-step PIN, reports, push notifications) and Storage
(photos, voice notes, files) only run on Blaze. Blaze has a free monthly allowance; a small test
app usually costs ₹0 to a few hundred rupees a month.

1. In the console, bottom-left, click **Spark plan → Upgrade**.
2. Choose **Blaze (pay as you go)**.
3. Create or select a **Cloud Billing account** and add a card. Indian cards need international or online
   transactions turned on in your bank app, and an e-mandate may be requested.
4. When asked for a **budget**, set **₹1,000 per month** (or what you're comfortable with). It sends e-mails
   at 50%, 90% and 100%. It's an alert, not a hard cap.
5. Click **Purchase / Continue**.

**Tell Claude:** `Blaze done`

---

## Step 3: Turn on Storage (2 minutes, after Step 2)

1. Open https://console.firebase.google.com/project/samvad-messenger/storage
2. Click **Get started**.
3. Choose **Start in production mode** (locked). I'll deploy the real rules.
4. **Location:** **asia-south1 (Mumbai)** if offered.
5. Click **Done**.

**Tell Claude:** `Storage done`

---

## Step 4: Turn on phone sign-in (3 minutes)

1. Open https://console.firebase.google.com/project/samvad-messenger/authentication
2. Click **Get started** if shown.
3. **Sign-in method** tab → **Add new provider** → **Phone** → switch **Enable** on → **Save**.
4. Still in the Phone provider, open **Phone numbers for testing** and add two test numbers. They sign in
   without sending SMS, so testing is free:
   - `+91 99999 00001` with code `123456`
   - `+91 99999 00002` with code `654321`
5. **Settings** tab → **SMS region policy** → **Allow** → select only **India**. This blocks SMS fraud from
   other countries.
6. **Settings** tab → **User actions** → leave defaults.

**Tell Claude:** `Phone auth done`

---

## Step 5: Give Functions permission for the two-step PIN (3 minutes, after Step 2)

The two-step PIN signs special sign-in tokens, so the Functions service account needs one extra role.

1. Open https://console.cloud.google.com/iam-admin/iam?project=samvad-messenger
2. Click **Grant access**.
3. **New principals:** `746365614392-compute@developer.gserviceaccount.com`
4. **Role:** search **Service Account Token Creator** and select it.
5. Click **Save**.

**Tell Claude:** `IAM done`

### Step 5b: Let Cloud Build build the functions (2 minutes)

New projects don't give the build account permission to build functions, so every function fails with
"missing permission on the build service account".

1. Open https://console.cloud.google.com/iam-admin/iam?project=samvad-messenger
2. Find the row `746365614392-compute@developer.gserviceaccount.com` (the same account as Step 5) → click the
   **pencil** (Edit principal).
3. **Add another role** → search **Cloud Build Service Account** (`roles/cloudbuild.builds.builder`) → select.
4. **Save**. Wait about 1 minute for it to take effect.

**Tell Claude:** `Build role done`

### Step 5c: Raise the Cloud Run CPU quota (5 minutes, then Google approves)

New projects start with a very small CPU allowance per region, so some functions fail to deploy with
"Quota exceeded for total allowable CPU per project per region".

1. Open https://console.cloud.google.com/iam-admin/quotas?project=samvad-messenger
2. In the **Filter** box type `Total CPU allocation` and pick the one for **Cloud Run Admin API**.
3. Tick the row whose dimension is **region: asia-south1** → **Edit quotas** (top right).
4. New value: **20** (vCPU). Reason: `Production messaging app backend (Cloud Functions 2nd gen), India launch.`
5. **Submit**. Approval usually takes from a few minutes to a day; Google e-mails you.

**Tell Claude:** `Quota requested` (and later `Quota approved`)

---

## Step 6: App Check (5 minutes, after I deploy and you run a debug build once)

App Check proves requests come from the real Samvad app. The functions require it.

1. Open https://console.firebase.google.com/project/samvad-messenger/appcheck
2. **Apps** tab → **Samvad** (com.samvad.messenger) → **Play Integrity** → **Save**. (Release builds; full
   protection after the app is on Google Play.)
3. For the **debug** app: when I tell you, run the debug build once. In Android Studio's **Logcat**, search
   `DebugAppCheckProvider` and copy the line's secret, which looks like a UUID.
4. **Apps** tab → **Samvad (debug)** → ⋮ → **Manage debug tokens** → **Add debug token** → paste → **Save**.
   This secret is for the console only; don't paste it in chat.
5. Leave enforcement **off** for Firestore, Realtime Database and Storage until I say the app is verified.

**Tell Claude:** `App Check done`

---

## Step 7: Cost alerts (2 minutes, after Step 2)

1. Open https://console.cloud.google.com/billing → your billing account → **Budgets & alerts**.
2. Check the budget from Step 2 exists, with alerts at 50%, 90% and 100%.
3. Optional: add your phone's e-mail as a recipient.

**Tell Claude:** `Budget done`

---

## Step 8: Rotate the old leaked passwords (15 to 30 minutes, important)

The old Flutter/Node project put real secrets in git history. Rotating makes the old values useless.
Full details are in `docs/security/incident-leaked-credentials.md`.

1. **MongoDB Atlas** (if you still have that cluster): Database Access → create a new user with a new
   password → update anything still using it → **delete the old user**. If nothing uses it any more,
   delete the old cluster.
2. **Old JWT secret:** if the old Node server still runs anywhere (Render, Heroku and so on), set a new random
   secret there or shut that server down.
3. **Old Firebase project** (the one the Flutter app used, if different from `samvad-messenger`):
   Google Cloud Console → APIs & Services → Credentials → restrict or delete its old API key.
4. **Supabase** (if the project still exists): Project Settings → API → rotate keys, or pause or delete the
   project.
5. On GitHub, make the repository **private** if it isn't, and turn on **Secret scanning** and **Push
   protection** (Settings → Code security).

**Tell Claude:** `Secrets rotated`

---

## Step 9: Legal pages (when you are ready to publish)

Google Play and the sign-in screen need two public web pages:

1. **Privacy Policy:** must mention phone numbers used for sign-in and contact matching (numbers are not
   stored), messages stored on Google Cloud (not end-to-end encrypted yet), report evidence (last 5
   messages), and account deletion.
2. **Terms of Service.**

Host them anywhere public (Google Sites, a simple website, GitHub Pages), then send me the two URLs.
I can draft the text for you to review with a lawyer.

**Tell Claude:** `Privacy URL: <link>` and `Terms URL: <link>`

---

## Step 10: Release signing and Google Play (when you are ready to publish)

1. Create a **Google Play Console** developer account ($25 one-time): https://play.google.com/console
2. In Android Studio: **Build → Generate Signed App Bundle → Create new keystore**. Store the `.jks` file
   and passwords somewhere safe, such as a password manager and an offline backup. **Never** put them in git
   or in chat. Losing them means you can't update the app.
3. Send me only the keystore's **SHA-256 fingerprint**, which isn't secret. Android Studio shows it, or run
   `keytool -list -v -keystore your.jks`. I'll register it for phone sign-in and App Check.

**Tell Claude:** `Release SHA-256: AB:CD:…`

---

## Step 11: Translations (optional, for Indian languages)

For each language you want (Hindi first), find a native speaker to review the app's text. I'll prepare
the translation files and a review sheet; they mark corrections and you send them back.

**Tell Claude:** which languages, and when a reviewer is ready.

---

## What happens after you reply

| You say | I do |
|---|---|
| `RTDB done` | Deploy Realtime Database rules; download the updated app config |
| `Blaze done` + `Storage done` | Deploy Storage rules and all Cloud Functions |
| `Phone auth done` | Build the app against the real project; you sign in with a test number |
| `IAM done` | Test the two-step PIN end to end |
| `App Check done` | Verify, then tell you when to switch on enforcement |
| URLs / SHA-256 | Put them into the release build |
