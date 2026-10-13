# Key decisions

The full per-plan rulings are in the `docs/superpowers/plans/*-followups.md` files. This is the short list that shapes everything.

| Decision | Why | Rejected alternatives |
|---|---|---|
| Native apps on each side (Swift/SwiftUI + Kotlin/Compose) with our own protocol | Native feel, sandbox fit, no licence issues | Kotlin Multiplatform or Rust shared core (build complexity); the KDE Connect protocol (GPL, legacy quirks) |
| HTTPS + WebSocket over TLS 1.3 with mutual auth and fingerprint pinning | Standard protocols and mature libraries (Hummingbird/SwiftNIO, OkHttp); same throughput as raw TCP; easy to debug | Custom TCP framing; HTTP/3/QUIC (no LAN benefit, immature Mac server support) |
| The Mac is the server and the phone is always the client | Android background and battery rules; the phone never listens | Peer-to-peer listeners |
| Pairing by QR code (host, port, fingerprint, 128-bit code, 120 s window); manual fallback with visual fingerprint comparison | Secure first contact without a cloud | PIN-only pairing |
| Phone → Mac clipboard is user-triggered | Android 10+ blocks background clipboard reads | The Accessibility-service hack (Play policy risk) |
| Battery optimisation via the system settings list, not the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission | Play policy | Direct exemption request |
| `ACCESS_LOCAL_NETWORK` is requested first in setup | Required on Android 17 for any LAN access, including pairing | — |
| NSD rediscovery uses IPv4 only | The QR code and Bonjour flow are IPv4; v4/v6 alternation would reset the backoff | Dual-stack |
| UI direction is native per platform (macOS-native; Material You) | Owner's choice | Nothing-inspired dot-matrix look; custom brand look |
| Mac uses a hybrid of a menu-bar popover and a sidebar window; relaunching opens the window; Dock icon only while the window is open | Lightweight background app that stays findable when the notch hides the icon | Popover-only; full Dock app |
| Android uses a pairing-centred setup wizard plus 5 bottom tabs, with "coming soon" tabs until features ship | The final shape is visible now and later rounds fill the tabs in | Hub grid; hiding the tabs |
| After Forget, the Mac shows a "Pair a Phone" button instead of opening a code automatically | An open window lets the forgotten phone past TLS, so it would show "Waiting" instead of "Pairing problem" | A core change (403 on untrusted WS upgrade), deferred |
| `ConnectionState.Waiting.retryAtNanos` (additive core change) | An accurate reconnect countdown that survives StateFlow conflation and resubscription | Timestamping in the view model |
| File transfer comes before notifications and clipboard | Files depend only on the connection core; clipboard images reuse the transfer channel | The original order (notifications → clipboard → files) |
| Received files save automatically (default `~/Downloads/MacDroid` and `Download/MacDroid`), with an Open notification; "Ask where to save" is optional | Both devices are the owner's and already paired, so it should feel like AirDrop to yourself | Accept or decline every file; prompt only for large files |
| Full resumable design: Range/HEAD resume, SHA-256, 3 in parallel | The owner sends multi-GB files | Simple restart-on-failure; streaming over the WebSocket |
| Finder Share extension deferred to its own small round | It's about a third of the Mac work; drag-drop and the picker cover sending now | Building all Mac entry points at once |
| Mac popover "Control Center" design: battery ring, Send/Open tiles, transfer rows, whole popover as drop target | The owner rejected the dashed-box popover; this feels native | Native-menu style popover |
| Android uses the Photo Picker and SAF; no `MANAGE_EXTERNAL_STORAGE` | Play policy; no storage permission needed | Broad file access |
| Android default save folder uses MediaStore Downloads (pending item as the part file); SAF only for a folder the user picks | SAF can't write without a tree grant, and choosing a folder is optional | SAF only (no default without a pick); `MANAGE_EXTERNAL_STORAGE` |
| Share-target URIs are handed to `ConnectionService` with a read grant; copy to cache only up to 100 MB | The activity's grant can end when it finishes; copying multi-GB videos doubles storage | Always copying to cache; keeping the activity open |
| Mac stores a security-scoped bookmark for each outgoing file | Sandbox access to dropped or picked files ends at quit, so resume after a relaunch would fail | Failing every Mac→phone transfer interrupted by a relaunch |
| Resume = re-send `transfer.offer` with the same id. The receiver accepts a known id again, treats an unknown id as a new offer (starting from byte 0), and replays the outcome for an id in its history | No new message type; a receiver can't tell a re-offer from a new offer anyway | A separate `transfer.resume` message; rejecting unknown ids with `invalid` |
| Downloads always make a `GET`, which is empty when nothing is left; the Mac sends `finish` after every `GET` that reaches the end | The Mac then always knows when to send `finish` (zero-byte files, a phone that already has every byte) | Special-casing zero-byte and already-complete downloads |
| Transfer limit is 3 outgoing **per side** | Needs no coordination between sides | A global limit of 3 (can't be enforced by queues on each sender) |
| Offers don't time out (cancel any time) | "Ask where to save" waits on a person | A fixed offer timeout |
