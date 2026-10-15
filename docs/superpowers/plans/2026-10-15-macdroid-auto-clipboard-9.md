# Automatic Phone → Mac Clipboard Implementation Plan (Plan 9)

> **Dropped 2026-10-15 (owner).** The on-device probe showed Android shows its clipboard-access message and the screen refreshes twice on every automatic read, and a one-tap floating "Send to Mac" button didn't look good either. Phone → Mac stays manual (Quick Settings tile, notification button, Share). Kept for the record; see DECISIONS.md.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Text copied on the phone lands on the Mac's clipboard by itself, with no tap, no app switch and no Share sheet.

**Architecture:**
- **Detect:** the existing "MacDroid control" accessibility service watches System UI for the "copied" preview.
- **Read:** it briefly opens the existing invisible `ClipboardSendActivity`, which reads the clipboard once it has focus.
- **Send:** the activity hands the clip to `ClipboardBridge.sendAutoInBackground`, which sends `clipboard.set` with a new optional `auto: true` field.
- **Rules:** what is sent, and which toasts show, are decided by pure, tested core classes (`CopyDetector`, `AutoClipboardPolicy`).
- **Mac:** applies the item as now, and skips its notification when `auto` is true.

**Tech Stack:** Kotlin (Android core + app, Compose), Swift 6 (MacDroidCore, SwiftUI app), kotlinx.serialization, Swift Testing, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-15-macdroid-auto-clipboard-design.md`

## Global Constraints

- **Branch:** `feat/auto-clipboard`, from `main`. Don't merge; the owner checks first.
- **No developer options at runtime (owner, 2026-10-15).** The feature must work with no developer options, no wireless debugging, no adb-granted permissions (such as `READ_LOGS`) and no Shizuku. Wireless debugging is only for installing builds.
- **Scope:**
  - Text and links only. Image clips are skipped silently.
  - The service keeps listening only to System UI (`android:packageNames="com.android.systemui"`).
- **Store builds:** `BuildConfig.STORE_BUILD` turns automatic sending off and hides its switch.
- **Timers:**
  - Preview repeats within **1 s** are ignored.
  - Previews within **2 s** of MacDroid itself setting the phone's clipboard are ignored (the echo window).
- **Sensitive:** a clip whose `ClipDescription.extras` has `"android.content.extra.IS_SENSITIVE"` set to true is never sent automatically.
- **Copy (Android):**
  - Toasts:
    - "Mac not connected — copy not sent" (once per disconnect)
    - "Your Mac's MacDroid can't receive the clipboard" (once per connection)
    - "Too large to send to your Mac"
  - Switch title: "Send copies to your Mac automatically"
  - Switch subtitle: "When you copy text on this phone", or when control is off: "Needs MacDroid control (Settings → Remote control)"
- **Protocol:** `clipboard.set` gains an optional `auto` (boolean). Only the phone sets it, only for automatic sends. Absent means `false` and is omitted on the wire. No new `hello` feature.
- **Mac:** no "Clipboard from <phone>" notification for `auto == true`. Everything else (apply, history, the receive switch, echo guard) is unchanged.
- **Ruling, switch location:** the spec says "Settings → Clipboard", but the phone's Settings has no Clipboard section, and the existing clipboard switch ("Get Mac clipboard") lives on the **Clipboard tab**. The new switch goes on the Clipboard tab, under "Get Mac clipboard". Cost if wrong: moving one Card.
- **Ruling, "appears":** the spec counts only the moment the preview appears. While the preview is still on screen, a second copy only changes its content, so the detector treats "the preview appeared **or** now shows different text" as a copy. Without this, a second copy within the preview's ~5 s lifetime would be lost. Cost if wrong: none; the duplicate rule still stops re-sends.
- **The owner's phone:** Task 1 is done **with the owner present**.
  - The owner turns accessibility services on and off.
  - Don't toggle them, grant permissions, `pm clear` or tap from adb.
  - Never log clipboard text, only lengths and ids.
- **Handoff:** every task's commit updates `docs/handoff/`. Task 1 adds a CHANGELOG entry at the top, `## <date> — Plan 9: automatic phone → Mac clipboard (in progress on feat/auto-clipboard)`, and each later task adds a bullet under it.
- **Gates (all must pass with no warnings):**
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`
  - `./scripts/cross-platform-test.sh`

## Review Focus

1. **Echo:** an item that came from the Mac must never be sent back, whether or not System UI shows a preview for it → Task 3 `previewsRightAfterOurOwnWriteAreIgnored`, `textFromTheMacIsNeverSentBack`; Task 5 calls `ownWrite` in both places that set the clipboard.
2. **Passwords:** a sensitive clip (a password manager's copy) must never be sent automatically → Task 3 `sensitiveClipsAreNeverSent`.
3. **Two copies in a row:** a second, different copy while the first preview is still on screen must still be sent → Task 3 `aDifferentItemWhileThePreviewStaysIsANewCopy`.
4. **Away from the Mac:** copying all day without a connection must not toast on every copy → Task 3 `notConnectedToastsOncePerDisconnect`.
5. **Mixed versions:** a Mac without this change still decodes the phone's `clipboard.set`, and manual sends never carry `auto` → Task 2 `clipboardFixturesDecode` (old fixture decodes with `auto == null`; manual encode has no `auto`).

---

## File Structure

```
android/app/src/debug/AndroidManifest.xml, debug/.../ClipProbeAccessibility.kt, ClipProbeActivity.kt,
  res/xml/clip_probe_accessibility.xml                                                        (T1, deleted in T7)
