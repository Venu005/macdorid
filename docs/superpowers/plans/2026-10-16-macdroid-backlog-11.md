# Backlog clean-up (Plan 11) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clear the open deferred minors from every follow-up list, plus the cheap hardening that needs no Apple Developer account, so `main` carries no known small defects.

**Architecture:** No new features. Each fix lands in the module that already owns the code. Testable logic goes into `MacDroidCore` / `android/core` (with a failing test first); platform glue stays thin and is verified by the build. One protocol change: an optional `speed` on `media.state`.

**Tech Stack:** Swift 6 / SwiftUI / Hummingbird / AVFoundation (Mac), Kotlin / Compose / OkHttp / MediaCodec / AccessibilityService (Android), JDK 21 for JVM tests.

**Spec:** the follow-up lists are the spec. Each item below quotes the list and item it closes:
- `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md` (Plan 7 minors 1–9, Plan 8 minors 1–10, the volume ruling)
- `docs/superpowers/plans/2026-10-13-macdroid-small-wins-followups.md` (11 minors)
- `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md` (2a and 2b minors)
- `docs/superpowers/plans/2026-10-12-macdroid-plan1-followups.md` and `docs/superpowers/plans/2026-10-13-macdroid-ui-ux-redesign-followups.md` (the subset chosen in "Older items" below)

## Owner rulings (2026-10-16)
- **All hands-on checklists passed**, including the 2 GB+ file in both directions.
- **Volume while mirroring audio:** when the phone's sound is playing on the Mac, the mirror window's Volume −/+ change the **Mac player's** volume and send nothing to the phone. Without audio on the Mac they still go to the phone, as now.
- **Monetization:** staying free and unsigned for now (no Apple Developer account). Contacts sync is dropped. Both go in DECISIONS.md / ROADMAP.md in Task 1.
- **Scope:** every open minor except those under "Kept as is" below; plus Android backup exclusions, restart after an app update, the Gradle wrapper checksum, Bonjour name-conflict handling and the keychain write via `SecItemUpdate`. Signing, notarization and store-policy work stay out.

## Kept as is (closed with a reason, no code change)
Record each in its follow-up list as "kept (Plan 11)" with this reason:
- Plan 7 #6, the 4 MiB frame limit on `/v1/ws`: Hummingbird has one WebSocket config per server; only the trusted paired phone can upgrade, and control messages are still capped at 1 MiB after decoding.
- Plan 7 #9, the latency readout measures to enqueue: an under-estimate of a few ms; measuring at display would need a display-link callback for a debug number.
- Plan 8 #3, each keystroke rewrites the whole field: the owner's typing checklist passed on the device.
- Small wins, play/pause is always enabled: virtually every media session supports both; adding `canPlay` is a protocol change for no visible gain.
- Plan 1: pairing has no wrong-code attempt cap (the code is 128-bit random, valid for 120 s); test-gap-only and comment-only items; items already fixed (persistent `warnings`, launch-at-login revert, the "later plans add" comments, the Forget confirmation).
- UI/UX: extracting the `AppModel` pairing state machine (a refactor with no defect behind it), and the cosmetic Task 1–5 items not listed in Task 12.

## Global Constraints
- The protocol is a contract: `media.state.speed` needs a fixture in `protocol/fixtures`, both codecs and PROTOCOL.md, and both suites must pass.
- Security model unchanged: mutual TLS 1.3 with pinning; only `POST /v1/pair` is reachable unpaired, and only while the window is open.
- Features must work without developer options, adb grants or Shizuku.
- One commit per fixed item (or per tightly coupled pair), message `fix(<area>): …`, ending with the attribution trailer.
- Every task's last step updates `docs/handoff/CHANGELOG.md` (a dated entry under one "Plan 11" heading) and ticks the item in its follow-up list.
- Build gates, all with no warnings:
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- Branch: `fix/backlog` from `main`.

## Review Focus
1. **Audio output device switch mid-mirror** (AirPods connect or disconnect): sound continues on the new device within about a second, with no crash. Task 2 restarts the engine on `AVAudioEngineConfigurationChange`; owner check in Task 13.
2. **Rotating the phone in a small mirror window:** the landscape window keeps the old long edge and stays on screen. Test `landscapeKeepsTheLongEdge` (Task 3).
3. **Pasting 4,096+ characters of emoji:** no broken character at the cut. Tests `capsInUtf16UnitsWithoutSplittingAPair` (Mac) and `textCapNeverSplitsASurrogatePair` (Android) (Task 5).
4. **Mac asleep, then awake at the same address:** the phone reconnects within a few seconds, not after the 30 s backoff. Test `sameAddressReappearanceWakesTheRetry` (Task 11).
5. **A restored phone backup:** after a restore, the app goes back to setup instead of failing with a trust error (the Keystore key never travels, so the pairing prefs must not either). Task 11 excludes the pairing prefs from backup and device transfer.

