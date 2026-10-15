# MacDroid Remote Control — Plan 8: Use the Phone from the Mirror Window (and Auto-Start)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Click, drag, scroll and type in the Mac's mirror window to use the phone; Back/Home/Recents/volume/lock buttons; and MacDroid presses Android's "Share screen" itself, so starting a mirror needs no tap on an unlocked phone.

**Architecture:**
- **Input path:** the Mac turns mouse, scroll and keys into `input.*` messages on the existing control WebSocket. They are sent only while streaming, and only when the phone reports its control service is on.
- **Phone side:** an Android accessibility service, "MacDroid control", plays them as gestures (`dispatchGesture` with continued strokes), global actions and text edits on the focused field. The same service presses the capture consent's "Share screen" while a session is waiting for consent.
- **Tested core:** pointer → gesture segments, segment coalescing, scroll swipes, text editing, consent-dialog matching (Android core); point mapping, key mapping, gating (Mac core).

**Tech Stack:**
- Android: `AccessibilityService` (`dispatchGesture`, `GestureDescription.StrokeDescription.continueStroke`, `performGlobalAction`, `AccessibilityNodeInfo.ACTION_SET_TEXT`/`ACTION_SET_SELECTION`), Kotlin.
- Mac: AppKit `NSView` mouse, scroll and key events inside the SwiftUI mirror window.

**Spec:** `docs/superpowers/specs/2026-10-14-macdroid-mirroring-control-design.md`: §3.1 step 4 (auto-Start), §3.5, §5.1 (input messages), §6.5, §7 (Android Remote control settings), §8 (control rows), §9. Probe results: `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`.

## Global Constraints

- **Branch:** continue on `feat/mirroring` (Plan 7 is there). Don't merge.
- **Handoff:** every task's commit updates `docs/handoff/`. Task 1 adds `## <date> — Plan 8: remote control (in progress on feat/mirroring)` at the top of CHANGELOG, and each later task adds a bullet under it.
- **Features** after Task 1: `files, notifications, clipboard, ring, links, media, photos, mirror, control`, in that order, on both sides.
- **Messages (spec §5.1):**
  - `input.pointer{action: down|move|up|cancel, x, y, t}` (x, y as 0–1 fractions of the picture; t in Mac ms)
  - `input.scroll{x, y, dx, dy}` (dx, dy as fractions of the picture, finger direction)
  - `input.key{key: back|home|recents|volumeUp|volumeDown|power|backspace}`
  - `input.text{text}`, at most 4,096 characters
- **Ruling:** a new message `input.notice{reason: noTextField}` (phone → Mac) carries spec §8's "Click a text field on the phone first", which the spec's message list didn't cover. Cost if wrong: one extra message type.
- **Gating:** the Mac sends input only while the session is `streaming`, the phone lists `control`, and `mirror.state.control == true`. The phone applies input only while its session is streaming.
- **Gestures:**
  - touch slop 10 px; tap = down/up within slop; long-press starts after 500 ms held within slop
  - drag segments are continued strokes; a segment is at least 1 ms and at most 100 ms
  - scroll = one 120 ms swipe, kept inside 2–98 % of the screen
- **Mac input:**
  - moves are sent at most every 8 ms (≤ 120 Hz); down and up are always sent
  - scroll deltas are accumulated and sent every 33 ms
  - typed text is batched for 30 ms
  - Return → `"\n"`, Delete → `backspace`, Esc → `back`, right-click → `back`, ⌘V → the Mac clipboard text
- **Auto-Start:**
  - armed only while the phone session is waiting for consent and the setting is on
  - presses `android:id/button1` inside a window containing `com.android.systemui:id/screen_share_permission_dialog`
  - fallback: a clickable node whose text is exactly "Share screen", "Start" or "Start now", inside a window that also shows a text containing "MacDroid"
  - never anything else
- **Accessibility service:**
  - label "MacDroid control"
  - `isAccessibilityTool="true"` (needed to see the consent dialog, per the probe)
  - `canPerformGestures`, `canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`
- **The service's privacy rule (spec §3.5):** it reads only the System UI consent window during auto-Start and the focused editable node when the Mac sends text. It never sends screen content anywhere.
- **Copy (Mac):**
  - bar tooltips "Back", "Home", "Recents", "Volume down", "Volume up", "Lock phone"
  - "Turn on MacDroid control on your phone to use the mouse."
  - "Click a text field on the phone first"
- **Copy (Android):**
  - Settings section "Remote control"
  - row "MacDroid control", with "On" / "Off: tap to turn on in Accessibility"
  - switch "Start mirroring without touching the phone"
  - row "Microphone for phone audio", with "Allowed" / "Not allowed"
  - service description: "Lets your paired Mac tap, scroll and type on this phone while you mirror it, and press Start on the screen-sharing prompt for you. MacDroid only acts when your Mac asks, and never sends what's on screen anywhere except to your Mac's mirror window."
- **Build flag:** `BuildConfig.STORE_BUILD` (false). When true, the auto-Start switch is hidden and off.
- **Gates:** `swift test`; the Mac app build with no warnings; `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` with no warnings; `./scripts/cross-platform-test.sh`.
- **The owner's phone:** don't automate taps on it or toggle its accessibility settings from adb; the owner turns the service on.

## Review Focus

1. **Auto-Start presses only Android's screen-share consent for MacDroid,** never a different dialog with a "Start" button, and never when no session is waiting → Task 2 `onlyTheMirroringConsentIsMatched`, Task 2 `notArmedWithoutAWaitingSession`.
2. **Input reaches the phone only during a live session with control on.** Stale clicks after Stop, or when the service is off, do nothing → Task 3 `inputIsSentOnlyWhileStreamingWithControl`, Task 2 `phoneIgnoresInputWhenNotStreaming`.
3. **A fast drag is not a stuttering series of separate swipes** (one continued stroke; queued segments coalesce) → Task 2 `aDragIsOneContinuedStroke`, `pendingMovesCoalesce`.
4. **Clicks in the black bars around the picture, or outside the window, aren't sent as taps at the screen edge** → Task 3 `pointsOutsideThePictureAreIgnored`.
5. **Typing emoji or Delete at a surrogate pair doesn't corrupt the text field** → Task 2 `backspaceRemovesAWholeEmoji`.

---

## File Structure

```
protocol/fixtures/input.pointer.json, input.scroll.json, input.key.json, input.text.json, input.notice.json (new); PROTOCOL.md (T1)
mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift, MessageCodec.swift                                        (T1)
mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorInput.swift (new: InputMapper, MirrorKeys); MirrorSession.swift       (T3)
mac/MacDroidCore/Sources/MacDroidCore/Presentation/MirrorPresentation.swift                                             (T3)
mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift; MirrorInputTests.swift (new)                        (T1, T3)
mac/MacDroid/Mirror/MirrorInputView.swift (new); Views/Mirror/MirrorContentView.swift; AppModel.swift                    (T5)
android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt, MessageCodec.kt                                      (T1)
android/core/src/main/kotlin/dev/macdroid/core/control/ControlRules.kt (new: GestureTracker, GestureSegment,
  SegmentQueue, ScrollGesture, TextEdit, ConsentMatcher, UiNode); mirror/MirrorRules.kt (awaitingConsent)               (T2)
android/core/src/test/kotlin/dev/macdroid/core/control/ControlRulesTest.kt (new)                                         (T2)
android/app/src/main/java/dev/macdroid/app/platform/control/MacDroidControlService.kt, GesturePlayer.kt, ControlPrefs.kt (new) (T4)
android/app/src/main/res/xml/control_service.xml, res/values/strings.xml (new); AndroidManifest.xml; build.gradle.kts  (T4)
android/app/src/main/java/dev/macdroid/app/platform/mirror/MirrorBridge.kt, MirrorService.kt; MacDroidApp.kt             (T4)
android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt, SettingsViewModel.kt                               (T4)
mac/MacDroidCore/Sources/MacDroidDevServer/main.swift; CrossPlatformTest.kt; scripts/cross-platform-test.sh              (T6)
docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md                                                         (T7)
```

---

### Task 1: Protocol — `input.*` messages and the `control` feature

**Files:**
- Create: `protocol/fixtures/input.pointer.json`, `input.scroll.json`, `input.key.json`, `input.text.json`, `input.notice.json`
- Modify: `Messages.swift`, `MessageCodec.swift`, `Messages.kt`, `MessageCodec.kt`, `PROTOCOL.md`
- Test: `ProtocolFixtureTests.swift`, `MessageCodecTest.kt`

**Interfaces (produces):**
- **Swift:**
  - `Feature.control`
  - `PointerAction` (`.down`, `.move`, `.up`, `.cancel`), `InputPointer(action:x:y:t:)` with `x`, `y` `Double` and `t` `Int64`
  - `InputScroll(x:y:dx:dy:)`, all `Double`
  - `InputKeyName` (`.back`, `.home`, `.recents`, `.volumeUp`, `.volumeDown`, `.power`, `.backspace`), `InputKey(key:)`
  - `InputText(text:)`
  - `InputNoticeReason` (`.noTextField`), `InputNotice(reason:)`
  - `Message` cases `.inputPointer`, `.inputScroll`, `.inputKey`, `.inputText`, `.inputNotice`
