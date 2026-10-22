# End-to-end encrypted calls and meetings: plan (queued)

Status: **queued behind the chat end-to-end encryption work** (`docs/security/E2EE_DESIGN.md`, in progress). Nothing here
is built. Calls today are encrypted in transit to LiveKit only, and the app says so.

## Start condition

Start only after the chat E2EE branch is merged and verified, because calls reuse its identity keys, its Signal sessions
and its "security code changed" handling. Do not start earlier.

## Goals

- 1:1 voice and 1:1 video calls: end-to-end encrypted between the two phones, at close to zero running cost.
- Group meetings: end-to-end encrypted with LiveKit frame encryption, audio first, video capped.
- The UI says "end-to-end encrypted" for a call only when the keys on both sides are verified; otherwise it says
  "encrypted in transit only".

## Build order

1. **Data-saving controls (no dependency on E2EE; can be done first).** Start every call as audio with a one-tap switch to
   video; default video 360p-480p, 720p only on Wi-Fi; a "use less data for calls" setting; automatic fallback to audio
   after a long weak-connection period with a prompt.
2. **1:1 calls over direct WebRTC.**
   - Android WebRTC peer connection (the library bundled with the LiveKit SDK or a standalone prebuilt WebRTC).
   - Signalling through the existing `calls/{id}` document and callables (offer, answer, ICE candidates as server-written
     or participant-only docs; rules so only the two participants can read them; same blocks, busy and rate-limit logic).
   - Free STUN. A TURN relay for the roughly 10-20% of calls that cannot connect directly: first a provider with a free
     monthly allowance (verify current terms), later the open-source `coturn` on a flat-rate server. Short-lived TURN
     credentials minted by a callable, never shipped in the app.
   - **Fingerprint binding:** each side signs its DTLS fingerprint with its chat identity key and sends it inside the
     encrypted chat channel (or verifies it against the Signal session), so a malicious signalling server cannot swap it.
     Show a verification code per call that matches the chat safety number.
   - Fall back to the current LiveKit path if direct connect and relay both fail, labelled "encrypted in transit only".
3. **Meetings with LiveKit frame encryption.** The host creates a random meeting key and sends it to each participant
   through their own E2EE session; rotate on leave or removal; late joiners get the current key from the host; members
   without an E2EE session join as "in transit only" and the room shows it plainly.
4. **Honest labelling and docs.** Update the privacy policy, Play data-safety sheet and in-app strings (all four languages);
   keep `E2EE_ENABLED` as the master switch.

## Limits to state in the product and docs

- The server still sees who called whom, when and for how long.
- LiveKit server-side features (recording, transcripts, server effects) do not work on encrypted meetings.
- A participant can still record the screen or share a key by hand.
- Encrypted video uses a little more battery and bandwidth.
- A very large video meeting cannot be free anywhere; keep the 50-person cap and cap the number who send video.

## Cost notes (verify prices before committing)

1:1 calls: near zero (relay only for calls that cannot connect directly; audio is a few MB per call). Meetings: LiveKit
Cloud free tier first; because LiveKit is open source, moving to a self-hosted LiveKit on a flat-rate server later only
changes the server URL.

## Testing

Needs two or more real devices. Cover: voice, video, audio-to-video switch, relay-only network, weak network, key rotation
when someone leaves, changed security code mid-call, fallback labelling. External security review before launch.