---

### Task 1: Branch and record the rulings

**Files:**
- Modify: `docs/handoff/DECISIONS.md`, `docs/handoff/ROADMAP.md`, `docs/handoff/STATUS.md`, `docs/handoff/CHANGELOG.md`, `docs/handoff/README.md` (round table)
- Modify: every follow-up list's checklist (tick the owner checklists; mark the "Kept as is" items)

- [ ] **Step 1:** `git checkout -b fix/backlog main`
- [ ] **Step 2:** DECISIONS.md: add three dated entries: free/unsigned distribution for now (rejected: paying $99 now; selling unsigned); contacts sync dropped (owner: not needed); the volume ruling above.
- [ ] **Step 3:** ROADMAP.md: add "Plan 11 — backlog clean-up (in progress on `fix/backlog`)" to the agreed order; mark contacts sync "dropped 2026-10-16"; note the monetization stance under "Before publishing". STATUS.md: all owner checklists passed, including the 2 GB file; Plan 11 in flight.
- [ ] **Step 4:** Tick every owner checklist in the follow-up lists, and write "kept (Plan 11): <reason>" against each "Kept as is" item.
- [ ] **Step 5:** CHANGELOG entry; commit `docs(handoff): Plan 11 backlog round — rulings and passed checklists`.

### Task 2: Mac mirroring audio (Plan 7 #1, #2, volume ruling)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorVolume.swift`
- Modify: `mac/MacDroid/Mirror/MirrorAudioPlayer.swift`, `mac/MacDroid/AppModel.swift` (around `sendMirrorKey`), `mac/MacDroid/Views/Mirror/MirrorContentView.swift:76-77`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorCoreTests.swift`

**Interfaces:**
- Produces: `public enum MirrorVolumeRoute: Equatable, Sendable { case mac, phone }`, `public enum MirrorVolume { static func route(audioOnMac: Bool) -> MirrorVolumeRoute; static func step(_ current: Float, up: Bool) -> Float }`, `MirrorAudioPlayer.volume: Float` (get/set, 0…1, persisted by AppModel in `UserDefaults` key `mirror.volume`, default 1).

- [ ] **Step 1: Failing tests**
```swift
@Test func volumeGoesToTheMacWhileItPlaysTheSound() {
    #expect(MirrorVolume.route(audioOnMac: true) == .mac)
    #expect(MirrorVolume.route(audioOnMac: false) == .phone)
}
@Test func volumeStepsByOneSixteenthAndClamps() {
    #expect(MirrorVolume.step(1, up: true) == 1)
    #expect(MirrorVolume.step(0, up: false) == 0)
    #expect(MirrorVolume.step(0.5, up: true) == 0.5625)
}
```
- [ ] **Step 2:** `swift test --filter MirrorCoreTests` → FAIL (no `MirrorVolume`).
- [ ] **Step 3:** Implement `MirrorVolume` (16 steps, like the macOS volume keys). `audioOnMac` = the session's streaming phase reports `audio == true` **and** `mirrorAudio` is on.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** In `MirrorContentView`, the volume buttons call `model.mirrorVolume(up:)`. That routes `.phone` → `sendMirrorKey(.volumeUp/.volumeDown)`, and `.mac` → `mirrorAudioPlayer.volume = step(...)`, saved to `mirror.volume`. The help text reads "Mac volume" or "Phone volume" to match. `MirrorAudioPlayer.volume` sets `engine.mainMixerNode.outputVolume` on its queue.
- [ ] **Step 6:** In `MirrorAudioPlayer`, observe `.AVAudioEngineConfigurationChange` for `engine`. On the queue, if a session is active (`started && !muted`): `node.stop()`, reconnect `node` → `mainMixerNode` with `format`, reset `jitter`/`bufferedFrames`, `try? engine.start()`. Remove the observer in `deinit`.
- [ ] **Step 7:** Add `private var epoch = 0` (queue). `start`/`stop` increment it, and the completion handler captures it, so `bufferedFrames -= frames` applies only when `epoch` still matches. The counter can no longer go negative.
- [ ] **Step 8:** Mac app build; commit each of the three fixes separately.

### Task 3: Mac mirror window, cursor and scroll (Plan 7 #4; Plan 8 #5, #6, #9)

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorInput.swift`, `mac/MacDroid/Mirror/MirrorWindowController.swift:39-63`, `mac/MacDroid/Mirror/MirrorInputView.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorInputTests.swift`

