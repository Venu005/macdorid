# MacDroid — Round 1 Design

**Date:** 2026-10-12
**Status:** Draft for review

## 1. Goal

A LinkMyMac-style app pair that connects an Android phone to a Mac over the local network, with no account and no cloud. Built first for personal use (Nothing Phone (3), Android 17 + this Mac), but designed from day one to be publishable on the Mac App Store and Google Play.

Round 1 delivers:

1. **Connection core** — discovery, QR pairing, mutually authenticated encrypted link, auto-reconnect.
2. **Notifications** — Android notifications mirrored to the Mac, with reply/actions/dismiss sync.
3. **Clipboard** — Mac → phone automatic; phone → Mac on demand; history on the Mac.
4. **File transfer** — both directions, user-chosen destination, parallel, resumable, checksummed.

### Out of scope (later rounds)

Photo gallery browsing, SMS/contacts, screen mirroring/remote control, webcam/mic, USB transport, Wi-Fi Direct/hotspot mode, multiple phones or Duo Mode (one phone ↔ two Macs), iPad app.

## 2. Platforms and stack

| | Mac | Android |
|---|---|---|
| Minimum OS | macOS 14 | Android 10 (API 29) |
| Target | current macOS, sandboxed | latest SDK (Android 17) |
| Language / UI | Swift 6, SwiftUI | Kotlin, Jetpack Compose |
| HTTP / WebSocket | Hummingbird 2 (SwiftNIO) + HummingbirdWebSocket, TLS via NIOSSL | OkHttp 5 |
| JSON | `Codable` | kotlinx.serialization |
| Discovery | Bonjour (`NWListener` service advertisement) | `NsdManager` |
| Key storage | Keychain | Android Keystore |
| QR | Core Image `CIQRCodeGenerator` | CameraX + ML Kit barcode scanning (bundled model) |

Publishing constraints that shaped the design:
- **Mac sandbox:** entitlements `com.apple.security.network.server`, `network.client`, user-selected files read-write. Info.plist `NSLocalNetworkUsageDescription` and `NSBonjourServices = [_macdroid._tcp]`. Chosen download folder persisted as a security-scoped bookmark.
- **Play policy:** no SMS permissions, no `MANAGE_EXTERNAL_STORAGE` (use Storage Access Framework), no Accessibility Service. Notification access via `NotificationListenerService` is permitted for this use case.

## 3. Repository layout

```
macdroid/
  mac/          Xcode project (app + Share extension) and a local Swift package `MacDroidCore`
  android/      Gradle project (single app module + `core` module)
  protocol/     PROTOCOL.md and JSON fixtures for every message type
  docs/
```

`protocol/` is the single source of truth. Both apps' unit tests decode and re-encode every fixture so the Swift and Kotlin sides cannot drift.

## 4. Architecture

The **Mac is the server**; the **phone is always the client**. The phone never listens on a port, which keeps Android background/battery behaviour simple.

```
Android (client)                                   Mac (server, :port advertised as _macdroid._tcp)
  NsdManager discovers Mac ───────────────────────▶ Bonjour advertisement
  OkHttp (mTLS, pinned) ──── WSS /v1/ws ─────────▶ control channel (JSON messages)
                         ──── PUT /v1/files/{id} ─▶ phone → Mac file body
                         ──── GET /v1/files/{id} ─▶ Mac → phone file body
                         ──── POST /v1/pair ──────▶ pairing (only while QR is shown)
```

### 4.1 Identity and pairing

- On first launch each side generates a long-lived identity key pair (P-256) and a self-signed X.509 certificate. Mac: private key in Keychain. Android: private key in Android Keystore.
- **Mac → QR code** (shown in Settings ▸ Pairing, valid 2 minutes) encodes:
  `macdroid://pair?host=<ip>&port=<port>&fp=<sha256 of Mac cert>&code=<random 128-bit, base32>&name=<Mac name>`
