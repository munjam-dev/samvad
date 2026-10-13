# Phase 1.5 — Two-step verification (PIN)

Status: implemented on `claude/clever-pasteur-6shc8p`. Free: Cloud Functions and Firestore only.

## Problem

Samvad signs in with an SMS code. Anyone who receives that code (SIM swap, a stolen phone, an
intercepted SMS, a code read off a lock screen) becomes the account owner. Two-step verification adds
a 6-digit PIN that only the owner knows. A new sign-in cannot read or write anything until the PIN is
entered, and the server, not the app, enforces that.

## Session model

Entering the PIN creates a new **PIN session**. The server picks a random nonce, stores it in the
account claim `tsv`, and returns a Firebase custom token whose per-session claim `pv` is the same nonce.
The app signs in with that token.

| Claim | Where | Meaning |
|---|---|---|
| `ts: true` | account (custom user claims, on every session) | two-step verification is on |
| `tsv: <nonce>` | account | the nonce of the one currently verified session |
| `pv: <nonce>` | session (custom-token claim) | this session came from a PIN check |

A session is **verified** when it is a phone sign-in and `ts` is off, or a custom-token sign-in with a
`pv` that equals `tsv` (or with `ts` off). Every Firestore, Realtime Database and Storage rule starts from
`signedIn()`, which now checks this. Every callable requires it too, except the two-step callables that
an unverified session needs (verify, status, reset).

Why not `auth_time`: it names a sign-in time, but the Auth emulator returns the newest sign-in's
`auth_time` when an older session refreshes, and production does not document cross-session behaviour.
A per-session claim that only our server can mint does not depend on that.

Effects: an SMS sign-in by anyone (SIM swap, intercepted code) is a phone session, so it is never
verified while two-step is on. Verifying on a new phone replaces `tsv`, so the older PIN session stops
working at its next token refresh (at most an hour). Custom tokens without `pv` are rejected everywhere.

## Server state

`twoStep/{uid}` (server-only):
`hash`, `salt` (scrypt, N=2^15, r=8, p=1, 32-byte key, 16-byte random salt), `failedAttempts`,
`lockedUntil`, `resetRequestedAt`, `updatedAt`. The PIN itself is never stored or logged.

## Callables

| Callable | Session | Does |
|---|---|---|
| `setTwoStepPin {pin}` | verified (checked against the server record) | Validates, hashes and stores the PIN; starts a PIN session (returns its custom token) |
| `verifyTwoStepPin {pin}` | any | Checks the PIN with lockout; on success starts a PIN session (returns its custom token) and cancels a pending reset |
| `disableTwoStep {pin}` | verified | Checks the PIN, deletes `twoStep/{uid}`, clears the claims |
| `twoStepStatus` | any | `{enabled, verified, lockedUntil, resetAvailableAt}` |
| `requestTwoStepReset` | any | Starts the 7-day reset clock (once) and alerts the owner's registered devices |
| `completeTwoStepReset` | any | After 7 days, and only if the owner has not used Samvad since the request, turns two-step off |

PIN rules: exactly 6 digits; not all one digit; not a run like 123456 or 987654.

Lockout: 5 free attempts, then a lock of 1, 2, 4, … minutes after each further failure, capped at 24 hours.
Every attempt is counted before the PIN is checked, so parallel guesses cannot beat the lock. An attacker
gets about 16 guesses on the first day and one a day after that, against 1,000,000 possible PINs.

Reset: "the owner has not used Samvad since the request" means the owner's RTDB `presence` shows neither
`online` nor a `lastSeen` after the request. Presence writes need a verified session, so only the owner's
devices can produce that activity. The reset request also sends a security push to the owner's devices
(unverified sessions cannot register devices, so the requester never receives it).

## Account deletion with two-step on

`deleteAccount {pin}` accepts the PIN instead of a fresh SMS re-authentication. A re-authentication
would start a new, unverified session and lock the app mid-flow. Without two-step, the five-minute
recent-sign-in rule is unchanged. Deletion also removes `twoStep/{uid}`.

## Client

- After sign-in, the session reads the ID token. If the account has `ts` and this is not the verified
  PIN session, the app shows the PIN screen: PIN entry, wrong-PIN feedback with attempts left, lock countdown,
  and "Forgot PIN?" (request reset, show the date, then reset).
- The session also follows ID-token changes, so a phone whose session was replaced shows the PIN screen
  instead of failing.
- Settings → Account → Two-step verification: turn on (enter and confirm), change PIN, turn off (PIN).
- Security pushes appear as a high-priority "Security alert" notification.

## Tests

Rules (Firestore, RTDB, Storage): verified, unverified and two-step-off tokens. Functions: PIN
validation, hashing, lockout schedule, claims, reset timing, reset cancelled by owner activity,
security push, deleteAccount with PIN, every existing callable refusing unverified sessions, an HTTP
end-to-end flow. `:core`: PIN validation and the verified-session check.

## Owner setup

Minting custom tokens in Cloud Functions needs the functions' runtime service account to hold the
**Service Account Token Creator** role on itself (IAM, free). Without it, turning two-step on and
verifying fail with an internal error; nothing else is affected.