**Interfaces:**
- Produces: `public enum MirrorWindowFit { static func content(current: CGSize, aspect: CGSize, barHeight: CGFloat) -> CGSize }`, and `InputMapper.scroll(deltaX:deltaY:precise:in:picture:)`, which gains a `picture: CGSize` parameter.

- [ ] **Step 1: Failing tests**
```swift
@Test func landscapeKeepsTheLongEdge() {
    // portrait 400×(889+bar) → landscape 20:9: the long edge (889) becomes the width
    let s = MirrorWindowFit.content(current: CGSize(width: 400, height: 889 + 44), aspect: CGSize(width: 20, height: 9), barHeight: 44)
    #expect(s.width == 889)
    #expect(s.height == (889 * 9 / 20).rounded() + 44)
}
@Test func portraitToPortraitKeepsTheWidth() {
    let s = MirrorWindowFit.content(current: CGSize(width: 400, height: 933), aspect: CGSize(width: 9, height: 20), barHeight: 44)
    #expect(s.width == 400)
}
@Test func scrollIsAFractionOfThePictureNotTheView() {
    // view 400×400, picture 200×400 drawn 200 wide: 20 points = 0.1 of the picture's width
    let d = InputMapper.scroll(deltaX: 20, deltaY: 40, precise: true, in: CGSize(width: 400, height: 400), picture: CGSize(width: 200, height: 400))
    #expect(d.dx == 0.1)
    #expect(d.dy == 0.1)
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `MirrorWindowFit.content`. When the picture's orientation changes, take the long edge of the current picture area (excluding the bar) as the new long edge; otherwise keep the width. `setAspect` uses it, then clamps the frame to `window.screen?.visibleFrame`. `InputMapper.scroll` divides by the aspect-fit picture size, using the same scale as `fraction`.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** `MirrorInputNSView`:
  - The dot cursor applies only when `enabled`, and only over the picture rect (compute that rect with the same aspect-fit math; add `InputMapper.pictureRect(in:picture:) -> CGRect`, tested by `pictureRectIsCentredAspectFit`). Otherwise the arrow cursor shows.
  - Setting `enabled` to false while `down`: send `.cancel` and clear `down`.
- [ ] **Step 6:** Mac app build; commit.

### Task 4: Mac mirror keyboard (Plan 8 #7, #8)

**Files:**
- Modify: `mac/MacDroid/Mirror/MirrorInputView.swift`, `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorInput.swift` (`MirrorKeys`)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorInputTests.swift`

**Interfaces:**
- Produces: `MirrorKeys.action(keyCode:characters:command:fullScreen:) -> MirrorKeyAction?`, with `MirrorKeyAction` gaining `case leaveFullScreen` and `case swallow`.

