# Calls on the Mac Implementation Plan (Plan 10)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** see the phone's calls on the Mac and Answer, Decline or Hang up from it, and dial from the Mac with a number pad or a `tel:` link.

**Architecture:**
- **Phone:** the notification listener hands call-style notifications to a tested core `CallTracker`. It sends `call.state` and resolves the Mac's `call.action` to the notification's own Answer / Decline / Hang up intents. `call.dial` uses `TelecomManager.placeCall`.
- **Mac:** a tested core `CallCenter` turns `call.state` into a panel state. The app shows a non-activating floating panel, a number pad on the Phone page, a link confirmation, and a line in the popover.

**Tech Stack:** Kotlin (Android core + app, Compose), Swift 6 (MacDroidCore, SwiftUI + AppKit), kotlinx.serialization, Swift Testing, JUnit 4, XcodeGen.

**Spec:** `docs/superpowers/specs/2026-10-15-macdroid-calls-design.md`

## Global Constraints

- **Branch:** `feat/calls`, from `main`. Don't merge; the owner checks first.
- **No developer options at runtime (owner, 2026-10-15).** No developer options, wireless debugging, adb grants or Shizuku. The only new permission is `CALL_PHONE`, granted by the owner on screen.
- **Call audio stays on the phone.** No automatic speakerphone (owner's choice A).
- **Feature:** `calls`, appended after `control`, on both sides. Calls are used only when both sides list it.
- **Messages:**

  | Message | Direction | Body |
  |---|---|---|
  | `call.state` | phone → Mac | `callId`, `state` (`ringing` \| `active` \| `ended`), `name` (optional), `number` (optional) |
  | `call.action` | Mac → phone | `callId`, `action` (`answer` \| `decline` \| `hangUp`) |
  | `call.dial` | Mac → phone | `number` (1–32 characters of `+0-9*#`, `+` only first) |
  | `call.error` | phone → Mac | `reason` (`callGone` \| `notSupported` \| `noPermission`), `callId` (optional) |
- **State mapping (phone):** call-style incoming or screening → `ringing`; ongoing → `active`; notification removed → `ended`. Only changes are sent.
- **Copy (Mac):**
  - panel: "Incoming call"; buttons "Decline", "Answer"; active line "On a call with <name>"; button "Hang up"
  - notices: "Couldn't answer — use your phone", "Couldn't decline — use your phone", "Couldn't hang up — use your phone", "Allow MacDroid to make calls on your phone (phone Settings → Calls)"
  - link confirmation: "Call <number> on your phone?" with "Call" and "Cancel"
  - dial problems: "Your phone isn't connected", "Update MacDroid on your phone to use calls", "That doesn't look like a phone number"
  - Settings section "Calls": "Show incoming calls", "Use MacDroid for phone links", footer "Call audio stays on your phone."
- **Copy (Android):** Settings section "Calls", row "Make calls from your Mac", supporting text "Allowed" or "Off: tap to allow".
- **Privacy:** never log names or numbers. The Mac keeps calls in memory only.
- **The owner's phone:** Task 1 is done **with the owner present**. The owner grants permissions and notification access. Don't toggle them, grant permissions, `pm clear` or tap from adb.
- **Handoff:** every task's commit updates `docs/handoff/`. Task 1 adds a CHANGELOG entry at the top, `## <date> — Plan 10: calls on the Mac (in progress on feat/calls)`, and each later task adds a bullet under it.
- **Gates (all must pass with no warnings):**
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`
  - `./scripts/cross-platform-test.sh`

## Review Focus

1. **Two calls at once (call waiting):** ending one call must not clear the other on the Mac, and the panel shows the ringing one first → Task 3 `twoCallsAreTrackedSeparately`, Task 4 `ringingWinsOverActiveAndEndedCallsLeave`.
2. **Stale actions:** pressing Answer just after the call was answered, declined or missed on the phone must produce a clear notice, not silence → Task 3 `actionsOnUnknownCallsAreCallGone`, Task 4 `errorsBecomeNoticesAndDropTheCall`.
3. **The Mac reconnects mid-call:** after a Wi-Fi blip the Mac must show the call again → Task 3 `reconnectingResendsCurrentCalls`.
4. **Junk phone links:** `tel:` links with spaces, dashes, brackets, `%2B`, `;ext=`, letters, nothing usable, or over 32 characters must dial a clean number or nothing → Task 4 `phoneNumbersAreCleaned`, `telLinksAreParsed`; Task 3 `onlyCleanNumbersAreDialled`.
5. **A disconnect while ringing:** the panel must not stay up forever → Task 4 `disconnectClearsCalls`.

---

## File Structure

```
android/app/src/debug/AndroidManifest.xml, debug/java/.../debug/CallProbeListener.kt, CallProbeActivity.kt   (T1, deleted in T8)
docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md (probe results, checklist)                         (T1, T8)
protocol/fixtures/call.state.json, call.state.ended.json, call.action.json, call.dial.json, call.error.json; PROTOCOL.md (T2)
android/core/.../protocol/Messages.kt, MessageCodec.kt; test MessageCodecTest.kt                                (T2)
mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift, MessageCodec.swift; ProtocolFixtureTests.swift     (T2)
android/core/src/main/kotlin/dev/macdroid/core/calls/CallTracker.kt (new: CallTracker, CallNotification, CallType,
  CallCommand, CallNumbers, CallLabels); test calls/CallTrackerTest.kt                                           (T3)
mac/MacDroidCore/Sources/MacDroidCore/Calls/CallCenter.swift, PhoneNumbers.swift (new);
  Presentation/CallPresentation.swift (new); Tests/MacDroidCoreTests/CallsTests.swift (new)                      (T4)
android/app/.../platform/calls/CallReader.kt, CallBridge.kt (new); MacDroidApp.kt (graph);
  platform/notifications/MacDroidNotificationListener.kt; AndroidManifest.xml (CALL_PHONE);
  ui/main/SettingsScreen.kt, SettingsViewModel.kt                                                                 (T5)
mac/project.yml, mac/MacDroid/Info.plist (tel scheme); AppDelegate.swift; MacDroidApp.swift; AppModel.swift;
  Calls/CallPanelController.swift, Views/Calls/CallPanelView.swift, Views/Calls/DialPadView.swift (new);
  Views/Phone/PairedDeviceView.swift; Views/MenuBarPopover.swift; Views/Settings/GeneralSettingsView.swift        (T6)
mac/MacDroidCore/Sources/MacDroidDevServer/main.swift; android/core/src/test/.../CrossPlatformTest.kt;
  scripts/cross-platform-test.sh                                                                                  (T7)
docs/handoff/*                                                                                                    (every task)
```

---

### Task 1: On-device probe (with the owner)

**Purpose:** answer spec §6.1 Q1–Q4 on the owner's Nothing Phone (3), Android 17, before any product code.
- The probe lives only in the **debug** source set, and is deleted in Task 8.
- All output goes to `Log.w("CallProbe", …)`.
- Never log names or numbers, only flags, lengths and URI schemes.

**Files:**
- Create: `android/app/src/debug/AndroidManifest.xml`
- Create: `android/app/src/debug/java/dev/macdroid/app/debug/CallProbeListener.kt`, `CallProbeActivity.kt`
- Create: `docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md`

**Interfaces:**
- Produces, in the follow-ups file:
  - whether call-style extras are present
  - whether Answer / Decline / Hang up fire from the background
  - whether `placeCall` works from the background
  - whether a number is present

  Task 5 reads these. If Q1 shows only labelled buttons (no call-style intents), `CallReader` relies on `CallLabels`; that's already in the plan.

- [ ] **Step 1: Branch and debug manifest**

```bash
git checkout main && git checkout -b feat/calls
```

`android/app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CALL_PHONE" />
    <application>
        <activity
            android:name=".debug.CallProbeActivity"
            android:exported="true"
            android:label="Call probe"
            android:taskAffinity="dev.macdroid.callprobe">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".debug.CallProbeListener"
            android:exported="true"
            android:label="MacDroid call probe"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>
    </application>
</manifest>
```

- [ ] **Step 2: Probe code**

`CallProbeListener.kt`:

```kotlin
package dev.macdroid.app.debug

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Debug-only (Plan 10 Task 1): logs call notifications (flags, lengths, schemes; never names or numbers) and, in the mode
 * chosen in [CallProbeActivity], fires their Answer / Decline / Hang up actions from the background.
 */
class CallProbeListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val acted = mutableSetOf<String>()

    override fun onListenerConnected() { Log.w(TAG, "call probe listener connected") }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        if (callType == 0 && n.category != Notification.CATEGORY_CALL) return
        val person = person(n)
        val answer = intent(n, Notification.EXTRA_ANSWER_INTENT)
        val decline = intent(n, Notification.EXTRA_DECLINE_INTENT)
        val hangUp = intent(n, Notification.EXTRA_HANG_UP_INTENT)
        Log.w(TAG, "call notification pkg=${sbn.packageName} category=${n.category} callType=$callType " +
            "ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0} person=${person != null} " +
            "nameLength=${person?.name?.length ?: -1} titleLength=${extras.getCharSequence(Notification.EXTRA_TITLE)?.length ?: -1} " +
            "uriScheme=${person?.uri?.substringBefore(':')} uriLength=${person?.uri?.length ?: -1} " +
            "answer=${answer != null} decline=${decline != null} hangUp=${hangUp != null} " +
            "actions=${n.actions?.map { it.title?.toString() }}")
        val mode = CallProbeActivity.mode
        if (sbn.key in acted) return
        when {
            mode == "answer" && callType == CALL_TYPE_INCOMING && answer != null -> fireLater(sbn.key, "answer", answer, 4_000)
            mode == "answer" && callType == CALL_TYPE_ONGOING && hangUp != null -> fireLater(sbn.key, "hangUp", hangUp, 6_000)
            mode == "decline" && callType == CALL_TYPE_INCOMING && decline != null -> fireLater(sbn.key, "decline", decline, 4_000)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val n = sbn.notification
        val callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        if (callType != 0 || n.category == Notification.CATEGORY_CALL) Log.w(TAG, "call notification removed callType=$callType")
    }

    private fun fireLater(key: String, what: String, intent: PendingIntent, delayMs: Long) {
        acted += "$key/$what"
        main.postDelayed({
            val result = runCatching { intent.send() }
            Log.w(TAG, "fired $what from the background: ${result.exceptionOrNull() ?: "ok"}")
        }, delayMs)
    }

    @Suppress("DEPRECATION")
    private fun person(n: Notification): Person? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun intent(n: Notification, key: String): PendingIntent? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> n.extras.getParcelable(key, PendingIntent::class.java)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> n.extras.getParcelable(key)
        else -> null
    }

    companion object {
        const val TAG = "CallProbe"
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
    }
}
```

The `acted` set keys on `"$key/$what"`. The guard `if (sbn.key in acted)` never matches, because entries carry a `/what` suffix, so each kind of action fires once per notification key. That's intended: answer once, then hang up once on the same key.

`CallProbeActivity.kt`:

```kotlin
package dev.macdroid.app.debug

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only (Plan 10 Task 1): pick what the probe listener does, grant CALL_PHONE, and dial from the background. */
class CallProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        fun show() { status.text = "Mode: $mode" }
        val number = EditText(this).apply { hint = "Number to dial"; inputType = InputType.TYPE_CLASS_PHONE }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(Button(context).apply { text = "Log only"; setOnClickListener { mode = "log"; show() } })
            addView(Button(context).apply { text = "Answer after 4 s, hang up 6 s later"; setOnClickListener { mode = "answer"; show() } })
            addView(Button(context).apply { text = "Decline after 4 s"; setOnClickListener { mode = "decline"; show() } })
            addView(Button(context).apply { text = "Allow calls"; setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1) } })
            addView(number)
            addView(Button(context).apply {
                text = "Dial in 5 s (press Home now)"
                setOnClickListener {
                    val target = number.text.toString()
                    Handler(Looper.getMainLooper()).postDelayed({
                        val result = runCatching {
                            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", target, null), null)
                        }
                        Log.w(CallProbeListener.TAG, "placeCall from the background: ${result.exceptionOrNull() ?: "ok"}")
                    }, 5_000)
                }
            })
        }
        show()
        setContentView(layout)
    }

    companion object {
        @Volatile var mode = "log"
    }
}
```

Run `cd android && ./gradlew :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines. If `placeCall` is flagged for a missing permission check, wrap the call in `if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)` and log "no permission" otherwise.

