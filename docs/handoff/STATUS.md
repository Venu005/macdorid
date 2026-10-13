# Status (as of 2026-10-13, after the file-transfer merge)

## Branches
- `main`: Plan 1 (connection core), the UI/UX redesign, the app icons and file transfer (Plan 2, merged 2026-10-13). All gates pass on `main`. The file-transfer hands-on checklist passed except the 2 GB+ file.
- `main` also has notifications and clipboard (Plans 3 and 4, merged 2026-10-13 as `db85323`), version 0.4.0. All gates pass on `main`.

- `main` also has the 9 deferred-minor fixes (merged 2026-10-13 as `bd6c661`; `swift test` 217).
- `feat/small-wins`: Plan 5 (ring, links, media controls) done, all gates pass. Final review next, then the owner's checklist. Not merged.
## In flight
- **Small wins (Plan 5):** executed on `feat/small-wins`; owner checklist in `docs/superpowers/plans/2026-10-13-macdroid-small-wins-followups.md`.
- **Notifications + clipboard (Plans 3 and 4):** merged to `main` on 2026-10-13 after the owner's hands-on check. Deferred minors: `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`.
- **File transfer (Plan 2):** merged to `main` on 2026-10-13. The remaining hands-on checks are in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`; fix anything they turn up on a new branch.
  - Spec: `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`.
  - Android 0.3.0 is installed on the phone. Build and open the Mac app with the commands in DEV-GUIDE.md.
  - **Owner-verified (2026-10-13):** the whole hands-on checklist except a 2 GB+ file in each direction, which the owner will check later.

## Working and verified
- Pairing by QR code and by manual entry, with a fingerprint check.
- Mutual TLS 1.3 with pinning, and auto-reconnect across Wi-Fi toggles, Mac quit/relaunch, sleep/wake and phone reboot. The owner verified these by hand on Plan 1.
- Battery level and charging state sent to the Mac and shown in the popover and on the Phone page.
- Forget and re-pair on either side.
- Android 17 `ACCESS_LOCAL_NETWORK` flow.
- Keystore-backed TLS client authentication on the real phone (instrumented test).
- Automated tests:
  - Swift: 144 tests.
  - Android core: protocol, TLS, pairing, connection manager.
  - Android app: status/setup logic.
  - Cross-platform script (Swift server ↔ Kotlin client).
  - Cross-platform file transfer both ways (24 MiB each), with resume after a dropped connection.

## Pending or unverified
- **File transfer with a 2 GB+ file** (owner, later). The rest of the checklist passed.
- **Login-item launch.** With a phone paired, MacDroid should start quietly with no window. Not verified. If macOS restores the window at login, disable window restoration.
- Real login items need a properly signed build. Dev builds are ad-hoc signed (expect a keychain prompt after each rebuild).

## Known limitations (by design for now)
- One paired phone per Mac.
- IPv4 only for NSD rediscovery. After Mac sleep/wake the phone may take up to about 30 s to reconnect.
- The Mac has no "reconnecting" state; it shows "Not connected — waiting for your phone".
- No placeholder pages or tabs are left on either side. Files, Notifications and Clipboard are live (Plan 3, in progress on `feat/notifications-clipboard`).

## Follow-up lists
- [Plan 1 follow-ups](../superpowers/plans/2026-10-12-macdroid-plan1-followups.md)
- [UI/UX redesign follow-ups](../superpowers/plans/2026-10-13-macdroid-ui-ux-redesign-followups.md)
- [File transfer follow-ups](../superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md) (includes the open hands-on checklist)