- [ ] **Step 1: Failing tests**
```swift
@Test func escLeavesFullScreenFirst() {
    #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false, fullScreen: true) == .leaveFullScreen)
    #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false, fullScreen: false) == .key(.back))
}
@Test func tabStaysOnThePicture() {
    #expect(MirrorKeys.action(keyCode: 48, characters: "\t", command: false, fullScreen: false) == .swallow)
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Tab (48) and arrow/function keys without ⌘ return `.swallow`. Other ⌘ shortcuts still return nil, so they reach the menu.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** Make `MirrorInputNSView` an `NSTextInputClient`:
  - `keyDown` handles Return, Delete and Esc through `MirrorKeys` first. Everything else goes through `inputContext?.handleEvent(event)`.
  - `insertText(_:replacementRange:)` calls `type(...)`.
  - `setMarkedText` keeps the composition locally and sends nothing until it is committed. That gives dead keys (é via ⌥E E) and IMEs (Pinyin, Japanese).
  - `.leaveFullScreen` → `window?.toggleFullScreen(nil)`. `.swallow` does nothing.
- [ ] **Step 6:** Mac app build; commit.

### Task 5: Remote control text and gestures (Plan 8 #1, #2, #4, #10)

**Files:**
- Modify: `mac/MacDroid/AppModel.swift` (`pasteOnPhone`, `flushText`), `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift` (`InputText`), `protocol/PROTOCOL.md` (input.text rules), `android/core/src/main/kotlin/dev/macdroid/core/control/ControlRules.kt` (`TextEdit`), `android/app/src/main/java/dev/macdroid/app/platform/control/MacDroidControlService.kt:101,166`, `android/app/src/main/java/dev/macdroid/app/platform/control/GesturePlayer.kt`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt:218-219`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorInputTests.swift`, `android/core/src/test/kotlin/dev/macdroid/core/control/ControlRulesTest.kt`

**Interfaces:**
- Produces: `InputText.capped(_ text: String) -> String` (Swift; at most 4,096 UTF-16 units, never ending on a high surrogate), `TextEdit.cap(text: String, max: Int): String` (Kotlin, same rule).

- [ ] **Step 1: Failing tests**
```swift
@Test func capsInUtf16UnitsWithoutSplittingAPair() {
    let text = String(repeating: "a", count: 4095) + "😀"
    #expect(InputText.capped(text).utf16.count == 4095)
}
```
```kotlin
@Test fun textCapNeverSplitsASurrogatePair() {
    assertEquals(4095, TextEdit.cap("a".repeat(4095) + "😀", 4096).length)
}
@Test fun backspaceRemovesAWholeFlagAndAZwjFamily() {
    assertEquals("a" to 1, TextEdit.backspace("a🇮🇳", 5, 5))
    assertEquals("a" to 1, TextEdit.backspace("a👨‍👩‍👧", 9, 9))
}
```
- [ ] **Step 2:** Run both suites → FAIL.
- [ ] **Step 3:** Implement `capped` and use it in `pasteOnPhone` and `flushText`. Implement `TextEdit.cap` and use it in place of `take(MAX_TEXT)`. `TextEdit.backspace` finds the previous boundary with `java.text.BreakIterator.getCharacterInstance()`; that handles extended grapheme clusters on JDK 20+ and on Android's ICU. PROTOCOL.md: "`text` is at most 4,096 UTF-16 code units; senders never split a surrogate pair."
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** `GesturePlayer.pump`: when `dispatchGesture` returns false, drop that segment and `main.post { pump() }`, so queued input continues without a tight loop.
- [ ] **Step 6:** `CrossPlatformTest`: merge `delay(300)` + `delay(500)` (lines 218–219) into one `delay(800)`.
- [ ] **Step 7:** All gates; commit.

### Task 6: Android mirroring capture (Plan 7 #3, #5, #7, #8)

**Files:**
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/mirror/ScreenEncoder.kt:37-53`, `MirrorService.kt` (fields, `onStartCommand`, `onVideo`, `onOpen`), `MediaStreamClient.kt:41`

- [ ] **Step 1:** `ScreenEncoder.resize`/`release`: set `display?.surface = null` **before** `stopCodec()`, then attach the new surface after `begin`.
- [ ] **Step 2:** `MirrorService.onStartCommand`: when a session is already running (`projection != null`) and the intent isn't `ACTION_STOP`, ignore it and return `START_NOT_STICKY`. `finished` is reset only by a fresh service instance.
- [ ] **Step 3:** Mark `stream` and `policy` `@Volatile`.
- [ ] **Step 4:** `MediaStreamClient.send` returns false (drops the frame) until `onOpen` has run; track that with an `@Volatile var open`. `MirrorService.onVideo` stores config frames in `lastConfig` and sends them only if `stream.isOpen`. `onOpen` sends `lastConfig` once and requests a key frame, so the config can't go out twice and no stale frames are queued.
- [ ] **Step 5:** `:app:assembleDebug`; commit.

### Task 7: Small wins on Android (small-wins minors: link parsing, swiped ring, appName, art key, link ids, `timedOut`)

**Files:**
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/extras/LinkExtractor.kt:24-30`, `android/core/src/main/kotlin/dev/macdroid/core/extras/RingSession.kt:24`, `android/app/src/main/java/dev/macdroid/app/platform/extras/Ringer.kt` (~136), `MediaWatcher.kt:137,150,189`, `LinkNotifications.kt:20`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/extras/ExtrasTest.kt`

