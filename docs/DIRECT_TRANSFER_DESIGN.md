# Direct phone-to-phone transfer: design

Status: **design only, nothing built.** Decision needed from the owner at the end.

## What it is, and what it is not

Send photos, videos and files straight from one phone to another over a local connection, with no server and no mobile
data, for example in a place with no signal or to move a large video without waiting for an upload.

It is **not** a delivery guarantee. Both phones must be present and awake at the same moment. If the other phone is off or
out of range, the transfer does not happen, and nothing is stored anywhere for later (there is no store-and-forward in
this design). The app must say so. Chat messages keep using the normal path.

## Options compared

| Option | Works offline | Server cost | Effort | Verdict |
|---|---|---|---|---|
| **Google Nearby Connections API** (Play Services; Bluetooth and Wi-Fi under the hood) | Yes | None | Low | **Recommended for v1** |
| Wi-Fi Direct directly | Yes | None | High (OEM quirks, manual group handling) | No: Nearby wraps it |
| Wi-Fi Aware | Yes | None | High, patchy device support | No |
| WebRTC data channel over the internet (Firebase signalling + TURN) | No (needs internet) | TURN bandwidth costs money | Medium | Possible v2 for "send to a contact far away" |
| Bluetooth only | Yes | None | Medium | Too slow for videos |

**Recommendation:** Nearby Connections with the `P2P_POINT_TO_POINT` strategy. It picks the fastest available link
(Bluetooth to start, then Wi-Fi hotspot or Wi-Fi Direct for the payload), needs no internet and has no servers. Limits: it
needs Google Play services (so not on phones without them), and it only links Android to Android.

## Flow

1. **Receiver** opens Attach (or the home menu) > *Receive nearby*, and the phone starts advertising under a random
   session name (never the phone number or profile).
2. **Sender** opens *Send nearby*, picks files, and sees nearby receivers. Taps one.
3. **Pairing check.** Both screens show the same 4-digit code from the Nearby authentication token (and a QR option for
   when many phones are around). The receiver taps *Accept* only if the codes match. This blocks a stranger who is
   also advertising.
4. **Consent per transfer.** The receiver sees file count, names, total size, and the sender's chosen label, and taps
   *Accept* or *Decline*. Nothing is written to disk before acceptance.
5. **Transfer** with progress and Cancel on both sides. Interrupted transfers restart from the beginning in v1 (resume is v2).
6. **Verify.** The sender sends a manifest (names, sizes, SHA-256 per file) first; the receiver verifies every file's hash
   after it arrives and discards mismatches.
7. **Land.** Files go to the app's private storage with an option to *Save to Downloads* (system picker, no broad storage
   permission) or *Add to Personal Vault*. They are never opened automatically.

If both people are in a Samvad chat with each other, an optional system line "Files sent nearby" can be added to the
chat on each side later, when online. This is local metadata only.

## Security

- Nearby encrypts the connection (UKEY2). We add our own integrity layer (the signed-off manifest and per-file hashes) so
  corruption or substitution is caught, and the 4-digit pairing code so the right phone is connected.
- Reuse the chat rules for types and risk: the allow-list in `MediaRules` (no executables or APKs), a warning for
  documents, a size cap per transfer (suggest 2 GB), and a count cap.
- The receiver's screen never shows the sender's phone number. A random display name chosen by the sender is shown.
- Block and rate-limit locally: ignore repeated requests from the same endpoint after two declines for ten minutes.
- No keys or contents leave the two phones. No data is sent to Samvad's servers or to Google except what Play services
  itself does for discovery.
- This is separate from end-to-end encrypted chat. Do not describe it as end-to-end encrypted chat.

## Permissions (ask only when a transfer starts, with a rationale screen)

- Android 13+: `NEARBY_WIFI_DEVICES`. Android 12: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`. Android 11
  and older: location (required by the OS for scanning; say so honestly).
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE` (already present).
- Play data safety: nothing collected or shared; add a line to the permissions justification table.

## Integration points in the code

- New package `ui/nearby/` (Send, Receive, Progress screens) and `data/nearby/NearbyTransfer.kt` wrapping the Nearby client;
  the pure parts (manifest format, hashing, size and type checks, pairing-code display, state machine) in `:core` with
  JUnit tests, so the risky logic is tested without devices.
- Entry points: Attach menu, home overflow menu. No backend, no Firestore, no rules.
- Dependency: `com.google.android.gms:play-services-nearby`.

## Testing

Nearby cannot be tested on emulators (no Bluetooth or Wi-Fi hardware). Unit tests cover the core logic. The transfer itself
needs two real Android phones: small file, 500 MB video, cancel mid-way, decline, wrong code, out of range, airplane mode
on one phone, and low-battery behaviour.

## Effort

About a week for v1 (Android to Android, Nearby). A later v2 could add resume, folder transfers, and a remote "send to a
contact" mode over WebRTC (this one costs money because of relay bandwidth).

## Decision needed

Approve **Nearby Connections v1** as described (Android only, needs Play services, no delivery guarantee). If you want
iPhone to Android or phone to web later, this design will not cover it; that needs a different approach (the WebRTC
option, with relay costs).