docs/superpowers/plans/2026-10-15-macdroid-auto-clipboard-followups.md (probe results, checklist)   (T1, T7)
protocol/fixtures/clipboard.set.auto.json (new); protocol/PROTOCOL.md                              (T2)
android/core/.../protocol/Messages.kt (ClipboardSetBody.auto); test MessageCodecTest.kt             (T2)
mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift (ClipboardSet.auto); ProtocolFixtureTests.swift (T2)
android/core/.../clipboard/AutoClipboard.kt (new: CopyDetector, AutoClipboardPolicy, AutoGate, AutoToast) (T3)
android/core/.../clipboard/ClipboardHub.kt (sendText auto); tests AutoClipboardTest.kt (new), ClipboardHubTest.kt (T3)
mac/MacDroidCore/Sources/MacDroidCore/Clipboard/ClipboardSync.swift; Tests ClipboardTests.swift      (T4)
mac/MacDroid/AppModel.swift; mac/MacDroidCore/Sources/MacDroidDevServer/main.swift                   (T4)
android/app/.../platform/clipboard/ClipboardPrefs.kt, ClipboardBridge.kt                            (T5)
android/app/.../ui/clipboard/ClipboardSendActivity.kt, ClipboardScreen.kt, ClipboardViewModel.kt    (T5)
android/app/.../ui/presentation/ClipboardPresentation.kt; test ClipboardPresentationTest.kt          (T5)
android/app/.../platform/control/MacDroidControlService.kt; res/xml/control_service.xml             (T5)
android/core/src/test/.../CrossPlatformTest.kt; scripts/cross-platform-test.sh                      (T6)
docs/handoff/*                                                                                       (every task)
```

---

### Task 1: On-device probe (with the owner)

**Purpose:** answer spec §7.1 Q1–Q4 on the owner's Nothing Phone (3), Android 17, before any product code.
- The probe lives only in the **debug** source set, so it never reaches a release build.
- It is deleted in Task 7.
- All output goes to `Log.w("ClipProbe", …)`, because Nothing OS filters `Log.d`.
- It never logs clipboard text, only lengths, ids and flags.

**Files:**
- Create: `android/app/src/debug/AndroidManifest.xml`
- Create: `android/app/src/debug/java/dev/macdroid/app/debug/ClipProbeAccessibility.kt`, `ClipProbeActivity.kt`
- Create: `android/app/src/debug/res/xml/clip_probe_accessibility.xml`
- Create: `docs/superpowers/plans/2026-10-15-macdroid-auto-clipboard-followups.md`

**Interfaces:**
- Produces: the preview's view id(s), written to the follow-ups file. Task 5 copies them into `MacDroidControlService.PREVIEW_IDS`.

- [ ] **Step 1: Branch and debug manifest**

```bash
git checkout main && git checkout -b feat/auto-clipboard
```

`android/app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name=".debug.ClipProbeActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service
            android:name=".debug.ClipProbeAccessibility"
            android:exported="false"
            android:label="MacDroid clipboard probe"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter><action android:name="android.accessibilityservice.AccessibilityService" /></intent-filter>
            <meta-data android:name="android.accessibilityservice" android:resource="@xml/clip_probe_accessibility" />
        </service>
    </application>
</manifest>
```

`android/app/src/debug/res/xml/clip_probe_accessibility.xml` (same scope as the real service, plus windows-changed events):

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
    android:packageNames="com.android.systemui"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:isAccessibilityTool="true"
    android:notificationTimeout="50" />
```

- [ ] **Step 2: Probe code**

`ClipProbeAccessibility.kt`:

```kotlin
package dev.macdroid.app.debug

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Debug-only (Plan 9 Task 1): logs System UI windows around a copy and tries to open [ClipProbeActivity]. */
class ClipProbeAccessibility : AccessibilityService() {
    private var lastLaunch = 0L

    override fun onServiceConnected() { Log.w(TAG, "probe service connected") }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != SYSTEM_UI) return
        val content = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != SYSTEM_UI) continue
            val nodes = mutableListOf<String>()
            collect(root, nodes, 0)
            val clipboard = nodes.any { "clipboard" in it.lowercase() }
            // Content changes are constant in System UI: log them only around the clipboard UI.
            if (content && !clipboard) continue
            Log.w(TAG, "${AccessibilityEvent.eventTypeToString(event.eventType)} window type=${window.type} title=${window.title}")
            nodes.take(60).forEach { Log.w(TAG, "    $it") }
            if (clipboard && SystemClock.uptimeMillis() - lastLaunch > 2_000) {
                lastLaunch = SystemClock.uptimeMillis()
                val result = runCatching {
                    startActivity(Intent(this, ClipProbeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                }
                Log.w(TAG, "clipboard UI seen → startActivity: ${result.exceptionOrNull() ?: "ok"}")
            }
        }
    }

    /** "id | class | text length" per node; never the text itself. */
    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 25) return
        out += "${node.viewIdResourceName} | ${node.className} | text=${node.text?.length ?: -1}"
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    companion object {
        const val TAG = "ClipProbe"
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
```

`ClipProbeActivity.kt`:

```kotlin
package dev.macdroid.app.debug

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/** Debug-only (Plan 9 Task 1): reads the clipboard once focused, logs what kind of clip it is, closes. */
class ClipProbeActivity : Activity() {
    private val created = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(ClipProbeAccessibility.TAG, "activity created")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val description = clip?.description
        val mimes = description?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }
        val sensitive = description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE")
        val length = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.length
        Log.w(ClipProbeAccessibility.TAG,
            "focused after ${SystemClock.uptimeMillis() - created} ms; items=${clip?.itemCount} mimes=$mimes sensitive=$sensitive textLength=$length")
        finish()
    }
}
```

Run `cd android && ./gradlew :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines.

- [ ] **Step 3: Run it with the owner**

1. Install: `ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`. Use `adb mdns services` to find the serial. This keeps the app and its pairing.
2. Ask the owner to turn on "MacDroid clipboard probe" in Settings → Accessibility, and to leave "MacDroid control" as it is.
3. Start the log: `adb logcat -c && adb logcat -s ClipProbe:W > <scratchpad>/clip-probe.log`, in the background.
4. Ask the owner to do the following, one at a time, saying when each is done:
   - a. **Q1/Q2/Q3:** in Chrome, long-press a word → Copy. With the keyboard open in Messages, select typed text → Copy. In a notes app, Copy.
     After each: did the keyboard close, did the selection disappear, did the preview vanish early, did anything flicker?
   - b. **Two copies within ~2 s** of each other, while the first preview is still on screen.
   - c. **Echo:** copy something on the Mac (the installed MacDroid sends it to the phone). Is there a preview on the phone?
   - d. **Q4:** copy a password from the owner's password manager.
   - e. Copy a screenshot or an image (expect `mimes=[image/...]`).
5. Read the log after each step and note:
   - which view id(s) mark the preview (look for ids containing `clipboard`)
   - whether `startActivity` was `ok`
   - the `focused after … ms` value
   - `sensitive=`