- **Phone** scans it, opens TLS to `host:port` presenting its own certificate, and verifies the server certificate's SHA-256 fingerprint equals `fp`. It then calls `POST /v1/pair` with `{code, deviceName, model}`.
- **Mac** accepts the call only while the pairing window is open and `code` matches (constant-time compare). It stores the client certificate fingerprint taken from the TLS session (never from the request body), closes the window, and returns `{macName}`.
- Phone stores the Mac fingerprint + last known host/port. Pairing is now complete. Round 1 supports exactly one paired phone; re-pairing replaces it.
- A manual fallback is offered for when the camera can't be used. The Mac shows the same fields as text; the phone has an input form for host, port, and code, and shows the fingerprint for visual comparison.

### 4.2 TLS rules

- TLS 1.3 only, both directions authenticated (mutual TLS).
- **Phone** trusts the server iff its leaf certificate SHA-256 fingerprint equals the stored (or QR-supplied) one. Implemented as a custom `X509TrustManager`; client cert supplied by a `KeyManager` backed by the Keystore.
- **Mac** verification callback accepts a client certificate iff its fingerprint is the paired one, **or** the pairing window is open. A connection from an unpaired certificate may reach only `POST /v1/pair`; every other route returns 403.

### 4.3 Discovery and reconnect

- Mac advertises `_macdroid._tcp` with TXT records `v=1`, `fp=<first 16 hex of fingerprint>` so the phone picks the right Mac.
- Phone connection order: last known host/port → NSD resolution matching `fp`. Retries use exponential backoff from 1s, capped at 30s. Backoff resets and an immediate retry fires on Wi-Fi network change (`ConnectivityManager.NetworkCallback`) or when NSD sees the Mac appear.
- Liveness: WebSocket ping frames every 15s; connection considered dead after 45s without a pong.
- Android runs the connection inside a **foreground service** (type `connectedDevice`) with a persistent notification. Onboarding asks the user to exempt the app from battery optimisation (with a Nothing OS–specific hint).

### 4.4 Control channel messages

All WebSocket text frames are JSON:

```json
{ "v": 1, "type": "clipboard.set", "id": "<uuid>", "body": { ... } }
```

Unknown `type` values are ignored (forward compatibility). On connect, both sides send `hello`:

```json
{ "type": "hello", "body": { "protocol": 1, "app": "1.0.0", "device": "Nothing Phone (3)", "features": ["notifications","clipboard","files"] } }
```

Features are used only where both sides list them.

| Type | Direction | Body (summary) |
|---|---|---|
| `hello` | both | protocol, app version, device name, features |
| `device.status` | phone → Mac | battery %, charging |
| `notification.posted` | phone → Mac | key, package, appName, title, text, postedAt, actions[{index,title}], canReply, iconHash, icon (base64 PNG ≤96px, only if Mac lacks `iconHash`) |
| `notification.removed` | phone → Mac | key |
| `notification.action` | Mac → phone | key, actionIndex |
| `notification.reply` | Mac → phone | key, text |
| `notification.dismiss` | Mac → phone | key |
| `notification.filter` | Mac → phone | mutedPackages[] (full replacement) |
| `clipboard.set` | both | itemId, kind=`text`, text |
| `transfer.offer` | both | transferId, name, size, mime, purpose=`file`\|`clipboard` |
| `transfer.accept` / `transfer.reject` | both | transferId, (reason) |
| `transfer.finish` | sender → receiver | transferId, sha256 |
| `transfer.result` | receiver → sender | transferId, ok, (error) |
| `transfer.cancel` | both | transferId |

Exact JSON for each lives in `protocol/fixtures/*.json`.

## 5. Features

### 5.1 Notifications (Android → Mac)

