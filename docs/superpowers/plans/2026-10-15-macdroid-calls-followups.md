# Calls (Plan 10): follow-ups

## Probe (Task 1)
Skipped at the owner's request on 2026-10-15. The probe was built and installed, but not run. The behaviours it would have checked are covered by fallbacks in the code, and by the owner checklist at the end:
- **Call-style intents missing:** buttons matched by label (`CallLabels`).
- **An action failing from the background:** the Mac shows "Couldn't … — use your phone".
- **`placeCall` refused in the background:** to be checked in the owner checklist; the spec §2.2 fallback gets added if needed.