6. Ask the owner to turn the probe service off.

- [ ] **Step 4: Record the results**

Create `docs/superpowers/plans/2026-10-15-macdroid-auto-clipboard-followups.md` with a results table in the shape of the Plan 7 probe table: `| # | Question | Observed | Decision |`. One row each for Q1 (preview ids), Q1-echo, Q2, Q3 (disruption), Q4, two copies in a row, and the image clip.

The **Decision** column of Q1 names the exact id(s) Task 5 uses as `PREVIEW_IDS`, and which node's text changes between copies (for the "different text" rule).

**Stop rules (spec §7.1):**
- No preview id seen, or `startActivity` failed or never got focus: **stop and report to the owner.** Don't change approach without asking.
- The keyboard closes, or the selection is lost, on every copy: **stop and bring the options to the owner** before Task 2.

- [ ] **Step 5: Handoff and commit**

- CHANGELOG: add the Plan 9 entry, with a Task 1 bullet summarising the results.
- STATUS: Plan 9 in progress on `feat/auto-clipboard`.

```bash
git add android/app/src/debug docs
git commit -m "chore(auto-clipboard): on-device probe and its results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Protocol — `clipboard.set` gets `auto`

**Files:**
- Create: `protocol/fixtures/clipboard.set.auto.json`
- Modify: `protocol/PROTOCOL.md` (the `clipboard.set` row)
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt` (`ClipboardSetBody`)
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift` (`ClipboardSet`)
- Test: `android/core/src/test/kotlin/dev/macdroid/core/protocol/MessageCodecTest.kt`, `mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift`

**Interfaces:**
- Produces:
  - Kotlin: `data class ClipboardSetBody(val itemId: String, val kind: ClipboardKind = ClipboardKind.Text, val text: String, val auto: Boolean? = null)`
  - Swift: `ClipboardSet.auto: Bool?`, with `init(itemId:kind:text:auto: Bool? = nil)`

- [ ] **Step 1: Fixture and failing tests**

`protocol/fixtures/clipboard.set.auto.json`:

```json
{ "v": 1, "type": "clipboard.set", "id": "6f1c2a0e-0000-4000-8000-000000000060", "body": { "itemId": "2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091", "kind": "text", "text": "copied on the phone", "auto": true } }
```

Add `"clipboard.set.auto.json"` after `"clipboard.set.json"` in **both** `messageFixtures` lists (Kotlin `MessageCodecTest`, Swift `ProtocolFixtureTests`).

Kotlin, at the end of `clipboardFixturesDecode()`:

```kotlin
        assertEquals(
            Message.ClipboardSet(ClipboardSetBody("2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091", ClipboardKind.Text, "copied on the phone", auto = true)),
            MessageCodec.decode(Fixtures.text("clipboard.set.auto.json")),
        )
        // Review Focus #5: old messages decode without it, and manual sends never carry it.
        assertNull((MessageCodec.decode(Fixtures.text("clipboard.set.json")) as Message.ClipboardSet).body.auto)
        assertFalse(MessageCodec.encode(Message.ClipboardSet(ClipboardSetBody("i", text = "t"))).contains("auto"))
```

(Add `import org.junit.Assert.assertNull` if missing.)

Swift, at the end of `clipboardFixturesDecode()`:

```swift
        #expect(try MessageCodec.decode(Fixtures.text("clipboard.set.auto.json")) == .clipboardSet(ClipboardSet(
            itemId: "2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091", kind: .text, text: "copied on the phone", auto: true
        )))
        // Review Focus #5: the old fixture decodes with no `auto`; the Mac never sends one.
        guard case .clipboardSet(let old) = try MessageCodec.decode(Fixtures.text("clipboard.set.json")) else { Issue.record("not clipboard.set"); return }
        #expect(old.auto == nil)
        #expect(!(try MessageCodec.encode(.clipboardSet(ClipboardSet(itemId: "i", text: "t")))).contains("auto"))
```

Run `cd android && ./gradlew -q :core:compileTestKotlin` and `cd mac/MacDroidCore && swift build --build-tests`. Expected: both fail on the unknown `auto` parameter.

- [ ] **Step 2: Implement**

Kotlin `Messages.kt`:

```kotlin
/** [auto] is true only on the phone's automatic sends (Plan 9); absent otherwise. */
@Serializable
data class ClipboardSetBody(val itemId: String, val kind: ClipboardKind = ClipboardKind.Text, val text: String, val auto: Boolean? = null)
```

Keep whatever annotations the existing declaration has. `explicitNulls = false` in `MessageCodec.json` keeps a null `auto` off the wire.

Swift `Messages.swift`:

```swift
public struct ClipboardSet: Codable, Equatable, Sendable {
    public var itemId: String
    public var kind: ClipboardKind
    public var text: String
    /// True only on the phone's automatic sends (Plan 9); absent otherwise.
    public var auto: Bool?

    public init(itemId: String, kind: ClipboardKind = .text, text: String, auto: Bool? = nil) {
        self.itemId = itemId
        self.kind = kind
        self.text = text
        self.auto = auto
    }
}
```

`PROTOCOL.md`, the `clipboard.set` row becomes:

```
| `clipboard.set` | both | `itemId` (UUID), `kind` (`text`), `text`, `auto` (optional, phone → Mac only: `true` when sent automatically after a copy on the phone; absent means `false`; the Mac then skips its notification) — the encoded frame must be ≤ 1 MiB |
```

Run `cd android && ./gradlew :core:test` and `cd mac/MacDroidCore && swift test`. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 2: `clipboard.set` gets an optional `auto` (phone → Mac, automatic sends only); fixture `clipboard.set.auto.json`; both codecs; PROTOCOL.md." ARCHITECTURE: mention `auto` where `clipboard.set` is described.

```bash
git add protocol android/core mac/MacDroidCore docs/handoff
git commit -m "feat(protocol): clipboard.set auto flag

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Android core — copy detection and the automatic-send rules

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/clipboard/AutoClipboard.kt`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/clipboard/ClipboardHub.kt` (`sendText` gets `auto`; `fitsInAFrame` measures with `auto = true`)
- Test: `android/core/src/test/kotlin/dev/macdroid/core/clipboard/AutoClipboardTest.kt` (new), `ClipboardHubTest.kt`