- **Kotlin:**
  - `Features.CONTROL`
  - `PointerAction { Down, Move, Up, Cancel }`, `InputPointerBody(action, x: Double, y: Double, t: Long)`
  - `InputScrollBody(x, y, dx, dy)`, all `Double`
  - `InputKeyName { Back, Home, Recents, VolumeUp, VolumeDown, Power, Backspace }`, `InputKeyBody(key)`
  - `InputTextBody(text)`
  - `InputNoticeReason { NoTextField }`, `InputNoticeBody(reason)`
  - `Message.InputPointer`, `InputScroll`, `InputKey`, `InputText`, `InputNotice`

- [ ] **Step 1: Fixtures** (ids `6f1c2a0e-0000-4000-8000-0000000000NN`, NN = 60–64)

```json
{ "v": 1, "type": "input.pointer", "id": "6f1c2a0e-0000-4000-8000-000000000060", "body": { "action": "down", "x": 0.25, "y": 0.5, "t": 123456 } }
```
```json
{ "v": 1, "type": "input.scroll", "id": "6f1c2a0e-0000-4000-8000-000000000061", "body": { "x": 0.5, "y": 0.6, "dx": 0, "dy": -0.125 } }
```
```json
{ "v": 1, "type": "input.key", "id": "6f1c2a0e-0000-4000-8000-000000000062", "body": { "key": "volumeUp" } }
```
```json
{ "v": 1, "type": "input.text", "id": "6f1c2a0e-0000-4000-8000-000000000063", "body": { "text": "On my way 🙂\n" } }
```
```json
{ "v": 1, "type": "input.notice", "id": "6f1c2a0e-0000-4000-8000-000000000064", "body": { "reason": "noTextField" } }
```

In `input.scroll`, `dx` is written as `0`. Both decoders read it as a `Double`, and the round-trip test compares JSON values. If the Swift round trip writes `0.0` and the comparison fails, change the fixture to `0.0`, since both encoders write doubles that way, and record it.

- [ ] **Step 2: Failing tests, both sides**

**Swift:**
- Add the 5 names to `messageFixtures`.
- Replace `supportedFeaturesIncludeMirror` with `supportedFeaturesIncludeControl`, expecting `[… Feature.photos, Feature.mirror, Feature.control]`.
- Add:

```swift
    @Test func inputFixturesDecode() throws {
        #expect(try MessageCodec.decode(Fixtures.text("input.pointer.json")) == .inputPointer(InputPointer(action: .down, x: 0.25, y: 0.5, t: 123_456)))
        #expect(try MessageCodec.decode(Fixtures.text("input.scroll.json")) == .inputScroll(InputScroll(x: 0.5, y: 0.6, dx: 0, dy: -0.125)))
        #expect(try MessageCodec.decode(Fixtures.text("input.key.json")) == .inputKey(InputKey(key: .volumeUp)))
        #expect(try MessageCodec.decode(Fixtures.text("input.text.json")) == .inputText(InputText(text: "On my way 🙂\n")))
        #expect(try MessageCodec.decode(Fixtures.text("input.notice.json")) == .inputNotice(InputNotice(reason: .noTextField)))
    }
```

**Kotlin:**
- Add the 5 names.
- Rename the features test to `supportedFeaturesIncludeControl`, expecting `listOf("files", "notifications", "clipboard", "ring", "links", "media", "photos", "mirror", "control")`.
- Add:

```kotlin
    @Test fun inputFixturesDecode() {
        assertEquals(Message.InputPointer(InputPointerBody(PointerAction.Down, 0.25, 0.5, 123_456)), MessageCodec.decode(Fixtures.text("input.pointer.json")))
        assertEquals(Message.InputScroll(InputScrollBody(0.5, 0.6, 0.0, -0.125)), MessageCodec.decode(Fixtures.text("input.scroll.json")))
        assertEquals(Message.InputKey(InputKeyBody(InputKeyName.VolumeUp)), MessageCodec.decode(Fixtures.text("input.key.json")))
        assertEquals(Message.InputText(InputTextBody("On my way 🙂\n")), MessageCodec.decode(Fixtures.text("input.text.json")))
        assertEquals(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField)), MessageCodec.decode(Fixtures.text("input.notice.json")))
    }
```

Run both. Expected: compile errors.

- [ ] **Step 3: Implement**

**Swift (`Messages.swift`):**
- Add `Feature.control = "control"` and append it to `ProtocolInfo.features`.
- Before `public enum Message`:

```swift
// MARK: Remote control (Plan 8)

public enum PointerAction: String, Codable, Sendable { case down, move, up, cancel }

/// A pointer event as 0–1 fractions of the mirrored picture (spec §5.1).
public struct InputPointer: Codable, Equatable, Sendable {
    public var action: PointerAction
    public var x: Double
    public var y: Double
    /// Mac milliseconds; only differences matter.
    public var t: Int64
    public init(action: PointerAction, x: Double, y: Double, t: Int64) {
        self.action = action
        self.x = x
        self.y = y
        self.t = t
    }
}

/// A scroll at (x, y); dx/dy are how far the content moves, as fractions of the picture, in finger direction.
public struct InputScroll: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double
    public var dx: Double
    public var dy: Double
    public init(x: Double, y: Double, dx: Double, dy: Double) {
        self.x = x
        self.y = y
        self.dx = dx
        self.dy = dy
    }
}

public enum InputKeyName: String, Codable, Sendable { case back, home, recents, volumeUp, volumeDown, power, backspace }

public struct InputKey: Codable, Equatable, Sendable {
    public var key: InputKeyName
    public init(key: InputKeyName) { self.key = key }
}

public struct InputText: Codable, Equatable, Sendable {
    public static let maxLength = 4096
    public var text: String
    public init(text: String) { self.text = text }
}

public enum InputNoticeReason: String, Codable, Sendable { case noTextField }

public struct InputNotice: Codable, Equatable, Sendable {
    public var reason: InputNoticeReason
    public init(reason: InputNoticeReason) { self.reason = reason }
}
```

- `Message` cases: `case inputPointer(InputPointer)`, `inputScroll(InputScroll)`, `inputKey(InputKey)`, `inputText(InputText)`, `inputNotice(InputNotice)`.
- `MessageCodec.swift`: encode and decode branches for `input.pointer`, `input.scroll`, `input.key`, `input.text` and `input.notice`, following the existing pattern.

**Kotlin (`Messages.kt`):**
- Add `const val CONTROL = "control"`, appended to `SUPPORTED`.
- Add:

```kotlin
// Remote control (Plan 8)

@Serializable enum class PointerAction { @SerialName("down") Down, @SerialName("move") Move, @SerialName("up") Up, @SerialName("cancel") Cancel }
@Serializable data class InputPointerBody(val action: PointerAction, val x: Double, val y: Double, val t: Long)
@Serializable data class InputScrollBody(val x: Double, val y: Double, val dx: Double, val dy: Double)
@Serializable enum class InputKeyName {
    @SerialName("back") Back, @SerialName("home") Home, @SerialName("recents") Recents, @SerialName("volumeUp") VolumeUp,
    @SerialName("volumeDown") VolumeDown, @SerialName("power") Power, @SerialName("backspace") Backspace,
}
@Serializable data class InputKeyBody(val key: InputKeyName)
@Serializable data class InputTextBody(val text: String)
@Serializable enum class InputNoticeReason { @SerialName("noTextField") NoTextField }
@Serializable data class InputNoticeBody(val reason: InputNoticeReason)
```

- `Message` subclasses: `InputPointer(body: InputPointerBody)`, `InputScroll(InputScrollBody)`, `InputKey(InputKeyBody)`, `InputText(InputTextBody)`, `InputNotice(InputNoticeBody)`.
- Codec branches for the five type strings.