- [ ] **Step 1: Failing test**
```kotlin
@Test fun linksSwiftAcceptsAreAllowed() {
    listOf("https://example.com/a|b", "https://example.com/{x}", "https://example.com/^", "https://bücher.de/").forEach {
        assertTrue(LinkExtractor.isAllowed(it), it)
    }
    assertFalse(LinkExtractor.isAllowed("javascript:alert(1)"))
    assertFalse(LinkExtractor.isAllowed("https://"))
}
```
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** `isAllowed` without `java.net.URI`: the scheme (before `:`) is `http` or `https`, case-insensitive; the text continues with `//`; the authority (up to the first `/?#`) is non-empty with no whitespace or control characters.
- [ ] **Step 4:** Test passes.
- [ ] **Step 5:** Platform fixes, each its own commit:
  - Ringer: the ring notification gets `setDeleteIntent` → `ACTION_STOP_RING`.
  - MediaWatcher: cache `appName(pkg)` in a `HashMap`. The art cache key adds `durationMs` and the bitmap's `width×height`, and a key whose bitmap is under 128 px isn't cached (no low-res placeholder sticks).
  - LinkNotifications: ids come from an `AtomicInteger` starting at 20_000 (the fixed ids are < 1000), not from `url.hashCode()`.
  - Remove `RingSession.timedOut` and its tests.
- [ ] **Step 6:** `:core:test :app:assembleDebug`; commit.

### Task 8: Small wins on the Mac and protocol (playback speed, seek snap-back, `MediaArt` prune, link throttle)

**Files:**
- Modify: `protocol/PROTOCOL.md`, `protocol/fixtures/` (new `media.state.speed.json`), `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift:191-215` (`MediaState`), `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt:95`, `android/app/src/main/java/dev/macdroid/app/platform/extras/MediaWatcher.kt:142`, `mac/MacDroidCore/Sources/MacDroidCore/Presentation/MediaPresentation.swift`, `mac/MacDroid/Views/Popover/NowPlayingCard.swift:27-32`, `mac/MacDroidCore/Sources/MacDroidCore/Notifications/IconCache.swift`, `mac/MacDroid/AppModel.swift:193`, `mac/MacDroidCore/Sources/MacDroidCore/Extras/LinkRules.swift`
- Test: `ProtocolFixtureTests.swift`, `MessageCodecTest.kt`, `ExtrasTests.swift`

**Interfaces:**
- Produces: `MediaState.speed: Double?` (Swift) / `val speed: Double? = null` (Kotlin), JSON key `speed`; `IconCache.prune(keeping: Int)`; `LinkThrottle` (`mutating func allows(at: Date) -> Bool`, at most 3 links per 10 s).

- [ ] **Step 1: Failing tests:** the fixture decodes `speed: 1.5` on both sides; `projectedPositionAdvancesAtSpeed` (2 s at 1.5× → +3,000 ms); `linkThrottleAllowsThreeIn10Seconds` (the 4th within 10 s refused, allowed again at 10 s); `pruneKeepsTheNewest` (8 files kept, oldest by modification date removed).
- [ ] **Step 2:** Run both suites → FAIL.
- [ ] **Step 3:** Implement:
  - Android sends `PlaybackState.playbackSpeed` as `speed`.
  - The Mac's projection multiplies elapsed time by `speed ?? 1`, treated as 1 when ≤ 0.
  - `NowPlayingCard` keeps the released seek value as `pendingSeek` until a `media.state` arrives whose position is within 2 s of it, or until 3 s pass.
  - `AppModel` calls `mediaArt.prune(keeping: 8)` after each art save.
  - Incoming `link.open` goes through `LinkThrottle`; refused links are logged and dropped.
- [ ] **Step 4:** All gates and the cross-platform test; commit.

### Task 9: File transfer on Android (2b minors and 2a "speed re-added")

**Files:**
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/MediaStoreSaveTarget.kt:60`, `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferEngine.kt` (createPart call and progress), `android/app/src/main/java/dev/macdroid/app/ui/files/SaveAsActivity.kt`, `android/app/src/main/java/dev/macdroid/app/ui/main/HomeScreen.kt` / `HomeViewModel.kt`, `android/app/src/main/java/dev/macdroid/app/ui/files/FilesViewModel.kt` (row tap), `android/app/src/main/java/dev/macdroid/app/platform/TreeSaveTarget.kt:53`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferEngineReceiveTest.kt`