**Interfaces:**
- Consumes:
  - Task 2's `ClipboardSetBody.auto`
  - `dev.macdroid.core.control.UiNode` (Plan 8)
- Produces:
  - `class CopyDetector(debounceMs: Long = 1_000, echoMs: Long = 2_000)`, with:
    - `fun preview(signature: String?, tMs: Long): Boolean`: true when this is a new copy to act on
    - `fun ownWrite(tMs: Long)`
    - `companion fun signature(node: UiNode): String`
  - `enum class AutoToast { NotConnected, MacTooOld, TooLarge }`
  - `sealed interface AutoGate { Go; Quiet; data class Toast(val toast: AutoToast) }`
  - `class AutoClipboardPolicy`, with:
    - `connectionChanged(state: ConnectionState)`
    - `gate(state: ConnectionState): AutoGate`
    - `textToSend(text: String?, sensitive: Boolean): String?`
    - `received(text: String)`
    - `result(text: String, result: SendResult): AutoToast?`
    - `companion const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"`
  - `ClipboardHub.sendText(text: String?, state: ConnectionState, auto: Boolean = false): SendResult`

- [ ] **Step 1: Failing tests**

`AutoClipboardTest.kt`:

```kotlin
package dev.macdroid.core.clipboard

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.control.UiNode
import dev.macdroid.core.protocol.Features
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClipboardTest {
    private val connected = ConnectionState.Connected("Mac", Features.SUPPORTED.toSet())
    private val oldMac = ConnectionState.Connected("Mac", setOf(Features.FILES))

    @Test fun onlyTheAppearanceIsACopy() {
        val d = CopyDetector()
        assertFalse(d.preview(null, 0))
        assertTrue(d.preview("hello", 100))
        assertFalse(d.preview("hello", 150)) // same preview, more events
        assertFalse(d.preview("hello", 3_000))
        assertFalse(d.preview(null, 6_000)) // it went away
        assertTrue(d.preview("hello", 9_000)) // the same text copied again later is a new copy (the policy decides)
    }

    // Review Focus #3
    @Test fun aDifferentItemWhileThePreviewStaysIsANewCopy() {
        val d = CopyDetector()
        assertTrue(d.preview("first", 0))
        assertTrue(d.preview("second", 2_500))
    }

    @Test fun repeatsWithinASecondAreIgnored() {
        val d = CopyDetector()
        assertTrue(d.preview("a", 0))
        assertFalse(d.preview(null, 200))
        assertFalse(d.preview("a", 400))
        assertFalse(d.preview("b", 900))
        assertTrue(d.preview("c", 1_200))
    }

    // Review Focus #1
    @Test fun previewsRightAfterOurOwnWriteAreIgnored() {
        val d = CopyDetector()
        d.ownWrite(10_000)
        assertFalse(d.preview("from mac", 10_300))
        assertFalse(d.preview("from mac", 11_000)) // still the same preview
        assertTrue(d.preview("typed on phone", 12_500)) // a real copy after the window
    }

    private data class Node(override val viewId: String? = null, override val text: String? = null, override val clickable: Boolean = false,
                            override val children: List<UiNode> = emptyList()) : UiNode

    @Test fun theSignatureIsThePreviewsText() {
        val preview = Node("com.android.systemui:id/clipboard_preview", children = listOf(Node(text = "hello"), Node(text = null), Node(text = "Copied")))
        assertEquals("hello\u0000Copied", CopyDetector.signature(preview))
    }

    // Review Focus #4
    @Test fun notConnectedToastsOncePerDisconnect() {
        val p = AutoClipboardPolicy()
        assertEquals(AutoGate.Toast(AutoToast.NotConnected), p.gate(ConnectionState.Connecting))
        assertEquals(AutoGate.Quiet, p.gate(ConnectionState.Connecting))
        p.connectionChanged(connected)
        assertEquals(AutoGate.Go, p.gate(connected))
        p.connectionChanged(ConnectionState.Connecting)
        assertEquals(AutoGate.Toast(AutoToast.NotConnected), p.gate(ConnectionState.Connecting))
        assertEquals(AutoGate.Quiet, p.gate(ConnectionState.Connecting))
    }

    @Test fun anOldMacToastsOncePerConnection() {
        val p = AutoClipboardPolicy()
        p.connectionChanged(oldMac)
        assertEquals(AutoGate.Toast(AutoToast.MacTooOld), p.gate(oldMac))
        assertEquals(AutoGate.Quiet, p.gate(oldMac))
        p.connectionChanged(ConnectionState.Connecting)
        p.connectionChanged(oldMac)
        assertEquals(AutoGate.Toast(AutoToast.MacTooOld), p.gate(oldMac))
    }

    // Review Focus #2
    @Test fun sensitiveClipsAreNeverSent() {
        assertNull(AutoClipboardPolicy().textToSend("hunter2", sensitive = true))
    }

    @Test fun emptyAndNonTextAreSkipped() {
        val p = AutoClipboardPolicy()
        assertNull(p.textToSend(null, sensitive = false))
        assertNull(p.textToSend("", sensitive = false))
        assertEquals("hi", p.textToSend("hi", sensitive = false))
    }

    // Review Focus #1
    @Test fun textFromTheMacIsNeverSentBack() {
        val p = AutoClipboardPolicy()
        p.received("from mac")
        assertNull(p.textToSend("from mac", sensitive = false))
        assertEquals("other", p.textToSend("other", sensitive = false))
    }

    @Test fun theSameTextIsNotSentTwice() {
        val p = AutoClipboardPolicy()
        assertNull(p.result("note", SendResult.Sent))
        assertNull(p.textToSend("note", sensitive = false))
        assertEquals("new", p.textToSend("new", sensitive = false))
    }

    @Test fun resultsMapToToasts() {
        val p = AutoClipboardPolicy()
        assertEquals(AutoToast.TooLarge, p.result("x", SendResult.TooLarge))
        assertEquals(AutoToast.TooLarge, p.result("x", SendResult.TooLarge))
        assertNull(p.result("x", SendResult.Empty))
        assertEquals(AutoToast.NotConnected, p.result("x", SendResult.NotConnected))
        assertNull(p.result("x", SendResult.NotConnected)) // shares the once-per-disconnect rule with gate()
        assertEquals(AutoToast.MacTooOld, p.result("x", SendResult.MacTooOld))
        assertNull(p.result("x", SendResult.MacTooOld))
    }
}
```

