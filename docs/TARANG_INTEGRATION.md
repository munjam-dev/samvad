# Tarang (तरंग): nearby chat over Bluetooth

Tarang lets people chat with others nearby over a Bluetooth mesh, with no internet and no phone number.
It is built on the open-source **bitchat-android** engine and speaks the same wire protocol, so Samvad users
and official bitchat users can talk to each other.

## Upstream

- Project: https://github.com/permissionlesstech/bitchat-android
- Commit copied: `20501fdfe0d56c7705c377d4a263b01d8f38eeeb` (shallow clone of the default branch)
- Licence: GNU GPL v3 (`LICENSE.md` in upstream; the upstream README also describes it as public domain,
  and Samvad treats it as GPL-3.0 to be safe). GPL-3.0 code may be combined into Samvad's AGPL-3.0 app.
- Original copyright and licence headers in copied files are kept untouched (the Southern Storm Noise
  sources keep their MIT headers). Upstream's other files carry no per-file header.

## Where it lives

Gradle module `:tarang` (`android-native/tarang`). The package stays `com.bitchat.android` so upstream
changes can be merged with little friction. Samvad-owned code is in `com.samvad.messenger.tarang` (facade)
and `com.samvad.messenger.ui.tarang` (Compose screens).

## Copied (stage 1: Bluetooth only)

`mesh/` (BLE GATT client and server, connection tracking, packet routing, relay, fragmenting, store and
forward, peer manager, power manager, message handler, security manager), `noise/` (Noise XX session,
Southern Storm Noise-Java), `crypto/EncryptionService`, `identity/SecureIdentityStateManager`, `protocol/`,
`model/`, `sync/` (gossip sync), `services/meshgraph/`, `services/VerificationService`,
`service/TransportBridgeService`, `service/MeshServiceHolder` (gossip part only) and `util/` helpers.

## Left out (and why)

- Application, MainActivity, onboarding, themes, all upstream UI (Samvad has its own screens).
- Nostr, geohash channels, Tor, Wi-Fi Aware, hotspot APK sharing, favourites persistence: stage 2 or never.
  Stage 1 makes **no network requests**.
- Voice notes, push-to-talk, file and image transfer: incoming files and voice are dropped, never written to disk.
- Upstream spam filter, debug panel, conversation store, notifications.

## Samvad modifications to copied files

Each edit is marked with a `Samvad` comment. Summary:

- `mesh/MessageHandler.kt`: file, voice and favourite handlers reduced to "ignore".
- `mesh/BluetoothMeshService.kt`: removed MessageRouter, geohash read-receipt routing, upstream notifications,
  seen-message store; added `onNoiseSessionEstablished` hook.
- `noise/NoiseEncryptionService.kt`: removed the Nostr index update.
- `model/`, `protocol/`: `@Parcelize` removed (never used for IPC here).
- `service/MeshServiceHolder.kt`: trimmed to shared gossip.

Replacement stubs (original Samvad code): `ui/debug/DebugSettingsManager.kt`, `services/AppStateStore.kt`,
`services/NicknameProvider.kt`.

## Privacy

Messages and the nickname are visible to nearby devices running a bitchat-compatible app. Public room
messages may be relayed through other phones. Private messages are Noise-encrypted. Nothing is sent to Samvad
servers and nothing is tied to your phone number: Tarang uses a random peer id and the nickname you choose.
Messages are kept in memory only and are gone when you stop Tarang.

## Not testable without hardware

BLE discovery, connections, relay and the Noise handshake need two or more real phones.
