# Architecture

## Topology
The **Mac is the server**; the **phone is always the client** (it never listens on a port, which keeps Android background rules simple).

```
Android (client)                                  Mac (server, advertised as _macdroid._tcp)
  NsdMacLocator finds the Mac (TXT fp=16 hex) ───▶ BonjourAdvertiser (dnssd)
  OkHttp, mTLS, pinned ──── WSS  /v1/ws ────────▶ ConnectionHub (one phone session; hello first)
                        ──── POST /v1/pair ──────▶ PairingManager (only while the pairing window is open)
                        ──── GET  /v1/info ──────▶ TrustedPeerMiddleware (global: everything but /v1/pair)
                        ──── HEAD/PUT/GET /v1/files/{id} ──▶ TransferManager (bytes; control over WS)
```
- Transport: HTTPS + WebSocket over **TLS 1.3, mutual authentication**.
- Messages: WebSocket text frames shaped `{"v":1,"type":…,"id":…,"body":{…}}`.
  - Current types: `hello` (both directions), `device.status` (phone → Mac: battery %, charging), `transfer.offer`, `transfer.accept`, `transfer.reject`, `transfer.finish`, `transfer.result`, `transfer.cancel` (Plan 2a); `notification.posted|removed|sync|icon|apps|reply|action|dismiss|mute|error` (Plan 3); `clipboard.set` and `transfer.offer.purpose` (Plan 4); `device.ring`, `device.ringState`, `link.open`, `media.state|art|command` (Plan 5).
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
| Session | `Session/Broadcast.swift` | Multi-subscriber stream fan-out |
| Server | `Server/MacDroidServer.swift`, `Server/FileRoutes.swift` | Hummingbird 2 + mTLS callback, routes, WS bridge |
| Transfer | `Transfer/TransferManager.swift` (actor), `PartFileWriter.swift`, `SourceReader.swift`, `FileNaming.swift`, `TransferModel.swift`, `JSONFileStore.swift` | File transfers: handshake, part files, checksums, resume, history. Clipboard images (`purpose: clipboard`) use the same engine but go to a fixed clipboard folder, never ask, and are hidden from snapshots and history. `ReceiveLocationProvider.saveLocation(for: IncomingOffer)` runs in a cancellable task (the app shows a save panel there) |
| Notifications | `Notifications/NotificationStore.swift`, `FloodGate.swift`, `NotificationCategories.swift`, `IconCache.swift`, `PhoneNotifications.swift` (actor) | Mirrored notifications: the page's entries, the phone's app list, banners (flood gate), reply/action/dismiss/mute requests; icons cached by validated hash |
| Extras | `Extras/LinkRules.swift`, `Extras/PhoneExtras.swift` (actor) | Small wins: ring state, the phone's media state and art cache, links (validated both ways) |
| Clipboard | `Clipboard/ClipboardRules.swift`, `ClipboardHistory.swift`, `ClipboardSync.swift` (actor) | What is sent (never concealed/transient/auto-generated items or file URLs; frame-size limit), the 10/20/50 history with image files, echo prevention by pasteboard change count |
| Presentation | `Presentation/StatusPresentation.swift`, `Presentation/TransferPresentation.swift`, `Presentation/NotificationPresentation.swift` | Pure status → icon/text mapping for the UI |
| Dev server | `Sources/MacDroidDevServer/main.swift` | Headless server for the cross-platform test |

Mac app (`mac/MacDroid/`):
- `AppModel` (`@MainActor @Observable`) is the single state owner: warnings vs transient errors, the pairing code lifecycle, re-pair, and window requests.
- `MenuBarLabel` owns opening the `Window("main")`. `AppDelegate` handles reopen and login-launch detection. `DockPolicy` sets the Dock icon only while the window is open.
- `Clipboard/`: `PasteboardWatcher` (polls and writes `NSPasteboard`; returns the change count it produced for echo prevention), `ClipboardNotifier` ("Clipboard from <phone>").
- `Notifications/`: `NotificationRouter` is the only `UNUserNotificationCenter` delegate (macOS allows one); it owns the category set and routes responses. `PhoneNotifier` posts mirrored phone notifications. `ReceivedNotifier` posts received-file notifications through the router.
- `Files/`: `FilesSettings` (save folder bookmark, ask-each-time, cancellable save panel; `MacReceiveLocation`), `SecurityScopedSourceAccess` (read-only bookmarks for outgoing files), `ReceivedNotifier` (UserNotifications with Show in Finder / Open). `AppModel` owns the `TransferManager` and exposes `transfers` plus the file actions.
- Views live in `Views/`: the design-A popover (`MenuBarPopover` + `Popover/`: battery ring, tiles, whole-popover drop target), `MainWindow` (sidebar), Phone (pairing/paired), Files (`Files/FilesView`, `TransferRow`), Notifications (`Notifications/NotificationsView`, `NotificationCard`, `MutedAppsSheet`), Clipboard (`Clipboard/ClipboardView`), Settings (General with Received files, Notifications and Clipboard, Security, About), and Shared (incl. `AppIconView`).