In `ClipboardHubTest.kt` add:

```kotlin
    @Test fun automaticSendsAreMarked() {
        assertEquals(SendResult.Sent, hub.sendText("auto", connected, auto = true))
        assertEquals(SendResult.Sent, hub.sendText("manual", connected))
        assertEquals(listOf(true, null), sent.map { (it as Message.ClipboardSet).body.auto })
    }
```

Run `cd android && ./gradlew -q :core:compileTestKotlin`. Expected: unresolved references (`CopyDetector`, `AutoClipboardPolicy`, `auto`).

- [ ] **Step 2: Implement** `AutoClipboard.kt`

```kotlin
package dev.macdroid.core.clipboard

import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.control.UiNode
import dev.macdroid.core.protocol.Features

/**
 * Turns System UI's "copied" preview into copies to act on (Plan 9 spec §3.1). [preview] gets the preview's text
 * ([signature]) or null when it isn't on screen. A copy is the preview appearing, or showing different text while it stays.
 * Previews right after MacDroid itself set the clipboard are ignored (spec §3.2 echo window). Thread-safe.
 */
class CopyDetector(private val debounceMs: Long = 1_000, private val echoMs: Long = 2_000) {
    private var shown: String? = null
    private var lastCopy: Long? = null
    private var ownWrite: Long? = null

    /** MacDroid is about to set the phone's clipboard (an item from the Mac, or Recent → copy). */
    @Synchronized fun ownWrite(tMs: Long) { ownWrite = tMs }

    @Synchronized fun preview(signature: String?, tMs: Long): Boolean {
        val previous = shown
        shown = signature
        if (signature == null || signature == previous) return false
        val own = ownWrite
        if (own != null && tMs - own < echoMs) return false
        val last = lastCopy
        if (last != null && tMs - last < debounceMs) return false
        lastCopy = tMs
        return true
    }

    companion object {
        /** The texts in the preview, in order; changes when a different item is copied. */
        fun signature(node: UiNode): String = texts(node, 0).joinToString("\u0000")

        private fun texts(node: UiNode, depth: Int): List<String> =
            if (depth > 30) emptyList() else listOfNotNull(node.text) + node.children.flatMap { texts(it, depth + 1) }
    }
}

enum class AutoToast { NotConnected, MacTooOld, TooLarge }

sealed interface AutoGate {
    data object Go : AutoGate
    data object Quiet : AutoGate
    data class Toast(val toast: AutoToast) : AutoGate
}

/**
 * What an automatic copy may send, and which failures say so (spec §3.2–3.3): text only, never sensitive, never back
 * to the Mac, never twice; "not connected" once per disconnect, "Mac too old" once per connection. Thread-safe.
 */
class AutoClipboardPolicy {
    private var notConnectedShown = false
    private var tooOldShown = false
    private var lastReceived: String? = null
    private var lastSent: String? = null

    @Synchronized fun connectionChanged(state: ConnectionState) {
        if (state is ConnectionState.Connected) {
            notConnectedShown = false
            tooOldShown = false
        }
    }

    /** Before opening anything: can a send happen now? */
    @Synchronized fun gate(state: ConnectionState): AutoGate = when {
        state !is ConnectionState.Connected -> toastOnce(notConnected = true)
        !state.macSupports(Features.CLIPBOARD) -> toastOnce(notConnected = false)
        else -> AutoGate.Go
    }

    /** The clipboard as read: the text to send, or null to skip silently. */
    @Synchronized fun textToSend(text: String?, sensitive: Boolean): String? = when {
        text.isNullOrEmpty() || sensitive -> null
        text == lastReceived || text == lastSent -> null
        else -> text
    }

    /** An item from the Mac was put on the phone's clipboard. */
    @Synchronized fun received(text: String) { lastReceived = text }

    /** The send's outcome: a toast to show, or null. */
    @Synchronized fun result(text: String, result: SendResult): AutoToast? = when (result) {
        SendResult.Sent -> { lastSent = text; null }
        SendResult.Empty -> null
        SendResult.TooLarge -> AutoToast.TooLarge
        SendResult.NotConnected -> (toastOnce(notConnected = true) as? AutoGate.Toast)?.toast
        SendResult.MacTooOld -> (toastOnce(notConnected = false) as? AutoGate.Toast)?.toast
    }

    private fun toastOnce(notConnected: Boolean): AutoGate =
        if (notConnected) {
            if (notConnectedShown) AutoGate.Quiet else { notConnectedShown = true; AutoGate.Toast(AutoToast.NotConnected) }
        } else {
            if (tooOldShown) AutoGate.Quiet else { tooOldShown = true; AutoGate.Toast(AutoToast.MacTooOld) }
        }

    companion object {
        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33), by value so core stays plain JVM. */
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    }
}
```

`ClipboardHub.kt`:
- `sendText` becomes `fun sendText(text: String?, state: ConnectionState, auto: Boolean = false): SendResult`.
- Its send line becomes `send(Message.ClipboardSet(ClipboardSetBody(UUID.randomUUID().toString(), text = value, auto = if (auto) true else null)))`.
- `fitsInAFrame` measures `ClipboardSetBody(ZERO_ID, text = text, auto = true)`, the larger of the two shapes.