Run `swift test` and `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 4: PROTOCOL.md, builds, handoff, commit**

- PROTOCOL.md: the 5 rows, `control` in the features sentence, and a "Remote control rules" section covering:
  - the gating
  - fractions of the picture
  - finger-direction scroll
  - the 4,096-character text limit
  - `input.notice` when no text field has focus
- Run the Mac app build and `assembleDebug`.
- CHANGELOG: the Plan 8 entry with "Task 1: `input.*` messages, `input.notice`, `control` feature in both codecs."

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): remote control input messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Android core — gestures, scroll, text edits, consent matching, gating

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/control/ControlRules.kt`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/mirror/MirrorRules.kt` (`MirrorPhoneSession.awaitingConsent`)
- Test: `android/core/src/test/kotlin/dev/macdroid/core/control/ControlRulesTest.kt`; `MirrorRulesTest.kt` (one test)

**Interfaces (produces):**
- `data class GestureSegment(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long, first: Boolean, last: Boolean)`.
- `class GestureTracker(slopPx: Float = 10f, longPressMs: Long = 500)`, with:
  - `down(x: Float, y: Float, tMs: Long): List<GestureSegment>`
  - `move(...)`, `up(...)` and `tick(tMs: Long)`, all returning `List<GestureSegment>`
  - `cancel(): List<GestureSegment>`
- `class SegmentQueue`, with `offer(segment)`, `next(): GestureSegment?` and `val isEmpty`. Middle segments (not first, not last) waiting together merge into one.
- `object ScrollGesture`, with `fun swipe(x: Double, y: Double, dx: Double, dy: Double, width: Int, height: Int): GestureSegment` (first and last, 120 ms, clamped to 2–98 %).
- `object TextEdit`:
  - `fun insert(current: String, selStart: Int, selEnd: Int, text: String): Pair<String, Int>`
  - `fun backspace(current: String, selStart: Int, selEnd: Int): Pair<String, Int>`
  - Selections below 0 mean the end of the text; results are (new text, cursor).
- `interface UiNode`, with `val viewId: String?`, `val text: String?`, `val clickable: Boolean`, `val children: List<UiNode>`.
- `object ConsentMatcher`, with `fun startButton(root: UiNode): UiNode?`.
- `MirrorPhoneSession.awaitingConsent: Boolean` (`current != null && !isStreaming`).
- `MirrorPhoneSession.controlChanged(control: Boolean)`: while streaming, re-sends the last `streaming` state with the new `control` value (the user turned the service on or off mid-session).

- [ ] **Step 1: Failing tests** (`ControlRulesTest.kt`)

```kotlin
package dev.macdroid.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {
    private fun seg(fx: Float, fy: Float, tx: Float, ty: Float, d: Long, first: Boolean, last: Boolean) = GestureSegment(fx, fy, tx, ty, d, first, last)

    @Test fun aQuickClickIsATap() {
        val t = GestureTracker()
        assertEquals(emptyList<GestureSegment>(), t.down(100f, 200f, 0))
        assertEquals(emptyList<GestureSegment>(), t.move(104f, 203f, 30)) // within slop
        assertEquals(listOf(seg(100f, 200f, 100f, 200f, 50, first = true, last = true)), t.up(104f, 203f, 80))
    }

    // Review Focus #3
    @Test fun aDragIsOneContinuedStroke() {
        val t = GestureTracker()
        t.down(100f, 200f, 0)
        assertEquals(listOf(seg(100f, 200f, 100f, 260f, 40, first = true, last = false)), t.move(100f, 260f, 40))
        assertEquals(listOf(seg(100f, 260f, 100f, 320f, 16, first = false, last = false)), t.move(100f, 320f, 56))
        assertEquals(listOf(seg(100f, 320f, 100f, 340f, 1, first = false, last = true)), t.up(100f, 340f, 56))
    }

    @Test fun holdingStillIsALongPress() {
        val t = GestureTracker()
        t.down(50f, 50f, 0)
        assertEquals(emptyList<GestureSegment>(), t.tick(499))
        assertEquals(listOf(seg(50f, 50f, 50f, 50f, 100, first = true, last = false)), t.tick(500))
        assertEquals(listOf(seg(50f, 50f, 50f, 50f, 100, first = false, last = true)), t.up(50f, 50f, 900))
    }

    @Test fun segmentDurationsAreClamped() {
        val t = GestureTracker()
        t.down(0f, 0f, 0)
        assertEquals(100, t.move(0f, 500f, 5_000).single().durationMs)
    }

    @Test fun cancelEndsAStrokeInProgress() {
        val t = GestureTracker()
        t.down(0f, 0f, 0)
        t.move(0f, 50f, 20)
        assertEquals(listOf(seg(0f, 50f, 0f, 50f, 1, first = false, last = true)), t.cancel())
        assertEquals(emptyList<GestureSegment>(), t.cancel())
    }

    // Review Focus #3
    @Test fun pendingMovesCoalesce() {
        val q = SegmentQueue()
        q.offer(seg(0f, 0f, 0f, 10f, 10, first = true, last = false))
        q.offer(seg(0f, 10f, 0f, 20f, 10, first = false, last = false))
        q.offer(seg(0f, 20f, 0f, 30f, 10, first = false, last = false))
        q.offer(seg(0f, 30f, 0f, 35f, 5, first = false, last = true))
        assertEquals(seg(0f, 0f, 0f, 10f, 10, first = true, last = false), q.next())
        assertEquals(seg(0f, 10f, 0f, 30f, 20, first = false, last = false), q.next())
        assertEquals(seg(0f, 30f, 0f, 35f, 5, first = false, last = true), q.next())
        assertNull(q.next())
        assertTrue(q.isEmpty)
    }

    @Test fun scrollIsAShortClampedSwipe() {
        assertEquals(seg(540f, 1200f, 540f, 900f, 120, first = true, last = true), ScrollGesture.swipe(0.5, 0.5, 0.0, -0.125, 1080, 2400))
        assertEquals(seg(540f, 2352f, 540f, 48f, 120, first = true, last = true), ScrollGesture.swipe(0.5, 0.99, 0.0, -5.0, 1080, 2400))
    }

    @Test fun textGoesInAtTheCursor() {
        assertEquals("helXlo" to 4, TextEdit.insert("hello", 3, 3, "X"))
        assertEquals("hXo" to 2, TextEdit.insert("hello", 1, 4, "X")) // replaces the selection
        assertEquals("helloX" to 6, TextEdit.insert("hello", -1, -1, "X")) // no cursor: append
        assertEquals("hllo" to 1, TextEdit.backspace("hello", 2, 2))
        assertEquals("ho" to 1, TextEdit.backspace("hello", 1, 4))
        assertEquals("hello" to 0, TextEdit.backspace("hello", 0, 0))
    }

    // Review Focus #5
    @Test fun backspaceRemovesAWholeEmoji() {
        val text = "hi 🙂"
        assertEquals("hi " to 3, TextEdit.backspace(text, text.length, text.length))
    }

    private data class Node(override val viewId: String? = null, override val text: String? = null, override val clickable: Boolean = false,
                            override val children: List<UiNode> = emptyList()) : UiNode

    // Review Focus #1
    @Test fun onlyTheMirroringConsentIsMatched() {
        val share = Node("android:id/button1", "Share screen", clickable = true)
        val dialog = Node(children = listOf(Node("com.android.systemui:id/screen_share_permission_dialog", children = listOf(
            Node(text = "Share your screen with MacDroid?"), Node("android:id/button2", "Cancel", true), share))))
        assertSame(share, ConsentMatcher.startButton(dialog))
        val older = Node(children = listOf(Node(text = "Start recording or casting with MacDroid?"), Node(null, "Start now", true)))
        assertEquals("Start now", ConsentMatcher.startButton(older)?.text)
        val other = Node(children = listOf(Node(text = "Start a workout?"), Node(null, "Start", true)))
        assertNull(ConsentMatcher.startButton(other))
        val otherApp = Node(children = listOf(Node(text = "Share your screen with Zoom?"), Node(null, "Share screen", true)))
        assertNull(ConsentMatcher.startButton(otherApp))
    }
}
```

In `MirrorRulesTest.kt` add:

```kotlin
    // Review Focus #1 (arming) and #2 (gating)
    @Test fun notArmedWithoutAWaitingSession() {
        val s = session()
        assertFalse(s.awaitingConsent)
        s.start(start)
        assertTrue(s.awaitingConsent)
        s.streaming(864, 1920, 0, audio = false, control = true)
        assertFalse(s.awaitingConsent)
        s.ended(MirrorStopReason.User)
        assertFalse(s.awaitingConsent)
    }

    @Test fun controlChangeResendsTheState() {
        val s = session()
        s.controlChanged(true) // no session: nothing
        assertTrue(sent.isEmpty())
        s.start(start)
        s.streaming(864, 1920, 90, audio = true, control = false)
        s.controlChanged(true)
        assertEquals(Message.MirrorState(MirrorStateBody("s1", MirrorPhaseName.Streaming, 864, 1920, 90, audio = true, control = true)), sent.last())
    }

    @Test fun phoneIgnoresInputWhenNotStreaming() {
        val s = session()
        assertFalse(s.isStreaming)
        s.start(start)
        assertFalse(s.isStreaming) // waiting for consent: no input yet
    }
```

Run `./gradlew -q :core:compileTestKotlin`. Expected: unresolved references.

- [ ] **Step 2: Implement** `ControlRules.kt`

```kotlin
package dev.macdroid.core.control

/** One piece of a stroke for `dispatchGesture` (spec §3.5). [first] starts a stroke; [last] lifts the finger. */
data class GestureSegment(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMs: Long,
    val first: Boolean,
    val last: Boolean,
)

/**
 * Mac pointer events → stroke segments: a tap, a long-press (held 500 ms within slop), or a drag that follows the mouse
 * as one continued stroke. Not thread-safe.
 */
class GestureTracker(private val slopPx: Float = 10f, private val longPressMs: Long = 500) {
    private enum class State { Idle, Pending, Stroking }
    private var state = State.Idle
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L

    fun down(x: Float, y: Float, tMs: Long): List<GestureSegment> {
        val ended = cancel()
        state = State.Pending
        downX = x; downY = y; downT = tMs
        lastX = x; lastY = y; lastT = tMs
        return ended
    }

    fun move(x: Float, y: Float, tMs: Long): List<GestureSegment> = when (state) {
        State.Idle -> emptyList()
        State.Pending ->
            if (distance(x, y, downX, downY) <= slopPx) emptyList()
            else listOf(segment(downX, downY, x, y, tMs - downT, first = true, last = false)).also { stroking(x, y, tMs) }
        State.Stroking -> listOf(segment(lastX, lastY, x, y, tMs - lastT, first = false, last = false)).also { stroking(x, y, tMs) }
    }

    fun up(x: Float, y: Float, tMs: Long): List<GestureSegment> {
        val out = when (state) {
            State.Idle -> emptyList()
            State.Pending -> listOf(GestureSegment(downX, downY, downX, downY, TAP_MS, first = true, last = true))
            State.Stroking -> listOf(segment(lastX, lastY, x, y, tMs - lastT, first = false, last = true))
        }
        state = State.Idle
        return out
    }

