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
