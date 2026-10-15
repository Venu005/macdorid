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
| Transfer | `Transfer/TransferManager.swift` (actor), `PartFileWriter.swift`, `SourceReader.swift`, `FileNaming.swift`, `TransferModel.swift`, `JSONFileStore.swift` | File transfers: handshake, part files, checksums, resume, history. Clipboard images (`purpose: clipboard`) use the same engine but go to a fixed clipboard folder, never ask, and are hidden from snapshots and history. Photos (`purpose: photo`) are accepted only for transfer ids the Mac registered with `expectPhoto` (others are rejected `invalid`); a cache fetch goes to `Caches/PhotoCache/` hidden (`hidden`/`isHidden`) and is cancelled on disconnect, a save goes to the received-files folder without asking and shows in history; outcomes are `photoReceived` / `photoFailed` events. `ReceiveLocationProvider.saveLocation(for: IncomingOffer)` runs in a cancellable task (the app shows a save panel there) |
| Notifications | `Notifications/NotificationStore.swift`, `FloodGate.swift`, `NotificationCategories.swift`, `IconCache.swift`, `PhoneNotifications.swift` (actor) | Mirrored notifications: the page's entries, the phone's app list, banners (flood gate), reply/action/dismiss/mute requests; icons cached by validated hash |
| Extras | `Extras/LinkRules.swift`, `Extras/PhoneExtras.swift` (actor) | Small wins: ring state, the phone's media state and art cache, links (validated both ways) |
| Photos | `Photos/ThumbnailCache.swift`, `Photos/PhotosClient.swift` (actor), `Presentation/PhotosPresentation.swift` | Browsing the phone's photos (Plan 6): albums and cursor pages matched by `requestId` (stale replies ignored, `photos.changed` merges the new first page), 256 px thumbnails cached in App Support/PhotoThumbs (digits-only ids, 200 MB LRU), originals fetched over the transfer engine three at a time (previews first) with a 30 s no-offer timeout and a 30 s no-progress timeout once the offer arrived. `PhotoSelection` is the click/⌘/⇧ logic |
| Mirror | `Mirror/MirrorFrame.swift`, `H264.swift`, `MirrorTiming.swift`, `VideoBacklog.swift`, `AudioJitter.swift`, `MirrorInput.swift`, `MirrorSession.swift` (actor), `Presentation/MirrorPresentation.swift`; route in `Server/MacDroidServer.swift` | Screen mirroring (Plan 7): `MirrorSession` starts/stops over the control WebSocket (`mirror.*`), shows a consent hint at 20 s and times out at 60 s, and gates `/v1/mirror` to the current session (`X-MacDroid-Session`). Frames (12-byte header) go straight to a `MirrorFrameSink`. `H264` packages Annex-B into CoreMedia sample buffers. `VideoBacklog` drops to the next key frame past 3 waiting; `AudioJitter` keeps a 40 ms cushion and skips over 100 ms; ping/pong gives the clock offset for the latency readout. Remote control (Plan 8): `MirrorInput` maps view points to 0–1 fractions of the aspect-fit picture (nil in the black bars), scroll deltas to view fractions, throttles drags to 120 Hz and maps keys (`MirrorKeys`); `MirrorSession.sendInput` is the input gate: `input.*` goes out only while streaming, the phone lists `control` and its `mirror.state` says `control: true`. `input.notice` sets `textFieldMissing` |
| Clipboard | `Clipboard/ClipboardRules.swift`, `ClipboardHistory.swift`, `ClipboardSync.swift` (actor) | What is sent (never concealed/transient/auto-generated items or file URLs; frame-size limit), the 10/20/50 history with image files, echo prevention by pasteboard change count |
| Presentation | `Presentation/StatusPresentation.swift`, `Presentation/TransferPresentation.swift`, `Presentation/NotificationPresentation.swift` | Pure status → icon/text mapping for the UI |
| Dev server | `Sources/MacDroidDevServer/main.swift` | Headless server for the cross-platform test |

Mac app (`mac/MacDroid/`):
- `AppModel` (`@MainActor @Observable`) is the single state owner: warnings vs transient errors, the pairing code lifecycle, re-pair, and window requests.
- `MenuBarLabel` owns opening the `Window("main")`. `AppDelegate` handles reopen and login-launch detection. `DockPolicy` sets the Dock icon only while the window is open.
- `Mirror/`: `MirrorRenderer` (an `AVSampleBufferDisplayLayer` fed on a serial queue, display immediately, resync on a new format, latency samples), `MirrorAudioPlayer` (`AVAudioEngine`, s16 → float), `MirrorWindowController` (its own aspect-locked window, Keep on top). `Views/Mirror/MirrorContentView` is the picture, status overlay, notices and bar (Back, Home, Recents, Volume −/+, Lock; disabled unless control is available). `Mirror/MirrorInputView` (an `NSView` over the picture) turns clicks/drags, right-click (Back), scroll (batched every 33 ms) and keys into `input.*`; `AppModel` sends all input through one ordered stream and batches typing every 30 ms (⌘V pastes the Mac clipboard). Settings → Mirroring holds quality, frame rate, audio and the latency readout.
- `Views/Photos/`: `PhotosView` (album column, day-grouped `LazyVGrid`, selection bar, `.quickLookPreview`, keyboard: Space/Escape/⌘A) and `PhotoCell` (drag-out via `NSItemProvider.registerFileRepresentation`, fetched into the cache on drop). `AppModel` empties the photo cache at launch and batches thumbnail requests (50 ms).
- `Extras/`: `ServicesProvider` answers right-click → Services → "Open on Phone". The popover has a ring button on the phone card and a `NowPlayingCard` (Views/Popover).
- `Clipboard/`: `PasteboardWatcher` (polls and writes `NSPasteboard`; returns the change count it produced for echo prevention), `ClipboardNotifier` ("Clipboard from <phone>").
- `Notifications/`: `NotificationRouter` is the only `UNUserNotificationCenter` delegate (macOS allows one); it owns the category set and routes responses. `PhoneNotifier` posts mirrored phone notifications. `ReceivedNotifier` posts received-file notifications through the router.
- `Files/`: `FilesSettings` (save folder bookmark, ask-each-time, cancellable save panel; `MacReceiveLocation`), `SecurityScopedSourceAccess` (read-only bookmarks for outgoing files), `ReceivedNotifier` (UserNotifications with Show in Finder / Open). `AppModel` owns the `TransferManager` and exposes `transfers` plus the file actions.
- Views live in `Views/`: the design-A popover (`MenuBarPopover` + `Popover/`: battery ring, tiles, whole-popover drop target), `MainWindow` (sidebar), Phone (pairing/paired), Files (`Files/FilesView`, `TransferRow`), Notifications (`Notifications/NotificationsView`, `NotificationCard`, `MutedAppsSheet`), Clipboard (`Clipboard/ClipboardView`), Settings (General with Received files, Notifications and Clipboard, Security, About), and Shared (incl. `AppIconView`).

