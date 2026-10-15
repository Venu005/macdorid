# MacDroid — Automatic Phone → Mac Clipboard (Plan 9)

> **Dropped 2026-10-15 (owner).** The on-device probe showed Android shows its clipboard-access message and the screen refreshes twice on every automatic read, and a one-tap floating "Send to Mac" button didn't look good either. Phone → Mac stays manual (Quick Settings tile, notification button, Share). Kept for the record; see DECISIONS.md.

Builds on:
- clipboard sync (Plan 4): `clipboard.set`, `ClipboardHub`, `ClipboardBridge`, the invisible `ClipboardSendActivity`, the Mac's `ClipboardSync` and its echo guard
- the "MacDroid control" accessibility service (Plan 8), which already receives System UI events

## 1. Goal and scope

Copying text on the phone puts it on the Mac's clipboard by itself. The owner shouldn't have to open MacDroid, use the tile or go through Share.

Success: copy on the phone, then ⌘V on the Mac within about a second. Nothing on the phone flickers, and the keyboard and text selection stay as they were.

Constraints (owner, 2026-10-15):
- **The app runs on a normal phone.** No developer options, wireless debugging, adb-granted permissions (such as `READ_LOGS`) or Shizuku.
- Personal build first. The store build keeps automatic sending off and hides its setting (`BuildConfig.STORE_BUILD`), as for auto-Start.

