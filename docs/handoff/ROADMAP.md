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

## Done: Plans 3 and 4 — Notifications and Clipboard (merged 2026-10-13)
- **Spec:** `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md` (approved in brainstorming 2026-10-13; awaiting the owner's review of the written spec). It refines round-1 §5.1/§5.2 and wins where they differ.
- **Next steps for a new session:**
  1. ~~Owner reviews the spec~~. The owner asked for a self-review instead: done 2026-10-13, fixes in spec §8.
  2. ~~Write the plans~~. Done: `docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md` (11 tasks) and `docs/superpowers/plans/2026-10-13-macdroid-clipboard-4.md` (11 tasks). Execution ledgers go under `.superpowers/sdd/`.
  3. Run both on `feat/notifications-clipboard` (the owner chose inline/"native" execution). Plans 3 and 4 done 2026-10-13. Then one final whole-branch review, the owner's hands-on checklist (spec §6) and one merge.
- **Plan 3 scope:** tolerant `hello.features` and peer features on both sides; `notification.*` messages (phone owns the mute list, `notification.sync` on reconnect); Android `NotificationListenerService`; Mac `UserNotifications` with reply/actions/dismiss; Mac Notifications page (grouped by app) and Settings; Android Notifications tab as a control panel; notification access in the existing setup step.
- **Plan 4 scope:** `clipboard.set`; `transfer.offer.purpose = clipboard` for images ≤ 10 MB; Mac pasteboard watcher (concealed/transient items never sent) and history page; Android Quick Settings tile, notification action, "Send now" and a "Copy to Mac" share target; Android Clipboard tab.

## Parked: small round — Finder Share extension
Brainstorming started 2026-10-13, then the owner parked it. Resume at the first open question.
- **Constraint found:** a Share extension is a separate sandboxed process. It can read the shared files only while it runs, and it can't use the network, so it must hand the files to the main app with read access.
- **Approaches on the table:**
  1. Copy the files into an app-group folder. Reliable, but slow for multi-GB files and doubles the disk use.
  2. Pass security-scoped bookmarks through the app group. Instant, but whether an extension's bookmark resolves in the main app needs a spike first.
  3. Use Finder's right-click Quick Actions / Services. Handled by the main app itself (like drag and drop), so it's instant at any size, but it isn't in other apps' Share menus.
- **Open question for the owner:** where should "Send to phone" appear: (A) the Share menu, (B) Finder's right-click only, or (C) both?

## Agreed order (2026-10-13)
1. ~~**The 9 deferred minors**~~ (done, merged 2026-10-13) from the Plans 3–4 final review (`docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`), one by one.
2. **Small wins (Plan 5):** ring my phone; open a link on the other device; media controls. Spec approved 2026-10-13 (`docs/superpowers/specs/2026-10-13-macdroid-small-wins-design.md`); plan written: `docs/superpowers/plans/2026-10-13-macdroid-small-wins-5.md` (10 tasks). Done, merged to `main` 2026-10-13.
3. **Photo browsing and transfer (Plan 6).** Spec (`docs/superpowers/specs/2026-10-13-macdroid-photos-design.md`, self-reviewed) and plan (`docs/superpowers/plans/2026-10-13-macdroid-photos-6.md`, 10 tasks) are written; Done, merged to `main` 2026-10-14 (`37b425c`); the 9 deferred minors were fixed and merged (`2807ce9`).
4. **Screen mirroring (low latency) and remote control (Plans 7 and 8).** Spec written 2026-10-14 (`docs/superpowers/specs/2026-10-14-macdroid-mirroring-control-design.md`, self-reviewed): mirroring with phone audio in its own Mac window over a dedicated mutual-TLS media WebSocket; control through an Android accessibility service (no debugging), including auto-Start of the capture prompt. Plan 7 (mirroring) built on `feat/mirroring` 2026-10-14; Plan 8 (control, 7 tasks: `docs/superpowers/plans/2026-10-14-macdroid-remote-control-8.md`) built on the same branch 2026-10-15. **Merged to `main` 2026-10-15** (`75ac85b`). On Android 17 mirroring needs an unlocked phone (capture ends on lock).
5. ~~**Automatic phone → Mac clipboard (Plan 9).**~~ Dropped 2026-10-15 after the on-device probe (see DECISIONS.md); phone → Mac stays manual.
6. **Calls on the Mac (Plan 10).** Spec approved 2026-10-15 (`docs/superpowers/specs/2026-10-15-macdroid-calls-design.md`); plan written (`docs/superpowers/plans/2026-10-15-macdroid-calls-10.md`, 8 tasks); **merged to `main` 2026-10-15** (`c9ae68f`).
7. **Backlog clean-up (Plan 11).** Started 2026-10-16 on `fix/backlog`: every open deferred minor plus cheap hardening (plan: `docs/superpowers/plans/2026-10-16-macdroid-backlog-11.md`). All owner checklists passed (2026-10-16), including the 2 GB file.
8. The rest of the owner's list, below.

## Owner's feature list (2026-10-13)
The owner's planned features, with status:
- File transfer (Android ↔ Mac / **iPad**): Android ↔ Mac is done. iPad is a new platform (an iPadOS app), not specced.
- Clipboard sync (text, images, links): done.
- Notifications on the Mac with reply: done.
- Photo browsing and transfer: done (Plan 6, merged 2026-10-14).
- Screen mirroring (low latency): done (Plan 7, merged), with phone audio; 50–80 ms target.
- Remote control of Android from the Mac: done (Plan 8, merged), through an accessibility service (owner's choice; Play-restricted, fine for personal use).
- Contact sync: **dropped 2026-10-16** (owner: not needed). Was: not specced. Android `READ_CONTACTS`; Mac Contacts framework plus the sandbox address-book entitlement.
- Use the phone as a webcam (Wi-Fi + USB): **parked 2026-10-15** (owner), before a spec. What was found:
  - A system-wide camera on macOS 26 needs a CoreMediaIO camera extension. Installing one requires the app signed with a paid Apple Developer Team ID (the Mac app is ad-hoc signed today; the owner plans to enrol later). Legacy DAL plug-ins no longer load.
  - The owner's Nothing Phone (3) has no built-in USB "Webcam" mode.
  - Route without the membership: Phase 1 = phone camera → a MacDroid Camera window on the Mac, bridged into calls by OBS Virtual Camera. Phase 2 (after enrolling) = a built-in "MacDroid Camera" extension.
- Mic support (Wi-Fi + USB): not specced. Needs a macOS audio driver/extension; USB means adb.

Also noted earlier: SMS (personal build only, Play-restricted), USB transport via `adb reverse`, Wi-Fi Direct, and the parked Finder Share extension.

## Before publishing
- **Stance (2026-10-16):** free and unsigned for now; no Apple Developer account. If it is ever sold: Android free, a one-time Pro unlock on the Mac only, offline signed license keys (see DECISIONS.md). Check the "MacDroid" name for trademark clashes first.
- Play declaration for photo/video access (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`) as core functionality (Plan 6).
- **Signing:** proper signing with a Team ID, and the data-protection keychain.
- **Android hardening:** Gradle wrapper checksum; backup exclusions for pairing prefs; restart on `MY_PACKAGE_REPLACED`.
- **Mac hardening:** Bonjour name-conflict handling; keychain write via `SecItemUpdate`.
- **Store work:** Play policy review of the foreground service type and notification-listener use; App Store privacy labels.
- Check every item marked "before publishing" in the follow-up lists.
