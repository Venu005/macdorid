# MacDroid — Calls on the Mac (Plan 10)

Builds on:
- the connection core (mutual TLS 1.3, pinned certificates, the control WebSocket, `hello` features)
- the notification listener and `NotificationBridge` (Plan 3), which already see every notification and deliberately skip ongoing ones
- the Mac's Phone page, menu-bar popover and Settings

## 1. Goal and scope

See and handle the phone's calls from the Mac, and start calls from the Mac:
- **Incoming:**
  - a call panel on the Mac with the caller's name or number, **Answer** and **Decline**
  - **Hang up** during a call
- **Outgoing:**
  - a number pad on the Phone page
  - `tel:` links on the Mac (Safari, Chrome, Contacts) dial on the phone, after a confirmation

Success: when the phone rings, the Mac shows who it is within about a second, and Answer, Decline and Hang up from the Mac work. Typing or clicking a number on the Mac starts the call on the phone.

Constraints:
- **Call audio stays on the phone.** Android doesn't let third-party apps capture or inject call audio, and a Mac can't be a Bluetooth headset for an Android phone. Answering from the Mac picks up on the phone, and the owner talks on the phone or on earbuds connected to it (owner's choice A; no automatic speakerphone).
- **The app runs on a normal phone** (owner, 2026-10-15): no developer options, debugging, adb grants or Shizuku. The only new permission is a normal runtime one.

Out of scope:
- call audio on the Mac
- automatic speakerphone
- SMS (owner: not needed)
- call history on the Mac
- "Call with phone" in the right-click menu (later, if wanted)

## 2. Phone

### 2.1 Incoming and ongoing calls
- **Source.** The notification listener already receives every notification. A notification is a call notification when:
  - it uses Android's call style (`Notification.EXTRA_CALL_TYPE` is incoming, ongoing or screening), or
  - its category is `Notification.CATEGORY_CALL`.

  Call notifications go to a new core `CallTracker`, and stay excluded from normal notification mirroring.
- **Read from each call notification:**
  - `callId`: the notification key
  - `name`: the caller as the phone app shows it (`EXTRA_CALL_PERSON` name, else the title)
  - `number`: from the person's `tel:` URI, if present
  - `state`: incoming or screening → `ringing`; ongoing → `active`
  - actions: `EXTRA_ANSWER_INTENT`, `EXTRA_DECLINE_INTENT`, `EXTRA_HANG_UP_INTENT`; for older notifications, buttons whose labels match Answer / Decline / Hang up
- **To the Mac:**
  - `call.state` when a call appears, changes state, or its notification is removed (`ended`)
  - Only changes are sent; repeated notification updates with the same state, name and number are not.
- **From the Mac:**
  - `call.action` fires the matching action's `PendingIntent`.
  - If the call isn't known: `call.error` `callGone`.
  - If the action isn't on the notification: `call.error` `notSupported`.
- **Fallback.** Only if the probe (§6.1) shows the phone app's notification lacks Answer or Hang up actions, the spec gains the `ANSWER_PHONE_CALLS` permission with `TelecomManager.acceptRingingCall` / `endCall`. Otherwise it isn't built.

### 2.2 Dialing
- `call.dial` → `TelecomManager.placeCall(Uri.fromParts("tel", number, null), null)`. This needs `CALL_PHONE`.
- The phone app takes over. With two SIMs and no default, the phone shows its SIM picker.
- The call's own notification then drives `call.state` (`active`).
- Without the permission: `call.error` `noPermission`.
- If the probe shows `placeCall` doesn't work while MacDroid is in the background: start `ACTION_CALL` through the MacDroid control service when it's on, else post a "Call <number>? Tap to call" notification.

### 2.3 Settings
Phone Settings gets a **Calls** section with one row, **"Make calls from your Mac"**:
- **Off:** it reads "Off: tap to allow" and asks for `CALL_PHONE`; after a denial, it opens App settings.
- **Allowed:** it reads "Allowed".

## 3. Mac

### 3.1 Core (`MacDroidCore`, tested)
`CallCenter` (actor):
- `call.state` → the current calls, and which panel shows:
  - `ringing(name, number)`
  - `active(name, since)`, where `since` is when the Mac first saw it active
  - `none`
- **Several calls:** the panel shows the newest ringing call if any, else the newest active one.
- **Actions** send `call.action`; dialing sends `call.dial`.
- **Errors:** `call.error` gives a short notice: "Couldn't answer — use your phone" (or decline / hang up), and for `noPermission`, "Allow MacDroid to make calls on your phone (phone Settings → Calls)".
- **Disconnects:** losing the connection clears all calls.

Number handling:
- `tel:` parsing, and number clean-up that keeps a leading `+`, digits, `*` and `#`.
- An empty result is refused.

### 3.2 Call panel (app)
- **Ringing:**
  - a small, non-activating floating panel at the top right of the screen: **"Incoming call"**, the name (or number), the number under it when both are known, and **Decline** and **Answer** buttons
  - one short alert sound when it appears
  - It doesn't take focus from the app you're in.
- **Active:** the panel shows **"On a call with <name>"**, a running timer, and **Hang up**. The menu-bar popover shows the same line.
- **When it closes:** when the call ends, from either side. A failed action shows its notice in the panel for 3 s, then the panel closes.

### 3.3 Dialing (app)
- **Dialer window** (owner feedback; it replaced the planned number pad on the Phone page): opened from **Call…** on the Phone page and the popover's phone icon. It has round keys (hold 0 for `+`), ⌫ (hold to clear), paste (cleaned), and a green call button / Return that sends at once and closes the window.
- **`tel:` links:**
  - The app declares the `tel` URL scheme.
  - Settings → Calls shows which app opens phone links. (The planned "Use MacDroid for phone links" button was dropped: the sandbox doesn't allow `NSWorkspace.setDefaultApplication`; see DECISIONS.)
  - A link shows **"Call <number> on your phone?"** with **Call** and **Cancel**.
  - If the phone isn't connected or doesn't support calls, it says so instead.

### 3.4 Settings
Mac Settings gets a **Calls** section:
- **"Show incoming calls"** (default on; off hides the panel and sound)
- a line saying which app opens phone links
- the note "Call audio stays on your phone."

## 4. Protocol
New feature `calls` in `hello.features`. Calls are used only when both sides list it.

| Message | Direction | Body |
|---|---|---|
| `call.state` | phone → Mac | `callId` (string), `state` (`ringing` \| `active` \| `ended`), `name` (optional), `number` (optional) |
| `call.action` | Mac → phone | `callId`, `action` (`answer` \| `decline` \| `hangUp`) |
| `call.dial` | Mac → phone | `number` (cleaned; 1–32 characters of `+0-9*#`) |
| `call.error` | phone → Mac | `reason` (`callGone` \| `notSupported` \| `noPermission`), `callId` (optional) |

Each message gets a fixture in `protocol/fixtures` (`call.state.json`, `call.state.ended.json`, `call.action.json`, `call.dial.json`, `call.error.json`), decoded by both suites, plus rules in PROTOCOL.md. The phone ignores a `call.dial` whose number is empty or not cleaned.

## 5. Privacy and trust
Names and numbers go only to the paired Mac over the existing mutual-TLS link. The Mac keeps them in memory only; there's no call history. Logs never contain names or numbers.

## 6. Testing

### 6.1 On-device probe first (Plan 10, Task 1)
This runs with the owner, with a debug-only build that logs flags and lengths, never names or numbers. The owner calls the phone from another phone.
- **Q1:** While ringing, and during the call, does the phone app (Google Phone on Nothing OS) post a call-style notification with the answer, decline and hang-up intents? Is it removed when the call ends?
- **Q2:** Fired from the listener, do Answer, Decline and Hang up work?
- **Q3:** With `CALL_PHONE` granted (by the owner, on screen), does `placeCall` start a call while MacDroid is in the background?
- **Q4:** Does the notification carry the number (`tel:` URI) or only a contact name?

**Stop rule:** if Q1 or Q2 fails, stop and decide with the owner.

### 6.2 Automated
- **Android core:**
  - `CallTracker`: notification snapshots → states; changes only; ended on removal; screening → ringing
  - actions → the right intent or `callGone` / `notSupported`
- **Mac core:**
  - `CallCenter` panel states and choosing among several calls
  - errors → notices; disconnect clears calls
  - `tel:` parsing and number clean-up, including an empty result
- **Protocol:** the five fixtures in both suites; `calls` in both feature lists.
- **Cross-platform:**
  - The test phone sends `call.state` `ringing`.
  - The dev server answers it with `call.action` `answer`, which the phone receives.
  - The dev server sends `call.dial`, which the phone receives.
  - The phone sends `call.state` `ended`.

### 6.3 Owner checklist
Written in the plan's last task. It covers:
- an incoming call: the panel appears, and Answer, Decline and Hang up from the Mac work
- answering on the phone switches the panel to "On a call"
- a missed call
- dialing from the number pad
- a Safari `tel:` link, with the confirmation
- dialing without the permission
- "Show incoming calls" off

## 7. Decisions
- **Detect and control calls through the phone app's call notification** (approach 1), not the telephony APIs plus the call-screening role (more permissions, conflicts with other screening apps, call-log restrictions) or pressing on-screen dialer buttons (fragile).
- **No call audio on the Mac.** Not possible for an Android app.
- **No automatic speakerphone** (owner's choice A).
- **Outgoing calls:** the number pad and `tel:` links, with a confirmation for links (owner's choice A + B); the right-click "Call with phone" comes later.

## 8. Plan
One plan (Plan 10), on branch `feat/calls`:
1. The probe.
2. Protocol.
3. Android core `CallTracker`.
4. Mac core `CallCenter` and numbers.
5. Android listener wiring, dialing and settings.
6. The Mac panel, number pad, links and settings.
7. Cross-platform test.
8. Handoff and owner checklist.