- `NotificationListenerService` forwards notifications except: muted packages, `FLAG_ONGOING_EVENT`, group summaries, `IMPORTANCE_MIN`, and MacDroid's own.
- Mac posts each via `UserNotifications`, `threadIdentifier = package` (grouping). If `canReply`, the category includes a `UNTextInputNotificationAction`; other actions become `UNNotificationAction`s (max 3).
- Reply → `notification.reply` → phone fills the action's `RemoteInput` and fires its `PendingIntent`. Action → fires the action's `PendingIntent`.
- Dismiss sync both ways: Mac dismiss → `notification.dismiss` → `cancelNotification(key)`. Phone removal → `notification.removed` → Mac removes delivered notification.
- Mac keeps the last 50 in memory for the popover. App icons are cached on disk keyed by `iconHash`.
- Mac Settings lists apps seen so far with mute toggles; changes send `notification.filter`.
- Notifications posted while disconnected are **not** replayed on reconnect (avoids floods).

### 5.2 Clipboard

- **Mac → phone (automatic):** poll `NSPasteboard.general.changeCount` every 0.5s. Text/URL → `clipboard.set`. PNG/TIFF image ≤10 MB → converted to PNG and sent via transfer with `purpose=clipboard`. Items marked `org.nspasteboard.ConcealedType` (password managers) are never sent.
- **Phone → Mac (on demand)**, because Android 10+ blocks background clipboard reads. The app exposes:
  - a Quick Settings tile ("Send clipboard"),
  - a "Send clipboard" action on the foreground-service notification,
  - a Share target that accepts text and images.
  
  Each entry point briefly opens a transparent activity to read the clipboard, which is allowed in the foreground.
- **Receiving:** the phone writes received items to `ClipboardManager` (allowed in the background). The Mac writes them to `NSPasteboard`.
- **Echo prevention:** each side remembers the last `itemId` it applied and ignores a local change whose content equals it.
- **Mac history:** capacity 10/20/50 (default 20), searchable, persisted in Application Support as JSON. Click → copy locally; secondary action → resend to phone. Images are stored as files next to the JSON.

### 5.3 File transfer

**Flow (either direction):**
1. Sender sends `transfer.offer`.
2. Receiver resolves the destination. If "Ask each time" is on, it shows the save dialog (Mac) or the SAF picker (Android). It then replies `transfer.accept` or `transfer.reject`.
3. Body moves over HTTPS. **Phone → Mac:** phone `PUT /v1/files/{id}` streaming the file. **Mac → phone:** phone `GET /v1/files/{id}` streaming to the SAF document.
4. Both sides compute SHA-256 while streaming. Sender sends `transfer.finish{sha256}`; receiver compares and replies `transfer.result`. A mismatch deletes the partial file and reports an error with Retry.

**Performance:**
- Bodies are streamed with ~1 MB buffers. There are no per-chunk acknowledgements; TCP flow control paces the transfer.
- Up to 3 concurrent transfers, configured via the OkHttp dispatcher.
- Files are queued beyond 3.

**Resume:**
- Partial files are kept as `<name>.macdroid-part` until verified.
- **Mac → phone:** phone re-issues `GET` with `Range: bytes=N-`; Mac replies `206`.
- **Phone → Mac:** phone sends `HEAD /v1/files/{id}`; Mac returns `Upload-Offset: N` (bytes on disk). Phone then sends `PUT` with `Content-Range: bytes N-(size-1)/size`.
- On resume, both sides re-hash the first N bytes from disk to restore the running SHA-256.
- Transfer state is kept for 24h; unaccepted or abandoned transfers are cleaned up after that.

**Destinations:**
- **Mac:** default `~/Downloads/MacDroid`, changeable in Settings (security-scoped bookmark). Toggle "Ask where to save each time".
- **Android:** a folder chosen in onboarding via `ACTION_OPEN_DOCUMENT_TREE` (persisted URI permission). Toggle "Ask each time" uses `ACTION_CREATE_DOCUMENT`.
- **Name conflicts:** append ` (1)`, ` (2)`, …

**Entry points:**
- **Mac:** drop onto the popover; "Send files…" button; Finder Share extension "Send to phone".
- **Android:** Share target (multiple files); in-app picker.

