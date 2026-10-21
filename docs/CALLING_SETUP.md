# Calling setup (Phase 7): voice and video calls, group meetings

Samvad uses **LiveKit Cloud** (managed; the client SDKs and server are open source, Apache-2.0) to carry the audio and video.
Our own servers never touch media: Firebase Functions only rings the other person and hands out short-lived
(10 minute) room tokens. Until you finish the steps below, the app builds and runs normally and shows
"Calling isn't set up yet" when someone taps call. Messages are unaffected.

Calls are encrypted in transit to the LiveKit server. They are **not end-to-end encrypted**; the app says so on the
permission screen and at the bottom of the Calls tab. Do not describe calls as end-to-end encrypted anywhere (store
listing, website, privacy policy) until that is actually built.

## 1. Create the LiveKit Cloud project

1. Sign up at https://cloud.livekit.io and create a project (name: `samvad`). Pick the region closest to your users
   (India: choose an Asia / Mumbai or Singapore region if offered).
2. Open **Settings > Keys** and create an API key. Note three values:
   - the project URL, like `wss://samvad-xxxxxxxx.livekit.cloud`
   - the API key (starts with `API`)
   - the API secret (shown once)
3. Do not commit these anywhere. They only go into Firebase secrets (next step).

## 2. Set the three Functions secrets

From the repository root, logged in with `firebase login` and the right project selected:

```
firebase functions:secrets:set LIVEKIT_URL
firebase functions:secrets:set LIVEKIT_API_KEY
firebase functions:secrets:set LIVEKIT_API_SECRET
```

Each command prompts for the value. Notes:

- Firebase will not deploy a function that is bound to a secret that does not exist. If you want to deploy the
  functions before the LiveKit project exists, set each secret to the word `not-configured`. The functions then answer
  `calls-not-configured` and the app shows the friendly "not set up yet" screen. Replace them later with the real values
  (run the same command again, then redeploy so the functions pick up the new version).
- The URL must start with `wss://` (or `https://`; it is converted).
- Rotating a secret: set it again, then redeploy the call functions.

## 3. Deploy

From `firebase/`:

```
npm --prefix functions install
firebase deploy --only firestore:rules,firestore:indexes
firebase deploy --only functions:startCall,functions:answerCall,functions:declineCall,functions:endCall,functions:getCallToken,functions:cleanupMissedCalls,functions:scheduleMeeting,functions:getMeetingToken,functions:endMeeting,functions:onMessageCreated,functions:deleteAccount
```

Index build takes a few minutes. The new Firestore indexes (on `calls`) must be READY before the Calls tab and the
busy-check work. Check in the Firebase console under Firestore > Indexes.

Optional but recommended: add a Firestore TTL policy later if you want old call records to expire; calls are kept
as history until an account is deleted (deleting an account ends its live calls and removes it from meetings).

## 4. Test it (two real phones, same build)

1. Sign in on two phones as two different people who already have a chat.
2. Open the chat on phone A and tap the phone or video icon at the top. Phone B should ring (full screen if locked,
   otherwise a notification with Accept and Decline). Accept: both see the timer; try mute, speaker, camera, flip.
3. Missed call: let it ring 45 seconds. Phone B gets a "Missed call" notification and the Calls tab shows it in red.
4. Busy: while A and B are talking, call B from a third phone. The caller sees "They are on another call".
5. Meetings: in a group where you are an admin, open the menu > Schedule a meeting > Start now. A meeting card appears
   in the chat; members tap Join (the window is 30 minutes before the start to 4 hours after; up to 50 people; the
   host can end it for everyone from the hang-up button).
6. With the secrets set to `not-configured`, tapping call shows "Calling isn't set up yet".

Backend checks run on the emulators (no LiveKit account needed; the token minter is faked in tests):
`cd firebase && npm run test:file -- functions/test/calls.test.ts` (and `meetings.test.ts`, `rules-tests/calls.test.ts`,
`rules-tests/meetings.test.ts`).

## 5. Costs to watch

LiveKit Cloud bills mainly on **participant-minutes** (every person connected to a room, per minute) plus bandwidth
(downstream data) for video. The free "Build" plan includes a monthly allowance of participant-minutes and
bandwidth (check the current numbers on https://livekit.io/pricing, they change). Rough guide:

- A 1:1 call of 10 minutes is 20 participant-minutes. A 20-person meeting of 1 hour is 1,200.
- Audio-only is cheap. Video bandwidth is what grows fastest in group meetings.
- Built-in brakes: 40 calls per person per day, meetings capped at 50 people, tokens valid 10 minutes, rings
  stop after 45 seconds, meetings joinable only inside the window, unanswered calls never open a room for the callee.
- Set a usage alert in the LiveKit Cloud dashboard (Billing) and check it weekly during the first month.
- Firebase side: a few callable invocations and Firestore reads per call, plus one scheduled function that runs every
  minute (`cleanupMissedCalls`), all very small.

## 6. App Check and Play policy notes

- All call and meeting callables use the same App Check enforcement as the rest of the backend (`buildCallableOptions`,
  replay protection on). The Android app calls them with limited-use App Check tokens. Nothing else is needed.
- LiveKit itself is authenticated only by the token the server mints; there is no App Check for that connection.
- **Foreground service types.** The manifest declares `CallService` with `microphone|camera` and the permissions
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CAMERA`. In the Play Console
  (App content > Foreground service permissions) declare: *Microphone and camera: user-initiated voice and video calls;
  the service runs only while a call or meeting is connected and shows an ongoing notification with a Hang up button.*
  Record a short video: start a call, show the ongoing notification, hang up.
- **Full-screen intent (`USE_FULL_SCREEN_INTENT`).** Used only to show the incoming-call screen over the lock screen,
  the standard calling use. In the Play Console declaration, choose *Calling or alarm*; justification: *Samvad is a
  messenger with voice and video calling; the permission shows the incoming-call screen when the phone is locked.*
  On Android 14+ the user can revoke it; then the call still arrives as a heads-up notification with Accept and Decline.
- `CAMERA` and `RECORD_AUDIO` are asked only when a call starts or is answered, after a screen that says why.
  `BLUETOOTH_CONNECT` lets calls use a Bluetooth headset. `MODIFY_AUDIO_SETTINGS` routes sound to speaker or earpiece.
- Do Not Disturb: the ringtone and vibration belong to the "Incoming calls" notification channel, so the system's
  Do Not Disturb and silent settings decide whether the phone rings.
- Data safety form: calls send audio and video to LiveKit Cloud (a processor) in transit; nothing is recorded or stored by
  Samvad. Update the privacy policy accordingly and name LiveKit as the call-media processor.

## 7. Known limits

- If a person has two devices, both ring; answering on one leaves the other ringing until the 45 second timeout (it then
  shows a missed call there).
- The app ships the LiveKit Android SDK, which bundles WebRTC: about 22 MB of native libraries across the four
  ABIs (roughly 5 to 6 MB for a single-ABI install from Play, more in a universal APK).
- Media cannot be tested without a real LiveKit project and two devices; the repository tests cover signalling, rules,
  tokens (faked) and the pure call logic.
