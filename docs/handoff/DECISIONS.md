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
| Incoming file names are claimed case-insensitively on the Mac | APFS is case-insensitive by default: `IMG_1.JPG` and `img_1.jpg` arriving together must not share a part file (the final review found this could save a corrupted file that passes its checksum) | Case-sensitive comparison |
| Cancelling a file transfer always surfaces as a coroutine cancellation on Android, never as a network error | The engine tells "user cancelled" from "network dropped" by exception type; a cancelled OkHttp call throws an IOException | Classifying by message text |
| Dropped files are read from the drag pasteboard at drop time, and the popover's drop overlay shows only the file count | The sandbox grants a sandboxed app access to dragged files only on drop; reading them while hovering (to add up sizes) or via SwiftUI's URL `dropDestination` failed with "Sandbox extension data required immediately" | Showing the total size while hovering (spec §3.2), which the sandbox doesn't allow |
| Notifications and clipboard: one spec, two plans (3 then 4) on one branch | Shared groundwork; one review and one merge | Two separate rounds; one giant plan |
| Mac shows full notification content | The owner's own Mac; macOS hides previews when locked; per-app mute covers the rest | Respecting Android's private flag; a setting |
| Phone owns the notification mute list; the Mac sends `notification.mute` requests | Filtering happens on the phone; no overwrite races | Round-1's full-list `notification.filter` from the Mac |
| Mac → phone clipboard on by default, switch in Settings; concealed/transient items never sent | Universal-Clipboard feel | Off by default; a popover pause switch |
| Clipboard images use the transfer channel (`purpose=clipboard`) | Resume and checksums for free | Base64 in the WebSocket |
| Mac Notifications page grouped by app; popover unchanged; Android Notifications tab is a control panel | Matches Notification Center; the phone already has a shade | Timeline layout; phone-side history |
| Android keeps Material You colours | Owner's choice (already B&W on their phone) | Forcing black and white |
| Small wins: Mac → phone links via the macOS Services menu ("Open on Phone") | No extension and no sandbox file handoff; works in most apps | Popover link field; offering to open every copied link |
| Ring like Find My (full alarm volume, even on silent) plus a flashing torch; heads-up notification with Stop | Findable on silent and in the dark; Play restricts full-screen intents | Current volume only; full-screen alert |
| Media controls: a popover Now Playing card with seek; position projected from timestamped updates | Everyday need without taking over the Mac's media keys | macOS Now Playing / media keys; streaming the position |
| The Mac projects the media position from its own receive time | Phone and Mac clocks can differ by seconds | Using the phone's `positionAt` |
| Photos: support full and limited Android access; the Mac shows what's granted | Personal use plus the cleanest Play story | Full only; selected only |
| Photos: thumbnails over the WebSocket; originals over the transfer engine (`purpose: photo`); the Mac picks the transfer id and rejects unasked offers | The phone can't serve HTTP; originals get resume and checksums; no unasked pushes | A new HTTP route on the phone |
| Photos page layout B: album column plus a day-grouped grid | Mirrors Photos.app | Album menu in the toolbar |
| Photos ordered and paged by `DATE_ADDED`, then `_ID` (not `DATE_TAKEN`) | `DATE_TAKEN` can be null and MediaProvider rejects SQL functions (`COALESCE`) in selections; a stable, never-null key keeps the cursor exact | `DATE_TAKEN` with a fallback (costs: photos downloaded long after they were taken sort by arrival) |
| The Mac asks for at most 3 photo originals at a time, previews and drags first | The phone offers at most 3 uploads at once; more would trip the 30 s "didn't respond" timeout | Asking for everything at once |
| Photo drag-out is one item at a time via `registerFileRepresentation`; several go through Save to Mac | File promises for many items need an NSView bridge; one item covers the everyday case | `NSFilePromiseProvider` per item |
| The photo cache (`Caches/PhotoCache/`) is emptied at every launch | Previews and drags are transient; no stale originals pile up | A size-capped cache |
| Remote control through an Android accessibility service | No developer mode or wireless debugging; uses the existing Wi-Fi link | adb/scrcpy-style input (smoother, Play-friendly, but debugging must stay on) |
| Mirroring first, control second: one spec, two plans (7, 8) | Control needs the picture to be useful | Control first |
| Mirroring auto-Start: the accessibility service presses Android's capture consent (a setting, on for personal builds) | Reach the phone asleep or in a pocket | Always tapping Start on the phone |
| Mirroring media on a dedicated mutual-TLS WebSocket; input on the control WebSocket; H.264 hardware codecs, no B-frames, drop to key frame; PCM audio | Lowest latency with the existing trust model; media can't block input | One WebSocket for everything; UDP/QUIC; Opus/AAC |
| A latency budget for every feature, with a measured readout for mirroring | Owner: everything must be low latency | Best effort |
| Mirroring consent in Plan 7 via a "Tap to start" notification | Android 17 blocks background activity starts (probe Q1a); Plan 8's accessibility service lifts that | Requiring MacDroid to be open on the phone |
| Capture consent limited to the entire screen (API 34+ `createConfigForDefaultDisplay`) | No "single app" choice to tap past; matches what the Mac shows | Offering single-app sharing |
| Mirroring keeps the phone awake (screen wake lock) and can't start while locked | Android ends capture on lock and shows no consent over the lock screen (probe Q2/Q3) | Starting or unlocking from the Mac (impossible with a secure lock) |
| The phone's speaker is muted while its audio streams to the Mac | Playback capture keeps working with media volume 0 (probe Q4); volume restored at the end | Double audio from phone and Mac |
| Sender drop thresholds in time at the bitrate (300 ms video, 200 ms audio) | The spec's 1 MB is a full second of lag at 8 Mbps | Byte thresholds |
| Both sides drop to the next key frame instead of single frames | P-frames depend on earlier frames; dropping one corrupts the picture | Dropping individual frames |
| Drags are one continued stroke (`continueStroke`), and segments waiting while one plays merge into one | The finger follows the mouse without lifting; a fast drag stays current instead of replaying every sample late | A new stroke per mouse sample (lifts and re-taps); replaying every segment |
| A new message, `input.notice` (`noTextField`), phone → Mac | Typing with no field focused should say so on the Mac, not fail silently | Silently dropping the text; a generic error message |
| The control service is marked `isAccessibilityTool` and reads System UI windows only for events | Needed to see and press the capture consent; events limited to `com.android.systemui` keeps it from reading other apps | Reading every app's windows; no auto-Start |
| Auto-Start is armed only while a mirroring session waits for consent (`awaitingConsent`), and only matches MacDroid's dialog | The service must never press anything on its own outside the moment the owner asked for | Always pressing any "Start"/"Share screen" button |
| A `STORE_BUILD` flag (false for personal builds) turns auto-Start off and hides its setting | Play restricts accessibility-driven automation; personal builds keep it | Shipping auto-Start everywhere; a separate flavour now |
| Scroll swipes are at least 3 % of the screen and add up while one plays; wheel lines count as 40 points | Shorter swipes are taps under Android's touch slop; one swipe per batch queued seconds of scrolling (final review C1) | Sending every batch; one continued stroke per scroll burst (smoother, but more state on the phone) |
| Password fields are edited from what MacDroid typed into them; a field with other contents is left alone | Accessibility reads password text as bullets, so a read-modify-write corrupts it (final review I1) | Pasting through the clipboard (leaks the password); a new `input.notice` reason (protocol change for a rare case) |