Run `cd android && ./gradlew :core:test`. Expected: PASS, including the existing `ClipboardHubTest`.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 3: Android core `CopyDetector` (appear-or-change, 1 s debounce, 2 s echo window) and `AutoClipboardPolicy` (text only, never sensitive, never back to the Mac, never twice; toasts once per disconnect/connection); `ClipboardHub.sendText(auto)`." ARCHITECTURE: add `AutoClipboard.kt` to the core clipboard row.

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): copy detection and automatic clipboard rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac — pass `auto` along and skip the notification

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Clipboard/ClipboardSync.swift`
- Modify: `mac/MacDroid/AppModel.swift` (the `handle(_ event: ClipboardEvent)` switch)
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift` (an events printer)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ClipboardTests.swift`

**Interfaces:**
- Consumes: Task 2's `ClipboardSet.auto`.
- Produces:
  - `ClipboardEvent.receivedFromPhone(ClipboardItem, auto: Bool)`
  - the dev-server log line `CLIPBOARD received text=<text> auto=<true|false>`

- [ ] **Step 1: Failing tests** (in `ClipboardTests`, next to `receivingSwitchedOffIgnoresThePhone`)

```swift
    @Test func automaticItemsAreAppliedAndFlagged() async throws {
        let h = try Harness()
        await h.connect()
        await h.sync.handle(.clipboardSet(ClipboardSet(itemId: "x", text: "copied", auto: true)))
        await h.sync.handle(.clipboardSet(ClipboardSet(itemId: "y", text: "shared")))
        #expect(await h.pasteboard.applied == [.text("copied"), .text("shared")])
        #expect(await h.sync.state.items.map(\.direction) == [.fromPhone, .fromPhone])
        try await eventually { await h.events.items.count == 2 }
        #expect(await h.events.items.map { if case .receivedFromPhone(_, let auto) = $0 { auto } else { false } } == [true, false])
    }

    @Test func receivingSwitchedOffBlocksAutomaticItemsToo() async throws {
        let h = try Harness(receiveFromPhone: false)
        await h.sync.handle(.clipboardSet(ClipboardSet(itemId: "x", text: "copied", auto: true)))
        #expect(await h.pasteboard.applied.isEmpty)
    }
```

Run `cd mac/MacDroidCore && swift build --build-tests`. Expected: a compile error (`receivedFromPhone` has one associated value).

- [ ] **Step 2: Implement**

`ClipboardSync.swift`:

```swift
public enum ClipboardEvent: Equatable, Sendable {
    /// Something from the phone is now on the Mac clipboard. The app shows "Clipboard from <phone>" unless `auto`
    /// (the phone sent it by itself after a copy, Plan 9).
    case receivedFromPhone(ClipboardItem, auto: Bool)
}
```

- `handle(_:)`: `await applyFromPhone(.text(body.text), auto: body.auto == true)`.
- `imageReceived`: `await applyFromPhone(.image(png), auto: false)`.
- `applyFromPhone(_ content: ClipboardContent, auto: Bool)`: same body, ending in `eventBroadcast.yield(.receivedFromPhone(item, auto: auto))`.

`AppModel.swift`:

```swift
        case .receivedFromPhone(let item, let auto):
            // Automatic copies (Plan 9) arrive silently; explicit sends still notify.
            if !auto { await clipboardNotifier.notify(item, from: pairedDevice?.name ?? "your phone") }
```

`MacDroidDevServer/main.swift`, after `await clipboard.attach(to: hub)`:

```swift
let clipboardEvents = Task {
    for await event in clipboard.events {
        if case .receivedFromPhone(let item, let auto) = event { print("CLIPBOARD received text=\(item.text ?? "") auto=\(auto)") }
    }
}
```

At the end, next to the other `….cancel()` calls, add `clipboardEvents.cancel()`.

Run `swift test` and the Mac app build. Expected: tests PASS, and the build succeeds with no warnings.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 4: Mac applies automatic phone copies like any other, with no notification (`ClipboardEvent.receivedFromPhone(_, auto:)`); the dev server logs them." ARCHITECTURE: the Mac clipboard paragraph mentions `auto`.

```bash
git add mac docs/handoff
git commit -m "feat(mac): silent automatic clipboard items from the phone

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Android app — the trigger, the reader and the switch

**Files:**
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/clipboard/ClipboardPrefs.kt`, `ClipboardBridge.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/clipboard/ClipboardSendActivity.kt`, `ClipboardScreen.kt`, `ClipboardViewModel.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/presentation/ClipboardPresentation.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/control/MacDroidControlService.kt`
- Modify: `android/app/src/main/res/xml/control_service.xml`
- Test: `android/app/src/test/java/dev/macdroid/app/ClipboardPresentationTest.kt`

**Interfaces:**
- Consumes:
  - Task 3: `CopyDetector`, `AutoClipboardPolicy`, `AutoGate`, `AutoToast`, `ClipboardHub.sendText(…, auto)`
  - Task 1: the preview id(s)
- Produces:
  - `ClipboardPrefs.autoSend: StateFlow<Boolean>`, `setAutoSend(on)` and `autoSendOn(): Boolean`
  - `ClipboardBridge.copyDetector`, `autoSendOn()`, `autoGate(): AutoGate` and `sendAutoInBackground(clip: ClipData?)`
  - `ClipboardSendActivity.autoIntent(context)`
  - `ClipboardPresentation.autoToast(AutoToast): String`

- [ ] **Step 1: Failing test** (`ClipboardPresentationTest`)

```kotlin
    @Test fun automaticToasts() {
        assertEquals("Mac not connected — copy not sent", ClipboardPresentation.autoToast(AutoToast.NotConnected))
        assertEquals("Your Mac's MacDroid can't receive the clipboard", ClipboardPresentation.autoToast(AutoToast.MacTooOld))
        assertEquals("Too large to send to your Mac", ClipboardPresentation.autoToast(AutoToast.TooLarge))
    }
```

(import `dev.macdroid.core.clipboard.AutoToast`). Run `cd android && ./gradlew -q :app:compileDebugUnitTestKotlin`. Expected: unresolved `autoToast`.

- [ ] **Step 2: Presentation and prefs**

`ClipboardPresentation`:

```kotlin
    /** Automatic sends (Plan 9) toast only when something went wrong. */
    fun autoToast(toast: AutoToast): String = when (toast) {
        AutoToast.NotConnected -> "Mac not connected — copy not sent"
        AutoToast.MacTooOld -> "Your Mac's MacDroid can't receive the clipboard"
        AutoToast.TooLarge -> "Too large to send to your Mac"
    }
```

`ClipboardPrefs` (the KDoc becomes "The Clipboard tab's switches: "Get Mac clipboard" and "Send copies to your Mac automatically" (both default on)."):

```kotlin
    private val _autoSend = MutableStateFlow(prefs.getBoolean(AUTO_KEY, true))
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()

    fun setAutoSend(on: Boolean) {
        prefs.edit().putBoolean(AUTO_KEY, on).apply()
        _autoSend.value = on
    }

    /** Always off in store builds (spec §5). */
    fun autoSendOn(): Boolean = !BuildConfig.STORE_BUILD && _autoSend.value
