# Calls (Plan 10): follow-ups

## Probe (Task 1)
Skipped at the owner's request on 2026-10-15. The probe was built and installed, but not run. The behaviours it would have checked are covered by fallbacks in the code, and by the owner checklist at the end:
- **Call-style intents missing:** buttons matched by label (`CallLabels`).
- **An action failing from the background:** the Mac shows "Couldn't … — use your phone".
- **`placeCall` refused in the background:** to be checked in the owner checklist; the spec §2.2 fallback gets added if needed.

## Owner checklist (Plan 10)
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

## Final review: deferred minors
1. **Wrong verb in a notice:** when a call ends at the moment Decline or Hang up is pressed, the notice says "Couldn't answer" (`asked` is cleared on `ended` before `callGone` arrives).
2. **Notice timing:** while a notice shows, a new ringing call waits (and its sound plays late), and other state updates restart the 3 s timer.
3. **Cold start:** clicking a `tel:` link while MacDroid isn't running says "Your phone isn't connected" before the phone has had time to connect.
4. **Vanity numbers:** `1-800-FLOWERS` becomes `1800`; letters should map to keypad digits or be refused.
5. **Crash risk:** an unexpected exception in `CallBridge.execute` (or `NotificationBridge`) would crash the app process; wrap it and report `notSupported`.
6. **Panel hardening:** use an `NSPanel` subclass with `canBecomeKey = true`, and set the hosting view once instead of on every update.
7. **Race:** status and message streams are separate tasks; a quick session replacement could clear a call that was just re-sent.
8. **Mirrored entry left behind:** a notification that becomes a call notification under the same key (some VoIP apps) leaves its old mirrored entry on the Mac.

## Final review: rulings on what the reviewer didn't judge
- **Answer, Decline, Hang up and `placeCall` from the background (Android 14+ background-start limits):** checked in the owner checklist (items 3–5, 8). The spec's fallback gets added if they fail.
- **`NSWorkspace.setDefaultApplication` in the sandboxed app, and `tel:` routing through `AppDelegate` with the SwiftUI window:** checked in checklist item 9.
- **The panel sits where macOS banners appear:** a design choice; kept.
- **Call waiting under one dialer notification key:** the Mac shows one call per key, a consequence of using the key as the call id (spec); kept.
- **The `CALL_PHONE` prompt opens App settings after a first dismissal:** same pattern as the photos permission; kept.
