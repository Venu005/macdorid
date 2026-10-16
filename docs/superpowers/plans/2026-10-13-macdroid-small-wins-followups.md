# Small wins (Plan 5) — follow-ups

## Owner's hands-on checklist
Install the new builds. Media controls need the notification access you already granted.
- [x] Phone on silent: Mac popover bell → loud ring + torch flashing + "Ringing — requested by your Mac".
- [x] Each stop works: notification Stop; a volume button; the popover's red Stop; wait 30 s. Alarm volume returns to what it was.
- [x] Turn Wi-Fi off while ringing → it stops.
- [x] Chrome on the phone → Share → "Open on Mac" → opens in the Mac's browser. Share plain text without a link → "No link to open".
- [x] Mac: select a link in Notes or Safari → right-click → Services → "Open on Phone" → a "Link from your Mac" notification on the phone; tap → opens.
- [x] Spotify/YouTube Music playing on the phone → Now Playing card in the popover: art, title, artist · app; play/pause, next, previous work; drag the seek bar.
- [x] Pause and leave it for 5+ minutes → the card disappears.
- [x] Phone page: "Ring Phone" / "Stop Ringing".

## Deferred / rulings
- Task 4: the Mac projects the media position from its own receive time (clock skew); `positionAt` is still sent.
- Final review (Opus): no Critical issues; all 5 review-focus items pass. Fixed: I-1 media updates serialized; I-2 a paused session is hidden after 5 minutes (60 s tick); I-3 balanced `)`/`]`/`}` kept in links; I-4 artwork cache bounded to 8.
- Rulings: I-1 and I-4 have no unit tests (Android platform code), so they're verified by the build; the reviewer couldn't judge whether a remote `VolumeProvider` catches volume keys on Nothing OS while the alarm plays, which is on the checklist.

## Deferred minors (final review)
- Playback speed**Fixed (Plan 11).**  isn't modelled: at 1.5x the Mac's seek bar drifts between updates (the protocol has no `speed`).
- Kotlin `java.net.URI`**Fixed (Plan 11).**  rejects some links Swift accepts (`|`, `{`, `^`, IDN hosts), so the Mac may say "sent" while the phone drops the link. Use `android.net.Uri`.
- Swiping away**Fixed (Plan 11).**  the ring notification (Android 14+) leaves no Stop button; add a delete intent with `ACTION_STOP_RING`.
- The seek bar snaps**Fixed (Plan 11).**  back after release until the phone's new state arrives.
- Any app on the phone**Fixed (Plan 11).**  can open http(s) pages on the Mac via the exported "Open on Mac" (no throttle).
- The art cache key**Fixed (Plan 11).**  `pkg|title|artist` can keep a low-res placeholder, or share art between untitled items.
- `appName(pkg)`**Fixed (Plan 11).**  runs a PackageManager lookup on the main thread on every media callback; cache it.
- The Mac's `MediaArt`**Fixed (Plan 11).**  folder is never pruned.
- `url.hashCode()`**Fixed (Plan 11).**  notification ids could, rarely, collide with fixed ids.
- `RingSession.timedOut`**Fixed (Plan 11).**  is unused (the app uses `delay`).
- **Kept (Plan 11):** virtually every media session supports both; `canPlay` would be a protocol change for no visible gain. Play/pause is always enabled (the protocol has no `canPlay`).