## Android components
`android/core` (pure JVM, tested):
| Area | Files | Role |
|---|---|---|
| protocol | `Messages.kt`, `MessageCodec.kt` | Same contract as Swift (shared fixtures) |
| calls | `CallTracker.kt` | Calls on the Mac (Plan 10): call notifications → `call.state` (changes only, `ended` on removal, re-sent after a reconnect); `call.action` → the notification's own Answer/Decline/Hang up or `call.error`; `call.dial` numbers checked by `CallNumbers`; `CallLabels` matches older notifications' buttons |
| control | `ControlRules.kt` | Remote control rules (Plan 8): `GestureTracker` (tap, long-press at 500 ms, drags as one continued stroke), `SegmentQueue` (merges waiting middle segments), `ScrollGesture` (120 ms swipe kept on screen), `ScrollBuffer` (adds scroll batches up while a swipe plays; never under 3 % of the screen, so never a tap), `TextEdit` (insert/backspace at the cursor, whole code points; Return submits single-line fields), `SecretShadow` (password fields are edited from what MacDroid typed), `ConsentMatcher` (only MacDroid's screen-share consent) |
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
  - `platform/extras/`: `Ringer` (ring my phone, driven by `ConnectionService`) `LinkNotifications` (tap-to-open links from the Mac) and `MediaWatcher` (media sessions → `MediaTracker`; refreshed when the notification listener connects). `ui/share/OpenOnMacActivity` is the "Open on Mac" share target.
  - `platform/mirror/`: `MirrorBridge` (answers `mirror.*`, pings answered on the collector thread; posts "Mirror to <Mac>? Tap to start" because Android blocks background activity starts), `MirrorConsentActivity` (wakes the screen, microphone permission, entire-screen-only capture consent), `MirrorService` (`mediaProjection` foreground service, screen wake lock because capture ends on lock, phone speaker muted while audio streams), `ScreenEncoder` (`VirtualDisplay` → MediaCodec H.264 surface, CBR, no B-frames, rebuilt on rotation), `AudioCapturer` (playback capture, 10 ms PCM chunks), `MediaStreamClient` (pinned `/v1/mirror` WebSocket, one reconnect). Core `mirror/`: `MirrorFrame`, `MirrorSize`, `SendPolicy` (drop video to a key frame past 300 ms queued, audio past 200 ms), `MirrorPhoneSession`.
  - `platform/control/`: `MacDroidControlService` (the "MacDroid control" accessibility service: plays `input.*` as gestures via `GesturePlayer`, global actions (Back/Home/Recents/lock), volume, and `ACTION_SET_TEXT` on the focused field, or sends `input.notice`; auto-Start presses "Share screen" on System UI's consent only while `MirrorPhoneSession.awaitingConsent`), `GesturePlayer` (continued strokes, one at a time, coalesced; scroll swipes from `ScrollBuffer` when idle), `reset()` lifts a finger when the session stops streaming (`MirrorPhoneSession.onInputEnd`), `ControlPrefs` (auto-Start, always off when `BuildConfig.STORE_BUILD`), `NodeAdapter`. `MirrorBridge` forwards input only while the session is streaming and reports the service turning on/off with `controlChanged`. `platform/mirror/ScreenSize` is shared by capture and input. Settings → Remote control shows the service, the auto-Start switch and the microphone permission.
  - `platform/photos/`: `PhotoLibrary` (MediaStore `PhotoSource`: albums by bucket, pages by `date_added`/`_id`, `loadThumbnail` → 256 px JPEG q80) and `PhotosBridge` (photos requests queued to one IO worker running core `PhotoService`; fetches go through `TransferEngine.enqueueWithId(…, Photo)`; a `ContentObserver` and access changes send `photos.changed`, debounced 1 s). Core `photos/`: `PhotoQueries` (cursor, selection SQL, limits, access level), `PhotoService`, `PhotoLanes` (thumbnails apart from fetches/pages) and `PhotoAccessWatcher` (when access changes reload the Mac).
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
