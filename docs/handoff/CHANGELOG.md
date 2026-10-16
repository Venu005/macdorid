# Changelog

Newest first. Every change to the repo adds an entry here (see "Keep the handoff current" in `/CLAUDE.md`).

Format: `## YYYY-MM-DD — title`, then what changed, why, the branch or commits, and how it was verified.

## 2026-10-16 — Plan 11: backlog clean-up (fix/backlog)
- **Task 1 (rulings):** owner rulings recorded in DECISIONS.md: free and unsigned for now (with the buy-once model if ever sold), contacts sync dropped, and Volume −/+ go to the Mac player while phone audio plays there. Every owner checklist is ticked as passed (2026-10-16), including the 2 GB+ file; items kept without a code change are marked "Kept (Plan 11)" with the reason in their follow-up lists. Plan: `docs/superpowers/plans/2026-10-16-macdroid-backlog-11.md`. Verified: docs only.
- **Task 2 (mirroring audio):** Volume −/+ in the mirror window change the Mac player's level (16 steps, kept as `mirror.volume`) while the phone's sound plays on the Mac, and they work even with remote control off; otherwise they still go to the phone (`MirrorVolume`, tests `volumeGoesToTheMacWhileItPlaysTheSound`, `volumeStepsByOneSixteenthAndClamps`). The player restarts itself when the output device changes (AirPods), and buffer completions from before a stop/restart no longer push its counter negative (an epoch). Verified: `swift test --filter MirrorVolumeTests` RED→GREEN; Mac app build, no code warnings.
- **Task 3 (mirror window, cursor, scroll):** turning the phone between portrait and landscape keeps the picture's long edge, so the window turns wide instead of short, and it stays on screen (`MirrorWindowFit`; tests `landscapeKeepsTheLongEdge`, `backToPortraitKeepsTheLongEdgeAsTheHeight`, `portraitToPortraitKeepsTheWidth`). The dot cursor shows only over the picture and only while control is on (`InputMapper.pictureRect`, `pictureRectIsCentredAspectFit`). Turning control off mid-drag lifts the finger. Scrolling is measured against the picture, not the whole view (`scrollIsAFractionOfThePictureNotTheView`). Verified: tests RED→GREEN; Mac app build, no code warnings.
- **Task 4 (mirror keyboard):** the mirror view is an `NSTextInputClient`, so dead keys (⌥E E → é) and input methods (Pinyin, Japanese) compose on the Mac and only committed text is sent. Esc leaves full screen first, then means Back. Tab, arrows and function keys are eaten rather than moving focus off the picture; other ⌘ shortcuts still reach the menu (tests `escLeavesFullScreenFirst`, `tabAndArrowsStayOnThePicture`, `otherCommandShortcutsStillReachTheMenu`; `keysMapToActions` now expects arrows to be swallowed). Verified: tests RED→GREEN; Mac app build, no code warnings.
- **Task 5 (remote-control text and gestures):** `input.text` is capped at 4,096 UTF-16 code units on both sides and never splits an emoji's surrogate pair (`InputText.capped`, `TextEdit.cap`; PROTOCOL.md says so). Backspace removes one character as the reader sees it, so a flag or a ZWJ family is one press (`BreakIterator`). A gesture Android refuses no longer stalls the queue until the next input. `CrossPlatformTest`'s back-to-back `delay(300)`/`delay(500)` are one `delay(800)`. Tests: `capsInUtf16UnitsWithoutSplittingAPair`, `textCapNeverSplitsASurrogatePair`, `backspaceRemovesAWholeFlagAndAZwjFamily` (RED→GREEN). Verified: `swift test` 352; Mac app build; `:core:test :app:testDebugUnitTest :app:assembleDebug`, no warnings.
- **Task 6 (Android capture):** on rotate or stop, the virtual display lets go of the encoder's surface before that surface is released. A second start while a session runs is ignored, and a service instance reused after a session ended starts clean, so it can still end. `stream`/`policy` are `@Volatile`. Frames are sent only once the media socket is open (`MediaStreamClient.isOpen`), so nothing queues up late and the codec config goes out exactly once. Platform code with no unit tests; verified by `:app:testDebugUnitTest :app:assembleDebug` with no warnings, and by the owner check at the end of the plan.
- **Task 7 (small wins on Android):** links that the Mac's parser accepts (`|`, `{`, `^`, IDN hosts) are no longer dropped on the phone. `LinkExtractor.isAllowed` checks the scheme and authority itself instead of using `java.net.URI` (`linksSwiftAcceptsAreAllowed`, RED→GREEN). Swiping away the ring notification stops the ringing (delete intent). App names are cached, so media callbacks don't query PackageManager on the main thread each time. The art cache key includes the art's size and the duration, and small placeholders aren't cached. Link notifications use their own id range instead of `url.hashCode()`. The unused `RingSession.timedOut` is removed (the Ringer's timer does the 30 s stop). Verified: `:core:test :app:testDebugUnitTest :app:assembleDebug`, no warnings.
- **Task 8 (small wins on the Mac, plus protocol):** `media.state` gains an optional `speed` (playback rate). The phone sends it, and sends a new state when it changes. The Mac projects the seek bar at that speed, so it no longer drifts at 1.5× (fixture `media.state.json`, PROTOCOL.md, both codecs; tests `mediaStateCarriesTheSpeed`, `projectedPositionAdvancesAtSpeed`, `sendsTheSpeedAndResendsWhenItChanges`). A released seek stays put until the phone catches up, or for 3 s (`MediaPresentation.PendingSeek`, `aReleasedSeekHoldsUntilThePhoneCatchesUp`). The `MediaArt` folder keeps the 8 most recently seen images (`IconCache.prune`, `pruneKeepsTheNewest`). Links opened on the Mac from the phone are limited to 3 in any 10 s (`LinkThrottle`, `linkThrottleAllowsThreeIn10Seconds`). Verified: `swift test` 357; `:core:test :app:testDebugUnitTest :app:assembleDebug`; Mac app build, no code warnings; cross-platform test passed.
- **Task 9 (file transfer on Android):** a cancel that lands while a part is being created no longer leaves the part behind. The engine keeps the part as soon as it exists and deletes it on cancellation, and "Ask where to save" prompts stay cancellable (`cancelDuringCreatePartDeletesThePart`, RED→GREEN). A late progress callback can't bring back a finished transfer's speed. Platform fixes:
- **Task 10 (file transfer on the Mac):**
  - **Core fixes:**
    - A forgotten incoming record (its part became unreadable) deletes its `.macdroid-part` (`forgetRemovesThePartFile`).
    - A re-offer that arrives while the file is being verified is ignored, so a HEAD can't recreate an empty part.
    - A PUT whose `Content-Range` total differs from the offered size gets 416 (`putRejectsARangeBeyondTheOfferedSize`).
    - A GET's security-scoped access is released even when the phone disconnects before the response body starts (`AccessLease`; `leaseReleasesOnceWhenDroppedUnused`, `leaseReleasesOnceWhenEndedThenDropped`).
  - **App fixes:**
    - History rows cache whether the received file still exists, re-checked when the Files page appears.
    - The "Received" notification's Open/Show says "The file was moved or deleted" when it's gone.
    - Notification permission is read each time instead of being cached for the session.

  Verified: tests RED→GREEN, `swift test` 361; Mac app build, no code warnings; cross-platform test passed.
  - A MediaStore item still pending after `finish` is a failure, not Done.
  - A stale "Save … from your Mac?" notification says "That transfer is no longer waiting" instead of creating an empty document.
  - Home's Send says "Connect to your Mac first" while disconnected (it was silent).
  - Tapping a received file uses the off-main-thread existence check.
  - The SAF copy fallback creates its target as `application/octet-stream` when the name has an extension, so no second extension is added.

  Verified: `:core:test :app:testDebugUnitTest :app:assembleDebug`, no warnings.

## 2026-10-15 — Merged the calls minors to main
- **What:** `fix/calls-minors` merged into `main` locally as `73fb277` (`--no-ff`) at the owner's request. Not pushed.
- **Verified on `main`:** `swift test` 341; Mac app build (no code warnings); `:core:test :app:testDebugUnitTest :app:assembleDebug` (no warnings); cross-platform test passed.

## 2026-10-15 — Calls: the 8 deferred minors fixed (fix/calls-minors)
- **Owner:** calls from the Mac work (Answer, Decline, Hang up confirmed). The 8 deferred minors from the Plan 10 final review are now fixed:
  1. **The notice names the right action** when a call ends as Decline or Hang up is pressed (`theNoticeNamesTheActionEvenWhenTheCallEnded`).
  2. **A ringing call shows over a notice** and its sound plays at once; the 3 s notice timer restarts only when the notice changes (`aRingingCallShowsOverANotice`).
  3. **A phone link that launches MacDroid** waits up to 5 s for the phone to connect (`CallCenter.waitUntilSupported`, `waitsForThePhoneToConnect`).
  4. **Vanity numbers in links** are spelled on the keypad (`tel:1-800-FLOWERS` → `18003569377`); pasting still drops words (`telLinksSpellVanityNumbers`).
  5. **No crash from an unexpected error** in `CallBridge` or `NotificationBridge`; the Mac hears "not supported" or "failed".
  6. **Call panel hardening:** the panel can become key without activating MacDroid, and its view is set once.
  7. **No race on reconnect:** `ConnectionHub.events` gives status changes and messages in one ordered stream, and `CallCenter` reads it (`eventsKeepStatusAndMessagesInOrder`).
  8. **A notification that turns into a call** leaves the Mac's notification list, and a call that turns back into a notification ends as a call.
- **Verified:** `swift test` 341; Mac app and Android builds with no warnings; `:core:test :app:testDebugUnitTest`; cross-platform test passed twice.

## 2026-10-15 — Merged calls on the Mac (Plan 10) to main
- **What:** `feat/calls` merged into `main` locally as `c9ae68f` (`--no-ff`) at the owner's request, after they tried the dialer, phone links and the Settings fixes. Not pushed.
- **Verified on `main`:** `swift test` 336; Mac app build (no code warnings); `:core:test :app:testDebugUnitTest :app:assembleDebug` (no warnings); cross-platform test passed.
- **Open:**
  - the owner hasn't yet confirmed Answer / Decline / Hang up from the Mac (checklist items 3–7, 10–12)
  - 8 deferred minors in `docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md`

## 2026-10-15 — Plan 10: calls on the Mac (built on feat/calls)
- **Task 1:** on-device probe skipped (owner). The fallbacks in the plan cover it (label-matched buttons, error notices); background answer and dialing are checked in the owner checklist. Notes in `docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md`.
- **Task 2:** `call.state` / `call.action` / `call.dial` / `call.error` in both codecs, 5 fixtures, `calls` feature on both sides, PROTOCOL.md 'Calls rules'. Verified: `swift test` 318, `:core:test`, Mac app build.
- **Task 3:** Android core `CallTracker` (changes only, ended on removal, re-send on reconnect, actions → the notification's intents or `callGone`/`notSupported`), `CallNumbers`, `CallLabels`. Verified: `:core:test` (CallTrackerTest 14).
- **Task 4:** Mac core `CallCenter` (panel: notice → newest ringing → newest active; errors → notices; disconnect clears; dialing gated on `calls`), `PhoneNumbers` (clean, `tel:` links), `CallPresentation`. Verified: `swift test` 327.
- **Task 5:** Android call notifications → `CallTracker` (kept out of notification mirroring); Answer/Decline/Hang up fire the phone app's own intents; `call.dial` via `TelecomManager.placeCall` (`CALL_PHONE`); Settings → Calls. Verified: `:core:test :app:testDebugUnitTest :app:assembleDebug`, no warnings.
- **Task 6:** Mac call panel (non-activating, top right, sound on ring, timer, notices for 3 s), number pad on the Phone page, `tel:` links with confirmation, active call in the popover, Settings → Calls. Verified: Mac app build, no warnings.
- **Task 7:** cross-platform test: the phone's ringing call reaches the Mac, the Mac answers and dials, and the end arrives. Verified: `scripts/cross-platform-test.sh` passed twice; all gates green.
- **Task 8:** handoff updated (DECISIONS, STATUS, ROADMAP, README); owner checklist in the follow-ups file. Final gates: `swift test` 327 passed; Mac app build succeeded, no warnings; `:core:test :app:testDebugUnitTest :app:assembleDebug` passed, no warnings; cross-platform test passed. Not merged.
- **Final review fixes** (fresh reviewer; 4 Important fixed, each with a test that failed first):
  - **A stale action no longer loses the live call.** After a refused or failed action the phone sends the call's state again, and the Mac drops a call only for `callGone` (`aStaleActionResendsTheLiveCall`, `notSupportedKeepsTheCall`).
  - **Missed-call notifications** with only the call category stay in notification mirroring. Older call notifications count as calls only with Answer, Hang up or the ongoing flag (`olderCallNotificationsNeedAFlagOrAButton`).
  - **`tel:` links can't run phone codes.** Links containing `*` or `#` (call forwarding, USSD) are refused with "That doesn't look like a phone number"; the number pad still allows codes (`phoneCodesInLinksAreRefused`).
  - **No leftover calls after the notification listener rebinds** (`CallTracker.sync`, `syncDropsCallsThatWentAway`).
  - Verified: `swift test` 329; CallTrackerTest 17; Mac and Android builds with no warnings; cross-platform test passed.
- **Owner check, step 9:** "Use MacDroid for phone links" failed with "The file couldn't be opened".
  - **Cause:** macOS doesn't let a sandboxed app make itself the `tel:` handler. The same call from outside the app succeeded, and it made MacDroid the owner's handler.
  - **Fix:** the button is replaced by a line in Settings → Calls that says which app opens phone links, and how to choose MacDroid (`phoneLinksLine`, test RED→GREEN; `swift test` 330).
- **Owner feedback: a better dialer.** The cramped number pad on the Phone page is replaced by its own small **"Call"** window, opened from **Call…** on the Phone page or the phone icon in the popover:
  - a large centred number, with ⌫ (hold it to clear)
  - round 1–9 / `*` / 0 / `#` keys; hold 0 for `+`
  - a big green call button; dialing closes the window, and the call panel takes over
  - keyboard: digits, ⌘V paste (number only), Delete, Return to call, Esc to close
  - typing rules live in the tested `DialInput` (`swift test` 336)
- **Owner feedback: no "…" on buttons.** Removed the trailing ellipsis from all button labels (Call, Pair a Different Phone, Forget This Phone, Pair a Phone, Send Files, Muted Apps, Choose). The "Settings…" menu item keeps it, as macOS menus do. Verified: Mac app build.

## 2026-10-15 — Calls plan (Plan 10)
- **What:** wrote `docs/superpowers/plans/2026-10-15-macdroid-calls-10.md`, with 8 tasks:
  1. an on-device probe with the owner
  2. `call.*` protocol
  3. Android core `CallTracker`
  4. Mac core `CallCenter`
  5. Android call notifications, actions and dialing
  6. the Mac panel, number pad, links and settings
  7. the cross-platform test
  8. the handoff and checklist
- **Branch:** `main` (plan only); the work goes on `feat/calls`.

## 2026-10-15 — Spec: calls on the Mac (Plan 10)
- **What:** wrote `docs/superpowers/specs/2026-10-15-macdroid-calls-design.md` after brainstorming with the owner:
  - **Incoming:** a call panel on the Mac (name/number, Answer, Decline; Hang up during a call), driven by the phone app's call-style notification, which the notification listener already sees.
  - **Outgoing:** a number pad on the Phone page, and `tel:` links (with confirmation) via `TelecomManager.placeCall` (`CALL_PHONE`).
  - **Protocol:** a `calls` feature with `call.state` / `call.action` / `call.dial` / `call.error`.
  - **Audio** stays on the phone (Android doesn't allow otherwise); no auto-speaker (owner's choice A).
  - Task 1 is an on-device probe.
- **Branch:** `main` (spec only); the work goes on `feat/calls`.

## 2026-10-15 — Webcam parked
- **What:** brainstormed "phone as a Mac webcam" and parked it before a spec.
  - A system-wide camera needs a camera extension, which needs a paid Apple Developer Team ID (the owner plans to enrol later).
  - The owner's phone has no USB webcam mode.
  - The OBS bridge route is noted for later. Recorded in ROADMAP.md.
- **Branch:** `main` (docs only).

## 2026-10-15 — Plan 9 (automatic phone → Mac clipboard) dropped
- **What:** ran Plan 9's on-device probe with the owner. The control service saw System UI's copy preview (`clipboard_ui`, which shows `minimized_preview`, then `clipboard_preview`/`text_preview`) and could open the invisible reader from the background and read the clipboard (28–219 ms).
- **The problem:** every read shows Android's clipboard-access message and refreshes the screen twice (the reader opening and closing).
- **What we tried and dropped:** a floating one-tap "Send to Mac" button; the owner didn't like it either.
- **Outcome:** Plan 9 dropped. Phone → Mac stays manual (tile, notification button, Share). The spec and plan are kept with a "Dropped" note. No product code changed; `feat/auto-clipboard` (no commits) deleted; the normal build is reinstalled on the phone.

## 2026-10-15 — Automatic clipboard plan (Plan 9)
- **What:** wrote `docs/superpowers/plans/2026-10-15-macdroid-auto-clipboard-9.md`, with 7 tasks:
  1. an on-device probe with the owner
  2. `clipboard.set` `auto`
  3. Android core `CopyDetector` / `AutoClipboardPolicy`
  4. a silent Mac apply
  5. the service trigger, reader and Clipboard-tab switch
  6. the cross-platform test
  7. the handoff and checklist
- **Rulings in the plan:** the switch lives on the Clipboard tab, next to "Get Mac clipboard" (the phone's Settings has no Clipboard section); a copy is the preview appearing or showing different text, so two quick copies both count.
- **Branch:** `main` (plan only); the work goes on `feat/auto-clipboard`.

## 2026-10-15 — Spec: automatic phone → Mac clipboard (Plan 9)
- **What:** wrote `docs/superpowers/specs/2026-10-15-macdroid-auto-clipboard-design.md` after brainstorming with the owner. Copying text on the phone goes to the Mac by itself:
  - The "MacDroid control" service sees System UI's "copied" preview and briefly opens the invisible `ClipboardSendActivity` to read and send.
  - Text only; never sensitive items; no echo.
  - Silent on success, a toast on failure.
  - `clipboard.set` gets an optional `auto` field so the Mac skips its notification.
  - Task 1 is an on-device probe.
- **Constraint (owner):** the app must work with no developer options, debugging, adb grants or Shizuku.
- **Branch:** `main` (spec only); the work goes on `feat/auto-clipboard`.

## 2026-10-15 — Merged mirroring and remote control (Plans 7 and 8) to main
- **What:** `feat/mirroring` merged into `main` locally as `75ac85b` (`--no-ff`) at the owner's request, after they tried mirroring and control on their phone and Mac. Not pushed.
- **Verified on `main`:** `swift test` 317; Mac app build (no code warnings); `:core:test :app:testDebugUnitTest :app:assembleDebug` (no warnings); cross-platform test passed.
- **Open:** the owner checklists, the deferred minors and the volume ruling in `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`.

## 2026-10-15 — Decision: no locked or screen-off mirroring
- **What:** the owner asked about iPhone-style mirroring (phone locked, showing "in use"). On Android a normal app can't do that: capture ends on lock, and a cover on the phone would be captured too. Screen-off mirroring through Shizuku (as scrcpy does) was offered and declined. Mirroring stays as built, with the phone unlocked and its screen on. Recorded in DECISIONS.md.
- **Branch:** `feat/mirroring` (docs only). Also installed the current build on the owner's phone (`installDebug` over Wireless debugging) and Mac.

## 2026-10-15 — Plan 8: remote control (built on feat/mirroring)
- **Task 1:** `input.pointer/scroll/key/text` and `input.notice` (a ruling: carries "Click a text field on the phone first") in both codecs, 5 fixtures, `control` feature on both sides, PROTOCOL.md "Remote control rules".
- **Task 2:** Android core control rules: `GestureTracker` (tap, long-press, continued drag strokes), `SegmentQueue` (coalescing), `ScrollGesture`, `TextEdit` (cursor, selection, whole-emoji delete), `ConsentMatcher` (only MacDroid's screen-share consent), `MirrorPhoneSession.awaitingConsent` and `controlChanged`. Verified: `:core:test` (ControlRulesTest 11, MirrorRulesTest 10).
- **Task 3:** Mac core input: `InputMapper` (aspect-fit fractions, nothing in the black bars, scroll fractions), `MoveThrottle` (≤ 120 Hz), `MirrorKeys`, `MirrorSession.sendInput` gated on streaming + `control` + the phone's service, and the control/text-field notices. Verified: `swift test` 316, Mac app build with no warnings.
- **Task 4:** Android 'MacDroid control' accessibility service: gestures with continued strokes and coalescing, Back/Home/Recents/lock, volume, text into the focused field (or `input.notice`), auto-Start of the screen-share consent while a session waits; `ScreenSize`; Settings → Remote control (service status, auto-Start switch, microphone); `STORE_BUILD` flag; `control` reported in `mirror.state` and on changes. Verified: `:core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings; on-device check is in the owner checklist.
- **Task 5:** Mac input over the mirror picture: click/drag as pointer events (≤ 120 Hz, nothing in the black bars, lift when leaving), right-click Back, scroll batched every 33 ms, typing in 30 ms batches, ⌘V paste, Delete/Return/Esc; Back/Home/Recents/Volume/Lock bar buttons; control and text-field notices; ordered sending through one stream. Verified: `swift test` 316, Mac app build with no code warnings.
- **Task 6:** cross-platform test covers remote control: the Mac sends pointer, scroll, key and text only once the phone reports control on; the phone's gesture rules turn the click into a tap; the phone's `input.notice` reaches the Mac. Verified: `scripts/cross-platform-test.sh` passed twice; all gates green.
- **Task 7:** handoff updated (ARCHITECTURE, DECISIONS, DEV-GUIDE, STATUS, ROADMAP, README); owner checklist added to `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`. Final gates: `swift test` 316 passed; Mac app build succeeded with no code warnings; `:core:test :app:testDebugUnitTest :app:assembleDebug` passed with no warnings; cross-platform test passed. Not merged: the owner checks Plans 7 and 8 together.
- **Final review fixes** (fresh reviewer; one Critical, five Important fixed, each with a test that failed first):
  - **Scrolling could tap:** a wheel notch or slow scroll made a swipe under Android's touch slop (a click), and every 33 ms batch queued its own 120 ms swipe. Now the phone's `ScrollBuffer` adds batches up while a swipe plays and never sends one under 3 % of the screen; the Mac scales wheel lines to 40 points (`tinyScrollsNeverBecomeATap`, `scrollBatchesMerge`, `aStaleScrollRemainderIsForgotten`, `wheelLinesScaleToPoints`).
  - **Stuck finger:** a session ending mid-drag or mid-long-press now lifts the finger (`MirrorPhoneSession` `onInputEnd` → `MacDroidControlService.reset()`; the long-press timer re-checks the session) (`leavingStreamingEndsInput`).
  - **Auto-Start:** the consent container must name MacDroid (System UI reuses it for its recorder and Cast), and the fallback needs "… with MacDroid", so the shade can't match (`consentNeedsMacDroidNamedInTheDialog`).
  - **Passwords:** password fields read back as bullets; typing now edits what MacDroid typed there (`SecretShadow`), and leaves a field with unknown contents alone (`passwordsAreEditedFromWhatWasTyped`).
  - **Return** in a single-line field presses the keyboard action (search, send) instead of adding a newline (`returnSubmitsSingleLineFields`).
  - **Tap position:** `ScreenSize` uses logical pixels (`maximumWindowMetrics`), so a lowered screen-resolution setting doesn't shift taps.
  - Verified: `swift test` 317; Mac app build; Android gates with no warnings; cross-platform test passed.

## 2026-10-14 — Remote control plan (Plan 8)
- **What:** wrote `docs/superpowers/plans/2026-10-14-macdroid-remote-control-8.md` (7 tasks): `input.*` protocol (+ `input.notice`, a ruling), Android core gesture/text/consent rules, Mac core input mapping and gating, the "MacDroid control" accessibility service with auto-Start and Settings → Remote control, the Mac input view and bar buttons, cross-platform, final checklist. Auto-Start also answers the owner's "it asks for Share screen every time".
- **Branch:** `feat/mirroring` (plan only).

## 2026-10-14 — Mirroring: lower audio delay (owner report)
- **What:** the owner heard the Mac's sound lag. Diagnostics showed the Mac's audio queue sitting near 100 ms instead of the 40 ms target, plus an 80 ms capture buffer on the phone. Now:
  - Past 80 ms queued, the Mac drops chunks until it is back at 40 ms (test `aFullQueueCatchesUpToTheTarget`).
  - The phone's playback-capture buffer is Android's minimum (at least two 10 ms chunks).
- **Also found:** the earlier "sound only on the phone" was Bluetooth earbuds plus the volume being raised again. Sound plays on the Mac once the phone is on its speaker with mirroring running. The owner's Wi-Fi is 2.4 GHz (channel 6, 802.11n) and was congested during testing: Mac → router ping averaged 339 ms. Retest on a calm network or 5 GHz.
- **Branch:** `feat/mirroring`. Verified: `swift test` 306, Mac app build, Android build; installed on both devices.

## 2026-10-14 — Plan 7: mirroring (in progress on feat/mirroring)
- **Task 1:** on-device probe with the owner (debug source set only); results in `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`:
  - background start needs the accessibility service
  - auto-Start presses "Share screen" (`android:id/button1`) on an unlocked phone
  - capture ends when the phone locks, and the lock screen can't be captured
  - playback capture works with the phone muted
- **Task 2:** `mirror.*` control messages (start, state, stop, keyframe, ping, pong) in both codecs, 7 fixtures, `mirror` feature on both sides. Verified: `swift test` 275, `:core:test`, `assembleDebug`.
- **Task 3:** media frame header (`MirrorFrame`) on both sides with the shared `mirror-frames.json` fixture; PROTOCOL.md "Mirroring media stream".
- **Task 4:** Mac core `H264` (Annex-B split, SPS/PPS → format, AVCC sample buffers marked display-immediately) and the shared `h264/sample.h264` fixture (ffmpeg, 320×240 then 240×320, parameter sets once per half like MediaCodec), made by `scripts/make-h264-fixture.sh`.
- **Task 5:** Mac core mirroring timing (monotonic clock, clock-offset median of 5, latency meter over 30 frames), `VideoBacklog` (drop to the next key frame past 3 waiting) and `AudioJitter` (start at 40 ms, skip over 100 ms, rebuild after running dry).
- **Task 6:** Mac core `MirrorSession` (start/stop, 20 s consent hint, 60 s timeout, ping/pong offset, end reasons, disconnect) and the `/v1/mirror` route gated by `X-MacDroid-Session`; the WebSocket max frame is now 4 MiB with control messages still capped at 1 MiB; `swift-http-types` declared explicitly in `Package.swift` (already resolved through Hummingbird).
- **Task 7:** Android core `MirrorSize` (fit to maxSize, multiples of 16, bitrate), `SendPolicy` (drop video to a key frame past 300 ms queued, audio past 200 ms; ruling: time at the bitrate instead of the spec's 1 MB, which is a second of lag) and `MirrorPhoneSession` (states, replace, 60 s consent timeout, Mac stop, lost connection).
- **Task 8:** Android mirroring:
  - the consent notification and activity (wakes the screen, microphone permission, entire screen only)
  - the `mediaProjection` foreground service holding a screen wake lock (capture ends on lock, from the probe)
  - the H.264 surface encoder (CBR, no B-frames, key frame on request, rebuilt on rotation)
  - playback-capture audio with the phone's speaker muted meanwhile (restored at the end)
  - the pinned `/v1/mirror` stream with one reconnect and the send policy
  - Verified: Android gates; capture is checked on device in the owner checklist.
- **Task 9:** Mac mirror window (aspect-locked to the phone, remembered frame, Keep on top, latency readout, Stop), `MirrorRenderer` (hardware decode, display immediately, drop to key frame, resync on a new format), `MirrorAudioPlayer` (40 ms cushion, skip ahead), `MirrorPresentation` copy, Mirror buttons on the Phone page and popover, the "Update MacDroid on your phone" line, and Settings → Mirroring (Quality, Frame rate, Phone audio, Show latency).
- **Task 10:** cross-platform test covers mirroring: the Mac starts a session, a wrong session id is refused, the H.264 fixture is decoded on the Mac (20 frames, 4 key frames), 3 audio chunks arrive, a key-frame request reaches the phone, and the phone's stop is seen. Passed twice.
- **Task 11:** probe removed (debug source set); handoff updated (ARCHITECTURE mirror components, DECISIONS, DEV-GUIDE, STATUS, ROADMAP, README) and the owner checklist in the follow-ups file. Gates: `swift test` 301, Mac app build with no warnings, Android gates with no warnings, cross-platform test passed.
- **Final review (Opus), 6 Important fixed:**
  - I1: clicking Mirror while mirroring brings the window forward instead of restarting (which silenced audio).
  - I2: the Mac stops reading a media stream as soon as its session ends, and clears the picture on stop.
  - I3: a lost key-frame request is repeated: the phone asks once more when its queue drains, and the Mac asks again every 30 dropped frames.
  - I4: the media socket uses a 128 KB send buffer and TCP_NODELAY, so a backlog stays visible to the send policy.
  - I5: no frame for over a second shows "Reconnecting…" and hides the latency.
  - I6: capture setup failures end the session instead of crashing the app; `onDestroy` cleans up; the muted volume is persisted and restored at the next app start if the app was killed.
  - Verified: `swift test` 305, Android gates, Mac app build with no warnings, cross-platform test passed.
  - 9 minors deferred in `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`.

## 2026-10-14 — Mirroring plan (Plan 7)
- **What:** wrote `docs/superpowers/plans/2026-10-14-macdroid-mirroring-7.md` (11 tasks):
  1. an on-device probe with the owner
  2. protocol
  3. the media frame header
  4. H.264 packaging with an ffmpeg-made fixture
  5. Mac timing, backlog and audio jitter
  6. `MirrorSession` and the `/v1/mirror` route
  7. Android size, send policy and phone session
  8. Android capture service, encoder, audio and stream
  9. the Mac window, video and audio
  10. cross-platform
  11. probe removal and checklist
- **Rulings the plan makes:**
  - consent via a "Tap to start" notification until Plan 8's accessibility service lifts Android's background-start block
  - entire screen only on API 34+
  - sender thresholds in time (300 ms video, 200 ms audio) instead of 1 MB
  - latency measured with the built readout
- **Branch:** `main` (docs only). Execution goes on `feat/mirroring`; Plan 8 (remote control) follows.

## 2026-10-14 — Screen mirroring and remote control spec (Plans 7 and 8)
- **What:** brainstormed with the owner and wrote `docs/superpowers/specs/2026-10-14-macdroid-mirroring-control-design.md`:
  - mirroring with phone audio (choice B: use apps and watch videos) in its own Mac window (A)
  - a dedicated mutual-TLS media WebSocket (H.264 hardware codecs, no B-frames, drop to key frame, PCM audio); input on the control WebSocket
  - remote control through an accessibility service (A: no debugging), including auto-Start of Android's capture consent so the phone can be reached asleep or locked (B)
  - latency targets: 50–80 ms screen to Mac, under 50 ms click to phone, with a readout
  - an on-device test first on the owner's phone
- **Why:** the owner's agreed next round; remote control depends on the mirror, so mirroring is Plan 7 and control is Plan 8 on one branch.
- **Branch:** `main` (docs only). Self-reviewed (Delete key folded into the protocol, accessibility scope stated, Mac-side catch-up drops to key frames, monotonic clocks for latency).

## 2026-10-14 — Photos follow-ups merged to main
- **What:** merged `fix/photos-minors` into `main` (`2807ce9`) at the owner's request: the 9 deferred photos minors (M1–M9, below).
- **Verified:** the merged tree is identical to the branch, where `swift test` (274), the Mac app build, the Android gates and the cross-platform test passed; both apps are installed on the owner's devices.
- **Next (agreed order):** screen mirroring (low latency), view-only first; then remote control.

## 2026-10-14 — Photos follow-ups (the 9 deferred minors, on fix/photos-minors)
- **Verified (whole branch):** `swift test` 274, Mac app build with no warnings, Android `:core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings, `./scripts/cross-platform-test.sh` passed. Installed on the owner's phone and Mac.
- **M9:** the Mac keeps only thumbnails it asked for (and hasn't given up on); unrequested ones are dropped. Test `unrequestedThumbnailsAreDropped`.
- **M2:** concurrent cache fetches of the same photo (double-click plus Space, a drag during a preview) share one transfer. Test `concurrentCacheFetchesOfOnePhotoShareATransfer`.
- **M1:** a photo fetch whose bytes stop moving for 30 s (not while verifying a finished file) now fails instead of spinning; forgetting a fetch (timeout, stall) also cancels a transfer that slipped in meanwhile. `PhotoTransfers.isActive` became `progress` (done, size); `TransferManager.photoProgress`, async `forgetPhoto`. Tests `aStalledTransferFailsAndIsForgotten`, `forgettingAPhotoCancelsItsTransfer`; `aStartedTransferDoesNotTimeOut` now moves its bytes.
- **M6:** Forget now stops every photo transfer, clears expected photo ids and deletes the cached originals (`TransferManager.forgetAllPhotos`); `PhotosClient.reset` also answers pending fetches instead of leaving them to time out. Tests `forgettingAllPhotosEmptiesTheCache`, `resetAnswersPendingFetches`.
- **M8:** on the phone, photos the Mac fetches have their own 3 upload slots, so a phone busy sending files still answers within the Mac's 30 s. PROTOCOL.md transfer rules updated. Test `photosHaveTheirOwnSlots`.
- **M7:** `enqueueClipboard` got its KDoc back from `enqueueWithId` (comment only).
- **M4:** after a photos permission prompt (setup step or Settings), a limited-access user who picked a different set now makes the Mac reload, even though the level stays "Selected". Core `PhotoAccessWatcher` (tested) replaces the bridge's inline check. Tests `PhotoAccessWatcherTest`.
- **M5:** Settings → Photos sends you to the app's settings only when nothing was granted and Android won't show the prompt again (`shouldShowRequestPermissionRationale`), not after a first "Don't allow". Test `settingsOnlyWhenAndroidStopsAsking`.
- **M3:** the phone watches only the Images and Video MediaStore collections for `photos.changed`, so downloads and files received from the Mac no longer reload the Mac's Photos page. Glue (no JVM test); verified by the build and on the device.

## 2026-10-14 — Photos merged to main
- **What:** merged `feat/photos` into `main` (`37b425c`) at the owner's request: Plan 6 photo browsing and transfer with the final-review fixes.
- **Verified:** the merged tree is identical to `feat/photos`, where `swift test` (268), the Mac app build, the Android gates and the cross-platform test passed; both apps were installed on the owner's devices.
- **Next:** the 9 deferred minors from `docs/superpowers/plans/2026-10-13-macdroid-photos-followups.md`, on `fix/photos-minors`.

## 2026-10-14 — Plan 6: photos (in progress on feat/photos)
- **Task 1:** photos messages (`photos.albums/albumList/query/page/thumbs/thumb/fetch/fetchFailed/changed`) and `purpose: photo` in both codecs, 10 fixtures, `photos` feature on both sides, PROTOCOL.md "Photos rules". Verified: `swift test` 234, `:core:test`, Mac app and `assembleDebug` build.
- **Task 2:** Android core `PhotoQueries` (cursor, SQL selection by `date_added`/`_id`, limits, id checks, access level) and `TransferEngine.enqueueWithId`; photo transfers are hidden like clipboard ones (`isHidden`). Verified: `:core:test`.
- **Task 3:** Mac `TransferManager` photo destinations: only expected transfer ids are accepted (others rejected `invalid`); the cache is hidden and cancelled on disconnect; the save folder is visible and never asks; `photoReceived` / `photoFailed` events; `hidden`/`isHidden` generalises clipboard hiding. Verified: `swift test` 240, Mac app build.
- **Task 4:** Mac core `ThumbnailCache` (digits-only ids, 200 MB LRU), `PhotosPresentation` (day groups, copy), `PhotoSelection` (click / ⌘ / ⇧) and the `PhotosClient` actor (paging with a stale-response guard, merge on change, thumbnail de-dup with 20 s expiry, fetches three at a time with previews first and a 30 s no-offer timeout). Verified: `swift test` 265 (timing tests ×5), Mac app build.
- **Task 5:** Android core `PhotoService` answers albums, pages, thumbnails and fetches from a `PhotoSource`; nothing is read without access; invalid photo and transfer ids are refused. Verified: `:core:test`.
- **Task 6:** Android `PhotoLibrary` (MediaStore paging by `date_added`/`_id`, albums by bucket, 256 px JPEG thumbnails via `loadThumbnail`) and `PhotosBridge` (requests queued to one worker, `ContentObserver` debounced to `photos.changed`); media read permissions in the manifest; `AppGraph.photosBridge`. Verified: Android gates.
- **Task 7:** Android setup step "See your photos on your Mac" (first run only; setup is now 7 steps), Settings → Photos (All photos / Selected photos / Off; tapping asks again or opens app settings), access re-checked on resume. Verified: Android gates.
- **Task 8:** Mac Photos page (album column, day-grouped 96 pt grid, click / ⌘ / ⇧ / ⌘A selection, selection bar, Space or double-click Quick Look, single-item drag-out, Save to Mac, dimmed while disconnected), the Phone page "Photos" line, and the photo cache emptied at launch. Verified: `swift test`, Mac app build with no warnings.
- **Task 9:** cross-platform test covers photos: page, thumbnail, save to the receive folder over the transfer engine, and `gone`. Verified: `./scripts/cross-platform-test.sh` passed twice.
- **Task 10:** handoff updated (ARCHITECTURE photos components and fetch flow, DECISIONS, DEV-GUIDE photos access, STATUS, ROADMAP, README round table) and the owner checklist in `docs/superpowers/plans/2026-10-13-macdroid-photos-followups.md`. Gates: `swift test` 265, Mac app build with no warnings, Android `:core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings, cross-platform test passed.
- **Final review (Opus), 5 Important fixed:**
  - I1: a change with more than a page of new photos no longer leaves a hole before the old items (start over instead of merging).
  - I2: the phone runs thumbnails in their own lane (`PhotoLanes`), so a fetch never waits behind a thumbnail backlog.
  - I3: page queries are capped in the loop too (older MediaProviders may ignore `QUERY_ARG_LIMIT`).
  - I4: photo saves are cancelled on disconnect like previews; the phone drops them on restart anyway.
  - I5: day groups are computed only when items change, thumbnail arrivals are batched every 100 ms, and the main thread no longer writes cache mtimes.
  - Verified: `swift test` 268, Android gates, Mac app build with no warnings, cross-platform test passed.
  - 9 minors deferred in `docs/superpowers/plans/2026-10-13-macdroid-photos-followups.md`.

## 2026-10-13 — Photos plan (Plan 6)
- **What:** self-reviewed the photos spec (§9, `06c1f71`) and wrote the implementation plan `docs/superpowers/plans/2026-10-13-macdroid-photos-6.md`. It has 10 tasks:
  1. protocol
  2. Android core queries and engine
  3. Mac transfer destinations
  4. Mac `PhotosClient`, cache and presentation
  5. Android `PhotoService`
  6. MediaStore library and bridge
  7. Android setup and settings
  8. the Mac Photos page
  9. the cross-platform test
  10. gates and checklist
- **Rulings the plan makes against the spec:**
  - Ordering and paging use `DATE_ADDED`, then `_ID`, instead of `DATE_TAKEN`. `DATE_TAKEN` can be null, and MediaProvider rejects `COALESCE` in sort orders.
  - The Mac asks for at most 3 originals at a time, with previews first. The phone offers at most 3 uploads at once, so more would trip the 30 s timeout.
  - The spec's `PhotoCursor` / `PhotoRequests` / `PhotoFetches` are named `PhotoQueries`, `PhotoService` and `TransferManager.expectPhoto`.
- **Branch:** `main` (docs only). Execution goes on `feat/photos`.

## 2026-10-13 — Photos spec (Plan 6)
- **What:** brainstormed and wrote `docs/superpowers/specs/2026-10-13-macdroid-photos-design.md`: browse the phone's photos and videos on the Mac and transfer them. It covers:
  - full or limited Android access, with the Mac showing what's granted
  - an album column and a day-grouped grid (layout B)
  - thumbnails over the WebSocket; originals over the transfer engine (`purpose: photo`, the Mac chooses the transfer id, unasked offers rejected)
  - cursor paging; Save to Mac, Quick Look, drag-out with file promises; live updates
- **Branch:** `main` (no code). Implementation goes on `feat/photos`.

## 2026-10-13 — Small wins merged to main
- **What:** merged `feat/small-wins` into `main` (`c9d202d`): ring my phone, links both ways, media controls, the final-review fixes and the owner's play/pause and previous fixes.
- **Verified on the merged `main`:** `swift test` 233, Mac app build with no warnings, Android gates. The owner tested on device.
- **Deferred:** 11 minors in `docs/superpowers/plans/2026-10-13-macdroid-small-wins-followups.md`.
- **Next (agreed order):** photo browsing and transfer.

## 2026-10-13 — Plan 5: small wins (done on feat/small-wins)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-small-wins-5.md` (executed inline).
- Task 1: `device.ring`, `device.ringState`, `link.open`, `media.state`, `media.art` and `media.command` in both codecs (7 fixtures); features `ring`, `links`, `media`. Verified: `swift test` 218, Android gates.
- Task 2: mirrored link rules, Swift `LinkRules` and Kotlin `LinkExtractor` (first http/https link, trailing punctuation trimmed, ≤ 8 KB). Verified: 4 tests on each side.
- Task 3: Android core `RingSession` (one ring, 30 s timeout) and `MediaTracker` (session choice, change detection, art once per connection, commands to the shown app). Verified: 11 tests.
- Task 4: Mac core `PhoneExtras` (ring state, media state + art cache, incoming links validated, Open on Phone results) and `MediaPresentation` (position projected from the Mac's receive time). Verified: `swift test` 232.
- Task 5: Android `Ringer`: alarm tone at full alarm volume (restored after), torch flash, volume keys caught by a temporary MediaSession, a Stop notification, 30 s, and it stops on a disconnect. Runs in `ConnectionService`. Verified: Android gates.
- Task 6: Android "Open on Mac" share target (`OpenOnMacActivity` + pure `OpenOnMacDecision`) and tap-to-open "Link from your Mac" notifications. Verified: `OpenOnMacDecisionTest`, Android gates.
- Task 7: Android `MediaWatcher` (media sessions via the notification listener; artwork encoded off the main thread; Mac commands applied to the shown app). Verified: Android gates.
- Task 8: Mac Services "Open on Phone" (`ServicesProvider` + `NSServices` in project.yml), ring button (popover + Phone page), Now Playing card with seek, incoming links open in the default browser. Verified: Mac app build; NSServices present in the built Info.plist.
- Task 9: the cross-platform run covers a ring round trip, a link each way, and media state → a pause command. Verified: `./scripts/cross-platform-test.sh` passed.
- Task 10: all gates pass from scratch (`swift test` 232, Mac app build, Android with `--rerun-tasks`, cross-platform). Owner checklist: `docs/superpowers/plans/2026-10-13-macdroid-small-wins-followups.md`.
- Final review fixes:
  - Links keep a closing bracket they opened (Wikipedia `Mercury_(planet)`). Verified: `balancedBracketsStay` RED→GREEN on both sides.
  - A paused media session is hidden after 5 minutes (`MediaTracker.tick` every 60 s). Verified: `aLongPausedSessionIsHiddenOnTheNextTick` RED→GREEN.
  - Media updates go through one ordered lane, and the artwork cache keeps 8 tracks. Verified: build.
  - 11 minors deferred to the follow-ups file.
- Owner feedback fixes:
  - **Play/pause icon was stale on the Mac.** Logs showed the Mac received every play/pause change, but the popover's Now Playing card never redrew from the shared model. It now gets the state passed in as a value, so every change redraws it.
  - **⏮ restarted the song instead of going back** (Media3 apps treat "previous" after 3 s as a restart). Past 3 s the phone now seeks to 0, then skips to previous (`PreviousTrack`). Verified: `rewindsFirstOnlyPastTheRestartWindow` RED→GREEN, all gates.

## 2026-10-13 — Plan 5 (small wins) written
- **What:** `docs/superpowers/plans/2026-10-13-macdroid-small-wins-5.md`, 10 tasks:
  - protocol; link rules on both sides
  - Android core ring/media logic; Mac core `PhoneExtras`
  - Android ringer, links, media watcher
  - Mac Services/popover/Now Playing
  - the cross-platform run; final
- **Ruling recorded in the plan:** the Mac projects the media position from its own receive time (clock skew between devices).
- **Branch:** `main` (docs only).

## 2026-10-13 — Small wins spec (Plan 5): ring, links, media controls
- **What:** brainstormed and wrote `docs/superpowers/specs/2026-10-13-macdroid-small-wins-design.md`:
  - ring my phone (loud on silent plus flashing torch, stopped many ways)
  - open links both ways (share sheet "Open on Mac"; Mac Services → "Open on Phone"; incoming links on the phone become a tap-to-open notification)
  - a Now Playing card with controls and seek in the Mac popover, using media sessions through the existing notification access
- **Why:** step 2 of the agreed order.
- **Branch:** `main` (no code). Implementation goes on `feat/small-wins`.

## 2026-10-13 — Deferred minors round (fix/review-minors)
The 9 minors from the Plans 3–4 final review, fixed one by one on `fix/review-minors`.
All 9 fixed. All gates pass from scratch, plus the cross-platform run.
Merged to `main` (`bd6c661`), and the new builds are installed on the owner's phone and Mac.
- Minor 1: clipboard bounce: the Mac records its own pasteboard writes in the watcher at write time (`PasteboardChangeTracker`), so the watcher never reports them as a copy. Verified: `ownWritesAreNeverReported` RED→GREEN, `swift test` 214, Mac app build.
- Minor 2: clipboard images are sent from a copy in `Clipboard/outgoing/` (`ClipboardHistory.outgoingCopy`; only the newest is kept), so Clear History or a trim no longer breaks a transfer in flight. Verified: `sentImagesAreCopiesThatSurviveClearingHistory` RED→GREEN, `swift test` 215.
- Minor 6: history Send applies the same 1 MB frame check and note as a fresh copy. Verified: `oversizedHistoryTextIsNotResent` RED→GREEN.
- Minor 9: a Mac image that couldn't be queued is removed from the history instead of showing "→ phone"; the empty Clipboard page now says "Items you sync between your Mac and phone appear here." Verified: `failedImageSendIsNotRecorded` RED→GREEN, `swift test` 217, Mac app build.
- Minor 4: a copy that includes plain text sends the text (Office/iWork tables, rich text); image-only copies and "Copy Image" (image + link) still send the image. Verified: updated `filesAreIgnoredImagesBeatTextAndUnknownIsIgnored` RED→GREEN, `swift test` 217.
- Minor 5: the first banner in a new notification category waits (≤ 0.5 s) until macOS has registered the category, so its buttons and reply field show. Verified: Mac app build (no harness for UserNotifications).
- Minor 7: an update held back by the flood gate quietly refreshes a banner that is still in Notification Center (passive, no sound), so it never shows stale text. Verified: Mac app build.
- Minor 8: the Mac converts copied TIFF images to PNG off the main thread (PNG copies are used as they are). Verified: Mac app build.
- Minor 3: Android: notification access checks, the "Show on your Mac" switch and per-app switches now run on the bridge worker (they can render app icons), and clipboard image cleanup runs on IO, so none of it touches the main thread. Verified: Android gates.

## 2026-10-13 — Owner's feature list and agreed order
- **What:** recorded the owner's planned features (iPad file transfer, photo browsing, low-latency mirroring, remote control, contact sync, webcam and mic over Wi-Fi and USB) and the agreed order in ROADMAP. The order: the 9 deferred minors one by one, small wins, photo browsing, mirroring then remote control.
- **Branch:** `main`. No code changed.

## 2026-10-13 — Finder Share extension parked
- **What:** brainstorming for the Finder Share extension started, then the owner parked it. The sandbox constraint, the three approaches and the open question ("where should Send to phone appear?") are recorded in ROADMAP so a later session can resume.
- **Branch:** `main`. No code changed.

## 2026-10-13 — Notifications and clipboard merged to main
- **What:** merged `feat/notifications-clipboard` into `main` (merge commit `db85323`): Plan 3 (notifications), Plan 4 (clipboard), the final-review fixes and the owner's UI feedback fixes.
- **Verified on the merged `main`:** `swift test` 212, Mac app build with no warnings, Android gates. The owner verified the hands-on checks and asked for the merge.
- **Deferred:** 9 minors, listed in `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`.
- **Next:** the Finder Share extension (small round), or a short planning session to rank the later rounds.

## 2026-10-13 — Owner feedback: Mac window layout, notification cards, toolbar buttons
- **Fix: window content slid under the title bar** (the sidebar was behind the traffic lights and banners over the title) until a restart. Cause: the Dock icon was turned on (`setActivationPolicy(.regular)`) after the window was already showing, and AppKit's re-layout lost the split view's title-bar insets. Now `DockPolicy.prepareToShowWindow()` switches first, then opens the window.
- **Fix: clicking a notification banner opened the Phone page.** The click also sends a "reopen", which always showed Phone. Reopen now shows the page last asked for (`selectedSection`), so the Notifications page stays.
- **Notifications page redesign:**
  - cards with the app icon, title, time and text
  - bordered buttons for the app's actions
  - an inline reply composer (Return sends, Esc cancels, "Reply sent" confirmation)
  - ✕ on hover
  - group headers with a count badge and a hover "Clear" for one app
- **Toolbar buttons are icons with tooltips** (no more text pills): Notifications "Muted Apps…" and "Clear All"; the Clipboard page's "Sync to phone: On" is now a real "Send to Phone" toggle.
- **Verified:** Mac app build with no warnings; `swift test` 212. The UI is checked by the owner (no UI test harness).
- **Branch:** `feat/notifications-clipboard`.

## 2026-10-13 — Final review fixes (feat/notifications-clipboard)
- **Newer clipboard content always wins (both directions).** A clipboard image still queued or in flight is cancelled when newer content is sent (text or image), when the connection drops, and on relaunch (never restored). Before, an old screenshot could land after a newer copy and overwrite it.
  - Mac: `TransferManager.cancelClipboardTransfers`, `ClipboardSync(cancelImages:)`.
  - Android: `TransferEngine.cancelClipboard`, `ClipboardHub(cancelImages:)`.
  - Verified: RED→GREEN `aNewClipboardImageCancelsTheOlderOne`, `cancellingClipboardTransfersLeavesFilesAlone`, `disconnectingCancelsOutgoingClipboardTransfers`, `clipboardTransfersAreNotRestoredAfterARelaunch`/`…Restart` (both sides) and `sendingTextCancelsPendingImages` (both sides). All gates pass.
- **"Copy to Mac" only reads images other apps shared with it.** The share target is exported, so it now refuses `file:` URIs and MacDroid's own FileProvider (which serves its private clipboard files). Verified: `SharedImageSourceTest` RED→GREEN.
- **Styled shared text works.** "Copy to Mac" reads `EXTRA_TEXT` as a `CharSequence`; before, styled text arrived as "Clipboard is empty". Verified: Android build only (no instrumentation harness).
- **Deferred:** 9 minor findings, listed in `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`. All gates and the cross-platform run pass (`swift test` 212).

## 2026-10-13 — Plan 4: clipboard (done on feat/notifications-clipboard)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-clipboard-4.md` (executed inline).
- Task 1: `clipboard.set`, optional `transfer.offer.purpose`, `ClipboardLimits`; both sides now announce `clipboard`. Verified: `swift test` 181, Android gates.
- Task 2: Mac `TransferManager` handles `purpose: clipboard` (fixed clipboard folder, never asks, hidden from snapshots, dropped not archived, `clipboardImageReceived` event, `sendClipboardImage`). Also commits the regenerated `Info.plist` (0.4.0 / 4). Verified: `swift test` 186, Mac app build.
- Task 3: Android `TransferEngine` handles `purpose: clipboard` (clipboard target, never archived, `ClipboardReceived` event, `enqueueClipboard`, `TransferSnapshot.files()`). Verified: Android core tests.
- Task 4: Mac core `ClipboardRules` (private/transient/file types never sent, frame-size limit), `ClipboardHistory` (10/20/50, JSON + image files), `ClipboardSync` actor (echo prevention by change count), `ClipboardPresentation`. Verified: `swift test` 207.
- Task 5: Android core `ClipboardHub` (send results, frame-size limit, received items, recent 10). Verified: 8 new tests.
- Task 6: Mac `PasteboardWatcher` (0.5 s change-count poll, PNG/TIFF → PNG), `ClipboardNotifier`, `ClipboardSync` wired to the hub, transfers (clipboard folder `ClipboardIncoming`) and settings. Verified: Mac app build.
- Task 7: Mac Clipboard page (search, direction, Copy/Send on hover) and Settings → General → Clipboard (send, receive, history size, Clear History). No "coming soon" sections left on the Mac. Verified: Mac app build.
- Task 8: Android clipboard: receive into `ClipboardManager` (images via a FileProvider), invisible `ClipboardSendActivity`, Quick Settings tile, "Send clipboard" notification action, "Copy to Mac" share target; clipboard transfers hidden from Files and the progress notification. Verified: Android gates (`ClipboardPresentationTest`).
- Task 9: Android Clipboard tab (Send now, "Get Mac clipboard", Recent with tap to copy again). Verified: Android gates.
- Task 10: the cross-platform run covers clipboard text both ways and a clipboard image to the Mac. Verified: `./scripts/cross-platform-test.sh` passed.
- Task 11: all gates pass from scratch (`swift test` 207, Mac app build, Android with `--rerun-tasks`, the cross-platform run); the clipboard checklist is appended to the follow-ups file.

## 2026-10-13 — Plan 3: notifications (done on feat/notifications-clipboard)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md` (spec: `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`). Executed inline (the owner chose "native").
- Task 1:
  - `notification.*` messages (10 fixtures) in both codecs; `hello` without `features` decodes as none; `ProtocolInfo.features` / `Features.SUPPORTED` = files, notifications.
  - Also fixed a flaky Android test: `pausedUploadIsRetriedWhileConnected` raced its 300 ms periodic resume. That interval is now 1 s; the test failed 1 in 5 on `main`.
  - Verified: `swift test`, `./gradlew :core:test`.
- Task 2: both sides announce `files`, `notifications`; the Mac hub forwards every feature message; Android `Connected` carries the Mac's features (`macSupports`). Verified: `swift test` 150, Android gates.
- Task 3: Android core `NotificationForwarder`: exclusions, update de-dupe, mutes owned by the phone, apps seen with daily counts, sync of still-active keys, icons once per connection. Verified: 18 new tests.
- Task 4: Mac core `NotificationStore` (50, grouped by app), `FloodGate` (10 per 5 s per app), `NotificationCategories`. Verified: `swift test` 158.
- Task 5: Mac core `PhoneNotifications` actor (entries, apps, banners via the flood gate, sync, errors, mute requests), `IconCache` (hash-validated file names), `NotificationPresentation`. Verified: `swift test` 179.
- Task 6: Mac `NotificationRouter` (the only notification delegate; received-file and phone categories together), `PhoneNotifier` (banners with reply/buttons/dismiss, icon attachment), `AppModel` wiring and the `showPhoneNotifications` setting. Verified: Mac app build, no compiler warnings.
- Task 7: Mac Notifications page (grouped by app, hover Reply/buttons/✕, Clear all), Muted apps sheet, Settings → General → Notifications, Phone page line "Notifications off on phone". Verified: Mac app build.
- Task 8: Android `MacDroidNotificationListener` + `NotificationBridge` (reads notifications, carries out reply/action/dismiss, reports `gone`/`failed`), icons, settings persistence, launcher `<queries>` for app names and icons. Verified: Android gates, `NotificationTextTest`.
- Task 9: Android Notifications tab (master switch, apps with switches and daily counts, access card), and the setup Notifications step now also asks for notification access (still 6 steps). Verified: Android gates (`NotificationsPresentationTest`, `SetupFlowTest`).
- Task 10: the cross-platform script now forwards a notification, gets a reply from the Swift server, and removes it. Verified: `./scripts/cross-platform-test.sh` passed.
- Task 11: versions 0.4.0 / 4 on both sides; all gates and the cross-platform run pass (`swift test` 179). The owner's notification checklist is in `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`.

## 2026-10-13 — Spec self-review and Plans 3 and 4 written
- **What:**
  - Self-reviewed the notifications + clipboard spec against the code and added §8 with 11 fixes, among them:
    - the clipboard text limit is the 1 MiB WebSocket frame
    - echo prevention runs only on the Mac, by pasteboard change count
    - a single macOS notification delegate
    - icon hashes are validated as file names
    - `transfer.offer.purpose` is optional on the wire
  - Wrote Plan 3 (`docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md`, 11 tasks) and Plan 4 (`docs/superpowers/plans/2026-10-13-macdroid-clipboard-4.md`, 11 tasks).
- **Why:** the owner asked for a self-review and then the plans.
- **Updated:** README (picking-up note, round table), STATUS, ROADMAP.
- **Verified:** plan self-review (spec coverage, placeholder scan, type consistency, review focus). No code changed.
- **Branch:** `main`.

## 2026-10-13 — Notifications + clipboard spec (Plans 3 and 4)
- **What:** brainstormed and wrote `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`: one spec for notification mirroring (Plan 3) and clipboard sync (Plan 4), delivered as two plans on `feat/notifications-clipboard`. Covers the new `notification.*` and `clipboard.set` messages, `transfer.offer.purpose`, behaviour on both sides, UI (Mac page layout A grouped by app; Android Notifications tab as a control panel; Clipboard page/tab), errors and testing.
- **Why:** next rounds on the roadmap; the owner asked to do them together.
- **Updated:** README (round table, picking-up note), STATUS (in flight), ROADMAP (Next), DECISIONS (7 rows).
- **Verified:** spec self-review (no placeholders; consistent with the round-1 and file-transfer specs). No code changed.
- **Branch:** `main`.

## 2026-10-13 — File transfer hands-on checklist passed
- **What:** the owner ran the file-transfer hands-on checklist and everything passed except one item they haven't run yet: a 2 GB+ file in each direction, which they'll check later. The checklist is ticked in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`; STATUS and README are updated.
- **Next:** Plan 3 (notifications).
- **Branch:** `main`.

## 2026-10-13 — Handoff check after the file-transfer merge
- **What:** re-checked `main` against the handoff and fixed stale lines left from before the merge:
  - README: the spec and Plan 2a were still marked "next up", and Plan 2b "will be" written. They now link the 2a and 2b plans and the follow-ups file.
  - ROADMAP: 2b was marked "not written yet", the step numbers skipped 2, the groundwork read as still to do, and Plan 3 said the hub hook "arrives in Plan 2".
  - STATUS: added the file-transfer follow-ups file to the follow-up lists.
- **Verified:** `swift test` (144 tests), the Mac app build and the Android gates all pass on `main`.
- **Branch:** `main`.

## 2026-10-13 — Repository README
- **What:** added a root `README.md` for the GitHub repo. It covers:
  - what MacDroid is and its feature status
  - file-transfer behaviour
  - how it works (topology and protocol) and the security and privacy model
  - requirements, building, running and pairing
  - tests and the project layout
  - links into `docs/handoff/` and the specs, plus the roadmap
- **Why:** the repo had no README; the owner asked for a detailed one before pushing.
- **Verified:** every relative link in the README points to an existing file.
- **Branch:** `main`.

## 2026-10-13 — File transfer merged to main
- **What:** merged `feat/file-transfer` into `main`: Plan 2a (engine), Plan 2b (UI), the owner-found fixes (MediaStore part file, Mac drag-and-drop in the sandbox) and the final-review fixes.
- **Verified on the merged `main`:**
  - `swift test`: 144 tests.
  - The Mac app build succeeds with no compiler warnings.
  - The Android gates pass with no warnings.
  - `./scripts/cross-platform-test.sh` passes.
- **Owner-verified:** phone → Mac, and Mac → phone via Send Files…, the Files page and the popover.
- **Still open:** the rest of the hands-on checklist, in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- **Next:** Plan 3 (notifications).

## 2026-10-13 — Plan 2b final-review fixes
- **Mac (critical):** with "Ask where to save each time", the file is now written straight into the file chosen in the save panel. The sandbox grants access to that exact file only, so the old sibling `.macdroid-part` failed anywhere outside Downloads. The Mac also keeps earlier save folders accessible for the session.
- **Android:**
  - Only completed sends are tidied up: the cache copy is deleted and the read grant is released. Retry of a failed share works again, and grants no longer pile up (choosing a new folder releases the old one).
  - Undecided incoming rows offer "Choose where to save", and the prompt notification stays until answered.
  - A deleted chosen folder counts as unreachable (asks, shows the banner).
  - Only a missing file shows as "Deleted from this phone".
- **Verified:**
  - Regression tests RED→GREEN: `savePanelChoiceIsWrittenInPlaceWithoutAPartFile`, `FinishedSendsTest`, `ReceivedFilesTest`, `undecidedIncomingRowOffersChoosingWhereToSave`.
  - All gates pass.
  - The deferred minors are listed in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Fix: drag and drop onto the Mac app did nothing
- **Symptom:** the owner dragged files onto MacDroid to send them to the phone, and nothing happened.
- **Evidence:** the unified log showed `CFPasteboard … Sandbox extension data required immediately for flavor public.file-url, but failed to obtain`. The popover read dragged file URLs while hovering (to add up their sizes), and the sandbox grants access only at drop.
- **Fix:**
  - Both drop targets read file URLs from `NSPasteboard(name: .drag)` at drop time (`Files/DroppedFiles.swift`).
  - The popover overlay shows only the count while hovering.
  - The Files page uses `onDrop(of: [.fileURL])` instead of `dropDestination(for: URL.self)`.
- **Verified:** `dropSummaryWithoutSizesShowsTheCount` (RED→GREEN) and the Mac app build. The owner's retest is pending.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Mac: never drop a file silently; transfer logs in Console
- **What:**
  - `TransferManager.sendReporting(files:)` reports files it couldn't queue, with the reason. The Mac shows them in a banner ("Couldn't send “name”: reason") instead of silently doing nothing.
  - The engine's and server's logs now reach the unified log (subsystem `dev.macdroid.mac`), including why a source file couldn't be reopened for the phone.
- **Why:** the owner's Mac → phone sends showed nothing at all, and there was no log to diagnose it.
- **Verified:** `unreadableFilesAreReportedNotDropped` (RED→GREEN), `swift test` 142, Mac app build with no compiler warnings.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Received files that were deleted say so
- **What (owner's request):** if a received file is deleted after it arrives, its row says so instead of offering to open it.
  - **Mac:** the row reads "Deleted from this Mac" and has no Show/Open. Show in Finder or Open on a file removed meanwhile shows the banner "“name” is no longer on this Mac — it was deleted or moved".
  - **Android:** the row reads "Deleted from this phone" (checked off the main thread, and again whenever Files or Home comes back). Tapping a missing file shows the toast "“name” was deleted from this phone".
- **Where:**
  - `TransferPresentation.row(…, savedFileExists:)` on both sides.
  - Mac: `AppModel.receivedFileExists`.
  - Android: `platform/ReceivedFiles`, `FilesUi.deleted`.
- **Verified:**
  - New presentation tests on both sides (RED→GREEN).
  - The Mac app and Android gates pass with no warnings.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Fix: Mac → phone transfers failed at 0 bytes
- **Symptom:** the owner's testing showed every Mac → phone transfer ending "Transfer failed". Phone → Mac worked.
- **Root cause:**
  - Both sides recorded `Io` at 0 bytes, with a MediaStore part created on the phone.
  - A new MediaStore item has no file until it's opened for writing.
  - The engine re-hashes the part before downloading, by opening it for reading, which threw `ENOENT`.
- **Fix:** `MediaStoreSaveTarget.createPart` creates the empty file (opens it once with `"w"`), and deletes the row if that fails.
- **Verified:**
  - The new on-device test `MediaStoreSaveTargetTest` covers create → read → append → reopen → resume → finish. It failed with `ENOENT` before the fix and passes after.
  - Installed on the phone.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Plan 2b: file transfer UI (done on feat/file-transfer; waiting for the owner's check)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`.
- **Verified:**
  - `swift test`: 140 tests.
  - The Mac app build succeeds with no compiler warnings.
  - The Android gates pass with no warnings.
  - `./scripts/cross-platform-test.sh` passes.
  - Version 0.3.0 is installed on the Nothing Phone (3).
  - The owner's hands-on checklist is `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- Task 1: Mac `TransferManager` shows undecided incoming offers as `.offered` rows, passes `IncomingOffer` (id, name, size, mime) to the save-location provider, and cancels the question when either side cancels; undecided offers are forgotten at relaunch. Verified: `swift test`.
- Task 2: `TransferPresentation` (row text, popover items, drop summary) in `MacDroidCore`. Verified: `TransferPresentationTests`.
- Task 3: Android `TransferEngine` shows undecided offers as `Offered`, cancels the save question on either side's cancel, creates parts on IO, forgets undecided offers on restart, and retries paused transfers every 30 s while connected. Verified: `TransferEngineDecisionTest` (stable over 3 reruns).
- Task 4: the Mac app runs `TransferManager` (security-scoped source bookmarks, Received-files settings with a folder bookmark and a cancellable save panel, notifications with Show in Finder/Open); entitlements for user-selected files, Downloads and app-scope bookmarks; version 0.3.0. Verified: app build with no compiler warnings, and `codesign` shows the three entitlements.
- Task 5: Mac Files page (drop zone, Send Files…, In progress, Recent with Show in Finder/Open/Retry/cancel) and a Received-files section in Settings → General. Verified: app build with no compiler warnings.
- Task 6: Mac popover in design A: device card with battery ring, Send Files and Open MacDroid tiles, up to two transfer rows, Settings/Quit icons, and the whole popover as a drop target with a "Drop to send" overlay (count and size). Verified: app build with no compiler warnings.
- Task 7: Android pure helpers (`TransferPresentation`, `SaveMode`, `ShareSource`, `ShareDecision`, `FolderLabel`) with unit tests. Verified: `:app:testDebugUnitTest` (21 new tests).
- Task 8: Android save targets (MediaStore Download/MacDroid by default, a chosen SAF folder, or ask each time via a notification and CreateDocument), content-URI sources, and the `TransferEngine` running in `AppGraph`; version 0.3.0. Verified: Android gates.
- Task 9: Android `SendFiles` (persistable grant, or a cache copy up to 100 MB), a progress notification with Cancel, a "Received <name> from Mac" notification with Open, and `ConnectionService` actions for sending and cancelling. Verified: Android gates.
- Task 10: Android Files tab (Send to Mac card with Photos/Files, In progress with cancel, Recent with open/retry, folder-problem banner) and Home's Send FAB plus Recent activity (last 3). Verified: Android gates.
- Task 11: Android share target ("Send to Mac" for one or many files of any type): queues silently with a toast when connected, otherwise "Connect to your Mac first" and opens the app. Verified: Android gates.
- Task 12: Android Settings → Files (save folder with "Use default", ask-each-time switch, problem text) and an optional first-run setup step "Where should files from your Mac go?". Verified: `SetupFlowTest` (10) and the Android gates.

## 2026-10-13 — Plan 2b written (file transfer UI and platform glue)
- **What:** wrote `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`. It has 13 tasks:
  - **Engine changes, both sides:** pending incoming offers become visible, cancellable rows, and the save-location question gets the offer's details. Android also retries paused transfers while connected.
  - **Mac:** app wiring with entitlements, the Files page, the Received-files settings and the design-A popover.
  - **Android:** pure helpers, the MediaStore/SAF/ask save targets, notifications, the Files tab with the Home Send button, the share target, Settings → Files and a setup step.
  - **Wrap-up:** a final task with the owner's hands-on checklist.
- **Why:** this is the second half of Plan 2. It also folds in the 2a review minors that 2b needed (save-location API gaps, transfers stuck "Paused" while connected).
- **Branch:** `feat/file-transfer`.
- **Verified:** docs only.

## 2026-10-13 — Plan 2a: file transfer engine (done on feat/file-transfer)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md` (engine); Plan 2b (UI and platform glue) follows.
- **Verified:**
  - `swift test`: 127 tests.
  - The Mac app build succeeds.
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` passes.
  - `./scripts/cross-platform-test.sh` passed twice in a row.
  - No warnings anywhere.
- **Execution:** Tasks 1–2 were run subagent-driven with per-task reviews. At the owner's request, Tasks 3–14 were run natively, with one whole-branch review at the end.
- Task 1: `TrustedPeerMiddleware` is global; every route except `POST /v1/pair` (and the not-found responder) requires the paired phone. Verified: `swift test`, including `strangerIsForbiddenEverywhereExceptPair`.
- Task 2: `transfer.*` messages in both codecs with 7 fixtures; `PROTOCOL.md` documents them and the `/v1/files` routes. Verified: both suites.
- Task 3: `Broadcast` fan-out; `ConnectionHub.statusUpdates` is multi-subscriber (replays the current status), and the new `ConnectionHub.messages` forwards control messages. Verified: `swift test` and the app build.
- Task 4: Swift `FileNaming`, `ByteRanges`, `TransferText`; shared vectors `file-naming.json` and `transfer-text.json`. Verified: `swift test`.
- Task 5: transfer model, JSON stores, rate smoothing, and checksummed `PartFileWriter`/`SourceReader` (resume re-hash, session supersede, disk-full detection). Verified: `swift test`.
- Task 6: `TransferManager` receiving: offer → part file → verify → rename; re-offer resume; outcome replay; 24 h cleanup; history 100; queued cancels. Verified: `TransferReceiveTests`.
- Task 7: `TransferManager` sending: FIFO queue with 3 slots, offer/accept/reject/result, re-offer on reconnect, source bookmarks, retry. Verified: `TransferSendTests`.
- Task 8: `HEAD`/`PUT`/`GET /v1/files/:id` with Upload-Offset resume, 409 on offset mismatch, Range/206, empty 206 at the end, 404 for wrong direction. Verified: `FileRouteTests`, `swift test`, app build.
- Task 9: Kotlin `FileNaming`, `TransferText`, `TransferRate`, passing the same shared vectors as Swift. Verified: `:core:test`.
- Task 10: Kotlin `TransferClient` (HEAD/PUT/GET with resume headers, running SHA-256, cancel → CancellationException, local vs network errors) and `FileEndpoint`; `MockMac` now speaks HTTP/1.1 like the real Mac. Verified: `TransferClientTest` against the mTLS MockWebServer.
- Task 11: `TransferEngine` receiving (offer → part → GET with resume → verify in either order → finish), replay, cancel, 24 h cleanup; `JsonFileTransferStore`, `FolderSaveTarget`. Verified: `TransferEngineReceiveTest`, `TransferStoreTest` (stable over 3 reruns).
- Task 12: `TransferEngine` sending: FIFO with 3 slots, HEAD-offset resume with prefix re-hash, straight-to-finish when the Mac has everything, re-offer on reconnect and on 404, retry, queued cancels. Verified: `TransferEngineSendTest` (stable over 3 reruns).
- Task 13: cross-platform test sends 24 MiB each way, drops the connection mid-file, and checks both resumed and match byte for byte; the file part runs inside the existing pairing test. Verified: `./scripts/cross-platform-test.sh`.
- Final review fixes: Mac claims incoming names case-insensitively (APFS), including two save panels picking the same file; Android `TransferEngine.load()` merges with files queued meanwhile; a resumed transfer waits for its cancelled predecessor to stop. Verified: new regression tests RED→GREEN, `swift test` 129, Android gates, cross-platform script.

## 2026-10-13 — File transfer review points resolved; Plan 2a (engine) written
- **What:**
  - The owner accepted the suggested answer for all 8 review points. They're folded into the spec, which gains a §8 list of rulings, and recorded in DECISIONS.md.
  - Three refinements came up while planning: an unknown re-offered id starts over instead of being rejected; downloads always make a `GET`, so `finish` is always sent; OkHttp uses 60 s read/write timeouts. They're in the spec (§2.2, §4.2, §8) and DECISIONS.md.
  - Split the plan, at the owner's request. Wrote Plan 2a, the engine (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md`, 14 tasks); Plan 2b (UI and platform glue) comes next.
  - Updated the README, ROADMAP and STATUS with the next steps.
- **Branch:** `main` (docs only).
- **Verified:** docs only; nothing to build.

## 2026-10-13 — File transfer spec reviewed; handoff made usable from Claude Desktop
- **What:**
  - Reviewed the Plan 2 spec against the code (`MacDroidServer`, `ConnectionHub`, `TlsClientFactory`, entitlements).
  - Recorded 8 open points, each with a suggested answer, in ROADMAP.md "Next": the Android default save folder (MediaStore vs SAF), share-target URI grants, Mac source bookmarks for resume after a relaunch, the resume handshake, the scope of the 3-transfer limit, the offer timeout, HTTP and naming edge cases, and plan-level details.
  - README: added a "Rules for whoever picks this up" section that repeats the key `CLAUDE.md` rules, because Claude Desktop doesn't load `CLAUDE.md` automatically. Updated the next action and the round table.
  - STATUS: "In flight" now points to the open points.
- **Why:** to settle these gaps before planning, and so any Claude session (Code or Desktop) can pick up from this folder alone.
- **Branch:** `main` (docs only).
- **Verified:** docs only; nothing to build.

## 2026-10-13 — File transfer spec (Plan 2) approved; roadmap reordered
- **What:**
  - Wrote `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`.
  - File transfer is now Plan 2, notifications Plan 3 and clipboard Plan 4.
  - Updated the handoff (README round table, ROADMAP "Next" with exact next steps, STATUS "In flight", DECISIONS).
- **Key choices:**
  - auto-save on receive
  - full resumable transfers
  - drag-drop and picker on the Mac, with the Finder Share extension deferred
  - Control Center-style popover
  - Android share target, Files tab, Photo Picker and SAF
- **Next:** write the implementation plan (writing-plans skill), then execute it subagent-driven.

## 2026-10-13 — App icons merged to main
- **What:** merged `feat/app-icons` into `main`.
- **Verified:** the owner checked the icon on the Mac and the phone. It shows normally in the Dock, with no grey tile.

## 2026-10-13 — App icons: the peeking droid
- **What:** the app icon for both apps is a minimal, flat green droid peeking over a MacBook lid, gripping the edge with two little hands, on a peach background. It is the name *Mac + Droid* as a character, chosen by the owner over generic sync or device glyphs.
  - **Source art:** `design/icon/` (`macdroid-icon-mac.svg`, `macdroid-icon-monochrome.svg`).
  - **Mac:**
    - `mac/MacDroid/Assets.xcassets/AppIcon.appiconset`: 10 PNGs, 16 to 1024 px on Apple's 824/1024 grid.
    - The PNGs are rendered by `scripts/render-icons.sh`, which uses `scripts/render-icon.swift` and AppKit's built-in SVG support.
    - `project.yml` sets `ASSETCATALOG_COMPILER_APPICON_NAME`.
  - **Android:**
    - An adaptive vector icon: background colour, a foreground scaled into the 66dp safe zone, and a monochrome layer for themed icons.
    - Manifest `icon`/`roundIcon`.
    - The status-bar icon `ic_stat_macdroid` is now the droid silhouette.
  - The menu-bar status icons are unchanged (they are SF Symbols that follow connection state).
- **Branch:** `feat/app-icons` (now merged).
- **Verified:**
  - Both apps build with no warnings, and the Android unit tests pass.
  - The icons render correctly (checked circle, squircle and themed masks).
  - Installed on the phone, and the Mac app relaunched. The owner confirmed it looks right.
- **Watch:** macOS 26 may put legacy (non-Icon-Composer) icons inside a grey tile. If so, add an Icon Composer `.icon` version.

## 2026-10-13 — UI/UX redesign merged to main
- **What:** merged `feat/ui-ux-redesign` into `main` (merge commit 1428d95).
- **Verified:** the owner ran the hands-on checklist on the Mac and the Nothing Phone (3), and everything passed.

## 2026-10-13 — Handoff folder and the keep-it-current rule
- **What:**
  - Added `CLAUDE.md` and `docs/handoff/` (README, ARCHITECTURE, STATUS, DEV-GUIDE, ROADMAP, DECISIONS, CHANGELOG).
  - Added the rule that every change updates the handoff in the same commit.
- **Why:** so any person or Claude Code session can see what has been built and why.
- **Branch:** `feat/ui-ux-redesign`.

## 2026-10-13 — UI/UX redesign (both apps)
- **Mac:**
  - Menu-bar popover plus a sidebar window (Phone, coming-soon Notifications/Clipboard/Files, Settings General/Security/About).
  - Relaunching the app opens the window. The Dock icon shows only while the window is open.
  - Pairing screen with a QR code, countdown, New code, an expired state and retry; "Pair a Phone" button after Forget.
  - Persistent warning banners.
- **Android:**
  - Material You theme.
  - Pairing-centred setup wizard (Welcome → Local network → Pair → Notifications → Battery).
  - 5-tab bottom navigation. Home shows a live status card: countdown, Retry now, Pair again, Connect.
  - Settings: keep-connected switch, permissions and battery rows, forget with confirmation.
- **Core changes:**
  - Additive only: `StatusPresentation.swift` (Mac) and `ConnectionState.Waiting.retryAtNanos` (Android).
  - Connection behaviour is unchanged.
- **Branch:** `feat/ui-ux-redesign`, commits 74d5cc1..ccf88a7. Every task was reviewed, the final review was clean, and all build gates are green.
- **Pending:** the owner's hands-on checklist, then merge to `main`.

## 2026-10-12 — Plan 1: protocol and connection core
- **Protocol:** shared protocol and fixtures; Swift and Kotlin codecs.
- **Mac:**
  - TLS identity in the Keychain.
  - Pairing manager: 120 s window, 128-bit code.
  - Bonjour.
  - Hummingbird mutual-TLS HTTPS/WS server and session hub.
  - Dev server.
- **Android:**
  - Pinned mutual-TLS OkHttp client.
  - Pairing client.
  - Connection manager with backoff, nudges and NSD locator.
  - Keystore identity, foreground service and boot receiver.
  - Basic UI.
- **Cross-platform test script:** the Swift server against the Kotlin client.
- **Merged to `main`** (merge commit 04a48a4). The owner verified pairing, reconnect, battery and forget/re-pair on the Nothing Phone (3).