- [ ] **Step 3: Run it with the owner**

1. Install: `ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`. Use `adb mdns services` to find the serial. This keeps the app and its pairing.
2. Ask the owner to:
   - grant **notification access** to "MacDroid call probe" (Settings → Notifications → Device & app notifications, or Notification read access)
   - open the **"Call probe"** app icon
3. Start the log in the background: `adb logcat -c && adb logcat -s CallProbe:W > <scratchpad>/call-probe.log`.
4. Ask the owner to do the following, one at a time:
   - a. **Log only:** someone calls the phone; let it ring, then decline on the phone. *(Q1 and Q4)*
   - b. **"Answer after 4 s, hang up 6 s later":** someone calls; don't touch the phone. Did it answer by itself, then hang up? *(Q2)*
   - c. **"Decline after 4 s":** someone calls; don't touch the phone. Did it decline? *(Q2)*
   - d. **"Allow calls"** → grant. Type a number the owner chooses, such as their other phone, tap **"Dial in 5 s"**, then press Home at once. Did the call start? *(Q3)*
   - e. Optional: a call from a number **not** in contacts, to see `uriScheme` and `uriLength` for an unknown caller.
5. Ask the owner to remove the probe's notification access afterwards.

- [ ] **Step 4: Record the results**

Create `docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md` with `| # | Question | Observed | Decision |` rows for Q1 (call type, ongoing, the extras), Q1-removal, Q2 answer / hang up / decline, Q3, and Q4 (known and unknown caller).

**Stop rule (spec §6.1):** if no call notification is logged, or none of answer / decline / hang-up fires from the background, **stop and report to the owner** before Task 2.

