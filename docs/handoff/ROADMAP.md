# Roadmap

Each round follows the same process: brainstorm, then a spec in `docs/superpowers/specs/`, then a plan in `docs/superpowers/plans/`, then subagent-driven execution with per-task review, then a final review, then the owner's hands-on check, then merge.

## Next: Plan 2 (app icons done on `feat/app-icons`, awaiting the owner's look)

## Plan 2: Notifications (round-1 spec §5.1)
- **Groundwork first:**
  - `ConnectionHub` needs a multi-subscriber status stream and an incoming-message hook.
  - Swift `Hello.features` should become tolerant (Kotlin already defaults it).
  - Android `ConnectionState.Connected` should expose the Mac's features.
- **Android:**
  - `NotificationListenerService`, plus an onboarding step for notification access.
  - Forward posted and removed notifications, with icons sent once per app and cached by hash.
  - Mute list.
- **Mac:**
  - `UserNotifications` with a thread per app.
  - Reply via `UNTextInputNotificationAction` and actions mirrored, so reply/action/dismiss sync both ways.
  - The Notifications sidebar page lists the last 50 notifications and has per-app mute toggles.
- **Messages:** `notification.posted|removed|action|reply|dismiss|filter`.

## Plan 3: Clipboard (§5.2)
- **Mac → phone:** automatic, by polling the `NSPasteboard` change count. Concealed (password-manager) items are skipped.
- **Phone → Mac:** on demand, because Android 10+ blocks background clipboard reads. Three ways: Quick Settings tile, notification action, Share target.
- History on the Mac (10/20/50 entries) and the Clipboard tabs.
- **Messages:** `clipboard.set`, plus image transfers via Plan 4's transfer channel.

## Plan 4: File transfer (§5.3)
- **Groundwork:** make `TrustedPeerMiddleware` global before adding `/v1/files/{id}`.
- **Endpoints:**
  - Phone → Mac: `PUT`.
  - Mac → phone: `GET`.
  - Resume: HTTP Range on GETs; HEAD + `Upload-Offset` on uploads.
  - SHA-256 is verified via `transfer.finish`/`result`.
- Up to 3 transfers run in parallel.
- **Destinations:** a folder choice with "ask each time" on both sides (security-scoped bookmark on the Mac, Storage Access Framework on Android).
- **Entry points:** Finder Share extension, Android Share target, and the Send button on Android Home.

## Later rounds (not specced)
Photo gallery, SMS (Play-restricted permissions), screen mirroring/remote control (scrcpy-style, needs debugging mode), phone as webcam/mic (CoreMediaIO extension), USB transport via `adb reverse`, Wi-Fi Direct.

## Before publishing
- **Signing:** proper signing with a Team ID, and the data-protection keychain.
- **Android hardening:** Gradle wrapper checksum; backup exclusions for pairing prefs; restart on `MY_PACKAGE_REPLACED`.
- **Mac hardening:** Bonjour name-conflict handling; keychain write via `SecItemUpdate`.
- **Store work:** Play policy review of the foreground service type and notification-listener use; App Store privacy labels.
- Check every item marked "before publishing" in the follow-up lists.
