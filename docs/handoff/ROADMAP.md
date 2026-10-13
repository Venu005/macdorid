# Roadmap

Each round follows the same process: brainstorm, then a spec in `docs/superpowers/specs/`, then a plan in `docs/superpowers/plans/`, then subagent-driven execution with per-task review, then a final review, then the owner's hands-on check, then merge.

## Next: Plan 2 — File transfer (spec approved, plan not written yet)
- **Spec:** `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`. It was approved in brainstorming on 2026-10-13.
- **Next step for a new session:**
  1. Invoke `superpowers:writing-plans` on that spec, saving to `docs/superpowers/plans/2026-10-13-macdroid-file-transfer.md`.
  2. Have the owner review the plan.
  3. Execute it with `superpowers:subagent-driven-development` (the owner's preferred method), on a branch such as `feat/file-transfer`.
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