**If Q3 fails** (`placeCall` didn't start the call from the background): ledger a ruling that Task 5's `dial` adds the spec §2.2 fallback. When `MacDroidControlService.instance` is non-null, call `instance.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(FLAG_ACTIVITY_NEW_TASK))`; otherwise report `noPermission`. Write that code into Task 5 at execution.

- [ ] **Step 5: Handoff and commit**

- CHANGELOG: add the Plan 10 entry, with a Task 1 bullet summarising the results.
- STATUS: Plan 10 in progress on `feat/calls`.

```bash
git add android/app/src/debug docs
git commit -m "chore(calls): on-device probe and its results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Protocol — `call.*` messages and the `calls` feature

**Files:**
- Create: `protocol/fixtures/call.state.json`, `call.state.ended.json`, `call.action.json`, `call.dial.json`, `call.error.json`
- Modify: `protocol/PROTOCOL.md`
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt`, `MessageCodec.kt`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift`, `MessageCodec.swift`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/protocol/MessageCodecTest.kt`, `mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift`

**Interfaces:**
- Produces:
  - **Kotlin:**
    - `CallPhase { Ringing, Active, Ended }`
    - `CallStateBody(callId, state: CallPhase, name: String? = null, number: String? = null)`
    - `CallActionKind { Answer, Decline, HangUp }`
    - `CallActionBody(callId, action)`
    - `CallDialBody(number)`
    - `CallErrorReason { CallGone, NotSupported, NoPermission }`
    - `CallErrorBody(reason, callId: String? = null)`
    - `Message.CallState / CallAction / CallDial / CallError`
    - `Features.CALLS = "calls"`
  - **Swift:**
    - `CallPhase` (`ringing`, `active`, `ended`)
    - `CallState(callId:state:name:number:)`
    - `CallActionKind` (`answer`, `decline`, `hangUp`)
    - `CallAction(callId:action:)`
    - `CallDial(number:)`
    - `CallErrorReason` (`callGone`, `notSupported`, `noPermission`)
    - `CallError(reason:callId:)`
    - `Message.callState/.callAction/.callDial/.callError`
    - `Feature.calls`

- [ ] **Step 1: Fixtures and failing tests**

```json
// protocol/fixtures/call.state.json
{ "v": 1, "type": "call.state", "id": "6f1c2a0e-0000-4000-8000-000000000070", "body": { "callId": "0|com.google.android.dialer|1|null|10123", "state": "ringing", "name": "Mom", "number": "+15551234567" } }
```
```json
// protocol/fixtures/call.state.ended.json
{ "v": 1, "type": "call.state", "id": "6f1c2a0e-0000-4000-8000-000000000071", "body": { "callId": "0|com.google.android.dialer|1|null|10123", "state": "ended" } }
```
```json
// protocol/fixtures/call.action.json
{ "v": 1, "type": "call.action", "id": "6f1c2a0e-0000-4000-8000-000000000072", "body": { "callId": "0|com.google.android.dialer|1|null|10123", "action": "hangUp" } }
```
```json
// protocol/fixtures/call.dial.json
{ "v": 1, "type": "call.dial", "id": "6f1c2a0e-0000-4000-8000-000000000073", "body": { "number": "+15551234567" } }
```
```json
// protocol/fixtures/call.error.json
{ "v": 1, "type": "call.error", "id": "6f1c2a0e-0000-4000-8000-000000000074", "body": { "reason": "callGone", "callId": "0|com.google.android.dialer|1|null|10123" } }
```

(The `// path` lines are labels; the files contain only the JSON.)

Add the five names after `"input.notice.json",` in **both** `messageFixtures` lists.

**Kotlin `MessageCodecTest`:**
- Rename `supportedFeaturesIncludeControl` to `supportedFeaturesIncludeCalls`, and append `"calls"` to its expected list.
- Add:

```kotlin
    @Test fun callFixturesDecode() {
        val id = "0|com.google.android.dialer|1|null|10123"
        assertEquals(Message.CallState(CallStateBody(id, CallPhase.Ringing, "Mom", "+15551234567")), MessageCodec.decode(Fixtures.text("call.state.json")))
        assertEquals(Message.CallState(CallStateBody(id, CallPhase.Ended)), MessageCodec.decode(Fixtures.text("call.state.ended.json")))
        assertEquals(Message.CallAction(CallActionBody(id, CallActionKind.HangUp)), MessageCodec.decode(Fixtures.text("call.action.json")))
        assertEquals(Message.CallDial(CallDialBody("+15551234567")), MessageCodec.decode(Fixtures.text("call.dial.json")))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.CallGone, id)), MessageCodec.decode(Fixtures.text("call.error.json")))
        assertFalse(MessageCodec.encode(Message.CallState(CallStateBody("c", CallPhase.Ended))).contains("name"))
    }
```

**Swift `ProtocolFixtureTests`:**
- Rename `supportedFeaturesIncludeControl` to `supportedFeaturesIncludeCalls`, and append `Feature.calls`.
- Add:

```swift
    @Test func callFixturesDecode() throws {
        let id = "0|com.google.android.dialer|1|null|10123"
        #expect(try MessageCodec.decode(Fixtures.text("call.state.json")) == .callState(CallState(callId: id, state: .ringing, name: "Mom", number: "+15551234567")))
        #expect(try MessageCodec.decode(Fixtures.text("call.state.ended.json")) == .callState(CallState(callId: id, state: .ended)))
        #expect(try MessageCodec.decode(Fixtures.text("call.action.json")) == .callAction(CallAction(callId: id, action: .hangUp)))
        #expect(try MessageCodec.decode(Fixtures.text("call.dial.json")) == .callDial(CallDial(number: "+15551234567")))
        #expect(try MessageCodec.decode(Fixtures.text("call.error.json")) == .callError(CallError(reason: .callGone, callId: id)))
    }
```

Run `cd android && ./gradlew -q :core:compileTestKotlin` and `cd mac/MacDroidCore && swift build --build-tests`. Expected: unresolved references on both sides.

- [ ] **Step 2: Implement**

Kotlin `Messages.kt`, after the Plan 8 input block:

```kotlin
// Calls (Plan 10)

@Serializable enum class CallPhase { @SerialName("ringing") Ringing, @SerialName("active") Active, @SerialName("ended") Ended }
@Serializable data class CallStateBody(val callId: String, val state: CallPhase, val name: String? = null, val number: String? = null)
@Serializable enum class CallActionKind { @SerialName("answer") Answer, @SerialName("decline") Decline, @SerialName("hangUp") HangUp }
@Serializable data class CallActionBody(val callId: String, val action: CallActionKind)
@Serializable data class CallDialBody(val number: String)
@Serializable enum class CallErrorReason { @SerialName("callGone") CallGone, @SerialName("notSupported") NotSupported, @SerialName("noPermission") NoPermission }
@Serializable data class CallErrorBody(val reason: CallErrorReason, val callId: String? = null)
```

In `sealed interface Message`:

```kotlin
    data class CallState(val body: CallStateBody) : Message
    data class CallAction(val body: CallActionBody) : Message
    data class CallDial(val body: CallDialBody) : Message
    data class CallError(val body: CallErrorBody) : Message
```

In `Features`, add `const val CALLS = "calls"` and append `CALLS` to `SUPPORTED`.

`MessageCodec.kt`, encode:

```kotlin
            is Message.CallState -> "call.state" to json.encodeToJsonElement(CallStateBody.serializer(), message.body)
            is Message.CallAction -> "call.action" to json.encodeToJsonElement(CallActionBody.serializer(), message.body)
            is Message.CallDial -> "call.dial" to json.encodeToJsonElement(CallDialBody.serializer(), message.body)
            is Message.CallError -> "call.error" to json.encodeToJsonElement(CallErrorBody.serializer(), message.body)
```

Decode:

```kotlin
                "call.state" -> Message.CallState(json.decodeFromJsonElement(CallStateBody.serializer(), body))
                "call.action" -> Message.CallAction(json.decodeFromJsonElement(CallActionBody.serializer(), body))
                "call.dial" -> Message.CallDial(json.decodeFromJsonElement(CallDialBody.serializer(), body))
                "call.error" -> Message.CallError(json.decodeFromJsonElement(CallErrorBody.serializer(), body))
```

Swift `Messages.swift`:
- Add `public static let calls = "calls"` to `Feature`.
- Append `Feature.calls` to `ProtocolInfo.features`.
- Add after the Plan 8 block:

```swift
// MARK: Calls (Plan 10)

public enum CallPhase: String, Codable, Sendable { case ringing, active, ended }

/// The phone's view of one call (spec §2.1). `callId` is the call notification's key.
public struct CallState: Codable, Equatable, Sendable {
    public var callId: String
    public var state: CallPhase
    public var name: String?
    public var number: String?
    public init(callId: String, state: CallPhase, name: String? = nil, number: String? = nil) {
        self.callId = callId
        self.state = state
        self.name = name
        self.number = number
    }
}

public enum CallActionKind: String, Codable, Sendable { case answer, decline, hangUp }

public struct CallAction: Codable, Equatable, Sendable {
    public var callId: String
    public var action: CallActionKind
    public init(callId: String, action: CallActionKind) {
        self.callId = callId
        self.action = action
    }
}

public struct CallDial: Codable, Equatable, Sendable {
    public var number: String
    public init(number: String) { self.number = number }
}

public enum CallErrorReason: String, Codable, Sendable { case callGone, notSupported, noPermission }

public struct CallError: Codable, Equatable, Sendable {
    public var reason: CallErrorReason
    public var callId: String?
    public init(reason: CallErrorReason, callId: String? = nil) {
        self.reason = reason
        self.callId = callId
    }
}
```

And in `enum Message`:

```swift
    case callState(CallState)
    case callAction(CallAction)
    case callDial(CallDial)
    case callError(CallError)
```

`MessageCodec.swift`, encode:

```swift
        case .callState(let body): return try encode(type: "call.state", id: id, body: body)
        case .callAction(let body): return try encode(type: "call.action", id: id, body: body)
        case .callDial(let body): return try encode(type: "call.dial", id: id, body: body)
        case .callError(let body): return try encode(type: "call.error", id: id, body: body)
```

Decode:

```swift
            case "call.state": return .callState(try decoder.decode(Incoming<CallState>.self, from: data).body)
            case "call.action": return .callAction(try decoder.decode(Incoming<CallAction>.self, from: data).body)
            case "call.dial": return .callDial(try decoder.decode(Incoming<CallDial>.self, from: data).body)
            case "call.error": return .callError(try decoder.decode(Incoming<CallError>.self, from: data).body)
```

Any other exhaustive `switch` over `Message` in the Mac sources that fails to compile gets the four cases in its `default`-equivalent branch. Report each in the ledger.

`PROTOCOL.md`:
- Add the four rows from Global Constraints to the message table.
- Add `calls` to the `hello.features` list.
- Add a "Calls rules" paragraph:
  - only changes are sent
  - `ended` follows removal
  - the phone ignores `call.dial` numbers that aren't 1–32 characters of `+0-9*#` with `+` only first
  - names and numbers are never logged

Run `./gradlew :core:test` and `swift test`. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 2: `call.state` / `call.action` / `call.dial` / `call.error` in both codecs, 5 fixtures, `calls` feature on both sides, PROTOCOL.md 'Calls rules'." ARCHITECTURE: the message list.

```bash
git add protocol android/core mac/MacDroidCore docs/handoff
git commit -m "feat(protocol): call messages

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Android core — `CallTracker`, numbers and labels

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/calls/CallTracker.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/calls/CallTrackerTest.kt`

**Interfaces:**
- Consumes: Task 2 types.
- Produces:
  - `enum class CallType { Incoming, Ongoing, Screening }`
  - `data class CallNotification(val key: String, val type: CallType, val name: String?, val number: String?, val actions: Set<CallActionKind>)`
  - `sealed interface CallCommand { data class Fire(val key: String, val action: CallActionKind); data class Dial(val number: String) }`
  - `class CallTracker(send: (Message) -> Boolean)`, with:
    - `connected()`, `disconnected()`
    - `posted(call: CallNotification)`, `removed(key: String)`
    - `handle(message: Message): CallCommand?`
    - `report(reason: CallErrorReason, callId: String?)`
  - `object CallNumbers`, with `fun clean(raw: String): String?`, `fun isClean(number: String): Boolean` and `fun fromUri(uri: String?): String?`
  - `object CallLabels`, with `fun kindOf(title: String?): CallActionKind?`

- [ ] **Step 1: Failing tests** (`CallTrackerTest.kt`)

```kotlin
package dev.macdroid.core.calls

import dev.macdroid.core.protocol.CallActionBody
import dev.macdroid.core.protocol.CallActionKind
import dev.macdroid.core.protocol.CallDialBody
import dev.macdroid.core.protocol.CallErrorBody
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.CallPhase
import dev.macdroid.core.protocol.CallStateBody
import dev.macdroid.core.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {
    private val sent = mutableListOf<Message>()
    private val tracker = CallTracker { sent += it; true }
    private val all = setOf(CallActionKind.Answer, CallActionKind.Decline, CallActionKind.HangUp)
    private fun ringing(key: String = "k1", name: String? = "Mom", number: String? = "+15551234567") =
        CallNotification(key, CallType.Incoming, name, number, setOf(CallActionKind.Answer, CallActionKind.Decline))
    private fun state(key: String, phase: CallPhase, name: String? = "Mom", number: String? = "+15551234567") =
        Message.CallState(CallStateBody(key, phase, name, number))

    @Test fun ringingThenActiveThenEnded() {
        tracker.connected()
        tracker.posted(ringing())
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", "+15551234567", setOf(CallActionKind.HangUp)))
        tracker.removed("k1")
        assertEquals(listOf(state("k1", CallPhase.Ringing), state("k1", CallPhase.Active), state("k1", CallPhase.Ended)), sent)
    }

    @Test fun onlyChangesAreSent() {
        tracker.connected()
        tracker.posted(ringing())
        tracker.posted(ringing()) // the dialer updates its notification while ringing
        tracker.posted(ringing(name = "Mom (mobile)"))
        assertEquals(listOf(state("k1", CallPhase.Ringing), state("k1", CallPhase.Ringing, name = "Mom (mobile)")), sent)
    }

    @Test fun screeningCountsAsRinging() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Screening, null, null, emptySet()))
        assertEquals(listOf(Message.CallState(CallStateBody("k1", CallPhase.Ringing))), sent)
    }

    @Test fun unknownRemovalsSendNothing() {
        tracker.connected()
        tracker.removed("not-a-call")
        assertTrue(sent.isEmpty())
    }

    // Review Focus #1
    @Test fun twoCallsAreTrackedSeparately() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", null, all))
        tracker.posted(ringing("k2", "Dad", null))
        tracker.removed("k2")
        assertEquals(listOf(
            state("k1", CallPhase.Active, number = null),
            state("k2", CallPhase.Ringing, "Dad", null),
            state("k2", CallPhase.Ended, "Dad", null),
        ), sent)
    }

    @Test fun nothingIsSentBeforeTheMacIsReady() {
        tracker.posted(ringing())
        assertTrue(sent.isEmpty())
        tracker.connected()
        assertEquals(listOf(state("k1", CallPhase.Ringing)), sent)
    }

    // Review Focus #3
    @Test fun reconnectingResendsCurrentCalls() {
        tracker.connected()
        tracker.posted(CallNotification("k1", CallType.Ongoing, "Mom", null, all))
        tracker.disconnected()
        tracker.removed("gone-while-offline")
        tracker.connected()
        assertEquals(listOf(state("k1", CallPhase.Active, number = null), state("k1", CallPhase.Active, number = null)), sent)
    }

    @Test fun actionsResolveToTheNotification() {
        tracker.connected()
        tracker.posted(ringing())
        assertEquals(CallCommand.Fire("k1", CallActionKind.Answer), tracker.handle(Message.CallAction(CallActionBody("k1", CallActionKind.Answer))))
    }

    // Review Focus #2
    @Test fun actionsOnUnknownCallsAreCallGone() {
        tracker.connected()
        assertNull(tracker.handle(Message.CallAction(CallActionBody("old", CallActionKind.Answer))))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.CallGone, "old")), sent.last())
    }

    @Test fun missingActionsAreNotSupported() {
        tracker.connected()
        tracker.posted(ringing())
        assertNull(tracker.handle(Message.CallAction(CallActionBody("k1", CallActionKind.HangUp))))
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.NotSupported, "k1")), sent.last())
    }

    // Review Focus #4
    @Test fun onlyCleanNumbersAreDialled() {
        assertEquals(CallCommand.Dial("+15551234567"), tracker.handle(Message.CallDial(CallDialBody("+15551234567"))))
        assertEquals(CallCommand.Dial("*#06#"), tracker.handle(Message.CallDial(CallDialBody("*#06#"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody(""))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("+1 555 123"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("1+5"))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("1".repeat(33)))))
        assertNull(tracker.handle(Message.CallDial(CallDialBody("+"))))
    }

    @Test fun reportSendsAnError() {
        tracker.report(CallErrorReason.NoPermission, null)
        assertEquals(Message.CallError(CallErrorBody(CallErrorReason.NoPermission)), sent.single())
    }

    @Test fun numbersAndUris() {
        assertEquals("+15551234567", CallNumbers.clean(" +1 (555) 123-4567 "))
        assertEquals("15551234567", CallNumbers.clean("1-555-123-4567 ext"))
        assertNull(CallNumbers.clean("call me"))
        assertNull(CallNumbers.clean("+"))
        assertTrue(CallNumbers.isClean("+15551234567"))
        assertFalse(CallNumbers.isClean("+1 555"))
        assertEquals("+15551234567", CallNumbers.fromUri("tel:+15551234567"))
        assertEquals("+15551234567", CallNumbers.fromUri("tel:%2B1-555-123-4567"))
        assertNull(CallNumbers.fromUri("mailto:mom@example.com"))
        assertNull(CallNumbers.fromUri("content://com.android.contacts/contacts/lookup/123"))
        assertNull(CallNumbers.fromUri(null))
    }

    @Test fun buttonLabels() {
        assertEquals(CallActionKind.Answer, CallLabels.kindOf("Answer"))
        assertEquals(CallActionKind.Decline, CallLabels.kindOf("DECLINE"))
        assertEquals(CallActionKind.Decline, CallLabels.kindOf("Reject"))
        assertEquals(CallActionKind.HangUp, CallLabels.kindOf("Hang up"))
        assertEquals(CallActionKind.HangUp, CallLabels.kindOf("End call"))
        assertNull(CallLabels.kindOf("Speaker"))
        assertNull(CallLabels.kindOf(null))
    }
}
```

Run `cd android && ./gradlew -q :core:compileTestKotlin`. Expected: unresolved references.

- [ ] **Step 2: Implement** `CallTracker.kt`

```kotlin
package dev.macdroid.core.calls

import dev.macdroid.core.protocol.CallActionKind
import dev.macdroid.core.protocol.CallErrorBody
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.CallPhase
import dev.macdroid.core.protocol.CallStateBody
import dev.macdroid.core.protocol.Message
import java.net.URLDecoder

enum class CallType { Incoming, Ongoing, Screening }

/** A call notification, read out of Android's notification by the app (Plan 10 spec §2.1). [key] is the notification key. */
data class CallNotification(val key: String, val type: CallType, val name: String?, val number: String?, val actions: Set<CallActionKind>)

/** What the app must do for a Mac request. */
sealed interface CallCommand {
    data class Fire(val key: String, val action: CallActionKind) : CallCommand
    data class Dial(val number: String) : CallCommand
}

/**
 * The phone's calls as the Mac sees them (spec §2.1): `call.state` on every change, `ended` on removal, everything again
 * after a reconnect; the Mac's `call.action` resolved to the notification's own actions. Thread-safe.
 */
class CallTracker(private val send: (Message) -> Boolean) {
    private val calls = LinkedHashMap<String, CallNotification>()
    private val lastSent = HashMap<String, CallStateBody>()
    private var macReady = false

    @Synchronized fun connected() {
        macReady = true
        lastSent.clear()
        calls.values.forEach(::sendState)
    }

    @Synchronized fun disconnected() {
        macReady = false
        lastSent.clear()
    }

    @Synchronized fun posted(call: CallNotification) {
        calls[call.key] = call
        if (macReady) sendState(call)
    }

    @Synchronized fun removed(key: String) {
        val call = calls.remove(key) ?: return
        val last = lastSent.remove(key)
        if (macReady && last != null) send(Message.CallState(CallStateBody(key, CallPhase.Ended, call.name, call.number)))
    }

    /** A Mac request: what to do, or null (an error was already reported, or it isn't a call request). */
    @Synchronized fun handle(message: Message): CallCommand? = when (message) {
        is Message.CallAction -> {
            val call = calls[message.body.callId]
            when {
                call == null -> { report(CallErrorReason.CallGone, message.body.callId); null }
                message.body.action !in call.actions -> { report(CallErrorReason.NotSupported, call.key); null }
                else -> CallCommand.Fire(call.key, message.body.action)
            }
        }
        is Message.CallDial -> if (CallNumbers.isClean(message.body.number)) CallCommand.Dial(message.body.number) else null
        else -> null
    }

    fun report(reason: CallErrorReason, callId: String?) {
        send(Message.CallError(CallErrorBody(reason, callId)))
    }

    private fun sendState(call: CallNotification) {
        val phase = if (call.type == CallType.Ongoing) CallPhase.Active else CallPhase.Ringing
        val body = CallStateBody(call.key, phase, call.name, call.number)
        if (lastSent[call.key] == body) return
        if (send(Message.CallState(body))) lastSent[call.key] = body
    }
}

/** Phone numbers on the wire (spec §4): 1–32 characters of `+0-9*#`, `+` only first. */
object CallNumbers {
    private val CLEAN = Regex("^\\+?[0-9*#]{1,32}$")

    fun isClean(number: String): Boolean = number.length <= 32 && CLEAN.matches(number)

    /** Keeps a leading `+`, digits, `*` and `#`; null when nothing usable is left. */
    fun clean(raw: String): String? {
        val out = StringBuilder()
        for (c in raw.trim()) {
            if (c in '0'..'9' || c == '*' || c == '#') out.append(c)
            else if (c == '+' && out.isEmpty()) out.append(c)
        }
        return out.toString().takeIf { isClean(it) }
    }

    /** The number in a `tel:` URI (a call notification's person), cleaned; null for other schemes. */
    fun fromUri(uri: String?): String? {
        if (uri == null || !uri.startsWith("tel:", ignoreCase = true)) return null
        // URLDecoder turns a literal '+' into a space; keep it as the international prefix.
        val raw = runCatching { URLDecoder.decode(uri.substring(4).replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return clean(raw.substringBefore(';'))
    }
}

/** Older call notifications without the call-style intents: their buttons by label (spec §2.1). */
object CallLabels {
    fun kindOf(title: String?): CallActionKind? = when (title?.trim()?.lowercase()) {
        "answer", "accept" -> CallActionKind.Answer
        "decline", "reject", "dismiss" -> CallActionKind.Decline
        "hang up", "end call", "end" -> CallActionKind.HangUp
        else -> null
    }
}
```

Run `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 3: Android core `CallTracker` (changes only, ended on removal, re-send on reconnect, actions → the notification's intents or `callGone`/`notSupported`), `CallNumbers`, `CallLabels`." ARCHITECTURE: a `calls` row in the core table.

```bash
git add android/core docs/handoff
git commit -m "feat(android-core): call tracking for the Mac

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Mac core — `CallCenter`, `PhoneNumbers`, `CallPresentation`

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Calls/CallCenter.swift`, `Calls/PhoneNumbers.swift`, `Presentation/CallPresentation.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/CallsTests.swift`

**Interfaces:**
- Consumes: Task 2 types.
- Produces:
  - `struct CallInfo: Equatable, Sendable { callId, name: String?, number: String?, phase: CallPhase, since: Date? }`
  - `enum CallPanel: Equatable, Sendable { case none, ringing(CallInfo), active(CallInfo), notice(String) }`
  - `struct CallsState`, with:
    - `calls: [CallInfo]` (oldest first), `connected`, `phoneFeatures`, `showIncoming`, `notice: String?`
    - computed `supported`, `panel`, `activeCall`
  - `enum DialResult { sent, invalidNumber, notConnected, unsupported }`
  - `actor CallCenter`, with:
    - `init(now:send:showIncoming:)`, `states: AsyncStream<CallsState>`, `state`
    - `attach(to:)`, `handle(_:)`, `connectionChanged(_:)`
    - `answer(callId:)`, `decline(callId:)`, `hangUp(callId:)`
    - `dial(_ raw: String) -> DialResult`
    - `setShowIncoming(_:)`, `clearNotice()`
  - `enum PhoneNumbers`, with `clean(_:) -> String?` and `fromTelURL(_:) -> String?`
  - `enum CallPresentation`, with:
    - `title(_ call: CallInfo) -> String`, `detail(_ call: CallInfo) -> String?`
    - `duration(from:to:) -> String`
    - `errorNotice(_ reason: CallErrorReason, action: CallActionKind?) -> String`
    - `confirmTitle(number:) -> String`
    - `dialProblem(_ result: DialResult) -> String?`

- [ ] **Step 1: Failing tests** (`CallsTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct CallsTests {
    final class Harness: Sendable {
        let center: CallCenter
        let sent: Collector<Message>
        init(showIncoming: Bool = true) {
            let sent = Collector<Message>()
            center = CallCenter(now: { Date(timeIntervalSince1970: 1_000) }, send: { await sent.append($0) }, showIncoming: showIncoming)
            self.sent = sent
        }
        func connect(features: [String] = ProtocolInfo.features) async {
            await center.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.10.0", features: features)))
        }
        func phone(_ id: String, _ phase: CallPhase, name: String? = "Mom", number: String? = "+15551234567") async {
            await center.handle(.callState(CallState(callId: id, state: phase, name: name, number: number)))
        }
    }

    @Test func ringingShowsThePanelAndAnsweringSendsTheAction() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        guard case .ringing(let call) = await h.center.state.panel else { Issue.record("no ringing panel"); return }
        #expect(call.name == "Mom")
        await h.center.answer(callId: "k1")
        #expect(await h.sent.items == [.callAction(CallAction(callId: "k1", action: .answer))])
        await h.phone("k1", .active)
        guard case .active(let active) = await h.center.state.panel else { Issue.record("no active panel"); return }
        #expect(active.since == Date(timeIntervalSince1970: 1_000))
        #expect(await h.center.state.activeCall?.callId == "k1")
        await h.phone("k1", .ended)
        #expect(await h.center.state.panel == .none)
    }

    // Review Focus #1
    @Test func ringingWinsOverActiveAndEndedCallsLeave() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .active)
        await h.phone("k2", .ringing, name: "Dad")
        guard case .ringing(let ringing) = await h.center.state.panel else { Issue.record("no ringing panel"); return }
        #expect(ringing.callId == "k2")
        await h.phone("k2", .ended)
        guard case .active(let active) = await h.center.state.panel else { Issue.record("k1 lost"); return }
        #expect(active.callId == "k1")
    }

    // Review Focus #2
    @Test func errorsBecomeNoticesAndDropTheCall() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        await h.center.answer(callId: "k1")
        await h.center.handle(.callError(CallError(reason: .callGone, callId: "k1")))
        #expect(await h.center.state.panel == .notice("Couldn't answer — use your phone"))
        #expect(await h.center.state.calls.isEmpty)
        await h.center.clearNotice()
        #expect(await h.center.state.panel == .none)
        await h.center.handle(.callError(CallError(reason: .noPermission)))
        #expect(await h.center.state.panel == .notice("Allow MacDroid to make calls on your phone (phone Settings → Calls)"))
    }

    // Review Focus #5
    @Test func disconnectClearsCalls() async {
        let h = Harness()
        await h.connect()
        await h.phone("k1", .ringing)
        await h.center.connectionChanged(.disconnected)
        #expect(await h.center.state.panel == .none)
        #expect(await h.center.state.calls.isEmpty)
    }

    @Test func hidingIncomingCallsHidesThePanelButKeepsTheCall() async {
        let h = Harness(showIncoming: false)
        await h.connect()
        await h.phone("k1", .active)
        #expect(await h.center.state.panel == .none)
        #expect(await h.center.state.activeCall?.callId == "k1")
    }

    @Test func dialling() async {
        let h = Harness()
        #expect(await h.center.dial("+1 555 123 4567") == .notConnected)
        await h.connect(features: [Feature.files])
        #expect(await h.center.dial("+1 555 123 4567") == .unsupported)
        await h.connect()
        #expect(await h.center.dial("hello") == .invalidNumber)
        #expect(await h.center.dial("+1 (555) 123-4567") == .sent)
        #expect(await h.sent.items == [.callDial(CallDial(number: "+15551234567"))])
    }

    // Review Focus #4
    @Test func phoneNumbersAreCleaned() {
        #expect(PhoneNumbers.clean(" +1 (555) 123-4567 ") == "+15551234567")
        #expect(PhoneNumbers.clean("1-800-FLOWERS") == "1800")
        #expect(PhoneNumbers.clean("*#06#") == "*#06#")
        #expect(PhoneNumbers.clean("1+5") == "15")
        #expect(PhoneNumbers.clean("call me") == nil)
        #expect(PhoneNumbers.clean("+") == nil)
        #expect(PhoneNumbers.clean(String(repeating: "1", count: 33)) == nil)
    }

    // Review Focus #4
    @Test func telLinksAreParsed() {
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:+15551234567")!) == "+15551234567")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:%2B1%20555%20123%204567")!) == "+15551234567")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel://555-0100")!) == "5550100")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:555-0100;ext=12")!) == "5550100")
        #expect(PhoneNumbers.fromTelURL(URL(string: "tel:")!) == nil)
        #expect(PhoneNumbers.fromTelURL(URL(string: "https://example.com")!) == nil)
    }

    @Test func presentation() {
        let both = CallInfo(callId: "k", name: "Mom", number: "+15551234567", phase: .ringing, since: nil)
        let numberOnly = CallInfo(callId: "k", name: nil, number: "+15551234567", phase: .ringing, since: nil)
        let nothing = CallInfo(callId: "k", name: nil, number: nil, phase: .ringing, since: nil)
        #expect(CallPresentation.title(both) == "Mom")
        #expect(CallPresentation.detail(both) == "+15551234567")
        #expect(CallPresentation.title(numberOnly) == "+15551234567")
        #expect(CallPresentation.detail(numberOnly) == nil)
        #expect(CallPresentation.title(nothing) == "Unknown caller")
        let start = Date(timeIntervalSince1970: 0)
        #expect(CallPresentation.duration(from: start, to: start.addingTimeInterval(65)) == "1:05")
        #expect(CallPresentation.duration(from: start, to: start.addingTimeInterval(3_725)) == "1:02:05")
        #expect(CallPresentation.errorNotice(.callGone, action: .decline) == "Couldn't decline — use your phone")
        #expect(CallPresentation.errorNotice(.notSupported, action: .hangUp) == "Couldn't hang up — use your phone")
        #expect(CallPresentation.errorNotice(.callGone, action: nil) == "Couldn't answer — use your phone")
        #expect(CallPresentation.confirmTitle(number: "+15551234567") == "Call +15551234567 on your phone?")
        #expect(CallPresentation.dialProblem(.notConnected) == "Your phone isn't connected")
        #expect(CallPresentation.dialProblem(.unsupported) == "Update MacDroid on your phone to use calls")
        #expect(CallPresentation.dialProblem(.invalidNumber) == "That doesn't look like a phone number")
        #expect(CallPresentation.dialProblem(.sent) == nil)
    }
}
```

Run `cd mac/MacDroidCore && swift build --build-tests`. Expected: unknown types.

- [ ] **Step 2: Implement**

`Calls/PhoneNumbers.swift`:

```swift
import Foundation

/// Phone numbers for `call.dial` (spec §3.1, §4): a leading `+`, digits, `*` and `#`; 1–32 characters.
public enum PhoneNumbers {
    public static func clean(_ raw: String) -> String? {
        var out = ""
        for ch in raw {
            if ch.isASCII, ch.isNumber || ch == "*" || ch == "#" { out.append(ch) }
            else if ch == "+", out.isEmpty { out.append(ch) }
        }
        guard out != "+", !out.isEmpty, out.count <= 32 else { return nil }
        return out
    }

    /// The number in a `tel:` link: `tel:+1…`, `tel://…`, percent-encoded, with `;params` dropped.
    public static func fromTelURL(_ url: URL) -> String? {
        guard url.scheme?.lowercased() == "tel" else { return nil }
        var rest = String(url.absoluteString.dropFirst("tel:".count))
        if rest.hasPrefix("//") { rest.removeFirst(2) }
        rest = String(rest.prefix { $0 != ";" && $0 != "?" })
        return clean(rest.removingPercentEncoding ?? rest)
    }
}
```

`Calls/CallCenter.swift`:

```swift
import Foundation

/// One call the phone told us about. `since` is when the Mac first saw it active (for the timer).
public struct CallInfo: Equatable, Sendable {
    public var callId: String
    public var name: String?
    public var number: String?
    public var phase: CallPhase
    public var since: Date?
    public init(callId: String, name: String?, number: String?, phase: CallPhase, since: Date?) {
        self.callId = callId
        self.name = name
        self.number = number
        self.phase = phase
        self.since = since
    }
}

public enum CallPanel: Equatable, Sendable {
    case none
    case ringing(CallInfo)
    case active(CallInfo)
    case notice(String)
}

public enum DialResult: Equatable, Sendable { case sent, invalidNumber, notConnected, unsupported }

public struct CallsState: Equatable, Sendable {
    /// Oldest first.
    public var calls: [CallInfo] = []
    public var connected = false
    public var phoneFeatures: Set<String> = []
    public var showIncoming = true
    public var notice: String?

    public var supported: Bool { connected && phoneFeatures.contains(Feature.calls) }
    /// The newest active call, for the popover line.
    public var activeCall: CallInfo? { calls.last { $0.phase == .active } }

    /// What the floating panel shows (spec §3.2): a notice first, then the newest ringing call, then the newest active one.
    public var panel: CallPanel {
        if let notice { return .notice(notice) }
        guard showIncoming else { return .none }
        if let ringing = calls.last(where: { $0.phase == .ringing }) { return .ringing(ringing) }
        if let active = activeCall { return .active(active) }
        return .none
    }

    public init() {}
    public static let empty = CallsState()
}

/// Calls on the Mac (Plan 10 spec §3.1): the phone's `call.state`, Answer / Decline / Hang up, dialing, and error notices.
public actor CallCenter {
    public nonisolated var states: AsyncStream<CallsState> { broadcast.stream() }
    public private(set) var state = CallsState.empty

    private let broadcast = Broadcast<CallsState>(replayLatest: true, initial: .empty)
    private let now: @Sendable () -> Date
    private let send: @Sendable (Message) async throws -> Void
    /// The last action asked for each call, to word an error.
    private var asked: [String: CallActionKind] = [:]
    private var tasks: [Task<Void, Never>] = []

    public init(now: @escaping @Sendable () -> Date = { Date() }, send: @escaping @Sendable (Message) async throws -> Void, showIncoming: Bool = true) {
        self.now = now
        self.send = send
        var initial = CallsState()
        initial.showIncoming = showIncoming
        state = initial
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        broadcast.yield(state)
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    func handle(_ message: Message) {
        switch message {
        case .callState(let body):
            update { s in
                let index = s.calls.firstIndex { $0.callId == body.callId }
                switch body.state {
                case .ended:
                    if let index { s.calls.remove(at: index) }
                case .ringing, .active:
                    let previous = index.map { s.calls[$0] }
                    let since = body.state == .active ? (previous?.since ?? now()) : nil
                    let info = CallInfo(callId: body.callId, name: body.name, number: body.number, phase: body.state, since: since)
                    if let index { s.calls[index] = info } else { s.calls.append(info) }
                }
            }
            if body.state == .ended { asked[body.callId] = nil }
        case .callError(let body):
            let action = body.callId.flatMap { asked[$0] }
            update { s in
                s.notice = CallPresentation.errorNotice(body.reason, action: action)
                if let id = body.callId { s.calls.removeAll { $0.callId == id } }
            }
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) {
        switch status {
        case .connected(let peer):
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
        case .disconnected:
            asked = [:]
            update {
                $0.connected = false
                $0.calls = []
                $0.notice = nil
            }
        }
    }

    public func answer(callId: String) async { await act(callId, .answer) }
    public func decline(callId: String) async { await act(callId, .decline) }
    public func hangUp(callId: String) async { await act(callId, .hangUp) }

    public func dial(_ raw: String) async -> DialResult {
        guard state.connected else { return .notConnected }
        guard state.supported else { return .unsupported }
        guard let number = PhoneNumbers.clean(raw) else { return .invalidNumber }
        do {
            try await send(.callDial(CallDial(number: number)))
            return .sent
        } catch {
            return .notConnected
        }
    }

    public func setShowIncoming(_ on: Bool) { update { $0.showIncoming = on } }
    public func clearNotice() { update { $0.notice = nil } }

    private func act(_ callId: String, _ action: CallActionKind) async {
        asked[callId] = action
        try? await send(.callAction(CallAction(callId: callId, action: action)))
    }

    private func update(_ change: (inout CallsState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        broadcast.yield(state)
    }
}
```

`Presentation/CallPresentation.swift`:

```swift
import Foundation

/// Words for the call panel, the popover line, notices and dialing (Plan 10 spec §3).
public enum CallPresentation {
    public static func title(_ call: CallInfo) -> String { call.name ?? call.number ?? "Unknown caller" }

    /// The number under the name, when both are known and differ.
    public static func detail(_ call: CallInfo) -> String? {
        guard let name = call.name, let number = call.number, name != number else { return nil }
        return number
    }

    public static func duration(from start: Date, to now: Date) -> String {
        let total = max(0, Int(now.timeIntervalSince(start)))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    public static func errorNotice(_ reason: CallErrorReason, action: CallActionKind?) -> String {
        if reason == .noPermission { return "Allow MacDroid to make calls on your phone (phone Settings → Calls)" }
        switch action {
        case .decline: return "Couldn't decline — use your phone"
        case .hangUp: return "Couldn't hang up — use your phone"
        case .answer, nil: return "Couldn't answer — use your phone"
        }
    }

    public static func confirmTitle(number: String) -> String { "Call \(number) on your phone?" }

    public static func dialProblem(_ result: DialResult) -> String? {
        switch result {
        case .sent: nil
        case .notConnected: "Your phone isn't connected"
        case .unsupported: "Update MacDroid on your phone to use calls"
        case .invalidNumber: "That doesn't look like a phone number"
        }
    }
}
```

In `handle(_:)`, `.callState` and `.callError` are `internal` like the other core actors, so tests call them through `@testable import`.

Run `swift test`. Expected: PASS.

- [ ] **Step 3: Handoff and commit**

CHANGELOG bullet: "Task 4: Mac core `CallCenter` (panel: notice → newest ringing → newest active; errors → notices; disconnect clears; dialing gated on `calls`), `PhoneNumbers` (clean, `tel:` links), `CallPresentation`." ARCHITECTURE: a `Calls` row in the Mac core table.

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac-core): call center, phone numbers and call texts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Android app — read call notifications, act, dial, settings

**Files:**
- Create: `android/app/src/main/java/dev/macdroid/app/platform/calls/CallReader.kt`, `CallBridge.kt`
- Modify: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt` (`AppGraph.callBridge`)
- Modify: `android/app/src/main/java/dev/macdroid/app/platform/notifications/MacDroidNotificationListener.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/main/SettingsScreen.kt`, `SettingsViewModel.kt`

**Interfaces:**
- Consumes: Task 3's `CallTracker`, `CallNotification`, `CallType`, `CallCommand`, `CallNumbers`, `CallLabels`; Task 1's results.
- Produces:
  - `CallReader.read(sbn): CallNotification?`, `CallReader.intent(notification, kind): PendingIntent?`
  - `CallBridge.posted(sbn): Boolean` (true for call notifications, which then skip mirroring), `removed(key)`, `listenerConnected(service)`, `listenerDisconnected()`
  - `SettingsUi.callsAllowed`

This is Android glue with no JVM unit tests; the logic is in Task 3. It's verified by the build, the cross-platform test (Task 7) and the owner checklist.

- [ ] **Step 1: Reader**

`platform/calls/CallReader.kt`:

```kotlin
package dev.macdroid.app.platform.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.macdroid.core.calls.CallLabels
import dev.macdroid.core.calls.CallNotification
import dev.macdroid.core.calls.CallNumbers
import dev.macdroid.core.calls.CallType
import dev.macdroid.core.protocol.CallActionKind

/** Reads a phone app's call notification (Plan 10 spec §2.1). Null for anything else. */
object CallReader {
    fun read(sbn: StatusBarNotification): CallNotification? {
        val n = sbn.notification
        val type = callType(n) ?: return null
        val person = person(n)
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val actions = CallActionKind.entries.filter { intent(n, it) != null }.toSet()
        return CallNotification(sbn.key, type, name, CallNumbers.fromUri(person?.uri), actions)
    }

    /** The notification's own intent for [kind]: the call-style extra, else a button with that label. */
    fun intent(n: Notification, kind: CallActionKind): PendingIntent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val key = when (kind) {
                CallActionKind.Answer -> Notification.EXTRA_ANSWER_INTENT
                CallActionKind.Decline -> Notification.EXTRA_DECLINE_INTENT
                CallActionKind.HangUp -> Notification.EXTRA_HANG_UP_INTENT
            }
            pendingIntent(n, key)?.let { return it }
        }
        return n.actions?.firstOrNull { CallLabels.kindOf(it.title?.toString()) == kind }?.actionIntent
    }

    private fun callType(n: Notification): CallType? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)) {
                Notification.CallStyle.CALL_TYPE_INCOMING -> return CallType.Incoming
                Notification.CallStyle.CALL_TYPE_ONGOING -> return CallType.Ongoing
                Notification.CallStyle.CALL_TYPE_SCREENING -> return CallType.Screening
            }
        }
        if (n.category != Notification.CATEGORY_CALL) return null
        // Older call notifications: an Answer button means it's ringing.
        return if (n.actions?.any { CallLabels.kindOf(it.title?.toString()) == CallActionKind.Answer } == true) CallType.Incoming else CallType.Ongoing
    }

    @Suppress("DEPRECATION")
    private fun person(n: Notification): Person? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> n.extras.getParcelable(Notification.EXTRA_CALL_PERSON)
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun pendingIntent(n: Notification, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.extras.getParcelable(key, PendingIntent::class.java)
        else n.extras.getParcelable(key)
}
```

- [ ] **Step 2: Bridge**

`platform/calls/CallBridge.kt`:

```kotlin
package dev.macdroid.app.platform.calls

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import dev.macdroid.app.platform.Permissions
import dev.macdroid.core.calls.CallCommand
import dev.macdroid.core.calls.CallTracker
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.macSupports
import dev.macdroid.core.protocol.CallErrorReason
import dev.macdroid.core.protocol.Features
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Calls on the Mac, phone side (Plan 10 spec §2): call notifications → the Mac; the Mac's requests → the phone app. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallBridge(private val context: Context, private val connection: ConnectionManager, private val scope: CoroutineScope) {
    val tracker = CallTracker(connection::send)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var listener: NotificationListenerService? = null

    fun start() {
        scope.launch(worker) {
            connection.state.map { it.macSupports(Features.CALLS) }.distinctUntilChanged().collect { ready ->
                if (ready) tracker.connected() else tracker.disconnected()
            }
        }
        scope.launch(worker) {
            connection.incoming.collect { message -> tracker.handle(message)?.let(::execute) }
        }
    }

    fun listenerConnected(service: NotificationListenerService) {
        listener = service
        scope.launch(worker) {
            runCatching { service.activeNotifications }.getOrNull()?.forEach { sbn -> CallReader.read(sbn)?.let(tracker::posted) }
        }
    }

    fun listenerDisconnected() { listener = null }

    /** True when [sbn] is a call notification: it goes to the Mac as a call, not as a mirrored notification. */
    fun posted(sbn: StatusBarNotification): Boolean {
        val call = CallReader.read(sbn) ?: return false
        scope.launch(worker) { tracker.posted(call) }
        return true
    }

    fun removed(key: String) {
        scope.launch(worker) { tracker.removed(key) }
    }

    private fun execute(command: CallCommand) {
        when (command) {
            is CallCommand.Fire -> {
                val sbn = runCatching { listener?.activeNotifications }.getOrNull()?.firstOrNull { it.key == command.key }
                    ?: return tracker.report(CallErrorReason.CallGone, command.key)
                val intent = CallReader.intent(sbn.notification, command.action)
                    ?: return tracker.report(CallErrorReason.NotSupported, command.key)
                try {
                    intent.send()
                } catch (e: PendingIntent.CanceledException) {
                    tracker.report(CallErrorReason.CallGone, command.key)
                }
            }
            is CallCommand.Dial -> {
                if (!Permissions.granted(context, Manifest.permission.CALL_PHONE)) return tracker.report(CallErrorReason.NoPermission, null)
                try {
                    context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", command.number, null), null)
                } catch (e: SecurityException) {
                    tracker.report(CallErrorReason.NoPermission, null)
                }
            }
        }
    }
}
```

**If Task 1 ruled that `placeCall` fails in the background**, add the fallback from Task 1 Step 4 to the `Dial` branch here and ledger it.

- [ ] **Step 3: Wiring**

`MacDroidApp.kt` (`AppGraph`), after `notificationBridge`:

```kotlin
    val callBridge = CallBridge(context, connectionManager, appScope).also { it.start() }
```

(import `dev.macdroid.app.platform.calls.CallBridge`).

`MacDroidNotificationListener`:

```kotlin
    override fun onListenerConnected() {
        bridge.listenerConnected(this)
        graph.callBridge.listenerConnected(this)
        graph.mediaWatcher.refresh()
    }

    override fun onListenerDisconnected() {
        bridge.listenerDisconnected()
        graph.callBridge.listenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (graph.callBridge.posted(sbn)) return // calls go to the Mac's call panel, not its notification list (Plan 10)
        val ranking = Ranking().takeIf { rankingMap.getRanking(sbn.key, it) }
        bridge.posted(sbn, ranking)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        graph.callBridge.removed(sbn.key)
        bridge.removed(sbn.key)
    }
```

`AndroidManifest.xml`, with the other permissions:

```xml
    <uses-permission android:name="android.permission.CALL_PHONE" />
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
```

- [ ] **Step 4: Settings → Calls**

`SettingsViewModel`:
- `SettingsUi` gets `val callsAllowed: Boolean = false`.
- `read()` sets `callsAllowed = Permissions.granted(app, android.Manifest.permission.CALL_PHONE)`.

`SettingsScreen`:
- Next to the `photosAccess` launcher:

```kotlin
    val callsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Android shows nothing once the permission was denied twice: then send the user to the app's settings instead.
        val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.CALL_PHONE)
        if (!granted && !canAskAgain) context.openAppSettings()
        viewModel.refresh()
    }
```

- A section after "Remote control" (before "Setup"):

```kotlin
        item { SectionHeader("Calls") }
        item {
            ListItem(
                headlineContent = { Text("Make calls from your Mac") },
                supportingContent = { Text(if (ui.callsAllowed) "Allowed" else "Off: tap to allow") },
                leadingContent = { Icon(Icons.Rounded.Call, contentDescription = null) },
                modifier = Modifier.clickable { if (!ui.callsAllowed) callsPermission.launch(android.Manifest.permission.CALL_PHONE) },
            )
        }
```

(import `androidx.compose.material.icons.rounded.Call`).

Run `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug --warning-mode all`. Expected: BUILD SUCCESSFUL, no `w:` lines.

- [ ] **Step 5: Handoff and commit**

CHANGELOG bullet: "Task 5: Android call notifications → `CallTracker` (kept out of notification mirroring); Answer/Decline/Hang up fire the phone app's own intents; `call.dial` via `TelecomManager.placeCall` (`CALL_PHONE`); Settings → Calls." Other docs:
- ARCHITECTURE: `platform/calls/`.
- DEV-GUIDE: "Calls: grant 'Make calls from your Mac' on the phone; call audio stays on the phone".

```bash
git add android docs/handoff
git commit -m "feat(android): calls to and from the Mac

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Mac app — call panel, number pad, phone links, popover, settings

**Files:**
- Create: `mac/MacDroid/Calls/CallPanelController.swift`, `mac/MacDroid/Views/Calls/CallPanelView.swift`, `mac/MacDroid/Views/Calls/DialPadView.swift`
- Modify:
  - `mac/project.yml` and `mac/MacDroid/Info.plist` (the `tel` scheme)
  - `mac/MacDroid/AppDelegate.swift`, `MacDroidApp.swift`, `AppModel.swift`
  - `mac/MacDroid/Views/Phone/PairedDeviceView.swift`, `Views/MenuBarPopover.swift`, `Views/Settings/GeneralSettingsView.swift`

**Interfaces:**
- Consumes: Task 4's `CallCenter`, `CallsState`, `CallPanel`, `CallInfo`, `DialResult`, `PhoneNumbers`, `CallPresentation`.
- Produces:
  - `AppModel.calls: CallsState`
  - `answerCall(_:)`, `declineCall(_:)`, `hangUpCall(_:)`
  - `dial(_:) async -> String?`
  - `openPhoneLinks(_:)`
  - `showIncomingCalls`
  - `usePhoneLinks() async -> String`

There are no unit tests here; the logic is in Task 4. It's verified by the build and the owner checklist.

- [ ] **Step 1: The `tel` scheme and URL delivery**

`project.yml`, under the app's `info.properties`:

```yaml
        CFBundleURLTypes:
          - CFBundleURLName: dev.macdroid.mac.tel
            CFBundleURLSchemes: [tel]
```

Run `cd mac && xcodegen generate`. It rewrites `MacDroid/Info.plist`, and that change is committed.

`AppDelegate.swift`:

```swift
    /// Set by the app at startup: `tel:` links opened with MacDroid (Plan 10).
    static var onOpenURLs: (@MainActor ([URL]) -> Void)?

    func application(_ application: NSApplication, open urls: [URL]) {
        Self.onOpenURLs?(urls)
    }
```

`MacDroidApp.swift`, next to the other `AppDelegate.on…` assignments:

```swift
        AppDelegate.onOpenURLs = { urls in model.openPhoneLinks(urls) }
```

- [ ] **Step 2: `AppModel`**

Properties, next to `extras`:

```swift
    private(set) var calls: CallsState = .empty
    @ObservationIgnored private let callCenter: CallCenter
    @ObservationIgnored private let callPanel = CallPanelController()
    @ObservationIgnored private var noticeTask: Task<Void, Never>?
    @ObservationIgnored private var ringingSeen: Set<String> = []
    var showIncomingCalls = UserDefaults.standard.object(forKey: "calls.showIncoming") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(showIncomingCalls, forKey: "calls.showIncoming")
            let on = showIncomingCalls
            Task { await callCenter.setShowIncoming(on) }
        }
    }
```

In `init`, after `phoneExtras = …`:

```swift
        callCenter = CallCenter(send: { message in try await hub.send(message) },
                                showIncoming: UserDefaults.standard.object(forKey: "calls.showIncoming") as? Bool ?? true)
```

In `start()`, after the `phoneExtras` tasks:

```swift
        tasks.append(Task { [callCenter, hub] in await callCenter.attach(to: hub) })
        tasks.append(Task { [weak self, callCenter] in
            for await state in callCenter.states { self?.callsChanged(state) }
        })
```

Methods (a new `// MARK: Calls (Plan 10)` section):

```swift
    func answerCall(_ id: String) { Task { await callCenter.answer(callId: id) } }
    func declineCall(_ id: String) { Task { await callCenter.decline(callId: id) } }
    func hangUpCall(_ id: String) { Task { await callCenter.hangUp(callId: id) } }

    /// The number pad's Call: nil when sent, else what went wrong.
    func dial(_ raw: String) async -> String? { CallPresentation.dialProblem(await callCenter.dial(raw)) }

    /// `tel:` links (spec §3.3): confirm, then dial on the phone.
    func openPhoneLinks(_ urls: [URL]) {
        guard let number = urls.lazy.compactMap(PhoneNumbers.fromTelURL).first else { return }
        NSApp.activate()
        let alert = NSAlert()
        guard calls.supported else {
            alert.messageText = CallPresentation.dialProblem(calls.connected ? .unsupported : .notConnected) ?? ""
            alert.runModal()
            return
        }
        alert.messageText = CallPresentation.confirmTitle(number: number)
        alert.addButton(withTitle: "Call")
        alert.addButton(withTitle: "Cancel")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        Task { _ = await callCenter.dial(number) }
    }

    /// Settings → Calls: make MacDroid the handler for `tel:` links (macOS asks the user to confirm).
    func usePhoneLinks() async -> String {
        do {
            try await NSWorkspace.shared.setDefaultApplication(at: Bundle.main.bundleURL, toOpenURLsWithScheme: "tel")
            return "Phone links now open in MacDroid."
        } catch {
            return "macOS didn't change it: \(error.localizedDescription)"
        }
    }

    private func callsChanged(_ state: CallsState) {
        calls = state
        switch state.panel {
        case .none:
            callPanel.hide()
        case .ringing(let call):
            if !ringingSeen.contains(call.callId) {
                ringingSeen.insert(call.callId)
                NSSound(named: "Glass")?.play()
            }
            callPanel.show(CallPanelView(model: self))
        case .active:
            callPanel.show(CallPanelView(model: self))
        case .notice:
            callPanel.show(CallPanelView(model: self))
            noticeTask?.cancel()
            noticeTask = Task { [callCenter] in
                try? await Task.sleep(for: .seconds(3))
                guard !Task.isCancelled else { return }
                await callCenter.clearNotice()
            }
        }
        ringingSeen.formIntersection(state.calls.map(\.callId))
    }
```

- [ ] **Step 3: The panel**

`Calls/CallPanelController.swift`:

```swift
import AppKit
import SwiftUI

/// The floating call panel (spec §3.2): top right, above other windows, never takes focus from the app you're in.
@MainActor
final class CallPanelController {
    private var panel: NSPanel?
    private static let size = NSSize(width: 320, height: 104)

    func show(_ content: some View) {
        let panel = panel ?? makePanel()
        panel.contentView = NSHostingView(rootView: content)
        if let screen = NSScreen.main?.visibleFrame {
            panel.setFrameOrigin(NSPoint(x: screen.maxX - Self.size.width - 16, y: screen.maxY - Self.size.height - 16))
        }
        panel.orderFrontRegardless()
        self.panel = panel
    }

    func hide() { panel?.orderOut(nil) }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: Self.size),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        return panel
    }
}
```

`Views/Calls/CallPanelView.swift`:

```swift
import MacDroidCore
import SwiftUI

/// What the call panel shows (spec §3.2): ringing, on a call, or a short notice.
struct CallPanelView: View {
    let model: AppModel

    var body: some View {
        Group {
            switch model.calls.panel {
            case .ringing(let call):
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").font(.title2).foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Incoming call").font(.caption).foregroundStyle(.secondary)
                        Text(CallPresentation.title(call)).font(.headline).lineLimit(1)
                        if let detail = CallPresentation.detail(call) {
                            Text(detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 8)
                    Button("Decline") { model.declineCall(call.callId) }.tint(.red)
                    Button("Answer") { model.answerCall(call.callId) }.tint(.green)
                }
                .buttonStyle(.borderedProminent)
            case .active(let call):
                HStack(spacing: 12) {
                    Image(systemName: "phone.fill").font(.title2).foregroundStyle(.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("On a call with \(CallPresentation.title(call))").font(.headline).lineLimit(1)
                        if let since = call.since {
                            TimelineView(.periodic(from: .now, by: 1)) { context in
                                Text(CallPresentation.duration(from: since, to: context.date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Spacer(minLength: 8)
                    Button("Hang up") { model.hangUpCall(call.callId) }.buttonStyle(.borderedProminent).tint(.red)
                }
            case .notice(let text):
                Label(text, systemImage: "exclamationmark.triangle.fill").font(.callout)
            case .none:
                EmptyView()
            }
        }
        .padding(14)
        .frame(width: 320, height: 104, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}
```

- [ ] **Step 4: Number pad, popover and settings**

`Views/Calls/DialPadView.swift`:

```swift
import MacDroidCore
import SwiftUI

/// The Phone page's number pad (spec §3.3).
struct DialPadView: View {
    let model: AppModel
    @State private var number = ""
    @State private var problem: String?
    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("Phone number", text: $number).textFieldStyle(.roundedBorder).font(.title3.monospacedDigit())
                Button { number = String(number.dropLast()) } label: { Image(systemName: "delete.left") }
                    .disabled(number.isEmpty).help("Delete")
                Button { number = PhoneNumbers.clean(NSPasteboard.general.string(forType: .string) ?? "") ?? number } label: {
                    Image(systemName: "doc.on.clipboard")
                }
                .help("Paste a number")
            }
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(56)), count: 3), spacing: 6) {
                ForEach(keys, id: \.self) { key in
                    Button(key) { number += key }.frame(width: 56)
                }
            }
            HStack {
                Button("+") { if number.isEmpty { number = "+" } }.disabled(!number.isEmpty).help("International prefix")
                Spacer()
                Button {
                    Task {
                        problem = await model.dial(number)
                        if problem == nil { number = "" }
                    }
                } label: {
                    Label("Call", systemImage: "phone.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .disabled(number.isEmpty)
            }
            if let problem { Text(problem).font(.callout).foregroundStyle(.secondary) }
        }
    }
}
```

`PairedDeviceView`, a section before the buttons section:

```swift
            if model.calls.supported {
                Section("Call") { DialPadView(model: model) }
            }
```

`MenuBarPopover`, after `tiles`:

```swift
                if let call = model.calls.activeCall { activeCallRow(call) }
```

with:

```swift
    private func activeCallRow(_ call: CallInfo) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "phone.fill").foregroundStyle(.green)
            Text("On a call with \(CallPresentation.title(call))").font(.callout).lineLimit(1)
            Spacer(minLength: 0)
            if let since = call.since {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text(CallPresentation.duration(from: since, to: context.date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            Button("Hang up") { model.hangUpCall(call.callId) }.buttonStyle(.bordered).tint(.red).controlSize(.small)
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.1)))
    }
```

`GeneralSettingsView`, a section after "Mirroring":

```swift
            Section {
                Toggle("Show incoming calls", isOn: $bindableModel.showIncomingCalls)
                HStack {
                    Button("Use MacDroid for phone links") { Task { phoneLinksMessage = await model.usePhoneLinks() } }
                    if let phoneLinksMessage { Text(phoneLinksMessage).foregroundStyle(.secondary) }
                }
            } header: {
                Text("Calls")
            } footer: {
                Text("Call audio stays on your phone.").foregroundStyle(.secondary)
            }
```

with `@State private var phoneLinksMessage: String?` next to the view's other `@State` properties.

Run `swift test` and the Mac app build. Expected: tests PASS, and the build succeeds with no warnings. If Swift 6 isolation warnings appear on `NSWorkspace.setDefaultApplication` or `NSSound`, fix them at the call site; don't silence them.

- [ ] **Step 5: Handoff and commit**

CHANGELOG bullet: "Task 6: Mac call panel (non-activating, top right, sound on ring, timer, notices for 3 s), number pad on the Phone page, `tel:` links with confirmation, active call in the popover, Settings → Calls." Other docs:
- ARCHITECTURE: the Mac app list.
- DEV-GUIDE: "Phone links: Settings → Calls → Use MacDroid for phone links".

```bash
git add mac docs/handoff
git commit -m "feat(mac): call panel, number pad and phone links

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Cross-platform — a call goes both ways

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`
- Modify: `scripts/cross-platform-test.sh`

**Interfaces:**
- Consumes:
  - Task 3: `CallTracker`, `CallNotification`, `CallType`, `CallCommand`
  - Task 4: `CallCenter`

- [ ] **Step 1: Failing script checks** (before the `cmp` lines)

```bash
grep -q 'CALL ringing name=Mom number=+15551234567' "$LOG" || { echo "the Mac never saw the call ringing"; cat "$LOG"; exit 1; }
grep -q 'CALL dialled' "$LOG" || { echo "the Mac never dialled"; cat "$LOG"; exit 1; }
grep -q 'CALL ended' "$LOG" || { echo "the Mac never saw the call end"; cat "$LOG"; exit 1; }
```

Run `./scripts/cross-platform-test.sh`. Expected: it fails with "the Mac never saw the call ringing".

- [ ] **Step 2: Dev server**

After the `extras` block:

```swift
let calls = CallCenter(send: { try await hub.send($0) })
await calls.attach(to: hub)

let callPrinter = Task {
    var answered = false
    var sawCall = false
    for await state in calls.states {
        if case .ringing(let call) = state.panel, !answered {
            answered = true
            sawCall = true
            print("CALL ringing name=\(call.name ?? "") number=\(call.number ?? "")")
            await calls.answer(callId: call.callId)
            if await calls.dial("+1 (555) 010-0000") == .sent { print("CALL dialled") }
        }
        if sawCall, state.calls.isEmpty {
            sawCall = false
            print("CALL ended")
        }
    }
}
```

At the end, next to the other cancels, add `callPrinter.cancel()`.

- [ ] **Step 3: Kotlin side**

In the main test, after `exchangeSmallWins(manager, received)`, add `exchangeCalls(manager, received)`, with:

```kotlin
    /** The phone rings; the Mac answers and dials a number; the call ends (Plan 10). */
    private suspend fun exchangeCalls(manager: ConnectionManager, received: List<Message>) {
        val tracker = CallTracker(manager::send)
        tracker.connected()
        val all = setOf(CallActionKind.Answer, CallActionKind.Decline, CallActionKind.HangUp)
        tracker.posted(CallNotification("call-1", CallType.Incoming, "Mom", "+15551234567", all))
        withTimeout(10_000) { while (received.none { it is Message.CallAction }) delay(50) }
        assertEquals(CallCommand.Fire("call-1", CallActionKind.Answer), tracker.handle(received.filterIsInstance<Message.CallAction>().first()))
        withTimeout(10_000) { while (received.none { it is Message.CallDial }) delay(50) }
        assertEquals(CallCommand.Dial("+15550100000"), tracker.handle(received.filterIsInstance<Message.CallDial>().first()))
        tracker.removed("call-1")
        delay(300)
    }
```

Imports:
- `dev.macdroid.core.calls.CallCommand`, `CallNotification`, `CallTracker`, `CallType`
- `dev.macdroid.core.protocol.CallActionKind`

- [ ] **Step 4: Run, handoff, commit**

Run `./scripts/cross-platform-test.sh` twice. Expected: "cross-platform test passed" both times. Then run all gates.

CHANGELOG bullet: "Task 7: cross-platform test — the phone's ringing call reaches the Mac, the Mac answers and dials, the end arrives." DEV-GUIDE: add "a call (ring, answer, dial, end)" to the cross-platform test's description.

```bash
git add mac android scripts docs/handoff
git commit -m "test: cross-platform calls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Remove the probe, final gates, handoff, owner checklist

**Files:**
- Delete: `android/app/src/debug/` (the Task 1 probe; nothing else lives there)
- Modify: `docs/handoff/STATUS.md`, `ARCHITECTURE.md`, `DEV-GUIDE.md`, `ROADMAP.md`, `DECISIONS.md`, `README.md`, `CHANGELOG.md`
- Modify: `docs/superpowers/plans/2026-10-15-macdroid-calls-followups.md`

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

- **DECISIONS.md:** rows for:
  - calls through the phone app's call notification (rejected: the telephony APIs plus the call-screening role; dialer button pressing)
  - no call audio on the Mac (not possible for an Android app)
  - no auto-speaker (owner's choice A)
  - links confirmed before dialing
  - calls kept in memory only
- **ARCHITECTURE.md / DEV-GUIDE.md:** check that Tasks 3–7 left them accurate.
- **STATUS / ROADMAP / README:** Plan 10 built on `feat/calls`, pending the owner's check.
- **CHANGELOG:** close the Plan 10 entry with the gate results.

- [ ] **Step 4: Owner checklist** (append to the follow-ups file and the final message)

1. **Phone:** Settings → Calls → **"Make calls from your Mac"** → allow. The row reads "Allowed".
2. **Incoming:** have someone call. Within about a second the Mac shows the panel at the top right ("Incoming call", the name, and the number if known) with one sound. The app you're typing in keeps focus.
3. **Answer from the Mac:** the call connects on the phone. The panel shows "On a call with …" and a running timer, and the popover shows the same line.
4. **Hang up from the Mac** (panel or popover): the call ends on the phone, and the panel closes.
5. **Decline from the Mac:** the caller is declined, and the panel closes.
6. **Answer on the phone** while the panel shows: it switches to "On a call with …".
7. **Miss a call:** the panel closes when it stops ringing; the phone app's "Missed call" notification appears in the Mac's notifications.
8. **Number pad:** Phone page → type or paste a number → **Call**. The phone starts the call, and the Mac shows "On a call with …".
9. **Phone link:** Settings → Calls → **Use MacDroid for phone links** (confirm macOS's prompt). Then click a phone number link in Safari → "Call … on your phone?" → **Call**.
10. **No permission:** turn off "Make calls from your Mac" in the phone's App settings → dial from the Mac → the notice "Allow MacDroid to make calls on your phone (phone Settings → Calls)".
11. **Show incoming calls** off in Mac Settings: no panel or sound. Turn it back on.
12. **Wi-Fi blip mid-call:** turn the phone's Wi-Fi off and on during a call. The Mac shows "On a call with …" again once reconnected.

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src docs
git commit -m "docs(handoff): calls on the Mac (Plan 10) built on feat/calls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Don't merge. The owner checks first.
