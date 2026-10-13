# Roadmap

Each round follows the same process: brainstorm, then a spec in `docs/superpowers/specs/`, then a plan in `docs/superpowers/plans/`, then subagent-driven execution with per-task review, then a final review, then the owner's hands-on check, then merge.

## Next: Plan 2 — File transfer (spec approved and reviewed; plan written)
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
  - **2b, UI and platform glue** (not written yet): `…-2b-ui.md`. It covers:
    - Mac: app wiring and entitlements, Files page, design-A popover, Settings → Files, notifications.
    - Android: MediaStore/SAF/ask save targets, content-URI sources, Files tab, Home Send FAB, share target, settings and setup step, progress and received notifications.
- **Next step for a new session:**
  1. Have the owner review Plan 2a (see STATUS.md for whether that's done).
  2. Execute it with `superpowers:subagent-driven-development` (the owner's preferred method).
  3. Write Plan 2b with `superpowers:writing-plans`. It builds on 2a's public APIs (`TransferManager`, `TransferEngine`, `SaveTarget`/`PartFile`/`SourceOpener`).
  4. Run 2b, then the owner's hands-on checklist (spec §6), then merge.
- **Groundwork tasks the plan must start with:**
  - a global `TrustedPeerMiddleware` (only `/v1/pair` exempt)
  - a `ConnectionHub` incoming-message hook
  - a multi-subscriber status stream
- **Scope:**
  - drag-and-drop and Send Files… on the Mac (Files page and the redesigned popover, design A)
  - Android Share target, Files tab and Home Send (Photo Picker / file picker)
  - auto-save with a notification; resumable, checksummed transfers, 3 in parallel
- **Deferred:** the Finder Share extension (a small round after this).

## Plan 3: Notifications (round-1 spec §5.1)
- **Groundwork:**
  - Swift `Hello.features` should become tolerant (Kotlin already defaults it).
  - Android `ConnectionState.Connected` should expose the Mac's features.
  - The hub message hook and multi-subscriber stream arrive in Plan 2.
- **Android:**
  - `NotificationListenerService`, plus an onboarding step for notification access.
  - Forward posted and removed notifications, with icons sent once per app and cached by hash.
  - Mute list.
- **Mac:**
  - `UserNotifications` with a thread per app.
  - Reply via `UNTextInputNotificationAction` and actions mirrored, so reply/action/dismiss sync both ways.
  - The Notifications sidebar page lists the last 50 notifications and has per-app mute toggles.
- **Messages:** `notification.posted|removed|action|reply|dismiss|filter`.

## Plan 4: Clipboard (round-1 spec §5.2)
- **Mac → phone:** automatic, by polling the `NSPasteboard` change count. Concealed (password-manager) items are skipped.
- **Phone → Mac:** on demand (Quick Settings tile, notification action, Share target), because Android 10+ blocks background clipboard reads.
- **History** on the Mac (10/20/50 entries) and the Clipboard tabs.
- **Images** use Plan 2's transfer channel.

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
