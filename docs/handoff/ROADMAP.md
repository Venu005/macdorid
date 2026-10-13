# Roadmap

Each round follows the same process: brainstorm, then a spec in `docs/superpowers/specs/`, then a plan in `docs/superpowers/plans/`, then subagent-driven execution with per-task review, then a final review, then the owner's hands-on check, then merge.

## Done: Plan 2 — File transfer (merged 2026-10-13)
- **Spec:** `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`. It was approved in brainstorming on 2026-10-13.
- **Review:** the 8 review points were resolved on 2026-10-13 (spec §8; rulings in DECISIONS.md).
- **Plans:** the work is split in two, at the owner's request, so execution can start sooner. Both run on the branch `feat/file-transfer`.
  - **2a, the engine:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md`. 14 tasks:
    - the protocol and the global middleware
    - the hub streams
    - the Mac `TransferManager` and file routes
    - the Android `TransferClient` and `TransferEngine`
    - a cross-platform test with a mid-file disconnect

    Nothing in 2a is visible to the user.
  - **2b, UI and platform glue:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`. It covers:
    - Mac: app wiring and entitlements, Files page, design-A popover, Settings → Files, notifications.
    - Android: MediaStore/SAF/ask save targets, content-URI sources, Files tab, Home Send FAB, share target, settings and setup step, progress and received notifications.
- **Next step for a new session:**
  1. ~~Review and execute Plan 2a~~. Done on 2026-10-13: 2a is complete on `feat/file-transfer`.
  2. ~~Write Plan 2b~~. Done: `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md` (13 tasks).
     - It starts with two engine changes: visible, cancellable save decisions on both sides, plus a periodic resume on Android.
     - Then the Mac app: wiring, Files page, design-A popover.
     - Then Android: helpers, save targets, notifications, Files tab, share target, settings and setup step.
     - It ends with a hands-on checklist for the owner.
  3. ~~Run 2b~~. Done on 2026-10-13.
  4. ~~Merge~~. Merged to `main` on 2026-10-13. The remaining hands-on checks are in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`. **Next round: Plan 3 (notifications).**
- **Groundwork (done in Plan 2a):**
  - a global `TrustedPeerMiddleware` (only `/v1/pair` exempt)
  - a `ConnectionHub` incoming-message hook
  - a multi-subscriber status stream
- **Scope:**
  - drag-and-drop and Send Files… on the Mac (Files page and the redesigned popover, design A)
  - Android Share target, Files tab and Home Send (Photo Picker / file picker)
  - auto-save with a notification; resumable, checksummed transfers, 3 in parallel
- **Deferred:** the Finder Share extension (a small round after this).

## Next: Plans 3 and 4 — Notifications and Clipboard (one spec)
- **Spec:** `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md` (approved in brainstorming 2026-10-13; awaiting the owner's review of the written spec). It refines round-1 §5.1/§5.2 and wins where they differ.
- **Next steps for a new session:**
  1. ~~Owner reviews the spec~~. The owner asked for a self-review instead: done 2026-10-13, fixes in spec §8.
  2. ~~Write the plans~~. Done: `docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md` (11 tasks) and `docs/superpowers/plans/2026-10-13-macdroid-clipboard-4.md` (11 tasks). Execution ledgers go under `.superpowers/sdd/`.
  3. Run both subagent-driven on `feat/notifications-clipboard`, then one final review, the owner's hands-on checklist (spec §6) and one merge.
- **Plan 3 scope:** tolerant `hello.features` and peer features on both sides; `notification.*` messages (phone owns the mute list, `notification.sync` on reconnect); Android `NotificationListenerService`; Mac `UserNotifications` with reply/actions/dismiss; Mac Notifications page (grouped by app) and Settings; Android Notifications tab as a control panel; notification access in the existing setup step.
- **Plan 4 scope:** `clipboard.set`; `transfer.offer.purpose = clipboard` for images ≤ 10 MB; Mac pasteboard watcher (concealed/transient items never sent) and history page; Android Quick Settings tile, notification action, "Send now" and a "Copy to Mac" share target; Android Clipboard tab.

## Small round: Finder Share extension
"Share → Send to phone" in Finder. It needs an app-extension target that hands files to the main app via an app group (the extension never opens network connections).

## Later rounds (not specced)
Photo gallery, SMS (Play-restricted permissions), screen mirroring/remote control (scrcpy-style, needs debugging mode), phone as webcam/mic (CoreMediaIO extension), USB transport via `adb reverse`, Wi-Fi Direct.

## Before publishing
- **Signing:** proper signing with a Team ID, and the data-protection keychain.
- **Android hardening:** Gradle wrapper checksum; backup exclusions for pairing prefs; restart on `MY_PACKAGE_REPLACED`.
- **Mac hardening:** Bonjour name-conflict handling; keychain write via `SecItemUpdate`.
- **Store work:** Play policy review of the foreground service type and notification-listener use; App Store privacy labels.
- Check every item marked "before publishing" in the follow-up lists.