## Android components
`android/core` (pure JVM, tested):
| Area | Files | Role |
|---|---|---|
| protocol | `Messages.kt`, `MessageCodec.kt` | Same contract as Swift (shared fixtures) |
| tls | `Fingerprint.kt`, `TrustManagers.kt` (pinned/capturing), `TlsClientFactory.kt`, `TrustFailures.kt` | Pinned mTLS OkHttp, classifying "wrong Mac / rejected" errors |
| pairing | `PairingInvite.kt`, `PairingStore.kt`, `PairingClient.kt` | QR parse, pair POST, fingerprint probe |
| connection | `ConnectionManager.kt`, `Backoff.kt`, `MacLocator.kt` | WS loop, reconnect, nudges, generation-guarded state, `Waiting.retryAtNanos` deadline |
| extras | `LinkExtractor.kt`, `RingSession.kt`, `MediaTracker.kt` | Small wins: link rules, ring state, which media session the Mac shows and when to send |
| clipboard | `ClipboardHub.kt` | Explicit sends (always sent), frame-size and image limits, received items, the in-memory recent 10 |
| notifications | `NotificationModel.kt`, `NotificationRules.kt`, `NotificationForwarder.kt` | Which notifications go to the Mac (exclusions, de-dupe, mutes, apps seen, sync, icons); the phone owns these settings |
| transfer | `TransferClient.kt`, `FileEndpoint.kt`, `TransferEngine.kt`, `TransferModel.kt`, `TransferStore.kt`, `SaveTarget.kt`, `FolderSaveTarget.kt` | File bytes over pinned mTLS HTTPS (HTTP/1.1, resume headers, running SHA-256); `TransferEngine` is the confined-dispatcher state machine; `SaveTarget`/`PartFile`/`SourceOpener` are the platform seams Plan 2b implements |

`android/app`:
- **Glue:**
  - `MacDroidApp.kt`: `AppGraph` singletons; `UiPrefs` with `setupDone`, `keepConnected` (observable flow) and `notificationsSkipped`.
  - `platform/`: Keystore identity, prefs pairing store, NSD locator (IPv4 only), permissions.
  - `platform/` (files): `SaveTargets` picks a `SaveTarget`: MediaStore Download/MacDroid by default, a chosen SAF tree, or ask-each-time via `SavePrompter` → notification → `ui/files/SaveAsActivity` (CreateDocument). It reopens part files by handle prefix `media:`/`tree:`/`doc:`. Also `FilesPrefs` and `ContentSourceOpener`. `AppGraph.transferEngine` starts with the process.
  - `platform/clipboard/`: `ClipboardBridge` (Mac items → `ClipboardManager`; phone sends → `ClipboardHub`), `ClipboardImages` (PNG conversion, FileProvider `${applicationId}.clipboard` over `cache/clipboard*`), `ClipboardPrefs`. `ui/clipboard/`: the invisible `ClipboardSendActivity` (reads the clipboard once focused), `CopyToMacActivity` (share target), `ClipboardTileService`.
  - `platform/notifications/`: the `MacDroidNotificationListener` service, `NotificationBridge` (listener ↔ connection ↔ `NotificationForwarder`, one ordered worker), `NotificationReader` (StatusBarNotification → `PhoneNotification`), `AppIcons` (96 px PNG + hash), `SharedPrefsNotificationSettings`.
  - `service/`: foreground `ConnectionService` (type connectedDevice), notifications, boot receiver, battery and network watchers. File transfers add `TransferNotifications` (progress with Cancel, received with Open, ask-where-to-save prompts) and the `SEND_FILES`/`CANCEL_TRANSFERS` actions; `platform/SendFiles` turns picked or shared URIs into queued transfers.
- **UI:**
  - `ui/navigation/AppNavHost.kt`: switches setup ⇄ main via `SetupFlow`.
  - `ui/setup/`: pairing-centred wizard. Step order: Welcome → Local network → Pair → Notifications (POST_NOTIFICATIONS + notification access, skippable) → Files ("Where should files from your Mac go?", first run only) → Battery. Settings has a Files section (`FilesSettingsViewModel`).
  - `ui/main/`: 5-tab bottom navigation: Home, Notifications, Clipboard, Files, Settings. Clipboard is live (`ui/clipboard/ClipboardScreen`: Send now, "Get Mac clipboard", Recent). Notifications is live (`ui/notifications/`: a control panel with the "Show on your Mac" switch and per-app switches). Files is live (`ui/files/`: `FilesScreen`, `TransferRow`, `SendPickers`). Home has a Send FAB and the last 3 transfers.
  - `ui/presentation/`: pure status text shared by Home and the notification.
  - `ui/theme/`: Material You, with a fallback palette.
  - `ui/share/ShareActivity`: the "Send to Mac" share target (ACTION_SEND / SEND_MULTIPLE, `*/*`). It has no UI: it hands the URIs to `ConnectionService` with a read grant and shows a toast, or says "Connect to your Mac first" and opens the app.

## Branding
- **App icon:** the peeking droid.
  - Source: `design/icon/`.
  - Mac: asset catalog, generated by `scripts/render-icons.sh`.
  - Android: adaptive vector icon with a monochrome layer, plus the droid silhouette as the notification icon.
- **Menu-bar icons:** SF Symbols, chosen by `StatusPresentation` according to connection state.

## Extension points for Plans 2–4
- **New message types:** add a fixture, then a case in both codecs and tests.
  - Mac: feature managers subscribe to `ConnectionHub.messages` and `statusUpdates` (each access is an independent subscription).
  - Android: `ConnectionManager.incoming` is a `SharedFlow`.
- **New HTTP routes** (files): Routes registered after `router.add(middleware:)` in `makeRouter()` are automatically paired-phone-only.
- **UI:**
  - Mac: replace a `ComingSoonView` case in `MainWindow.detail(for:)`, and flip `SidebarSection.comingSoonDescription` to nil. Files is already live (`Views/Files/FilesView`).
  - Android: replace the `ComingSoonScreen` composable for that tab in `MainScreen`.

## Feature exchange (Plan 3)
- Both sides list their features in `hello` (`ProtocolInfo.features`, `Features.SUPPORTED`). Android exposes them as `ConnectionState.Connected(macName, features)` and `ConnectionState.macSupports(feature)`; the Mac as `PeerInfo.supports(feature)`.
- `ConnectionHub.messages` carries every feature message (everything except `hello`, `device.status` and unknown types).
