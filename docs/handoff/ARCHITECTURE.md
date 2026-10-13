# Architecture

## Topology
The **Mac is the server**; the **phone is always the client** (it never listens on a port, which keeps Android background rules simple).

```
Android (client)                                  Mac (server, advertised as _macdroid._tcp)
  NsdMacLocator finds the Mac (TXT fp=16 hex) ───▶ BonjourAdvertiser (dnssd)
  OkHttp, mTLS, pinned ──── WSS  /v1/ws ────────▶ ConnectionHub (one phone session; hello first)
                        ──── POST /v1/pair ──────▶ PairingManager (only while the pairing window is open)
                        ──── GET  /v1/info ──────▶ TrustedPeerMiddleware (global: everything but /v1/pair)
```
- Transport: HTTPS + WebSocket over **TLS 1.3, mutual authentication**.
- Messages: WebSocket text frames shaped `{"v":1,"type":…,"id":…,"body":{…}}`.
  - Current types: `hello` (both directions) and `device.status` (phone → Mac: battery %, charging).
  - Unknown types are ignored. Malformed frames are dropped, and the connection stays up.
- Liveness: pings every 15 s from both sides.
- Reconnect: backoff 1 s → 30 s cap. It resets on a network change, on NSD finding the Mac at a new address, or on "Retry now".

## Trust model
1. Each side has a long-lived P-256 key and a self-signed certificate.
   - Mac: Keychain (`KeychainIdentityStore`).
   - Phone: Android Keystore (`KeystoreIdentity`); the key never leaves it.
2. The Mac shows a QR code: `macdroid://pair?host&port&fp&code&name`. The code is 128-bit base32 (26 characters) and the window lasts 120 s.
3. The phone connects, pins the Mac's fingerprint from the QR code, and POSTs the code.
4. The Mac verifies the code in constant time. It stores the phone's fingerprint **taken from the TLS session**.
5. From then on each side trusts only the other's pinned fingerprint.
   - **TLS gate:** paired phone always; any certificate only while the window is open.
   - **App layer:** routes and WS upgrade re-check `isTrusted`. This matters because TLS 1.3 session resumption skips the verify callback.
6. Manual pairing fallback: the phone probes the Mac's certificate with no trust, and the user compares fingerprints visually.

## Mac components (`mac/MacDroidCore/Sources/MacDroidCore/`)
| Area | Files | Role |
|---|---|---|
| Protocol | `Protocol/Messages.swift`, `MessageCodec.swift` | Message types and JSON envelope |
| Security | `Security/Identity.swift`, `IdentityStore.swift` | Key/cert generation, fingerprints, Keychain store |
| Pairing | `Pairing/PairingManager.swift` (actor), `PairedDeviceStore.swift`, `PairingInvite.swift` | Pairing window, code, trust decisions, persistence, QR URI |
| Network | `Network/LocalAddress.swift`, `BonjourAdvertiser.swift` | LAN IPv4 for the QR code; DNS-SD advertising |
| Session | `Session/ConnectionHub.swift` (actor) | One live session, hello-first ordering, the newest session wins, status stream |
| Server | `Server/MacDroidServer.swift` | Hummingbird 2 + mTLS callback, routes, WS bridge |
| Presentation | `Presentation/StatusPresentation.swift` | Pure status → icon/text mapping for the UI |
| Dev server | `Sources/MacDroidDevServer/main.swift` | Headless server for the cross-platform test |

Mac app (`mac/MacDroid/`):
- `AppModel` (`@MainActor @Observable`) is the single state owner: warnings vs transient errors, the pairing code lifecycle, re-pair, and window requests.
- `MenuBarLabel` owns opening the `Window("main")`. `AppDelegate` handles reopen and login-launch detection. `DockPolicy` sets the Dock icon only while the window is open.
- Views live in `Views/`: the popover, `MainWindow` (sidebar), Phone (pairing/paired), Settings (General/Security/About), and Shared.

## Android components
`android/core` (pure JVM, tested):
| Area | Files | Role |
|---|---|---|
| protocol | `Messages.kt`, `MessageCodec.kt` | Same contract as Swift (shared fixtures) |
| tls | `Fingerprint.kt`, `TrustManagers.kt` (pinned/capturing), `TlsClientFactory.kt`, `TrustFailures.kt` | Pinned mTLS OkHttp, classifying "wrong Mac / rejected" errors |
| pairing | `PairingInvite.kt`, `PairingStore.kt`, `PairingClient.kt` | QR parse, pair POST, fingerprint probe |
| connection | `ConnectionManager.kt`, `Backoff.kt`, `MacLocator.kt` | WS loop, reconnect, nudges, generation-guarded state, `Waiting.retryAtNanos` deadline |

`android/app`:
- **Glue:**
  - `MacDroidApp.kt`: `AppGraph` singletons; `UiPrefs` with `setupDone`, `keepConnected` (observable flow) and `notificationsSkipped`.
  - `platform/`: Keystore identity, prefs pairing store, NSD locator (IPv4 only), permissions.
  - `service/`: foreground `ConnectionService` (type connectedDevice), notifications, boot receiver, battery and network watchers.
- **UI:**
  - `ui/navigation/AppNavHost.kt`: switches setup ⇄ main via `SetupFlow`.
  - `ui/setup/`: pairing-centred wizard.
  - `ui/main/`: 5-tab bottom navigation: Home, Notifications, Clipboard, Files, Settings. The middle three are coming-soon placeholders.
  - `ui/presentation/`: pure status text shared by Home and the notification.
  - `ui/theme/`: Material You, with a fallback palette.

## Branding
- **App icon:** the peeking droid.
  - Source: `design/icon/`.
  - Mac: asset catalog, generated by `scripts/render-icons.sh`.
  - Android: adaptive vector icon with a monochrome layer, plus the droid silhouette as the notification icon.
- **Menu-bar icons:** SF Symbols, chosen by `StatusPresentation` according to connection state.

## Extension points for Plans 2–4
- **New message types:** add a fixture, then a case in both codecs and tests.
  - Mac: `ConnectionHub` currently has a single-consumer status stream and no incoming-message hook. **Plan 2 must add a multi-subscriber status stream plus a message hook first.**
  - Android: `ConnectionManager.incoming` is a `SharedFlow`.
- **New HTTP routes** (files): Routes registered after `router.add(middleware:)` in `makeRouter()` are automatically paired-phone-only.
- **UI:**
  - Mac: replace a `ComingSoonView` case in `MainWindow.detail(for:)`, and flip `SidebarSection.comingSoonDescription` to nil.
  - Android: replace the `ComingSoonScreen` composable for that tab in `MainScreen`.