    /** Call regularly while a button is down: starts a long-press once held still long enough. */
    fun tick(tMs: Long): List<GestureSegment> {
        if (state != State.Pending || tMs - downT < longPressMs) return emptyList()
        stroking(downX, downY, tMs)
        return listOf(GestureSegment(downX, downY, downX, downY, MAX_SEGMENT_MS, first = true, last = false))
    }

    fun cancel(): List<GestureSegment> {
        val out = if (state == State.Stroking) listOf(GestureSegment(lastX, lastY, lastX, lastY, 1, first = false, last = true)) else emptyList()
        state = State.Idle
        return out
    }

    private fun stroking(x: Float, y: Float, tMs: Long) {
        state = State.Stroking
        lastX = x; lastY = y; lastT = tMs
    }

    private fun segment(fx: Float, fy: Float, tx: Float, ty: Float, elapsed: Long, first: Boolean, last: Boolean) =
        GestureSegment(fx, fy, tx, ty, elapsed.coerceIn(1, MAX_SEGMENT_MS), first, last)

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.hypot(ax - bx, ay - by)

    companion object {
        const val TAP_MS = 50L
        const val MAX_SEGMENT_MS = 100L
    }
}

/**
 * Segments wait here while the previous one plays. Middle segments that pile up merge into one, so a fast drag stays smooth
 * instead of replaying every mouse sample late (Review Focus #3).
 */
class SegmentQueue {
    private val items = ArrayDeque<GestureSegment>()
    val isEmpty: Boolean get() = items.isEmpty()

    @Synchronized fun offer(segment: GestureSegment) {
        val tail = items.lastOrNull()
        if (tail != null && !tail.first && !tail.last && !segment.first && !segment.last) {
            items[items.lastIndex] = tail.copy(toX = segment.toX, toY = segment.toY,
                durationMs = (tail.durationMs + segment.durationMs).coerceAtMost(GestureTracker.MAX_SEGMENT_MS))
        } else {
            items.addLast(segment)
        }
    }

    @Synchronized fun next(): GestureSegment? = items.removeFirstOrNull()
}

/** A scroll as one short swipe in finger direction, kept on screen (spec §3.5). */
object ScrollGesture {
    const val DURATION_MS = 120L

    fun swipe(x: Double, y: Double, dx: Double, dy: Double, width: Int, height: Int): GestureSegment {
        fun clamp(v: Double) = v.coerceIn(0.02, 0.98)
        val fx = clamp(x); val fy = clamp(y)
        return GestureSegment((fx * width).toFloat(), (fy * height).toFloat(), (clamp(fx + dx) * width).toFloat(), (clamp(fy + dy) * height).toFloat(),
            DURATION_MS, first = true, last = true)
    }
}

/** Typing into the focused field (spec §3.5): insert at the cursor or over the selection; delete whole characters. */
object TextEdit {
    fun insert(current: String, selStart: Int, selEnd: Int, text: String): Pair<String, Int> {
        val (start, end) = range(current, selStart, selEnd)
        return (current.substring(0, start) + text + current.substring(end)) to start + text.length
    }

    fun backspace(current: String, selStart: Int, selEnd: Int): Pair<String, Int> {
        val (start, end) = range(current, selStart, selEnd)
        if (start != end) return (current.substring(0, start) + current.substring(end)) to start
        if (start == 0) return current to 0
        val before = current.offsetByCodePoints(start, -1) // a whole emoji, not half of it (Review Focus #5)
        return (current.substring(0, before) + current.substring(start)) to before
    }

    private fun range(current: String, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = if (selStart < 0) current.length else selStart.coerceAtMost(current.length)
        val b = if (selEnd < 0) current.length else selEnd.coerceAtMost(current.length)
        return minOf(a, b) to maxOf(a, b)
    }
}

/** What [ConsentMatcher] needs from an accessibility node (a fake in tests). */
interface UiNode {
    val viewId: String?
    val text: String?
    val clickable: Boolean
    val children: List<UiNode>
}

/**
 * Finds the "Share screen" button on Android's capture consent for MacDroid, and nothing else (spec §3.1 step 4, probe Q1b).
 */
object ConsentMatcher {
    private const val CONTAINER = "com.android.systemui:id/screen_share_permission_dialog"
    private const val POSITIVE = "android:id/button1"
    private val labels = setOf("share screen", "start", "start now")

    fun startButton(root: UiNode): UiNode? {
        val nodes = flatten(root)
        if (nodes.any { it.viewId == CONTAINER }) return nodes.firstOrNull { it.viewId == POSITIVE && it.clickable }
        // Older dialogs without the container id: only when the dialog names MacDroid.
        if (nodes.none { it.text?.contains("MacDroid") == true }) return null
        return nodes.firstOrNull { it.clickable && it.text?.trim()?.lowercase() in labels }
    }

    private fun flatten(node: UiNode, depth: Int = 0): List<UiNode> =
        if (depth > 30) emptyList() else listOf(node) + node.children.flatMap { flatten(it, depth + 1) }
}
```

The container case trusts the System UI dialog id. The matcher service only reads System UI windows (Task 4), so this can only be a screen-share consent. The probe showed the container dialog names the requesting app in its title. If a future System UI reuses the container for another app's request, the window is still MacDroid's: only MacDroid's own capture request opens it while the session is waiting.

In `MirrorRules.kt`, add to `MirrorPhoneSession`:

```kotlin
    /** A session is waiting for the capture consent: the only time auto-Start may act (spec §3.1). */
    val awaitingConsent: Boolean get() = current != null && !isStreaming

    private var lastStreaming: MirrorStateBody? = null

    /** The control service was turned on or off mid-session: tell the Mac (spec §5.1 `control`). */
    fun controlChanged(control: Boolean) {
        val last = lastStreaming ?: return
        if (!isStreaming || last.sessionId != current) return
        lastStreaming = last.copy(control = control)
        send(Message.MirrorState(lastStreaming!!))
    }
```

In `streaming(...)`, build the body once, keep it in `lastStreaming`, and send it:

```kotlin
    fun streaming(width: Int, height: Int, rotation: Int, audio: Boolean, control: Boolean) {
        val id = current ?: return
        isStreaming = true
        val body = MirrorStateBody(id, MirrorPhaseName.Streaming, width, height, rotation, audio = audio, control = control)
        lastStreaming = body
        send(Message.MirrorState(body))
    }
```

In `clear()`, add `lastStreaming = null`.

Run `./gradlew :core:test`. Expected: PASS. If `offsetByCodePoints` or the expected scroll numbers disagree, check the maths: `0.5 × 2400 = 1200`, then `1200 − 0.125 × 2400 = 900`, and the clamp gives `0.98 × 2400 = 2352` and `0.02 × 2400 = 48`. Fix the code, not the expectation.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 2: Android core control rules: `GestureTracker` (tap, long-press, continued drag strokes), `SegmentQueue` (coalescing), `ScrollGesture`, `TextEdit` (cursor, selection, whole-emoji delete), `ConsentMatcher` (only MacDroid's screen-share consent), `MirrorPhoneSession.awaitingConsent`."

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): gesture, text and consent rules for remote control

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Mac core — mapping points and keys, gating input

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Mirror/MirrorInput.swift`
- Modify: `Mirror/MirrorSession.swift`, `Presentation/MirrorPresentation.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/MirrorInputTests.swift`

**Interfaces:**
- **Consumes:** Task 1 messages.
- **Produces:**
  - `enum InputMapper`, with:
    - `static func fraction(of point: CGPoint, in view: CGSize, picture: CGSize) -> CGPoint?` (aspect-fit, top-left origin, nil outside the picture)
    - `static func scroll(deltaX: CGFloat, deltaY: CGFloat, in view: CGSize) -> (dx: Double, dy: Double)`
  - `struct MoveThrottle`, with `mutating func allows(atMillis: Int64) -> Bool` (≤ 120 Hz).
  - `enum MirrorKeyAction: Equatable` (`.text(String)`, `.key(InputKeyName)`, `.paste`).
  - `enum MirrorKeys`, with `static func action(keyCode: UInt16, characters: String?, command: Bool) -> MirrorKeyAction?`.
  - `MirrorSession`:
    - `func sendInput(_ message: Message) async -> Bool` (false and nothing sent unless the session is streaming, the phone lists `control` and `state.control == true`)
    - `var controlNotice: Bool` (set by `input.notice`; cleared on the next text)
  - `MirrorState.controlAvailable: Bool`.
  - `MirrorPresentation.controlNotice(_ state: MirrorState) -> String?` and `textFieldNotice: String`.

- [ ] **Step 1: Failing tests** (`MirrorInputTests.swift`)

