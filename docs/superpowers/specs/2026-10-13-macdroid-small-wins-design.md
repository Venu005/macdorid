# MacDroid — Small Wins (Plan 5): Ring, Links, Media Controls

**Date:** 2026-10-13
**Status:** approved in brainstorming on 2026-10-13; self-reviewed (volume-key mechanism and link rules made explicit); awaiting the owner's review of this written spec.

**Builds on:** the connection core (Plan 1), notifications (Plan 3, whose `NotificationListenerService` also grants media-session access), the design-A popover (Plan 2b).

**Delivery:** one spec and one plan, on branch `feat/small-wins`.

## 1. Goal and scope

Three everyday conveniences that reuse what's built:

1. **Ring my phone** from the Mac. A loud tone plays and the torch flashes, even on silent, until the phone is found.
2. **Open a link on the other device.**
   - Phone → Mac: "Open on Mac" in the share sheet.
   - Mac → phone: right-click → Services → "Open on Phone".
3. **Media controls.** A Now Playing card in the Mac popover shows the phone's current media, with previous/play-pause/next and a seek bar.

**Success:** the owner uses these without thinking. Nothing needs debugging mode, Google Play policy is respected, and there are no new Android runtime permissions.

**Out of scope**
- The Mac's own media keys or Now Playing (rejected: it takes over the Mac's play/pause key).
- Phone volume control.
- A full-screen ring alert. Play restricts `USE_FULL_SCREEN_INTENT` to alarm and calling apps.
- Finder Share extension (parked separately).

## 2. Protocol

Features in `hello`: `ring`, `links`, `media`. Each is active only when both sides list them. After this round, both apps list `files, notifications, clipboard, ring, links, media`.

| Type | Direction | Body |
|---|---|---|
| `device.ring` | Mac → phone | `action`: `start` \| `stop` |
| `device.ringState` | phone → Mac | `ringing` (bool) |
| `link.open` | both | `url`: `http`/`https` only, ≤ 8,192 UTF-8 bytes |
| `media.state` | phone → Mac | see below |
| `media.art` | phone → Mac | `hash` (16 lowercase hex), `jpeg` (base64, ≤ 300 px on the longest side) |
| `media.command` | Mac → phone | `command`: `play` \| `pause` \| `next` \| `previous` \| `seek`; `positionMs` (seek only) |

**`media.state` body**
- `active`: bool. When false, nothing is playing and the other fields are omitted.
- `package`: the app's package name.
- `appName`, `title`, `artist`: artist may be empty.
- `durationMs`: 0 when unknown, for example a live stream.
- `positionMs` and `positionAt`: the position, and the epoch milliseconds at which it was read.
- `playing`: bool.
- `canPrevious`, `canNext`, `canSeek`: bool.
- `artHash`: 16 lowercase hex characters, or empty.

**Rules**
- **When state is sent:** the phone sends `media.state` only on a change of track, play/pause state, available controls, or a seek / position jump. It also sends one after every `hello`. The Mac projects the position: `positionMs + (now − positionAt)` while playing, capped at `durationMs`.
- **Artwork:** `media.art` is sent once per hash per connection, before the `media.state` that references it. The Mac caches it on disk, and validates the hash exactly as `IconCache` does.
- **Links:** a receiver ignores any `link.open` whose URL isn't `http`/`https` or is over the size limit.
- **Ring state:** `device.ringState` is sent on every start and stop, including automatic stops.

Fixtures for every message go in `protocol/fixtures/`, plus `media.state.idle.json` for `active:false`. `PROTOCOL.md` is updated.

## 3. Phone

### 3.1 Ring (in `ConnectionService`)

**Start**
1. Save the alarm-stream volume, then set it to its maximum.
2. Loop the default alarm tone (`RingtoneManager.TYPE_ALARM`, falling back to the default ringtone) on `AudioAttributes.USAGE_ALARM`. This plays on silent and vibrate, and in DND when alarms are allowed, as they are by default.
3. Flash the torch with `CameraManager.setTorchMode` on the back camera, 250 ms on and 250 ms off. If the camera is busy, skip the torch quietly. No `CAMERA` permission is needed for the torch.
4. Post a high-importance notification on a new channel "Ring" (`IMPORTANCE_HIGH`): "Ringing — requested by your Mac", with a **Stop** action.

**Stop:** the ring stops on whichever comes first:
- Stop is tapped
- a volume key is pressed, caught by a temporary `MediaSession` that MacDroid holds only while ringing (playing state plus a relative `VolumeProvider`, whose `onAdjustVolume` stops the ring). Android has no public volume-key broadcast.
- `device.ring{stop}` arrives
- 30 seconds pass
- the connection drops

On stop, the phone restores the alarm volume, turns the torch off, cancels the notification, and sends `device.ringState{ringing:false}`.

**Pure logic** in `android/core` covers the ring state machine: start, stop reasons and the timeout.

### 3.2 Links

- **Share target "Open on Mac":** `ACTION_SEND` with `text/plain`. The first `http`/`https` URL in `EXTRA_TEXT` is extracted (`LinkExtractor`, pure, in core) and sent as `link.open`.
- **Toasts:**
  - "Opening on your Mac"
  - "No link to open"
  - "Not connected to your Mac"
  - "Update MacDroid on your Mac to use this"