```

Add `const val AUTO_KEY = "autoSendToMac"` to the companion and `import dev.macdroid.app.BuildConfig`.

Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 3: Bridge**

In `ClipboardBridge`:

```kotlin
    /** Plan 9: System UI's copy preview → copies to act on. Fed by MacDroidControlService. */
    val copyDetector = CopyDetector()
    private val autoPolicy = AutoClipboardPolicy()

    fun autoSendOn(): Boolean = prefs.autoSendOn()

    /** Before the invisible reader opens: can a send happen now? (spec §3.1 step 2) */
    fun autoGate(): AutoGate = autoPolicy.gate(connection.state.value)

    /** From [ClipboardSendActivity] in automatic mode, after reading [clip] with focus. Silent unless it fails (spec §3.3). */
    fun sendAutoInBackground(clip: ClipData?) {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val sensitive = clip?.description?.extras?.getBoolean(AutoClipboardPolicy.SENSITIVE_EXTRA) == true
        val text = autoPolicy.textToSend(item?.text?.toString(), sensitive) ?: return
        scope.launch(Dispatchers.IO) {
            val toast = autoPolicy.result(text, hub.sendText(text, connection.state.value, auto = true)) ?: return@launch
            withContext(Dispatchers.Main) {
                Toast.makeText(context, ClipboardPresentation.autoToast(toast), Toast.LENGTH_SHORT).show()
            }
        }
    }