```swift
import CoreGraphics
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct MirrorInputTests {
    @Test func pointsMapToFractionsOfThePicture() {
        // A 1080×2400 picture shown in a 540×1200 view: exact fit.
        let p = InputMapper.fraction(of: CGPoint(x: 135, y: 600), in: CGSize(width: 540, height: 1200), picture: CGSize(width: 1080, height: 2400))
        #expect(p == CGPoint(x: 0.25, y: 0.5))
    }

    // Review Focus #4
    @Test func pointsOutsideThePictureAreIgnored() {
        // Portrait picture in a wide view: black bars left and right (picture is 450 wide, centred → 75…525).
        let view = CGSize(width: 600, height: 1000), picture = CGSize(width: 900, height: 2000)
        #expect(InputMapper.fraction(of: CGPoint(x: 50, y: 500), in: view, picture: picture) == nil)
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: 500), in: view, picture: picture) == CGPoint(x: 0.5, y: 0.5))
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: -1), in: view, picture: picture) == nil)
        #expect(InputMapper.fraction(of: CGPoint(x: 300, y: 500), in: view, picture: .zero) == nil)
    }

    @Test func scrollIsAFractionOfTheView() {
        let s = InputMapper.scroll(deltaX: 0, deltaY: -150, in: CGSize(width: 500, height: 1200))
        #expect(s.dx == 0)
        #expect(s.dy == -0.125)
    }

    @Test func movesAreThrottledTo120Hz() {
        var throttle = MoveThrottle()
        #expect(throttle.allows(atMillis: 0))
        #expect(!throttle.allows(atMillis: 5))
        #expect(throttle.allows(atMillis: 9))
    }

    @Test func keysMapToActions() {
        #expect(MirrorKeys.action(keyCode: 0, characters: "a", command: false) == .text("a"))
        #expect(MirrorKeys.action(keyCode: 36, characters: "\r", command: false) == .text("\n"))
        #expect(MirrorKeys.action(keyCode: 51, characters: "\u{7F}", command: false) == .key(.backspace))
        #expect(MirrorKeys.action(keyCode: 53, characters: "\u{1B}", command: false) == .key(.back))
        #expect(MirrorKeys.action(keyCode: 9, characters: "v", command: true) == .paste)
        #expect(MirrorKeys.action(keyCode: 0, characters: "a", command: true) == nil) // other shortcuts stay on the Mac
        #expect(MirrorKeys.action(keyCode: 123, characters: "\u{F702}", command: false) == nil) // arrows: not sent
    }

    func session(features: [String] = ProtocolInfo.features) async -> (MirrorSession, Collector<Message>) {
        let sent = Collector<Message>()
        let s = MirrorSession(makeId: { "s1" }, send: { await sent.append($0) })
        await s.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.8.0", features: features)))
        return (s, sent)
    }

    // Review Focus #2
    @Test func inputIsSentOnlyWhileStreamingWithControl() async {
        let (s, sent) = await session()
        let tap = Message.inputPointer(InputPointer(action: .down, x: 0.5, y: 0.5, t: 1))
        #expect(await !s.sendInput(tap)) // no session
        _ = await s.start(MirrorOptions())
        #expect(await !s.sendInput(tap)) // waiting for consent
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: false)))
        #expect(await !s.sendInput(tap)) // control service off
        #expect(await !s.state.controlAvailable)
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        #expect(await s.state.controlAvailable)
        #expect(await s.sendInput(tap))
        await s.stop()
        #expect(await !s.sendInput(tap)) // stopped
        #expect(await sent.items.filter { if case .inputPointer = $0 { true } else { false } }.count == 1)
    }

    @Test func aPhoneWithoutControlGetsNoInput() async {
        let (s, _) = await session(features: ["files", "mirror"])
        _ = await s.start(MirrorOptions())
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        #expect(await !s.state.controlAvailable)
        #expect(await !s.sendInput(.inputKey(InputKey(key: .home))))
    }

    @Test func noTextFieldNoticeShowsUntilTheNextText() async {
        let (s, _) = await session()
        _ = await s.start(MirrorOptions())
        await s.handle(.mirrorState(MirrorStateUpdate(sessionId: "s1", state: .streaming, width: 1, height: 1, rotation: 0, control: true)))
        await s.handle(.inputNotice(InputNotice(reason: .noTextField)))
        #expect(await s.state.textFieldMissing)
        #expect(MirrorPresentation.textFieldNotice == "Click a text field on the phone first")
        _ = await s.sendInput(.inputText(InputText(text: "x")))
        #expect(await !s.state.textFieldMissing)
    }

    @Test func controlOffNotice() {
        var state = MirrorState()
        state.phase = .streaming(width: 1, height: 1, rotation: 0)
        state.connected = true
        state.phoneFeatures = [Feature.mirror, Feature.control]
        state.control = false
        #expect(MirrorPresentation.controlNotice(state) == "Turn on MacDroid control on your phone to use the mouse.")
        state.control = true
        #expect(MirrorPresentation.controlNotice(state) == nil)
    }
}
```

The Interfaces block names `MirrorState.textFieldMissing: Bool` as the notice flag. The test uses that name.

Run `swift test --filter MirrorInputTests`. Expected: compile errors.

- [ ] **Step 2: Implement**

`MirrorInput.swift`:

```swift
import CoreGraphics

/// Mac view coordinates → the phone picture (spec §6.5). Points are top-left origin (the input view is flipped).
public enum InputMapper {
    /// The point as 0–1 fractions of the picture drawn aspect-fit in the view, or nil in the black bars (Review Focus #4).
    public static func fraction(of point: CGPoint, in view: CGSize, picture: CGSize) -> CGPoint? {
        guard view.width > 0, view.height > 0, picture.width > 0, picture.height > 0 else { return nil }
        let scale = min(view.width / picture.width, view.height / picture.height)
        let shown = CGSize(width: picture.width * scale, height: picture.height * scale)
        let origin = CGPoint(x: (view.width - shown.width) / 2, y: (view.height - shown.height) / 2)
        let x = (point.x - origin.x) / shown.width
        let y = (point.y - origin.y) / shown.height
        guard (0...1).contains(x), (0...1).contains(y) else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// Scroll deltas (content direction, as macOS reports them with natural scrolling) as fractions of the view.
    public static func scroll(deltaX: CGFloat, deltaY: CGFloat, in view: CGSize) -> (dx: Double, dy: Double) {
        guard view.width > 0, view.height > 0 else { return (0, 0) }
        return (Double(deltaX / view.width), Double(deltaY / view.height))
    }
}

/// Drags are sent at most 120 times a second; down and up always go (spec §6.5).
public struct MoveThrottle: Sendable {
    public static let intervalMillis: Int64 = 8
    private var last: Int64?
    public init() {}

    public mutating func allows(atMillis now: Int64) -> Bool {
        if let last, now - last < Self.intervalMillis { return false }
        last = now
        return true
    }
}

public enum MirrorKeyAction: Equatable, Sendable {
    case text(String)
    case key(InputKeyName)
    case paste
}

/// Mac keys → phone input (spec §6.5). Other ⌘ shortcuts and arrow/function keys stay on the Mac.
public enum MirrorKeys {
    public static func action(keyCode: UInt16, characters: String?, command: Bool) -> MirrorKeyAction? {
        if command { return keyCode == 9 ? .paste : nil } // ⌘V
        switch keyCode {
        case 36, 76: return .text("\n") // Return, Enter
        case 51: return .key(.backspace) // Delete
        case 53: return .key(.back) // Esc
        default: break
        }
        guard let characters, !characters.isEmpty,
              characters.unicodeScalars.allSatisfy({ !(0xF700...0xF8FF).contains($0.value) && $0.value >= 0x20 && $0.value != 0x7F })
        else { return nil }
        return .text(characters)
    }
}
```

`MirrorSession.swift`:
- `MirrorState` gets `public var textFieldMissing = false` and:

```swift
    /// Input can go to the phone: streaming, the phone has `control`, and its control service is on.
    public var controlAvailable: Bool {
        guard case .streaming = phase else { return false }
        return phoneFeatures.contains(Feature.control) && control == true
    }
```

- In `MirrorSession`:

```swift
    /// Sends an `input.*` message only while control is available (Review Focus #2).
    public func sendInput(_ message: Message) async -> Bool {
        guard state.controlAvailable else { return false }
        if case .inputText = message, state.textFieldMissing { update { $0.textFieldMissing = false } }
        do {
            try await send(message)
            return true
        } catch {
            return false
        }
    }
```

- In `handle(_:)`: `case .inputNotice(let body): if body.reason == .noTextField, state.isActive { update { $0.textFieldMissing = true } }`.
- In `finish(_:)`: also reset `textFieldMissing = false` inside the `update`.

`MirrorPresentation.swift`:

```swift
    public static let textFieldNotice = "Click a text field on the phone first"

    /// Streaming to a phone that supports control, but its control service is off (spec §8).
    public static func controlNotice(_ state: MirrorState) -> String? {
        guard case .streaming = state.phase, state.phoneFeatures.contains(Feature.control), state.control != true else { return nil }
        return "Turn on MacDroid control on your phone to use the mouse."
    }
```

Run `swift test`. Expected: PASS. Run the Mac app build. Expected: no warnings.

- [ ] **Step 3: Handoff, commit**

CHANGELOG bullet: "Task 3: Mac core input: `InputMapper` (aspect-fit fractions, nothing in the black bars, scroll fractions), `MoveThrottle` (≤ 120 Hz), `MirrorKeys`, `MirrorSession.sendInput` gated on streaming + `control` + the phone's service, and the control/text-field notices."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): map and gate remote control input

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 4: Android — the "MacDroid control" service, auto-Start and Settings → Remote control

**Files:**
- Create:
  - `android/app/src/main/java/dev/macdroid/app/platform/control/MacDroidControlService.kt`, `GesturePlayer.kt`, `ControlPrefs.kt`, `NodeAdapter.kt`
  - `android/app/src/main/java/dev/macdroid/app/platform/mirror/ScreenSize.kt`
  - `android/app/src/main/res/xml/control_service.xml`, `android/app/src/main/res/values/strings.xml`
