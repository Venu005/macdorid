# Mirroring (Plans 7–8): follow-ups

## Probe results (2026-10-14, Nothing Phone (3), Android 17 / Nothing OS, with the owner)

| # | Question | Observed | Decision |
|---|---|---|---|
| Q1a | Can MacDroid open the consent screen from the background **without** an accessibility service? | No: `Background activity launch blocked` (BAL) | Plan 7 starts from the "Mirror to <Mac>? Tap to start" notification (as planned). |
| Q1b | With an accessibility service on? | Yes: the activity opens from the background, even while locked. But Android's own dialogs (microphone permission, the capture consent) **do not appear over the lock screen**; they show after unlocking. | Plan 8 auto-Start works only on an unlocked phone. |
| Q1b | Can the service press the consent button? | Yes. The dialog is System UI's `MediaProjectionPermissionActivity`: title "Share your screen with MacDroid?", "Share entire screen" preselected (our entire-screen-only config works), buttons Cancel (`android:id/button1` is **"Share screen"**, `android:id/button2` Cancel). Container id `com.android.systemui:id/screen_share_permission_dialog`. The service only sees it with `isAccessibilityTool="true"` + `flagRetrieveInteractiveWindows` and by walking `windows` (not `rootInActiveWindow`). `performAction(CLICK)` returned true and capture started. | Plan 8 matches the dialog by that container id and presses `android:id/button1`; fallback to text "Share screen"/"Start". |
| Q2 | Is the lock screen / PIN pad visible in the capture? | No frame was ever captured from the lock screen (see Q3). | Unlocking from the Mac is not possible; the phone must be unlocked on the phone. |
| Q3 | Does capture survive the phone locking? | **No.** `MediaProjection.onStop` fired the moment the phone locked (Android ends capture on lock). | Plan 7 Task 8: hold a screen wake lock while mirroring so the phone doesn't auto-lock mid-session; a lock (power button) ends the session with reason `ended` → "Mirroring stopped". |
| Q4 | Playback capture with the phone's media volume at 0? | Works: RMS 1395/1731 with `STREAM_MUSIC` = 0 while a YouTube video played (YouTube is not capture-blocked). | `MUTE_PHONE_SPEAKER = true`: the phone stays quiet while its sound plays on the Mac; volume restored at the end. |

Also seen: up to ~110 fps captured while video played; ~1–3 fps on a still screen.

**What this means for the spec's goal "reach the phone while asleep or locked":** with a PIN/pattern lock, Android 17 shows neither the consent dialog nor the screen while locked, and stops capture on lock. Mirroring therefore starts on an unlocked phone and keeps it awake while it runs. The owner can make "pick up and go" easier with Android's own unlock helpers, which MacDroid doesn't control.

## Plan 7 owner checklist

**Passed (owner, 2026-10-16):** every item below.

Start with the phone **unlocked** (Android ends capture on lock).

1. Install both builds. On the Mac: Settings → Mirroring shows Quality, Frame rate, Phone audio, Show latency.
2. Phone page → **Mirror** (and the popover icon).
   - The window opens with "Starting…".
   - The phone shows the consent screen (if MacDroid is open) or a "Mirror to <Mac>? Tap to start" notification.
   - Tap **Share screen**; allow the microphone the first time.
3. **Picture:** sharp text at Sharp/60, and the window keeps the phone's shape when resized.
4. **Latency:** the readout shows a number. Write down typical values at Sharp/60 and Balanced/30 (target 50–80 ms).
5. **Sound:** play a video on the phone; it plays on the Mac in sync. With Phone audio off, no sound.
6. **Rotation:** rotate the phone into a landscape video; the window reshapes and keeps playing.
7. **Keep on top:** the window stays above other apps.
8. **Stopping:** Stop on the Mac ends it on the phone (the notification goes); "Stop" in the phone notification ends it on the Mac ("Mirroring stopped"); closing the window ends it.
9. **Declining:** decline the consent → "Mirroring was declined on the phone." Ignore it for 20 s → "Tap Start on your phone to begin mirroring."; at 60 s → "Mirroring didn't start".
10. **Wi-Fi:** turn it off on the phone mid-session → the window says "Phone not connected" and closes after 10 s.
11. **Protected apps:** open a banking app → black (expected).
12. Lock the phone mid-session → "Mirroring stopped" on the Mac (Android ends capture on lock).
13. During a session the phone's speaker is silent and its sound plays on the Mac; afterwards the phone's volume is back.