In scope: text and links (owner's choice A).

Out of scope:
- Images. Copied images still go through the tile, the notification button or Share.
- Reading other apps' screens or events. The service keeps listening only to System UI.
- Changes to Mac → phone sync, which is already automatic.

## 2. Why this is possible now

Android 10+ lets an app read the clipboard only while it has focus, which is why Plan 4 made phone → Mac user-triggered (DECISIONS: "the Accessibility-service hack (Play policy risk)" was rejected). Plan 8 has since added an accessibility service for remote control, so two things are now available without new access:
- **A signal.** On Android 13+, System UI shows a "copied" preview after every copy, and the service already receives System UI events.
- **Focus.** A system-bound accessibility service may start an activity from the background, so it can bring the invisible `ClipboardSendActivity` forward for a moment, and that activity may read the clipboard.

The on-device probe (§7.1) confirms both on the owner's phone before any product code.

## 3. Phone

### 3.1 Flow
1. **Detect.** `MacDroidControlService.onAccessibilityEvent` already sees System UI events. A core `CopyDetector` reports a copy only when the clipboard preview *appears*: the window or view id that marks it comes from the probe (Q1). Content changes while it stays on screen don't count, and repeats within 1 s are ignored.
2. **Gate.** The service acts only when:
   - the new setting is on (§5)
   - the phone is paired
   - the echo window (§3.2) is not open: `ClipboardBridge` records when it last set the phone's clipboard, and the service reads that

   The service also checks the connection itself, without opening anything:
   - **Not connected:** it shows the once-per-disconnect toast (§3.3) and stops.
   - **The Mac doesn't list `clipboard`:** it shows the once-per-connection toast and stops.

   So the invisible activity opens only when a send can actually happen.
3. **Read.** The service starts `ClipboardSendActivity` with an `auto` extra and `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION`. The activity reads `primaryClip` once it has window focus, as it does today, hands it to `ClipboardBridge.sendAuto(clip)` and finishes at once.
4. **Send.** `sendAuto` applies the rules in §3.2. If they pass, it sends `clipboard.set` with `auto: true` through `ClipboardHub`, which records the item in Recent as usual.

The tile, the notification button, "Send now" and Share are unchanged: they still always send, with their toasts (Plan 4 rule).

### 3.2 Rules (core `AutoClipboardPolicy`, tested)
- **Text only.** Anything without text (an image or URI clip) is skipped silently.
- **Never sensitive.** A clip whose description has `ClipDescription.EXTRA_IS_SENSITIVE` set is skipped silently. Password managers set this on Android 13+. The tile still sends it if the owner asks.
- **No echo.**
  - While MacDroid sets the phone's clipboard (an item from the Mac), the service ignores copy previews for 2 s, so the invisible activity never opens for it.
  - Text identical to the last item received from the Mac is never sent back.
- **No duplicates.** Text identical to the last automatic send is skipped.
- **Empty or unreadable** clipboard: skipped silently.
- **Size:** the existing 1 MiB frame limit (`ClipboardHub.fitsInAFrame`).

### 3.3 Feedback (owner's choice C)
| Outcome | Feedback |
|---|---|
| Sent | Silent; the item shows in Recent on both sides |
| Mac not connected | Toast "Mac not connected — copy not sent", once; silent until the phone has connected and lost the connection again |
| Mac too old (no `clipboard`) | Toast "Your Mac's MacDroid can't receive the clipboard", at most once per connection |
| Too large | Toast "Too large to send to your Mac" |
| Skipped by a rule | Silent |

## 4. Protocol and Mac

### 4.1 `clipboard.set`
One optional field: `auto` (boolean). It is sent only by the phone, and only for automatic sends. When it is absent, it means `false`.
- New fixture `protocol/fixtures/clipboard.set.auto.json`, decoded by both test suites; the existing fixture (no `auto`) must still decode with `auto == false`.
- Both codecs and PROTOCOL.md are updated.
- No new `hello` feature. An older Mac ignores the unknown key and shows its usual notification.

### 4.2 Mac
- `ClipboardSync` applies the item and records it in history exactly as now. Its `receivedFromPhone` event carries `auto`.
- `AppModel` posts the "Clipboard from <phone>" notification only when `auto` is false.
- The Mac's "receive from phone" switch still blocks everything, automatic or not.
- The existing guard that stops the Mac re-sending a pasteboard change it made itself prevents bounce-back.

## 5. Settings (phone)
Settings → Clipboard gets **"Send copies to your Mac automatically"**:
- On by default in personal builds.
- Hidden and off when `STORE_BUILD`.
- When MacDroid control is off, the row is disabled and reads "Needs MacDroid control (Settings → Remote control)".

Stored in `ClipboardPrefs`.

## 6. Errors
- **No preview** (an Android or Nothing OS build that doesn't show one): nothing is sent automatically. The tile and Share still work.
- **Activity start refused:** nothing is sent; no retry, no toast. The owner checklist covers whether this happens.
- **Connection lost mid-send:** reported as "not connected", subject to the once-per-disconnect rule.

## 7. Testing

### 7.1 On-device probe first (Plan 9, Task 1)
This runs with the owner, using a debug-only, log-only build of the service (debug source set; removed after).
- **Q1:** Copy text in Chrome, Messages and Notes. Does the preview appear, and which window or view id identifies it? Also set the clipboard from the Mac: does a preview appear for that too?
- **Q2:** From another app, can the service start the invisible activity, and does it read the clipboard?
- **Q3:** Is there any disruption? Does the keyboard close, the selection disappear, the preview vanish early, or the screen flicker?
- **Q4:** For a password copied from the owner's password manager, is `EXTRA_IS_SENSITIVE` set?

**Stop rules:**
- If Q1 or Q2 fails, stop and come back to the owner. Don't switch to watching other apps' "Copy" taps without asking.
- If Q3 shows a real disruption, bring the options to the owner before building.

### 7.2 Automated
- **Android core:**
  - `CopyDetector`: appear-only, the 1 s debounce, and the 2 s echo window.
  - `AutoClipboardPolicy`: non-text, sensitive, echo of the last Mac item, duplicate, empty, too large, and the toast-once-per-disconnect and once-per-connection rules.
- **Protocol:** the new fixture, and the old fixture with `auto == false`, in Swift and Kotlin.
- **Mac core:** `ClipboardSync` passes `auto` through, and the "receive from phone" switch still blocks an automatic item.
- **Cross-platform:** the test phone sends `clipboard.set` with `auto: true`. The dev server applies it and logs `CLIPBOARD applied text=… auto=true`.

### 7.3 Owner checklist
Written in the plan's last task. It covers:
- copying in several apps, then ⌘V on the Mac
- that the keyboard and selection stay put
- that a password from the password manager isn't sent
- that a Mac → phone item doesn't come back
- the not-connected toast appearing once
- the setting turning it off

## 8. Decisions
- **Detect with System UI's copy preview**, not by watching every app for "Copy" taps. Rejected alternatives: watching every app (broader access, misses non-button copies); becoming the keyboard (replaces the owner's keyboard); `READ_LOGS` via adb, as KDE Connect does (needs debugging, against the owner's constraint).
- **Text only**, not images (owner's choice A). Images are heavier and rarer, and can be added later.
- **Silent on success, toast on failure, with "not connected" once per disconnect** (owner's choice C). Rejected: a toast on every copy; fully silent.
- **`auto` flag on `clipboard.set`**, not a Mac setting, to silence the Mac notification for automatic sends only.

## 9. Plan
One plan (Plan 9), on a new branch `feat/auto-clipboard`:
1. The probe.
2. Protocol.
3. Android core rules.
4. Mac core and app.
5. Android service, activity and setting.
6. Cross-platform test.
7. Handoff and owner checklist.