**UI:** per-transfer progress, speed, ETA, cancel; history of last 100 transfers (name, size, direction, result, time) with "Show in Finder" / open.

## 6. User interface

### Mac
- **Menu-bar extra**:
  - status icon (connected / reconnecting / disconnected / not paired)
  - phone battery
- **Popover tabs**:
  - Notifications — grouped by app
  - Clipboard — search and history
  - Transfers — active and history
  
  The whole popover is a file drop target.
- **Settings window tabs**:
  - General: launch at login via `SMAppService`
  - Pairing: QR, manual details, forget device
  - Notifications: muted apps
  - Clipboard: history size, enable/disable each direction
  - Files: folder, ask-each-time
- **Share extension**: hands file URLs to the main app via an app group container and a Darwin notification. The extension never opens network connections.

### Android
- **Onboarding** steps:
  1. Scan QR (or manual)
  2. Grant notification access
  3. `POST_NOTIFICATIONS`
  4. Battery optimisation exemption
  5. Pick download folder
- **Home**:
  - connection status
  - active and recent transfers
  - Send files
  - settings (ask-each-time, re-pair, forward-notifications on/off)
- **Foreground notification**: status + "Send clipboard" + "Disconnect".
- **Quick Settings tile** and **Share target** as above.

## 7. Error handling

| Situation | Behaviour |
|---|---|
| WebSocket drops | Both UIs show "Reconnecting"; phone backs off 1s→30s, retries immediately on network change / NSD reappearance. Active transfers pause and resume via Range/offset. |
| Certificate mismatch | Connection refused; phone shows "This Mac isn't the one you paired with" with Re-pair option. Mac logs and ignores. |
| Pairing code wrong / window expired | `POST /v1/pair` returns 403; phone shows "Code expired, rescan". |
| Checksum mismatch | Partial file deleted; error with Retry on both sides. |
| Destination unwritable / disk full | Receiver sends `transfer.reject` or `transfer.result{ok:false,error}`; sender shows the reason. |
| Notification access revoked / bookmark stale / SAF permission lost | Persistent banner in the app explaining how to re-grant; feature disabled until fixed. |
| Unknown message type | Ignored. Malformed JSON is logged and the frame dropped; the connection stays up. |

## 8. Testing

- **Shared fixtures:** each `protocol/fixtures/*.json` decodes and round-trips in both XCTest and JUnit.
- **Mac unit tests (XCTest):**
  - message codec
  - pairing state machine (window, code compare, expiry)
  - fingerprint verification
  - clipboard echo suppression
  - transfer bookkeeping (offsets, resume hashing, conflict naming)
  - history capacity
- **Android unit tests (JUnit + OkHttp MockWebServer):**
  - message codec
  - trust manager pinning
  - reconnect backoff
  - transfer client with Range/offset resume
  - notification → message mapping
- **Integration:**
  - Mac server started in-process on localhost
  - `curl`-based scripts exercising pair, PUT/HEAD resume, GET Range, checksum failure
- **Manual end-to-end checklist on the Nothing Phone (3):**
  - pair
  - notification + reply + dismiss both ways
  - clipboard both directions incl. image
  - 1 GB file both directions
  - Wi-Fi toggled mid-transfer (resume)
  - Mac sleep/wake
  - phone reboot
  - revoke notification access

## 9. Decisions log

- Native apps on each side over a shared KMP/Rust core (simplicity, native feel, sandbox fit) and over the KDE Connect protocol (GPL, legacy quirks).
- HTTPS + WebSocket over custom TCP framing: standard protocols, mature libraries, same throughput, easier debugging; the phone stays client-only.
- HTTP/3/QUIC rejected for now: no speed benefit on a LAN, immature Mac server support.
- Phone → Mac clipboard is user-triggered because of the Android 10+ background clipboard restriction; Accessibility workaround rejected for Play policy.