- Modify:
  - `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`
  - `platform/mirror/MirrorBridge.kt`, `platform/mirror/MirrorService.kt`
  - `ui/main/SettingsScreen.kt`, `ui/main/SettingsViewModel.kt`

**Interfaces:**
- **Consumes:**
  - Task 1 messages
  - Task 2 `GestureTracker`, `SegmentQueue`, `GestureSegment`, `ScrollGesture`, `TextEdit`, `ConsentMatcher`, `UiNode`, `MirrorPhoneSession.awaitingConsent`, `controlChanged`
- **Produces:**
  - `MacDroidControlService.running: StateFlow<Boolean>`, `MacDroidControlService.handle(message: Message)` (main thread)
  - `ControlPrefs.autoStart(context)` / `setAutoStart(context, on)`
  - `ScreenSize.current(context): Triple<Int, Int, Int>` (width, height in the current orientation, rotation)

This is Android glue with no JVM unit tests; its logic is in Task 2. It's verified by the build and the owner checklist. Don't toggle the service on the owner's phone from adb.

- [ ] **Step 1: Resources, manifest, build flag**

`res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="control_service_description">Lets your paired Mac tap, scroll and type on this phone while you mirror it, and press Start on the screen-sharing prompt for you. MacDroid only acts when your Mac asks, and never sends what\'s on screen anywhere except to your Mac\'s mirror window.</string>
</resources>
```

`res/xml/control_service.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:packageNames="com.android.systemui"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:isAccessibilityTool="true"
    android:notificationTimeout="50"
    android:description="@string/control_service_description" />
```

`packageNames` limits *events* to System UI (auto-Start). Gestures, global actions and `findFocus` still work in every app.

Manifest, inside `<application>`:

```xml
        <service
            android:name=".platform.control.MacDroidControlService"
            android:exported="false"
            android:label="MacDroid control"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/control_service" />
        </service>
```

`android/app/build.gradle.kts`, in `defaultConfig`:

```kotlin
        buildConfigField("boolean", "STORE_BUILD", "false")
```

- [ ] **Step 2: Shared screen size**

`platform/mirror/ScreenSize.kt` (moved out of `MirrorService.screenSize`, which now calls it):

```kotlin
package dev.macdroid.app.platform.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/** The default display's size as currently shown (natural size swapped when rotated) and its rotation in degrees. */
object ScreenSize {
    fun current(context: Context): Triple<Int, Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val rotation = when (display.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val short = minOf(mode.physicalWidth, mode.physicalHeight)
        val long = maxOf(mode.physicalWidth, mode.physicalHeight)
        return if (rotation == 90 || rotation == 270) Triple(long, short, rotation) else Triple(short, long, rotation)
    }
}
```

In `MirrorService`, replace the body of `screenSize(maxSize)` with:

```kotlin
        val (w, h, rotation) = ScreenSize.current(this)
        val (fw, fh) = MirrorSize.fit(w, h, maxSize)
        return Triple(fw, fh, rotation)
```

Remove imports that are now unused (`Surface`, `Display` if nothing else uses them; `Display.DEFAULT_DISPLAY` is still used by the display listener).

- [ ] **Step 3: Prefs, node adapter, gesture player**

`ControlPrefs.kt`:

```kotlin
package dev.macdroid.app.platform.control

import android.content.Context
import dev.macdroid.app.BuildConfig

/** "Start mirroring without touching the phone" (spec §7); always off in store builds. */
object ControlPrefs {
    private const val PREFS = "control"
    private const val AUTO_START = "autoStart"

    fun autoStart(context: Context): Boolean =
        !BuildConfig.STORE_BUILD && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_START, true)

    fun setAutoStart(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_START, on).apply()
    }
}
```

`NodeAdapter.kt`:

```kotlin
package dev.macdroid.app.platform.control

import android.view.accessibility.AccessibilityNodeInfo
import dev.macdroid.core.control.UiNode

/** An accessibility node seen through [UiNode], for [dev.macdroid.core.control.ConsentMatcher]. */
class NodeAdapter(val node: AccessibilityNodeInfo) : UiNode {
    override val viewId: String? get() = node.viewIdResourceName
    override val text: String? get() = node.text?.toString()
    override val clickable: Boolean get() = node.isClickable
    override val children: List<UiNode> get() = (0 until node.childCount).mapNotNull { node.getChild(it)?.let(::NodeAdapter) }
}
```

`GesturePlayer.kt`:

```kotlin
package dev.macdroid.app.platform.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import dev.macdroid.core.control.GestureSegment
import dev.macdroid.core.control.SegmentQueue

/**
 * Plays [GestureSegment]s one at a time; a drag is one continued stroke (spec §3.5). Segments that arrive while one plays
 * wait in a [SegmentQueue], which merges them so the finger keeps up with the mouse. Main thread.
 */
class GesturePlayer(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = SegmentQueue()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var playing = false

    fun play(segments: List<GestureSegment>) {
        segments.forEach(queue::offer)
        pump()
    }

    private fun pump() {
        if (playing) return
        val segment = queue.next() ?: return
        val path = Path().apply {
            moveTo(segment.fromX, segment.fromY)
            lineTo(segment.toX, segment.toY)
        }
        val previous = stroke
        val next = if (segment.first || previous == null) {
            GestureDescription.StrokeDescription(path, 0, segment.durationMs, !segment.last)
        } else {
            previous.continueStroke(path, 0, segment.durationMs, !segment.last)
        }
        stroke = if (segment.last) null else next
        playing = true
        val started = service.dispatchGesture(GestureDescription.Builder().addStroke(next).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                playing = false
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                playing = false
                stroke = null
                pump()
            }
        }, main)
        if (!started) {
            playing = false
            stroke = null
        }
    }
}
```

- [ ] **Step 4: The service**

`MacDroidControlService.kt`:

```kotlin
package dev.macdroid.app.platform.control

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.macdroid.app.graph
import dev.macdroid.app.platform.mirror.ScreenSize
import dev.macdroid.core.control.ConsentMatcher
import dev.macdroid.core.control.GestureTracker
import dev.macdroid.core.control.ScrollGesture
import dev.macdroid.core.control.TextEdit
import dev.macdroid.core.protocol.InputKeyName
import dev.macdroid.core.protocol.InputNoticeBody
import dev.macdroid.core.protocol.InputNoticeReason
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.PointerAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "MacDroid control" (spec §3.5): plays the Mac's input as gestures, global actions and text edits, and presses
 * "Share screen" on Android's capture consent while a mirroring session waits for it. Reads nothing else on screen.
 */
class MacDroidControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = GestureTracker()
    private lateinit var player: GesturePlayer
    private val longPress = Runnable { player.play(tracker.tick(SystemClock.uptimeMillis())) }

    override fun onServiceConnected() {
        player = GesturePlayer(this)
        instance = this
        _running.value = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopped()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopped()
        super.onDestroy()
    }

    private fun stopped() {
        instance = null
        _running.value = false
    }

    override fun onInterrupt() {}

    /** Auto-Start (spec §3.1 step 4): only System UI events, only while a session waits, only MacDroid's consent. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != SYSTEM_UI) return
        if (!ControlPrefs.autoStart(this) || !graph.mirrorBridge.session.awaitingConsent) return
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != SYSTEM_UI) continue
            val button = ConsentMatcher.startButton(NodeAdapter(root)) as? NodeAdapter ?: continue
            button.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
    }

    /** Input from the Mac; the bridge has checked the session is streaming. Main thread. */
    fun handle(message: Message) {
        val now = SystemClock.uptimeMillis()
        when (message) {
            is Message.InputPointer -> {
                val (w, h) = ScreenSize.current(this)
                val x = (message.body.x * w).toFloat()
                val y = (message.body.y * h).toFloat()
                val segments = when (message.body.action) {
                    PointerAction.Down -> tracker.down(x, y, now).also { main.postDelayed(longPress, LONG_PRESS_MS) }
                    PointerAction.Move -> tracker.move(x, y, now)
                    PointerAction.Up -> tracker.up(x, y, now)
                    PointerAction.Cancel -> tracker.cancel()
                }
                if (message.body.action != PointerAction.Down) main.removeCallbacks(longPress)
                player.play(segments)
            }
            is Message.InputScroll -> {
                val (w, h) = ScreenSize.current(this)
                val b = message.body
                player.play(listOf(ScrollGesture.swipe(b.x, b.y, b.dx, b.dy, w, h)))
            }
            is Message.InputKey -> key(message.body.key)
            is Message.InputText -> edit { text, start, end -> TextEdit.insert(text, start, end, message.body.text.take(MAX_TEXT)) }
            else -> Unit
        }
    }

    private fun key(key: InputKeyName) {
        when (key) {
            InputKeyName.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            InputKeyName.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            InputKeyName.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            InputKeyName.Power -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            InputKeyName.VolumeUp -> volume(AudioManager.ADJUST_RAISE)
            InputKeyName.VolumeDown -> volume(AudioManager.ADJUST_LOWER)
            InputKeyName.Backspace -> edit { text, start, end -> TextEdit.backspace(text, start, end) }
        }
    }

    private fun volume(direction: Int) {
        getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    /** Edits the focused text field, or tells the Mac there isn't one (spec §8). */
    private fun edit(change: (text: String, selStart: Int, selEnd: Int) -> Pair<String, Int>) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (node == null) {
            graph.connectionManager.send(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField)))
            return
        }
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val (text, cursor) = change(current, node.textSelectionStart, node.textSelectionEnd)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val LONG_PRESS_MS = 510L
        private const val MAX_TEXT = 4096
        private val _running = MutableStateFlow(false)
        /** The user has turned "MacDroid control" on and Android has connected it. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        @Volatile var instance: MacDroidControlService? = null
            private set
    }
}
```

