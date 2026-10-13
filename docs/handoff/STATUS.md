# Status (as of 2026-10-13, after the redesign merge)

## Branches
- `main`: Plan 1 (protocol and connection core), the UI/UX redesign and the app icons. All are merged and were verified by hand by the owner.

## In flight
- **File transfer (Plan 2):** the spec was written and approved on 2026-10-13 (`docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`). No code yet. The next step is the implementation plan.

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