```

`item?.text` is null for image and URI clips, so they're skipped (text only).

In `start()`:
- Change the incoming collector to:

  ```kotlin
  hub.handle(message)?.let { if (it is ClipboardApply.Text) autoPolicy.received(it.text); apply(it) }
  ```

- Add:

  ```kotlin
          scope.launch { connection.state.collect { autoPolicy.connectionChanged(it) } }
  ```

Echo (Review Focus #1): call `copyDetector.ownWrite(SystemClock.uptimeMillis())` immediately before `clipboard.setPrimaryClip(clip)` in **both** `apply(...)` and `copyLocally(...)`.

Imports:
- `android.os.SystemClock`, `android.widget.Toast`
- `dev.macdroid.app.ui.presentation.ClipboardPresentation`
- `dev.macdroid.core.clipboard.AutoClipboardPolicy`, `AutoGate`, `CopyDetector`

- [ ] **Step 4: The reader activity**

`ClipboardSendActivity`:

```kotlin
class ClipboardSendActivity : ComponentActivity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
            // Automatic (Plan 9): hand over and leave at once, without an animation, so the app underneath keeps going.
            graph.clipboardBridge.sendAutoInBackground(clip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TYPE_CLOSE, 0, 0)
            finish()
            return
        }
        lifecycleScope.launch {
            val result = graph.clipboardBridge.sendClip(clip)
            Toast.makeText(applicationContext, ClipboardPresentation.toast(result), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_AUTO = "auto"

        /** Opened by MacDroidControlService after a copy (spec §3.1 step 3). */
        fun autoIntent(context: Context): Intent = Intent(context, ClipboardSendActivity::class.java)
            .putExtra(EXTRA_AUTO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
```

Update the KDoc to say the service opens it too, and add the imports `android.content.Context`, `android.content.Intent` and `android.os.Build`.

- [ ] **Step 5: The service**

`res/xml/control_service.xml`: `android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"`.

`MacDroidControlService`:
- `onAccessibilityEvent` becomes:

  ```kotlin
      override fun onAccessibilityEvent(event: AccessibilityEvent) {
          if (event.packageName != SYSTEM_UI) return
          autoStart()
          autoClipboard()
      }
  ```

- Move the old body after the package check into `private fun autoStart()`, unchanged except that `return` exits `autoStart`.
- Then add:

```kotlin
    /** Plan 9: a copy on the phone goes to the Mac (spec §3.1). Reads only System UI's copy preview. */
    private fun autoClipboard() {
        val bridge = graph.clipboardBridge
        if (!bridge.autoSendOn()) return
        if (!bridge.copyDetector.preview(previewSignature(), SystemClock.uptimeMillis())) return
        when (val gate = bridge.autoGate()) {
            AutoGate.Go -> runCatching { startActivity(ClipboardSendActivity.autoIntent(this)) }
            AutoGate.Quiet -> Unit
            is AutoGate.Toast -> Toast.makeText(this, ClipboardPresentation.autoToast(gate.toast), Toast.LENGTH_SHORT).show()
        }
    }

    /** The copy preview's text, or null when System UI isn't showing it. */
    private fun previewSignature(): String? {
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName != SYSTEM_UI) continue
            val preview = PREVIEW_IDS.firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId(it).firstOrNull() } ?: continue
            return CopyDetector.signature(NodeAdapter(preview))
        }
        return null
    }
```

In the companion:

```kotlin
        /** System UI's copy preview (Plan 9 probe, Q1; see the auto-clipboard follow-ups file). */
        private val PREVIEW_IDS = listOf("com.android.systemui:id/clipboard_preview")
```

**Replace that list with the id(s) recorded in Task 1's Decision column.** If the probe found a different id, ledger it as a ruling: "PREVIEW_IDS = <ids> from the probe".

Imports:
- `android.widget.Toast`
- `dev.macdroid.app.ui.clipboard.ClipboardSendActivity`
- `dev.macdroid.app.ui.presentation.ClipboardPresentation`
- `dev.macdroid.core.clipboard.AutoGate`, `dev.macdroid.core.clipboard.CopyDetector`

- [ ] **Step 6: The switch** (Clipboard tab; Global Constraints ruling)

`ClipboardViewModel`:

```kotlin
    data class UiState(val recent: List<ClipItem>, val receiveFromMac: Boolean, val autoSend: Boolean = true, val controlOn: Boolean = false)

    val state: StateFlow<UiState> = combine(bridge.hub.recent, bridge.prefs.receiveFromMac, bridge.prefs.autoSend, MacDroidControlService.running) {
        recent, receive, auto, control -> UiState(recent, receive, auto, control)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        UiState(bridge.hub.recent.value, bridge.prefs.receiveFromMac.value, bridge.prefs.autoSend.value, MacDroidControlService.running.value))

    fun setAutoSend(on: Boolean) = bridge.prefs.setAutoSend(on)
```

`ClipboardScreen`, right after the "Get Mac clipboard" `item { Card { … } }`:

```kotlin
        if (!BuildConfig.STORE_BUILD) {
            item {
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send copies to your Mac automatically", style = MaterialTheme.typography.titleMedium)
                            Text(if (state.controlOn) "When you copy text on this phone" else "Needs MacDroid control (Settings → Remote control)",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoSend && state.controlOn, enabled = state.controlOn, onCheckedChange = viewModel::setAutoSend)
                    }
                }
            }
        }
```

Imports: `dev.macdroid.app.BuildConfig`, and `dev.macdroid.app.platform.control.MacDroidControlService` in the view model.

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines.

- [ ] **Step 7: Handoff and commit**

CHANGELOG bullet: "Task 5: the control service spots System UI's copy preview and opens the invisible reader (no animation), which sends the text with `auto`; echo window on MacDroid's own clipboard writes; once-only failure toasts; Clipboard tab switch 'Send copies to your Mac automatically' (needs MacDroid control; hidden in store builds)."

Other docs:
- ARCHITECTURE: `platform/control` and `platform/clipboard`.
- DEV-GUIDE: "Automatic clipboard needs MacDroid control on and an unlocked phone; turn it off on the Clipboard tab."

```bash
git add android docs/handoff
git commit -m "feat(android): send copies to the Mac automatically

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Cross-platform — an automatic copy reaches the Mac silently

**Files:**
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt` (`exchangeClipboard`)
- Modify: `scripts/cross-platform-test.sh`

**Interfaces:**
- Consumes:
  - Task 3's `ClipboardHub.sendText(…, auto = true)`
  - Task 4's dev-server line `CLIPBOARD received text=… auto=…`

- [ ] **Step 1: Failing script checks** (next to the existing `CLIPBOARD` greps)

```bash
grep -q 'CLIPBOARD received text=hello from phone auto=false' "$LOG" || { echo "the Mac didn't see a manual copy as manual"; cat "$LOG"; exit 1; }
grep -q 'CLIPBOARD received text=copied on the phone auto=true' "$LOG" || { echo "the Mac never got the automatic copy"; cat "$LOG"; exit 1; }
```

Run `./scripts/cross-platform-test.sh`. Expected: it fails with "the Mac never got the automatic copy". The manual line already passes after Task 4.

- [ ] **Step 2: Kotlin side**

In `exchangeClipboard`, right after `assertEquals(SendResult.Sent, hub.sendText("hello from phone", manager.state.value))`:

```kotlin
        // Plan 9: an automatic copy carries auto=true; the Mac applies it without a notification.
        assertEquals(SendResult.Sent, hub.sendText("copied on the phone", manager.state.value, auto = true))
```

- [ ] **Step 3: Run, handoff, commit**

Run `./scripts/cross-platform-test.sh` twice. Expected: "cross-platform test passed" both times. Then run all gates.

CHANGELOG bullet: "Task 6: cross-platform test sends an automatic copy; the Mac applies it and reports `auto=true` (manual copies `auto=false`)." DEV-GUIDE: add "automatic clipboard" to the cross-platform test's description.

```bash
git add android scripts docs/handoff
git commit -m "test: cross-platform automatic clipboard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Remove the probe, final gates, handoff, owner checklist

**Files:**
- Delete: `android/app/src/debug/` (the probe from Task 1; nothing else lives there)
- Modify: `docs/handoff/STATUS.md`, `ARCHITECTURE.md`, `DEV-GUIDE.md`, `ROADMAP.md`, `DECISIONS.md`, `README.md`, `CHANGELOG.md`
- Modify: `docs/superpowers/plans/2026-10-15-macdroid-auto-clipboard-followups.md`

- [ ] **Step 1: Remove the probe**

```bash
git rm -r android/app/src/debug
```

- [ ] **Step 2: Every gate**

```bash
cd mac/MacDroidCore && swift test 2>&1 | tail -3
cd .. && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | grep -E "^/.*(warning|error):|BUILD" | sort -u
cd ../android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all 2>&1 | grep -E "^e:|^w:|BUILD"
cd .. && ./scripts/cross-platform-test.sh
```

Expected: all pass, no warnings, "cross-platform test passed".

- [ ] **Step 3: Handoff**

- **DECISIONS.md:** a new row: "Phone → Mac clipboard is automatic when MacDroid control is on (Plan 9)". Reason: the control service exists since Plan 8, and System UI's copy preview gives a signal with no new access. Rejected: watching every app for "Copy" taps; becoming the keyboard; `READ_LOGS` via adb (needs debugging). Note that it supersedes "Phone → Mac clipboard is user-triggered" for personal builds. Add rows for the two rulings in Global Constraints (switch on the Clipboard tab; appear-or-change detection).
- **ARCHITECTURE.md / DEV-GUIDE.md:** check that Tasks 3–6 left them accurate.
- **STATUS / ROADMAP / README:** Plan 9 built on `feat/auto-clipboard`, pending the owner's check.
- **CHANGELOG:** close the Plan 9 entry with the gate results.

- [ ] **Step 4: Owner checklist** (append to the follow-ups file and the final message)

1. With MacDroid control on, the Clipboard tab shows **"Send copies to your Mac automatically"** switched on.
2. **Copy** a word in Chrome, then press ⌘V on the Mac. It pastes within about a second, with no notification on the Mac and no toast on the phone.
3. **While typing** in Messages, select some of your text and Copy. The keyboard stays open, and you can keep typing straight away.
4. **Copy two different things** quickly. ⌘V gives the second.
5. **Copy on the Mac**, wait for it on the phone, then ⌘V on the Mac again. It's still the Mac's item, and nothing came back from the phone (the Mac's Clipboard page shows no new "from phone" entry).
6. **Copy a password** from your password manager. ⌘V on the Mac does **not** paste it.
7. **Copy an image:** nothing is sent automatically. The tile still sends it.
8. **Turn off Wi-Fi** on the phone and copy twice: one toast, "Mac not connected — copy not sent", then none. Turn Wi-Fi back on: copies flow again.
9. **Switch it off** on the Clipboard tab: copies stay on the phone. The tile and Share still work.
10. **Turn MacDroid control off:** the switch greys out with "Needs MacDroid control (Settings → Remote control)".

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src docs
git commit -m "docs(handoff): automatic phone → Mac clipboard (Plan 9) built on feat/auto-clipboard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Don't merge. The owner checks first.