- [ ] **Step 5: Bridge and service wiring**

`MirrorBridge.handle(message)`, new branches:

```kotlin
            is Message.InputPointer, is Message.InputScroll, is Message.InputKey, is Message.InputText ->
                // Only into a live session (Review Focus #2); nothing happens without the control service.
                if (session.isStreaming) MacDroidControlService.instance?.handle(message)
```

In `MirrorBridge.start()`:

```kotlin
        scope.launch {
            MacDroidControlService.running.collect { on -> main.post { session.controlChanged(on) } }
        }
```

In `MirrorService`, both `graph.mirrorBridge.session.streaming(…, control = false)` calls become `control = MacDroidControlService.running.value`.

Add the import `dev.macdroid.app.platform.control.MacDroidControlService` in both files.

- [ ] **Step 6: Settings → Remote control**

`SettingsViewModel`:
- `SettingsUi` gets `val controlOn: Boolean = false`, `val autoStart: Boolean = true` and `val microphoneAllowed: Boolean = false`.
- `read()` sets:
  - `controlOn = MacDroidControlService.running.value`
  - `autoStart = ControlPrefs.autoStart(app)`
  - `microphoneAllowed = Permissions.granted(app, android.Manifest.permission.RECORD_AUDIO)`
- Add:

```kotlin
    fun setAutoStart(on: Boolean) {
        ControlPrefs.setAutoStart(getApplication(), on)
        refresh()
    }
```

`SettingsScreen`: a section after Photos:

```kotlin
        item { SectionHeader("Remote control") }
        item {
            ListItem(
                headlineContent = { Text("MacDroid control") },
                supportingContent = { Text(if (ui.controlOn) "On" else "Off: tap to turn on in Accessibility") },
                leadingContent = { Icon(Icons.Rounded.TouchApp, contentDescription = null) },
                modifier = Modifier.clickable { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
        }
        if (!BuildConfig.STORE_BUILD) {
            item {
                ListItem(
                    headlineContent = { Text("Start mirroring without touching the phone") },
                    supportingContent = { Text("MacDroid control presses Start on the screen-sharing prompt for you (phone unlocked)") },
                    leadingContent = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                    trailingContent = { Switch(checked = ui.autoStart && ui.controlOn, enabled = ui.controlOn, onCheckedChange = viewModel::setAutoStart) },
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Microphone for phone audio") },
                supportingContent = { Text(if (ui.microphoneAllowed) "Allowed" else "Not allowed") },
                leadingContent = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                modifier = Modifier.clickable { context.openAppSettings() },
            )
        }
```

Imports: `Icons.Rounded.TouchApp`, `PlayCircle`, `Mic`; `android.provider.Settings` (already imported); `BuildConfig` (already imported).

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines. If lint reports `isAccessibilityTool` or the protected `AccessibilityService` use, that is expected (personal build; spec §11).

- [ ] **Step 7: Handoff, commit**

CHANGELOG bullet: "Task 4: Android 'MacDroid control' accessibility service: gestures with continued strokes and coalescing, Back/Home/Recents/lock, volume, text into the focused field (or `input.notice`), auto-Start of the screen-share consent while a session waits; `ScreenSize`; Settings → Remote control (service status, auto-Start switch, microphone); `STORE_BUILD` flag; `control` reported in `mirror.state` and on changes."

```bash
git add android docs/handoff
git commit -m "feat(android): MacDroid control service, auto-Start and remote control settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Mac app — mouse, scroll and keys on the picture; bar buttons

**Files:**
- Create: `mac/MacDroid/Mirror/MirrorInputView.swift`
- Modify: `mac/MacDroid/Views/Mirror/MirrorContentView.swift`, `mac/MacDroid/AppModel.swift`

**Interfaces:**
- **Consumes:** Task 3 `InputMapper`, `MoveThrottle`, `MirrorKeys`, `MirrorSession.sendInput`, `MirrorState.controlAvailable`, `textFieldMissing`, `MirrorPresentation.controlNotice` and `textFieldNotice`.
- **Produces:** `AppModel.sendMirrorInput(_:)`, `sendMirrorKey(_:)`, `typeOnPhone(_:)` and `pasteOnPhone()`.

There are no unit tests here: the mapping logic is in Task 3. It's verified by the build and the owner checklist.

- [ ] **Step 1: Ordered sending in `AppModel`**

Input must arrive in order, so all of it goes through one stream.

```swift
    @ObservationIgnored private let mirrorInput = AsyncStream.makeStream(of: Message.self)
    @ObservationIgnored private var pendingText = ""
    @ObservationIgnored private var textFlush: Task<Void, Never>?
```

In `start()`:

```swift
        tasks.append(Task { [mirrorSession, stream = mirrorInput.stream] in
            for await message in stream { _ = await mirrorSession.sendInput(message) }
        })
```

Methods (in the Mirroring section):

```swift
    func sendMirrorInput(_ message: Message) {
        flushText() // typed text before a click or key stays in order
        mirrorInput.continuation.yield(message)
    }

    func sendMirrorKey(_ key: InputKeyName) { sendMirrorInput(.inputKey(InputKey(key: key))) }

    /// Typing is sent in 30 ms batches (spec §6.5).
    func typeOnPhone(_ text: String) {
        pendingText += text
        guard textFlush == nil else { return }
        textFlush = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(30))
            self?.flushText()
        }
    }

    func pasteOnPhone() {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { return }
        typeOnPhone(String(text.prefix(InputText.maxLength)))
    }

    private func flushText() {
        textFlush?.cancel()
        textFlush = nil
        guard !pendingText.isEmpty else { return }
        let text = String(pendingText.prefix(InputText.maxLength))
        pendingText = ""
        mirrorInput.continuation.yield(.inputText(InputText(text: text)))
    }
```

- [ ] **Step 2: The input view**

`mac/MacDroid/Mirror/MirrorInputView.swift`:

```swift
import AppKit
import MacDroidCore
import SwiftUI

/// Catches mouse, scroll and keys over the mirrored picture and turns them into `input.*` (spec §6.5).
final class MirrorInputNSView: NSView {
    var picture: CGSize = .zero
    var enabled = false
    var send: (Message) -> Void = { _ in }
    var key: (InputKeyName) -> Void = { _ in }
    var type: (String) -> Void = { _ in }
    var paste: () -> Void = {}
    private var throttle = MoveThrottle()
    private var scroll: (x: Double, y: Double, dx: Double, dy: Double)?
    private var scrollTimer: Timer?
    private var down = false

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        window?.makeFirstResponder(self)
    }

    override func resetCursorRects() {
        addCursorRect(bounds, cursor: Self.dot)
    }

    override func mouseDown(with event: NSEvent) { pointer(.down, event) }
    override func mouseDragged(with event: NSEvent) { pointer(.move, event) }
    override func mouseUp(with event: NSEvent) { pointer(.up, event) }
    override func rightMouseDown(with event: NSEvent) { if enabled { key(.back) } }

    override func scrollWheel(with event: NSEvent) {
        guard enabled, let at = fraction(event) else { return }
        let delta = InputMapper.scroll(deltaX: event.scrollingDeltaX, deltaY: event.scrollingDeltaY, in: bounds.size)
        if let pending = scroll {
            scroll = (pending.x, pending.y, pending.dx + delta.dx, pending.dy + delta.dy)
        } else {
            scroll = (at.x, at.y, delta.dx, delta.dy)
        }
        guard scrollTimer == nil else { return }
        // Accumulated and sent every 33 ms (one swipe per batch).
        scrollTimer = Timer.scheduledTimer(withTimeInterval: 0.033, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let s = self.scroll else { return }
                self.scroll = nil
                self.scrollTimer = nil
                self.send(.inputScroll(InputScroll(x: s.x, y: s.y, dx: s.dx, dy: s.dy)))
            }
        }
    }

    override func keyDown(with event: NSEvent) {
        guard enabled, let action = MirrorKeys.action(keyCode: event.keyCode, characters: event.characters,
                                                      command: event.modifierFlags.contains(.command)) else {
            super.keyDown(with: event)
            return
        }
        switch action {
        case .text(let text): type(text)
        case .key(let name): key(name)
        case .paste: paste()
        }
    }

    private func pointer(_ action: PointerAction, _ event: NSEvent) {
        guard enabled else { return }
        window?.makeFirstResponder(self)
        let millis = Int64(event.timestamp * 1000)
        guard let at = fraction(event) else {
            // Left the picture mid-drag: lift the finger rather than leave it down.
            if down, action != .down {
                down = false
                send(.inputPointer(InputPointer(action: .cancel, x: 0, y: 0, t: millis)))
            }
            return
        }
        switch action {
        case .down: down = true
        case .up, .cancel: down = false
        case .move:
            guard down, throttle.allows(atMillis: millis) else { return }
        }
        send(.inputPointer(InputPointer(action: action, x: at.x, y: at.y, t: millis)))
    }

    private func fraction(_ event: NSEvent) -> CGPoint? {
        InputMapper.fraction(of: convert(event.locationInWindow, from: nil), in: bounds.size, picture: picture)
    }

    /// A small white dot with a dark ring: "you're touching the phone here".
    static let dot: NSCursor = {
        let size = NSSize(width: 14, height: 14)
        let image = NSImage(size: size, flipped: false) { rect in
            let circle = NSBezierPath(ovalIn: rect.insetBy(dx: 2, dy: 2))
            NSColor.white.withAlphaComponent(0.9).setFill()
            circle.fill()
            NSColor.black.withAlphaComponent(0.6).setStroke()
            circle.lineWidth = 1.5
            circle.stroke()
            return true
        }
        return NSCursor(image: image, hotSpot: NSPoint(x: 7, y: 7))
    }()
}