- [ ] **Step 1: Failing tests:**
  - `cancelDuringCreatePartDeletesThePart`: a fake `SaveTarget` whose `createPart` suspends; cancel the transfer; assert the part's `delete()` was called.
  - `noSpeedAfterFinish`: a late progress callback after `Done` leaves `speedFor(id) == null`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Wrap the created part's cleanup in `withContext(NonCancellable)` when cancellation lands after `createPart` returns. Drop progress for ids no longer active.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** Platform fixes, each its own commit:
  - `MediaStorePart.finish` throws `IOException` when `update()` returns 0.
  - `SaveAsActivity` checks that the transfer is still pending before creating a document; if it's gone it shows "That transfer is no longer waiting" and finishes.
  - Home's Send while disconnected shows the "Connect to your Mac first" snackbar.
  - The row-tap existence check runs on `Dispatchers.IO`.
  - The `TreeSaveTarget` fallback passes `application/octet-stream` when `finalName` already has an extension, so the provider can't add another.
- [ ] **Step 6:** `:core:test :app:assembleDebug`; commit.

### Task 10: File transfer on the Mac (2a/2b minors)

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager.swift` (`forget(_:)` ~547, re-offer path ~401, checksum verify), `mac/MacDroidCore/Sources/MacDroidCore/Server/FileRoutes.swift:27,100`, `mac/MacDroid/Files/SecurityScopedSourceAccess.swift`, `mac/MacDroid/AppModel.swift:500`, `mac/MacDroid/Files/ReceivedNotifier.swift`, `mac/MacDroid/Notifications/NotificationRouter.swift:53`
- Test: `TransferReceiveTests.swift`, `FileRouteTests.swift`

- [ ] **Step 1: Failing tests:**
  - `forgetRemovesThePartFile`
  - `reofferDuringVerifyDoesNotRecreateThePart`: hold verification with an injected checksum gate, re-offer, then assert no new empty `.macdroid-part`.
  - `putRejectsARangeBeyondTheOfferedSize` (`Content-Range: bytes 0-9/20` for a 10-byte offer → 416)
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement:
  - `forget` deletes the part file.
  - A re-offer for an id in `.verifying` replies with the existing state and touches no files.
  - The PUT route checks that the range total equals the offered size and that end < total.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** App fixes, each its own commit:
  - `SecurityScopedSourceAccess` stops access in a `defer` that also runs when the response never starts (the route's cancellation handler).
  - History-row existence is cached per id in `AppModel` and refreshed only when that row's state changes or the Files page appears.
  - The notification's Open/Show checks that the file exists; if not, it shows "The file was moved or deleted".
  - Notification authorization is re-read on each `didBecomeActive`.
- [ ] **Step 6:** `swift test`, Mac app build, cross-platform test; commit.

### Task 11: Connection core and hardening (Plan 1: Ruling 8, Tasks 2, 4, 8, 9, 10, 11, 13)

**Files:**
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/NsdMacLocator.kt`, `android/core/src/main/kotlin/dev/macdroid/core/connection/ConnectionManager.kt:115-180,195-215`, `android/core/src/main/kotlin/dev/macdroid/core/tls/TrustManagers.kt` (fingerprint compare), `android/core/src/main/kotlin/dev/macdroid/core/pairing/PairingClient.kt` (body read), `PairingInvite.kt:33`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/app/src/main/AndroidManifest.xml`, create `android/app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`, `ConnectionService.kt` (boot receiver: add `MY_PACKAGE_REPLACED`), `mac/MacDroidCore/Sources/MacDroidCore/Security/IdentityStore.swift:55-90`, `mac/MacDroidCore/Sources/MacDroidCore/Network/BonjourAdvertiser.swift:15-50`, `mac/MacDroid/AppModel.swift:868`
- Test: `ConnectionManagerTest.kt`, `PairingTest.kt`, `TlsTest.kt`, `IdentityTests.swift`, `NetworkTests.swift`

**Interfaces:**
- Changes `MacLocator` semantics (KDoc): a long-lived flow; each emission is a **fresh appearance** (first sighting, or found again after being lost), and it may repeat the same address. `ConnectionManager` collects it once per loop generation into a `Channel.CONFLATED` and wakes on any appearance; a different address is also saved.
- Produces: `BonjourAdvertiser.onError: (@Sendable (String) -> Void)?`, `TXTRecord.entry(key:value:) -> [UInt8]` (≤ 255 bytes, cut on a UTF-8 boundary).

- [ ] **Step 1: Failing tests:**
  - `sameAddressReappearanceWakesTheRetry`: a fake locator emits the stored address once the loop is waiting with a 30 s backoff; assert a reconnect attempt within 1 s.
  - `staleNudgeIsDrainedOnHello`: nudge while connected, drop the connection, assert the first retry waits for the backoff.
  - `fingerprintPinIsCaseInsensitive`
  - `pairingResponseBodyIsBounded`: a 2 MiB body gives a failure, not an out-of-memory error; the cap is 64 KiB.
  - `normalizeCodeKeepsOnlyBase32`: `"ab-1 8c"` → `"ABC"`.
  - Swift: `keychainOverwriteKeepsTheItem` (`SecItemUpdate` path, then load gives the new value); `txtEntryCutsOnACharacterBoundary`.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement:
  - `NsdMacLocator` emits on `onServiceFound` after start or after `onServiceLost` for that service name, and no longer restarts discovery per wait.
  - `ConnectionManager` drains `nudges` when a hello arrives, and stores `socket` only after `hello` is sent.
  - `IdentityStore` tries `SecItemUpdate` first and falls back to `SecItemAdd` on `errSecItemNotFound`, with no delete before writing.
  - `BonjourAdvertiser` registers with a callback: on `kDNSServiceErr_NameConflict` or another error it calls `onError`, which `AppModel` turns into the existing Bonjour warning. It checks the `DNSServiceSetDispatchQueue` result.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** Hardening, each its own commit:
  - Gradle: remove `validateDistributionUrl=false`; add `distributionSha256Sum` with the published SHA-256 of `gradle-9.8.0-bin.zip`, taken from `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip.sha256`.
  - Manifest: `android:dataExtractionRules="@xml/data_extraction_rules"` and `android:fullBackupContent="@xml/backup_rules"`, both excluding the pairing, UI and transfer shared prefs (look up their names in `PrefsPairingStore`, `UiPrefs`, `FilesPrefs`) for cloud backup and device transfer.
  - The boot receiver also handles `android.intent.action.MY_PACKAGE_REPLACED`.
- [ ] **Step 6:** All gates and the cross-platform test; commit.

### Task 12: Leftover UI items (UI/UX redesign)

**Files:**
- Modify: `mac/MacDroid/Views/Phone/PairingView.swift`, `mac/MacDroid/Views/MenuBarPopover.swift` (Settings row), `android/app/src/main/java/dev/macdroid/app/ui/setup/SetupViewModel.kt:35-36,72,102`

- [ ] **Step 1:** `PairingView.onDisappear` → `Task { await model.cancelPairing() }`, unless pairing just succeeded, so the window doesn't stay open with no code on screen.
- [ ] **Step 2:** The popover's Settings row gets `.keyboardShortcut(",")`.
- [ ] **Step 3:** `SetupViewModel`: `welcomeDone`/`batteryDone` are backed by a `SavedStateHandle`, so process death resumes at the same step.
- [ ] **Step 4:** Mac app build, `:app:assembleDebug`; commit.

### Task 13: Final gates, handoff and owner check

- [ ] **Step 1:** Run every build gate and `scripts/cross-platform-test.sh`. All must pass with no warnings. Record the `swift test` count.
- [ ] **Step 2:** Handoff:
  - CHANGELOG Plan 11 entry, with the verification.
  - STATUS: `fix/backlog` built.
  - ARCHITECTURE: `MacLocator` semantics, `media.state.speed`, mirror volume routing.
  - DEV-GUIDE: the Gradle checksum gotcha (bumping Gradle means updating the sum).
  - Each follow-up list fully ticked or marked "kept".
- [ ] **Step 3:** Short owner check, on the device:
  1. While mirroring with sound, Mac Volume −/+ change the Mac's level, and the phone's speaker stays silent.
  2. Connect AirPods mid-mirror: the sound moves to them.
  3. Rotate to landscape: the window turns wide.
  4. Type é (⌥E E) and a 🇮🇳 flag, then backspace once to remove the flag.
  5. Sleep the Mac for 1 minute, then wake it: the phone reconnects within about 5 s.
  6. Swipe away the ring notification: ringing stops.
  7. Reinstall the Android app over itself: the connection comes back without opening the app.
- [ ] **Step 4:** Final whole-branch review, fix findings, then merge on the owner's word.