- **Incoming `link.open`:** a notification on channel "Links", "Link from your Mac" with the URL's host as text. Tapping it opens the URL (`ACTION_VIEW`). Android forbids background activity starts, so the link can't open directly.

### 3.3 Media

- **Watching sessions:** `MediaSessionManager.getActiveSessions(listenerComponent)` and `addOnActiveSessionsChangedListener`. Both are available because MacDroid's notification listener is enabled. Without notification access, the phone sends `media.state{active:false}` once.
- **Choosing the session:** the first session that is playing; otherwise the most recently paused one. A session that has been gone or stopped for more than 5 minutes is not shown.
- **Reading state:** from `MediaController.metadata` (title, artist, duration, art bitmap), `playbackState` (state, position, `lastPositionUpdateTime`, actions), and `packageName` / app label.
- **Artwork:** scaled to ≤ 300 px, JPEG quality 80, hashed with the first 8 bytes of its SHA-256.
- **Commands** go to `controller.transportControls`: play, pause, skipToNext, skipToPrevious, seekTo.
- **Pure logic** in `android/core` (`MediaTracker`): which session to show, whether a new state differs enough to send, art once per connection, and command mapping.

## 4. Mac

- **Popover (design A):**
  - **Phone card:** a bell button beside the battery ring. It becomes a red **Stop** while `ringing`, and is disabled when disconnected or when the phone lacks `ring`.
  - **Now Playing card:** placed between the tiles and Transfers, and visible only while `media.state.active`. It shows:
    - artwork (44 pt, rounded), title, and "artist · app"
    - previous, play/pause and next buttons (each disabled per `can*`)
    - a seek bar (`Slider`) that projects the position live and sends `media.command{seek}` when released, disabled when `!canSeek || durationMs == 0`
- **Phone page:** a **Ring Phone / Stop Ringing** button. When the phone lacks notification access (from Plan 3's `notification.apps.access`), it also shows: "Media controls need notification access on your phone."
- **Services menu "Open on Phone":** declared under `NSServices` in `Info.plist` (`NSSendTypes` `public.utf8-plain-text` and `public.url`; the menu item is "Open on Phone"). It is handled by an `NSApp.servicesProvider` object, which sends the first http(s) URL in the selection. Swift `LinkRules` (core, pure) mirrors Kotlin `LinkExtractor`: the first `http`/`https` URL, ≤ 8,192 UTF-8 bytes. With no link, or when disconnected, it shows a small notification: "No link in the selection" / "Your phone isn't connected".
- **Incoming `link.open`:** `NSWorkspace.shared.open(url)` for `http`/`https` only.
- **Core:** `PhoneExtras` actor (in `MacDroidCore`), plus `MediaPresentation` (pure: projected position, time labels). The actor holds the ring state, the media state, the art cache and `LinkRules`.

## 5. Errors

| Situation | Behaviour |
|---|---|
| Disconnected | Ring button disabled; Now Playing card hidden; Services shows "Your phone isn't connected" |
| Phone lacks notification access | Ring and links work; no Now Playing card; Phone page note |
| Other side lacks a feature | That feature's controls are hidden or disabled; nothing is sent |
| App ignores a media command | Nothing breaks; the card follows the phone's next `media.state` |
| Non-http(s) or oversized link | Ignored on receipt; never sent |
| Ring while the camera is busy | Rings without the torch |
| Connection drops mid-ring | The phone stops ringing and restores the volume |

## 6. Testing

- **Unit tests (both sides):**
  - fixtures decode and round-trip
  - `LinkExtractor` / `LinkRules`: first link, schemes, size limit
  - ring state machine: every stop reason, the timeout
  - `MediaTracker`: session choice, change detection, art once per connection, command mapping
  - Mac `PhoneExtras`: ring state, media state, art cache with hash validation
  - `MediaPresentation`: projected position capped at duration, time labels
- **Cross-platform script:**
  - a ring start → `ringState` round trip
  - `link.open` both ways
  - a `media.state` plus `media.art`, then a `media.command` arriving at the phone
- **Owner checklist:**
  - ring on silent, with the torch, stopped by each method
  - "Open on Mac" from Chrome
  - Services → "Open on Phone" from Safari and Notes, and the notification tap
  - Spotify/YouTube Music in the popover: controls and seek
  - nothing playing → card gone

## 7. Decisions

| Decision | Why | Rejected |
|---|---|---|
| Mac → phone links via the Services menu | No extension, no sandbox file-handoff problem; works in most apps | Popover field; auto-offer on copy (noisy) |
| Ring like Find My, plus a flashing torch | Findable on silent and in the dark | Current volume only |
| Heads-up notification, not a full-screen alert | Play restricts `USE_FULL_SCREEN_INTENT` | Full-screen intent |
| Incoming links on the phone become a tap-to-open notification | Android blocks background activity starts | Opening directly |
| Now Playing card with seek; no media keys, no volume | Media keys would take over the Mac's play/pause; volume adds little | Mac Now Playing integration; volume slider |
| Position is projected, not streamed | One message per change; smooth seek bar | Periodic position updates |
