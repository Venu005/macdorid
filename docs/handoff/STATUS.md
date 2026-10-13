# Status (as of 2026-10-13)

## Branches
- `main`: Plan 1 (protocol and connection core) is merged, with an old scaffold UI.
- `feat/ui-ux-redesign`: the full UI/UX redesign.
  - All tasks and reviews passed, and the final whole-branch review was clean.
  - **Not merged yet.** It waits for the owner's hands-on checklist, which is in the redesign plan, Task 5 Step 6.
  - Merge it into `main` once that checklist passes.

## Working and verified
- Pairing by QR code and by manual entry, with a fingerprint check.
- Mutual TLS 1.3 with pinning, and auto-reconnect across Wi-Fi toggles, Mac quit/relaunch, sleep/wake and phone reboot. The owner verified these by hand on Plan 1.
- Battery level and charging state sent to the Mac and shown in the popover and on the Phone page.
- Forget and re-pair on either side.
- Android 17 `ACCESS_LOCAL_NETWORK` flow.
- Keystore-backed TLS client authentication on the real phone (instrumented test).
- Automated tests:
  - Swift: 59 tests.
  - Android core: protocol, TLS, pairing, connection manager.
  - Android app: status/setup logic.
  - Cross-platform script (Swift server ↔ Kotlin client).

## Pending or unverified
- Owner's hands-on check of the redesign. Key items:
  - Window reopen.
  - Dock icon behaviour.
  - Countdown and Retry now.
  - Disconnect, then rotate.
  - Mac Forget → phone shows "Pairing problem".
  - First-run wizard after clearing app data.
  - Dark mode.
- **Login-item launch.** With a phone paired, MacDroid should start quietly with no window. Not verified. If macOS restores the window at login, disable window restoration.
- Real login items need a properly signed build. Dev builds are ad-hoc signed (expect a keychain prompt after each rebuild).

## Known limitations (by design for now)
- One paired phone per Mac.
- IPv4 only for NSD rediscovery. After Mac sleep/wake the phone may take up to about 30 s to reconnect.
- The Mac has no "reconnecting" state; it shows "Not connected — waiting for your phone".
- The Notifications, Clipboard and Files tabs and sidebar items are placeholders.

## Follow-up lists
- [Plan 1 follow-ups](../superpowers/plans/2026-10-12-macdroid-plan1-followups.md)
- [UI/UX redesign follow-ups](../superpowers/plans/2026-10-13-macdroid-ui-ux-redesign-followups.md)
