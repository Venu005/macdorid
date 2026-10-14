# MacDroid — Screen Mirroring and Remote Control (Plans 7 and 8)

Builds on:
- the connection core (mutual TLS 1.3, pinned certificates, the control WebSocket, `hello` features)
- the Mac server routes (Hummingbird) and the trust rules already used for file transfer and photos
- the Mac window, popover and settings; the Android settings

## 1. Goal and scope

See and use the phone from the Mac, with low latency:
- **Plan 7 — mirroring:** a live picture of the phone's screen in its own Mac window, with the phone's audio.
- **Plan 8 — remote control:** click, drag, scroll and type on that picture to use the phone; Back, Home, Recents, volume and lock buttons.

Use cases (owner's choice B): using phone apps (chats, apps without a Mac version) with sharp text, and watching videos or Reels with sound. Gaming is out of scope.

The owner should be able to reach the phone while it is **asleep or locked** (screen off, in a pocket or on a charger) without touching it.

Out of scope:
- USB transport, scrcpy/adb-based control, wireless-debugging setups.
- Gaming-grade input (multi-touch, sub-frame gestures).
- Mirroring the Mac onto the phone.
- Recording the mirror.
- Bypassing `FLAG_SECURE`: protected apps and DRM video show black or silent, as in every mirroring tool.

## 2. Latency budget

Latency is a first-class requirement. Targets on good home Wi-Fi (5 GHz, phone and Mac on the same access point):

| Path | Target |
|---|---|
| Screen change on the phone → shown on the Mac | 50–80 ms (p50), under 150 ms (p95) |
| Mac click → gesture lands on the phone | under 50 ms (p50) |
| Phone audio → Mac speakers | under 100 ms; never drifts more than 100 ms from video |

How the design meets them:
- hardware H.264 encode (phone) and hardware decode (Mac), zero-copy surfaces on the phone
- no B-frames, no encoder or decoder frame queues, "display immediately" on the Mac
- stale video is dropped up to the next key frame instead of queued; audio skips ahead instead of drifting
- PCM audio (no codec delay)
- input travels on the existing control WebSocket, separate from the media stream (§4)

A latency readout in the mirror window shows the measured capture-to-display time (§6.5).

## 3. Phone

### 3.1 Starting a session
1. The Mac sends `mirror.start` (§5.1) on the control WebSocket.
2. The phone wakes the screen with a transparent activity (`setTurnScreenOn`, `setShowWhenLocked`), which requests screen capture (`MediaProjectionManager.createScreenCaptureIntent()`).
3. Android shows its consent prompt. On Android 14+ the prompt first asks "A single app" or "Entire screen".
4. **Auto-Start** (setting "Start mirroring without touching the phone", §7): MacDroid's accessibility service (§3.5) watches System UI windows during the 20 s after a `mirror.start`. It selects **Entire screen** and presses **Start**, matching by visible text and view id. It never acts on any other window or at any other time. If it can't find the controls, the prompt stays for the user to tap.
5. On consent, a foreground service of type `mediaProjection` starts with an ongoing notification: "Mirroring to <Mac name>" with a **Stop** action. Android requires this.
6. The phone sends `mirror.state{starting}` when the prompt appears and `mirror.state{streaming, width, height, rotation}` once frames flow.

The screen stays on while mirroring: Android doesn't capture a display that is off. A locked phone shows its lock screen in the mirror and is unlocked by clicking or typing the PIN on the Mac (Plan 8). MacDroid never stores the PIN.

### 3.2 Video
- `MediaProjection` → `VirtualDisplay` → the `Surface` input of a `MediaCodec` H.264 encoder (no copies).
- **Size:** the device resolution scaled so the longest side is at most `maxSize` (default 1920, i.e. "1080p"); width and height rounded to multiples of 16.
- **Encoder:**
  - High profile without B-frames (fall back to Baseline if High isn't offered)
  - `KEY_LATENCY` 0 and `KEY_PRIORITY` 0 (realtime) where supported; `KEY_LOW_LATENCY` where supported
  - CBR around 8 Mbps at 1080p (4 Mbps at 720p)
  - `KEY_I_FRAME_INTERVAL` 2 s
  - `KEY_REPEAT_PREVIOUS_FRAME_AFTER` 100 ms, so a still screen sends almost nothing
  - `KEY_MAX_FPS_TO_ENCODER` = `fps`
- **Key frames on request:** `mirror.keyframe` → `PARAMETER_KEY_REQUEST_SYNC_FRAME`.
- **Rotation or display size change:** the encoder and virtual display are rebuilt at the new size; the phone sends a new codec-config frame and `mirror.state{streaming, …}` with the new size.

### 3.3 Audio
- `AudioPlaybackCaptureConfiguration` on the same `MediaProjection` (usages MEDIA, GAME, UNKNOWN), 48 kHz stereo 16-bit PCM, sent in 10 ms chunks.
- Needs `RECORD_AUDIO`, requested the first time a session starts with `audio: true`. If it is denied, the session runs without audio and the Mac shows "Phone audio is off: allow the microphone permission for MacDroid on your phone".
- Apps that opt out of capture and DRM content are silent.
- The phone's own speaker keeps playing. The on-device test (§9.1) checks whether it can be muted locally without silencing the capture; if not, the user lowers the phone's volume.

### 3.4 Media stream (sender)
- The phone opens a second WebSocket, `wss://<mac>:<port>/v1/mirror?session=<sessionId>`, with the same pinned client certificate and Mac fingerprint pinning as the control connection.
- Each binary message is one frame (§5.2).
- **Backpressure:** the sender tracks bytes queued in the socket. Above 1 MB (about a second of video), it drops video frames until the next key frame and requests one from the encoder. Audio is dropped above 200 ms queued. Latency never builds up behind a slow network.

### 3.5 Accessibility service "MacDroid control" (Plan 8)
- An `AccessibilityService` declared with `canPerformGestures`, `canRetrieveWindowContent` and window-state events.
- **What it touches:** it acts on System UI only during the 20 s auto-Start window after a `mirror.start`, and otherwise only on the focused editable node when the Mac sends text. It never reads, stores or sends any other screen content; the mirror picture comes from screen capture, not from accessibility.
- The user turns it on in Android's Accessibility settings. Android requires that; Settings → Remote control links there with a one-paragraph explanation.
- **Input:**
  - Pointer down/move/up becomes `dispatchGesture` with continued strokes (`StrokeDescription.continueStroke`), so a drag follows the mouse while it moves.
  - A down/up within 10 px and 300 ms is a tap; held over 500 ms it's a long-press.
  - `input.scroll` becomes a short swipe: scroll distance scaled to screen fractions, a 120 ms stroke, coalesced while scrolling.
  - `input.key` becomes global actions: Back, Home, Recents, Lock screen (`GLOBAL_ACTION_LOCK_SCREEN`). Volume keys become `AudioManager.adjustStreamVolume` (media stream, with the system volume UI shown). `backspace` deletes the character before the cursor in the focused editable node (same mechanism as text insertion).
  - `input.text` goes to the focused editable node: the text is inserted at the cursor using `ACTION_SET_TEXT` on the node's current text plus `ACTION_SET_SELECTION`. If no editable node has focus, the text is dropped and the Mac shows "Click a text field on the phone first".
- Coordinates arrive as 0–1 fractions of the mirrored picture and are mapped to the real display using the current rotation and size (pure, tested core code).

### 3.6 Ending a session
Any of these:
- `mirror.stop` from the Mac
- Stop in the phone notification
- `MediaProjection.Callback.onStop` (Android ended the capture)
- the control connection dropping
- the media stream failing twice

The phone stops the encoder, audio capture and foreground service, closes the media stream, and sends `mirror.state{stopped, reason}` if the control connection is up.

## 4. Why a separate media stream

Video (up to ~1 MB/s) would block control messages (notifications, clipboard, input) on the existing WebSocket, and the reverse. A dedicated mutual-TLS WebSocket per session keeps input responsive and lets the session end by closing one socket. UDP/QUIC was rejected: it would need a new encryption and pinning stack for a gain rarely felt on home Wi-Fi.

## 5. Protocol

### 5.1 Control WebSocket messages

| Type | Direction | Body |
|---|---|---|
| `mirror.start` | Mac → phone | `sessionId` (lowercase UUID), `maxSize` (720–1920), `fps` (30 \| 60), `audio` (bool) |
| `mirror.state` | phone → Mac | `sessionId`, `state` (`starting` \| `streaming` \| `stopped`), `width?`, `height?`, `rotation?` (0/90/180/270), `reason?` (`user` \| `mac` \| `denied` \| `consentTimeout` \| `noPermission` \| `ended` \| `error`), `audio?` (bool: audio actually captured), `control?` (bool: accessibility service on) |
| `mirror.stop` | both | `sessionId` |
| `mirror.keyframe` | Mac → phone | `sessionId` |
| `mirror.ping` | Mac → phone | `t0` (Mac monotonic µs) |
| `mirror.pong` | phone → Mac | `t0`, `t1` (phone µs when received), `t2` (phone µs when sent) |
| `input.pointer` | Mac → phone | `action` (`down` \| `move` \| `up` \| `cancel`), `x`, `y` (0–1), `t` (Mac ms) |
| `input.scroll` | Mac → phone | `x`, `y` (0–1), `dx`, `dy` (fractions of the screen) |
| `input.key` | Mac → phone | `key` (`back` \| `home` \| `recents` \| `volumeUp` \| `volumeDown` \| `power` \| `backspace`) |
| `input.text` | Mac → phone | `text` (≤ 4,096 characters) |

Features: `mirror` (§3.1–3.4) and `control` (§3.5), added to `hello.features` on both sides. Input messages are ignored unless a session is streaming. A phone without `control`, or without the accessibility service on (`mirror.state.control == false`), gets no input from the Mac.

### 5.2 Media stream frames (`/v1/mirror`, binary WebSocket messages)
A 12-byte header, big-endian, then the payload:

| Bytes | Field |
|---|---|
| 0 | type: 1 = video config (SPS+PPS, Annex-B), 2 = video frame (Annex-B access unit), 3 = audio (PCM s16le stereo 48 kHz) |
| 1 | flags: bit 0 = key frame |
| 2–3 | reserved (0) |
| 4–11 | capture time, phone monotonic clock (`System.nanoTime` base, the encoder's presentation time), microseconds |

A frame over 4 MiB, or of an unknown type, closes the stream.

### 5.3 Trust
- `/v1/mirror` accepts only the paired phone's certificate (as every route except pairing does), and only with the `sessionId` of a session the Mac started and that is still `starting` or `streaming`. Any other connection is closed with 4403, so a phone can't push a stream the Mac didn't ask for.
- One media stream per session; a second connection for the same session replaces the first (a reconnect).

## 6. Mac

### 6.1 Core (`MacDroidCore`, tested)
- **`MirrorSession` actor:**
  - the state machine (idle → requesting → starting → streaming → stopped)
  - the 20 s consent hint and 60 s timeout
  - session id gate for the route
  - ping/pong clock offset (median of the last 5 round trips)
  - latency figures
  - publishes `MirrorState` for the UI
- **`MirrorFrame`:** header encode/decode and limits.
- **`H264`:**
  - Annex-B NAL splitting
  - SPS/PPS extraction into a `CMVideoFormatDescription`
  - Annex-B → AVCC length-prefixed `CMSampleBuffer` with the `DisplayImmediately` attachment
  - key frame detection
- **`AudioJitter`:** decides when to schedule, hold or skip PCM chunks (40 ms target, skip ahead when over 100 ms behind).
- **`InputMapper`:** view points → 0–1 fractions; drag coalescing to ≤ 120 Hz; scroll scaling; key mapping.

### 6.2 Server
- **`/v1/mirror` route:** checks the session gate (§5.3), then feeds frames to the session's sink without awaiting the UI, so a slow window never blocks the socket.
- **Mac-side catch-up:** video frames depend on earlier ones, so single frames are never dropped. If more than 3 video frames are waiting to be displayed, the Mac drops everything up to the next key frame and sends `mirror.keyframe` (the same rule as the phone's sender).

### 6.3 Window (app)
- **`MirrorWindow`:**
  - an `NSWindow` with a hidden, transparent title bar (full-size content) and an aspect ratio locked to the phone's (updated on rotation), resizable, remembering its size and position
  - "Keep on top" sets the floating level
  - the content view hosts an `AVSampleBufferDisplayLayer`
- **Bar under the picture** (icons with tooltips):
  - Back, Home, Recents, Volume −, Volume +, Lock
  - Keep on top
  - latency readout ("46 ms": green under 100, orange under 150, red above; hidden by the setting)
  - Stop
- **Opening:**
  - a **Mirror** button on the Phone page and on the popover's phone card, enabled when the phone lists `mirror`
  - the window opens immediately with "Starting… Tap Start on your phone if asked" until frames arrive
- **Closing:** closing the window stops the session.

### 6.4 Audio (app)
- `AVAudioEngine` with an `AVAudioPlayerNode`; PCM buffers are scheduled as `AudioJitter` decides.
- Muted when the phone audio setting is off. The audio flag is sent in `mirror.start`, so the phone doesn't capture at all.

### 6.5 Input (app, Plan 8)
- Mouse down/drag/up on the picture → `input.pointer`.
- Scroll wheel and trackpad → `input.scroll`.
- Right-click → `back`.
- Typing while the window is key → `input.text`:
  - characters are batched for 30 ms
  - Return → `"\n"`
  - Delete → `input.key{backspace}`, which deletes the character before the cursor in the focused field
- ⌘V → the Mac clipboard text as `input.text`.
- Esc → `back`.
- The cursor is a dot while it's over the picture.

### 6.6 Latency readout
`latency = macDisplayTime − (phoneCaptureTime − clockOffset)`, averaged over the last 30 frames. Both clocks are monotonic (the phone's `System.nanoTime` base, the Mac's `ContinuousClock`), in µs. The offset comes from `mirror.ping`/`pong` every 2 s: `offset = ((t1 − t0) + (t2 − t3)) / 2` with `t3` the Mac's receive time, taking the median of the last 5.

## 7. Settings

- **Mac, General → Mirroring:**
  - quality: Sharp (1920) / Balanced (1280)
  - frame rate: 60 / 30
  - phone audio: on / off
  - show latency: on / off
- **Android, Settings → Remote control:**
  - "MacDroid control" (accessibility service: on/off status, taps open Android's Accessibility settings)
  - "Start mirroring without touching the phone" (switch; default on; needs the service; hidden when the build flag `STORE_BUILD` is true; debug builds and the owner's builds have it false)
  - Microphone for mirroring audio (status; tap requests or opens app settings)

No new first-run setup step: permissions are asked when the feature is first used.

## 8. Errors

| Situation | Behaviour |
|---|---|
| No consent within 20 s | Window: "Tap Start on your phone to begin mirroring." At 60 s: `consentTimeout`, "Mirroring didn't start". |
| Consent declined | "Mirroring was declined on the phone." |
| Accessibility service off | Mirroring works (the user taps Start); input disabled with the bar notice "Turn on MacDroid control on your phone to use the mouse." |
| `RECORD_AUDIO` denied | Video only; one-line notice in the window. |
| Protected app or DRM | Black or silent (Android's rule). |
| Network falls behind | Video skips to the next key frame; audio skips ahead; readout turns orange/red. |
| Media stream drops, control connection up | One automatic reconnect (the phone reopens `/v1/mirror` with the same session) with a key frame; on a second failure, `stopped{error}`, "Mirroring stopped". |
| Control connection drops | The window shows "Phone not connected" and closes after 10 s; the phone stops the session. |
| Phone lacks `mirror` or `control` | "Update MacDroid on your phone to use this." |
| Text with no focused field | "Click a text field on the phone first". |

## 9. Testing

### 9.1 On-device test first (Plan 7, Task 1)
A throwaway debug build on the owner's Nothing Phone (3), run with the owner, answering:
1. Does auto-Start find and press "Entire screen" and "Start" on Android 17 / Nothing OS?
2. Is the lock screen (including the PIN pad) visible in the capture?
3. Does capture keep running when the phone locks itself mid-session?
4. Does playback capture work alongside the phone's speaker, and can the speaker be muted locally without silencing the capture?
5. Measured end-to-end latency on the owner's Wi-Fi, at 1080p60.

The results are recorded in the plan's ledger. A "no" picks the fallback written in the plan:
- 1 → the user taps Start
- 2 → type the PIN blind
- 3 → the session ends on lock with a clear message
- 4 → no local mute

The test is never automated without the owner present.

### 9.2 Automated
- **Fixtures and codecs** for every new message on both sides.
- **Core (Swift):**
  - `MirrorFrame` header round trips and limits
  - `H264`: NAL splitting, SPS/PPS → format description, AVCC conversion, key frame flags; uses a recorded 10-frame fixture
  - `AudioJitter` decisions
  - `InputMapper` incl. rotation and coalescing
  - clock-offset median
  - `MirrorSession` states: start, consent hint, timeout, decline, stop, reconnect, the route gate
- **Core (Kotlin):**
  - frame header
  - the backpressure/drop policy
  - gesture building (tap / drag / long-press / scroll)
  - coordinate mapping incl. rotation
  - text insertion at a cursor
  - the session state machine
  - auto-Start node matching (text and id rules, applied to a fake node tree)
- **Cross-platform:** the Swift dev server and Kotlin client run a session with a synthetic H.264 stream (the recorded fixture) and PCM. They check:
  - the session gate rejects a wrong id
  - frames arrive in order with key frames
  - `mirror.keyframe` reaches the phone
  - `input.*` messages arrive on the phone side
  - a clean stop
- **Owner checklist:** latency numbers, text sharpness, video with sound, lock-screen unlock, auto-Start from a pocket, every bar button, typing and paste, rotation, stopping from either side.

## 10. Plans

One spec, two plans on one branch (`feat/mirroring`), one final review, one merge:
- **Plan 7 — mirroring:** the on-device test; protocol (mirror messages, frames); phone capture, encoder, audio, media stream; Mac route, session, H.264 display, audio, window, latency; settings; cross-platform.
- **Plan 8 — remote control:** protocol (input messages); the accessibility service, gesture building, text insertion, auto-Start; Mac input mapping and the bar buttons; settings; cross-platform input.

## 11. Decisions

| Decision | Why | Rejected |
|---|---|---|
| Control via an accessibility service | Works without developer mode or debugging; uses the Wi-Fi link already there | adb/wireless debugging (smoother, Play-friendly, but debugging must stay on and resets on network changes) |
| Mirroring before control, one spec, two plans | Control needs the picture to be useful | Control first (blind input) |
| Phone audio included | Owner wants video and Reels with sound | Video only |
| Auto-Start through the accessibility service, a setting (on for personal use, hidden in store builds) | Reach the phone while it is in a pocket or asleep | Always tapping Start on the phone |
| Its own Mac window | Sits beside other apps; like iPhone Mirroring | A page in the main window |
| A dedicated mutual-TLS WebSocket for media; input on the control WebSocket | Media can't block input, and the reverse; same trust model | Everything on one WebSocket; UDP/QUIC |
| H.264 hardware encode/decode, no B-frames, drop-to-keyframe | Lowest latency with universal hardware support | HEVC (higher latency on some encoders), software codecs |
| PCM audio | Zero codec delay; ~1.5 Mbps is nothing on a LAN | Opus/AAC (20–60 ms more) |