/// SwiftUI wrapper over the picture.
struct MirrorInputView: NSViewRepresentable {
    let model: AppModel

    func makeNSView(context: Context) -> MirrorInputNSView {
        let view = MirrorInputNSView()
        update(view)
        return view
    }

    func updateNSView(_ view: MirrorInputNSView, context: Context) { update(view) }

    private func update(_ view: MirrorInputNSView) {
        if case .streaming(let width, let height, _) = model.mirror.phase {
            view.picture = CGSize(width: width, height: height)
        }
        view.enabled = model.mirror.controlAvailable
        view.send = { [model] in model.sendMirrorInput($0) }
        view.key = { [model] in model.sendMirrorKey($0) }
        view.type = { [model] in model.typeOnPhone($0) }
        view.paste = { [model] in model.pasteOnPhone() }
        view.window?.invalidateCursorRects(for: view)
    }
}
```

If Swift 6 flags the `Timer` closure's isolation, keep `MainActor.assumeIsolated` (the timer runs on the main run loop). If the compiler still complains about capturing `self`, switch to `Task { @MainActor in try? await Task.sleep(for: .milliseconds(33)); … }` with a stored task instead of the timer. Don't silence the warning.

- [ ] **Step 3: Content and bar**

`MirrorContentView`: in the picture's `ZStack`, add `MirrorInputView(model: model)` right after `MirrorVideoView(...)`. Mark the status overlay `.allowsHitTesting(false)`. Add a second overlay at the bottom of the picture for the notices:

```swift
                VStack {
                    Spacer()
                    if let notice = MirrorPresentation.controlNotice(model.mirror) ?? (model.mirror.textFieldMissing ? MirrorPresentation.textFieldNotice : nil) {
                        Text(notice)
                            .font(.caption)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(.black.opacity(0.65), in: Capsule())
                            .padding(.bottom, 10)
                    }
                }
                .allowsHitTesting(false)
```

`MirrorBar`: before the `Spacer()`, add the phone buttons (disabled unless control is available):

```swift
            Group {
                bar("chevron.backward", "Back") { model.sendMirrorKey(.back) }
                bar("circle", "Home") { model.sendMirrorKey(.home) }
                bar("square.on.square", "Recents") { model.sendMirrorKey(.recents) }
                bar("speaker.minus", "Volume down") { model.sendMirrorKey(.volumeDown) }
                bar("speaker.plus", "Volume up") { model.sendMirrorKey(.volumeUp) }
                bar("lock", "Lock phone") { model.sendMirrorKey(.power) }
            }
            .disabled(!model.mirror.controlAvailable)
```

with:

```swift
    private func bar(_ symbol: String, _ help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol) }.help(help)
    }
```

Run `swift test` and the Mac app build. Expected: tests pass; the build succeeds with no warnings.

- [ ] **Step 4: Handoff, commit**

CHANGELOG bullet: "Task 5: Mac input over the mirror picture: click/drag as pointer events (≤ 120 Hz, nothing in the black bars, lift when leaving), right-click Back, scroll batched every 33 ms, typing in 30 ms batches, ⌘V paste, Delete/Return/Esc; Back/Home/Recents/Volume/Lock bar buttons; control and text-field notices; ordered sending through one stream."

```bash
git add mac docs/handoff
git commit -m "feat(mac): control the phone from the mirror window

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Cross-platform test — input reaches the phone

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`, `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`, `scripts/cross-platform-test.sh`

- [ ] **Step 1: Failing script checks** (before the `cmp` lines)

```bash
grep -q 'CONTROL sent 5' "$LOG" || { echo "the Mac never sent input"; cat "$LOG"; exit 1; }
grep -q 'CONTROL notice noTextField' "$LOG" || { echo "the Mac never heard the phone's notice"; cat "$LOG"; exit 1; }
```

Run `./scripts/cross-platform-test.sh`. Expected: fails with "the Mac never sent input".

- [ ] **Step 2: Dev server**

Inside `mirrorPrinter`'s `.streaming` case, after the key-frame request (once per session):

```swift
            if state.controlAvailable, !sentInput {
                sentInput = true
                let inputs: [Message] = [
                    .inputPointer(InputPointer(action: .down, x: 0.5, y: 0.5, t: 1)),
                    .inputPointer(InputPointer(action: .up, x: 0.5, y: 0.5, t: 60)),
                    .inputScroll(InputScroll(x: 0.5, y: 0.5, dx: 0, dy: -0.1)),
                    .inputKey(InputKey(key: .back)),
                    .inputText(InputText(text: "hi")),
                ]
                var sent = 0
                for input in inputs {
                    if await mirror.sendInput(input) { sent += 1 }
                }
                print("CONTROL sent \(sent)")
            }
```

Declare `var sentInput = false` next to `askedForKey`. Add a printer for the notice:

```swift
let noticePrinter = Task {
    for await message in hub.messages {
        if case .inputNotice(let body) = message { print("CONTROL notice \(body.reason.rawValue)") }
    }
}
```

and `noticePrinter.cancel()` at the end.

- [ ] **Step 3: Kotlin side**

In `exchangeMirror`, change the `mirror.state` streaming message it sends from `control = false` to `control = true` (this "phone" has its control service on). After `withTimeout(10_000) { while (received.none { it is Message.MirrorKeyframe }) delay(50) }`:

```kotlin
        withTimeout(10_000) { while (received.none { it is Message.InputText }) delay(50) }
        val pointers = received.filterIsInstance<Message.InputPointer>().map { it.body }
        assertEquals(listOf(PointerAction.Down, PointerAction.Up), pointers.map { it.action })
        // The phone's rules turn that into a tap at the middle of a 1080×2400 screen.
        val tracker = GestureTracker()
        tracker.down((pointers[0].x * 1080).toFloat(), (pointers[0].y * 2400).toFloat(), 0)
        assertEquals(listOf(GestureSegment(540f, 1200f, 540f, 1200f, 50, first = true, last = true)), tracker.up(540f, 1200f, 60))
        assertEquals(InputKeyName.Back, received.filterIsInstance<Message.InputKey>().single().body.key)
        assertEquals(1, received.count { it is Message.InputScroll })
        // No text field focused on this "phone": say so.
        assertTrue(manager.send(Message.InputNotice(InputNoticeBody(InputNoticeReason.NoTextField))))
        delay(300)
```

Imports: `dev.macdroid.core.control.GestureSegment`, `dev.macdroid.core.control.GestureTracker`, and the protocol types (`PointerAction`, `InputKeyName`, `InputNoticeBody`, `InputNoticeReason`).

- [ ] **Step 4: Run, handoff, commit**

Run `./scripts/cross-platform-test.sh` twice. Expected: "cross-platform test passed" both times. Then run all gates.

CHANGELOG bullet: "Task 6: cross-platform test covers remote control: the Mac sends pointer, scroll, key and text only once the phone reports control on; the phone's gesture rules turn the click into a tap; the phone's `input.notice` reaches the Mac."

```bash
git add mac android scripts docs/handoff
git commit -m "test: cross-platform remote control

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Final gates, handoff, owner checklist

**Files:**
- Modify: `docs/handoff/STATUS.md`, `ARCHITECTURE.md`, `DEV-GUIDE.md`, `ROADMAP.md`, `DECISIONS.md`, `README.md`, `CHANGELOG.md`; `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`

- [ ] **Step 1: Every gate**

```bash
cd mac/MacDroidCore && swift test 2>&1 | tail -3
cd .. && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "^/.*(warning|error):|BUILD" | sort -u
cd ../android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all 2>&1 | grep -E "^e:|^w:|BUILD"
cd .. && ./scripts/cross-platform-test.sh
```

Expected: all pass, no warnings, "cross-platform test passed".

- [ ] **Step 2: Handoff**

- **ARCHITECTURE.md:** Android `platform/control/` (service, gesture player, prefs, node adapter); core `control/`; the Mac `MirrorInputView` and `MirrorInput` core; the input gate; auto-Start.
- **DECISIONS.md:**
  - continued strokes plus coalescing
  - `input.notice` (a new message)
  - `isAccessibilityTool` for the consent dialog
  - auto-Start armed only while a session waits
  - `STORE_BUILD`
- **DEV-GUIDE.md:** turning on "MacDroid control"; that auto-Start needs an unlocked phone; testing input.
- **STATUS / ROADMAP / README:** Plans 7–8 built on `feat/mirroring`, pending the owner's check.
- **CHANGELOG:** close the Plan 8 entry with the gate results.

- [ ] **Step 3: Owner checklist** (append to the follow-ups file and the final message)

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

- [ ] **Step 4: Commit**

```bash
git add docs
git commit -m "docs(handoff): remote control (Plan 8) built on feat/mirroring

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Don't merge. The owner checks Plans 7 and 8 together.