## Deferred minors (Plan 7 final review)
1. **Output-device change:** `AVAudioEngine` stops when the output device changes (e.g. AirPods connect) and isn't restarted; observe `AVAudioEngineConfigurationChange`.
2. **Audio counter:** `MirrorAudioPlayer.bufferedFrames` can go negative after `stop()`; completions from before the reset still arrive.
3. **Teardown order:** the VirtualDisplay briefly targets a released encoder surface in `ScreenEncoder.resize`/`release`; detach the surface first.
4. **Landscape window size:** rotating to landscape keeps the window's width, so it gets short; keep the long edge instead.
5. **Second start:** `MirrorService.finished` is never reset if an instance gets a second start; guard `onStartCommand`.
6. **Kept (Plan 11):** Hummingbird has one WebSocket config per server; only the trusted paired phone can upgrade, and control messages are still capped at 1 MiB after decoding. **Frame limit:** the 4 MiB WebSocket frame limit also applies to `/v1/ws` frames (control messages are still capped at 1 MiB).
7. **Early frames:** frames sent before the media socket opens go out late, and the config frame is sent twice.
8. **Thread visibility:** `stream`/`policy` in `MirrorService` are read on other threads without `@Volatile`.
9. **Kept (Plan 11):** an under-estimate of a few ms on a debug number. **Readout timing:** the latency readout measures to enqueue, not display (a slight underestimate).

## Owner checklist: remote control (Plan 8)

**Passed (owner, 2026-10-16):** every item below.
1. Phone → Settings → Remote control → **MacDroid control** → turn on "MacDroid control" in Accessibility. MacDroid's row then reads "On".
2. With the phone **unlocked** on the home screen, click **Mirror** on the Mac. "Share screen" is pressed by itself and the picture appears.
3. **Click** an app icon: it opens. **Drag** up and down in a list: it scrolls smoothly. **Hold** on an icon: its long-press menu opens.
4. **Trackpad or wheel scroll** in a list: it scrolls in the same direction as on the Mac.
5. **Right-click** = Back. The bar's **Back, Home, Recents, Volume −/+ and Lock** all work. Lock ends mirroring (expected).
6. **Typing:**
   - Click a chat's text box and **type**; the text appears.
   - **Delete** removes characters, including a whole emoji.
   - **Return** gives a new line.
   - **⌘V** pastes the Mac clipboard.
   - Typing with no text box focused shows "Click a text field on the phone first".
7. Click in the **black bars** beside the picture: nothing happens on the phone.
8. Turn MacDroid control **off** while mirroring: the bar buttons grey out and "Turn on MacDroid control on your phone to use the mouse." appears. Turn it back on: control returns.
9. Phone → Settings → Remote control → turn **"Start mirroring without touching the phone"** off; Mirror again: you tap Share screen yourself.

## Plan 8 final review: open items
- **Ruled by the owner 2026-10-16 (fixed in Plan 11): yes, volume goes to the Mac player while audio streams.** Was: Mac volume buttons during audio mirroring.** With phone audio on, the phone's speaker is muted; Mac Volume + raises the phone's media volume, so the phone speaker plays too, and the restore at the end overwrites it. This is what the spec says (volume keys → phone volume). Owner to decide: map volume to the Mac player while audio streams?
- **Deferred minors:**
  1. The 4,096 cap counts Characters on the Mac and UTF-16 units on the phone; emoji-heavy pastes over the cap can split a surrogate pair. Cap in UTF-16 units on the Mac, back off a trailing high surrogate, and say so in PROTOCOL.md.
  2. Backspace removes one code point, not one grapheme (ZWJ emoji and flags take several presses).
  3. **Kept (Plan 11):** the owner's typing checklist passed on the device. Each keystroke rewrites the whole field (breaks undo/IME composition; may race the node cache when typing fast). Check on the device.
  4. `GesturePlayer` doesn't pump again after `dispatchGesture` returns false; queued segments wait for the next input.
  5. The dot cursor shows even when control is off and over the black bars.
  6. `MirrorInputNSView.down` can stay true if control turns off mid-drag.
  7. Unsent keys (Tab) go to `super.keyDown` and can move focus off the picture; dead keys and non-Latin input methods don't work (not an `NSTextInputClient`).
  8. Esc is always Back, so it can't leave full screen on the mirror window.
  9. Scroll deltas are normalised to the view, not the aspect-fit picture.
  10. `CrossPlatformTest` has `delay(300)` straight before `delay(500)`.
