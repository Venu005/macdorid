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
