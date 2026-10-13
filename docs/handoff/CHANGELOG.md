# Changelog

Newest first. Every change to the repo adds an entry here (see "Keep the handoff current" in `/CLAUDE.md`).

Format: `## YYYY-MM-DD — title`, then what changed, why, the branch or commits, and how it was verified.

## 2026-10-13 — Plan 3: notifications (in progress on feat/notifications-clipboard)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md` (spec: `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`). Executed inline (the owner chose "native").
- Task 1:
  - `notification.*` messages (10 fixtures) in both codecs; `hello` without `features` decodes as none; `ProtocolInfo.features` / `Features.SUPPORTED` = files, notifications.
  - Also fixed a flaky Android test: `pausedUploadIsRetriedWhileConnected` raced its 300 ms periodic resume. That interval is now 1 s; the test failed 1 in 5 on `main`.
  - Verified: `swift test`, `./gradlew :core:test`.
- Task 2: both sides announce `files`, `notifications`; the Mac hub forwards every feature message; Android `Connected` carries the Mac's features (`macSupports`). Verified: `swift test` 150, Android gates.
- Task 3: Android core `NotificationForwarder`: exclusions, update de-dupe, mutes owned by the phone, apps seen with daily counts, sync of still-active keys, icons once per connection. Verified: 18 new tests.
- Task 4: Mac core `NotificationStore` (50, grouped by app), `FloodGate` (10 per 5 s per app), `NotificationCategories`. Verified: `swift test` 158.
- Task 5: Mac core `PhoneNotifications` actor (entries, apps, banners via the flood gate, sync, errors, mute requests), `IconCache` (hash-validated file names), `NotificationPresentation`. Verified: `swift test` 179.
- Task 6: Mac `NotificationRouter` (the only notification delegate; received-file and phone categories together), `PhoneNotifier` (banners with reply/buttons/dismiss, icon attachment), `AppModel` wiring and the `showPhoneNotifications` setting. Verified: Mac app build, no compiler warnings.
- Task 7: Mac Notifications page (grouped by app, hover Reply/buttons/✕, Clear all), Muted apps sheet, Settings → General → Notifications, Phone page line "Notifications off on phone". Verified: Mac app build.
- Task 8: Android `MacDroidNotificationListener` + `NotificationBridge` (reads notifications, carries out reply/action/dismiss, reports `gone`/`failed`), icons, settings persistence, launcher `<queries>` for app names and icons. Verified: Android gates, `NotificationTextTest`.

## 2026-10-13 — Spec self-review and Plans 3 and 4 written
- **What:**
  - Self-reviewed the notifications + clipboard spec against the code and added §8 with 11 fixes, among them:
    - the clipboard text limit is the 1 MiB WebSocket frame
    - echo prevention runs only on the Mac, by pasteboard change count
    - a single macOS notification delegate
    - icon hashes are validated as file names
    - `transfer.offer.purpose` is optional on the wire
  - Wrote Plan 3 (`docs/superpowers/plans/2026-10-13-macdroid-notifications-3.md`, 11 tasks) and Plan 4 (`docs/superpowers/plans/2026-10-13-macdroid-clipboard-4.md`, 11 tasks).
- **Why:** the owner asked for a self-review and then the plans.
- **Updated:** README (picking-up note, round table), STATUS, ROADMAP.
- **Verified:** plan self-review (spec coverage, placeholder scan, type consistency, review focus). No code changed.
- **Branch:** `main`.

## 2026-10-13 — Notifications + clipboard spec (Plans 3 and 4)
- **What:** brainstormed and wrote `docs/superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md`: one spec for notification mirroring (Plan 3) and clipboard sync (Plan 4), delivered as two plans on `feat/notifications-clipboard`. Covers the new `notification.*` and `clipboard.set` messages, `transfer.offer.purpose`, behaviour on both sides, UI (Mac page layout A grouped by app; Android Notifications tab as a control panel; Clipboard page/tab), errors and testing.
- **Why:** next rounds on the roadmap; the owner asked to do them together.
- **Updated:** README (round table, picking-up note), STATUS (in flight), ROADMAP (Next), DECISIONS (7 rows).
- **Verified:** spec self-review (no placeholders; consistent with the round-1 and file-transfer specs). No code changed.
- **Branch:** `main`.

## 2026-10-13 — File transfer hands-on checklist passed
- **What:** the owner ran the file-transfer hands-on checklist and everything passed except one item they haven't run yet: a 2 GB+ file in each direction, which they'll check later. The checklist is ticked in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`; STATUS and README are updated.
- **Next:** Plan 3 (notifications).
- **Branch:** `main`.

## 2026-10-13 — Handoff check after the file-transfer merge
- **What:** re-checked `main` against the handoff and fixed stale lines left from before the merge:
  - README: the spec and Plan 2a were still marked "next up", and Plan 2b "will be" written. They now link the 2a and 2b plans and the follow-ups file.
  - ROADMAP: 2b was marked "not written yet", the step numbers skipped 2, the groundwork read as still to do, and Plan 3 said the hub hook "arrives in Plan 2".
  - STATUS: added the file-transfer follow-ups file to the follow-up lists.
- **Verified:** `swift test` (144 tests), the Mac app build and the Android gates all pass on `main`.
- **Branch:** `main`.

## 2026-10-13 — Repository README
- **What:** added a root `README.md` for the GitHub repo. It covers:
  - what MacDroid is and its feature status
  - file-transfer behaviour
  - how it works (topology and protocol) and the security and privacy model
  - requirements, building, running and pairing
  - tests and the project layout
  - links into `docs/handoff/` and the specs, plus the roadmap
- **Why:** the repo had no README; the owner asked for a detailed one before pushing.
- **Verified:** every relative link in the README points to an existing file.
- **Branch:** `main`.

## 2026-10-13 — File transfer merged to main
- **What:** merged `feat/file-transfer` into `main`: Plan 2a (engine), Plan 2b (UI), the owner-found fixes (MediaStore part file, Mac drag-and-drop in the sandbox) and the final-review fixes.
- **Verified on the merged `main`:**
  - `swift test`: 144 tests.
  - The Mac app build succeeds with no compiler warnings.
  - The Android gates pass with no warnings.
  - `./scripts/cross-platform-test.sh` passes.
- **Owner-verified:** phone → Mac, and Mac → phone via Send Files…, the Files page and the popover.
- **Still open:** the rest of the hands-on checklist, in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- **Next:** Plan 3 (notifications).

## 2026-10-13 — Plan 2b final-review fixes
- **Mac (critical):** with "Ask where to save each time", the file is now written straight into the file chosen in the save panel. The sandbox grants access to that exact file only, so the old sibling `.macdroid-part` failed anywhere outside Downloads. The Mac also keeps earlier save folders accessible for the session.
- **Android:**
  - Only completed sends are tidied up: the cache copy is deleted and the read grant is released. Retry of a failed share works again, and grants no longer pile up (choosing a new folder releases the old one).
  - Undecided incoming rows offer "Choose where to save", and the prompt notification stays until answered.
  - A deleted chosen folder counts as unreachable (asks, shows the banner).
  - Only a missing file shows as "Deleted from this phone".
- **Verified:**
  - Regression tests RED→GREEN: `savePanelChoiceIsWrittenInPlaceWithoutAPartFile`, `FinishedSendsTest`, `ReceivedFilesTest`, `undecidedIncomingRowOffersChoosingWhereToSave`.
  - All gates pass.
  - The deferred minors are listed in `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Fix: drag and drop onto the Mac app did nothing
- **Symptom:** the owner dragged files onto MacDroid to send them to the phone, and nothing happened.
- **Evidence:** the unified log showed `CFPasteboard … Sandbox extension data required immediately for flavor public.file-url, but failed to obtain`. The popover read dragged file URLs while hovering (to add up their sizes), and the sandbox grants access only at drop.
- **Fix:**
  - Both drop targets read file URLs from `NSPasteboard(name: .drag)` at drop time (`Files/DroppedFiles.swift`).
  - The popover overlay shows only the count while hovering.
  - The Files page uses `onDrop(of: [.fileURL])` instead of `dropDestination(for: URL.self)`.
- **Verified:** `dropSummaryWithoutSizesShowsTheCount` (RED→GREEN) and the Mac app build. The owner's retest is pending.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Mac: never drop a file silently; transfer logs in Console
- **What:**
  - `TransferManager.sendReporting(files:)` reports files it couldn't queue, with the reason. The Mac shows them in a banner ("Couldn't send “name”: reason") instead of silently doing nothing.
  - The engine's and server's logs now reach the unified log (subsystem `dev.macdroid.mac`), including why a source file couldn't be reopened for the phone.
- **Why:** the owner's Mac → phone sends showed nothing at all, and there was no log to diagnose it.
- **Verified:** `unreadableFilesAreReportedNotDropped` (RED→GREEN), `swift test` 142, Mac app build with no compiler warnings.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Received files that were deleted say so
- **What (owner's request):** if a received file is deleted after it arrives, its row says so instead of offering to open it.
  - **Mac:** the row reads "Deleted from this Mac" and has no Show/Open. Show in Finder or Open on a file removed meanwhile shows the banner "“name” is no longer on this Mac — it was deleted or moved".
  - **Android:** the row reads "Deleted from this phone" (checked off the main thread, and again whenever Files or Home comes back). Tapping a missing file shows the toast "“name” was deleted from this phone".
- **Where:**
  - `TransferPresentation.row(…, savedFileExists:)` on both sides.
  - Mac: `AppModel.receivedFileExists`.
  - Android: `platform/ReceivedFiles`, `FilesUi.deleted`.
- **Verified:**
  - New presentation tests on both sides (RED→GREEN).
  - The Mac app and Android gates pass with no warnings.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Fix: Mac → phone transfers failed at 0 bytes
- **Symptom:** the owner's testing showed every Mac → phone transfer ending "Transfer failed". Phone → Mac worked.
- **Root cause:**
  - Both sides recorded `Io` at 0 bytes, with a MediaStore part created on the phone.
  - A new MediaStore item has no file until it's opened for writing.
  - The engine re-hashes the part before downloading, by opening it for reading, which threw `ENOENT`.
- **Fix:** `MediaStoreSaveTarget.createPart` creates the empty file (opens it once with `"w"`), and deletes the row if that fails.
- **Verified:**
  - The new on-device test `MediaStoreSaveTargetTest` covers create → read → append → reopen → resume → finish. It failed with `ENOENT` before the fix and passes after.
  - Installed on the phone.
- **Branch:** `feat/file-transfer`.

## 2026-10-13 — Plan 2b: file transfer UI (done on feat/file-transfer; waiting for the owner's check)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`.
- **Verified:**
  - `swift test`: 140 tests.
  - The Mac app build succeeds with no compiler warnings.
  - The Android gates pass with no warnings.
  - `./scripts/cross-platform-test.sh` passes.
  - Version 0.3.0 is installed on the Nothing Phone (3).
  - The owner's hands-on checklist is `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md`.
- Task 1: Mac `TransferManager` shows undecided incoming offers as `.offered` rows, passes `IncomingOffer` (id, name, size, mime) to the save-location provider, and cancels the question when either side cancels; undecided offers are forgotten at relaunch. Verified: `swift test`.
- Task 2: `TransferPresentation` (row text, popover items, drop summary) in `MacDroidCore`. Verified: `TransferPresentationTests`.
- Task 3: Android `TransferEngine` shows undecided offers as `Offered`, cancels the save question on either side's cancel, creates parts on IO, forgets undecided offers on restart, and retries paused transfers every 30 s while connected. Verified: `TransferEngineDecisionTest` (stable over 3 reruns).
- Task 4: the Mac app runs `TransferManager` (security-scoped source bookmarks, Received-files settings with a folder bookmark and a cancellable save panel, notifications with Show in Finder/Open); entitlements for user-selected files, Downloads and app-scope bookmarks; version 0.3.0. Verified: app build with no compiler warnings, and `codesign` shows the three entitlements.
- Task 5: Mac Files page (drop zone, Send Files…, In progress, Recent with Show in Finder/Open/Retry/cancel) and a Received-files section in Settings → General. Verified: app build with no compiler warnings.
- Task 6: Mac popover in design A: device card with battery ring, Send Files and Open MacDroid tiles, up to two transfer rows, Settings/Quit icons, and the whole popover as a drop target with a "Drop to send" overlay (count and size). Verified: app build with no compiler warnings.
- Task 7: Android pure helpers (`TransferPresentation`, `SaveMode`, `ShareSource`, `ShareDecision`, `FolderLabel`) with unit tests. Verified: `:app:testDebugUnitTest` (21 new tests).
- Task 8: Android save targets (MediaStore Download/MacDroid by default, a chosen SAF folder, or ask each time via a notification and CreateDocument), content-URI sources, and the `TransferEngine` running in `AppGraph`; version 0.3.0. Verified: Android gates.
- Task 9: Android `SendFiles` (persistable grant, or a cache copy up to 100 MB), a progress notification with Cancel, a "Received <name> from Mac" notification with Open, and `ConnectionService` actions for sending and cancelling. Verified: Android gates.
- Task 10: Android Files tab (Send to Mac card with Photos/Files, In progress with cancel, Recent with open/retry, folder-problem banner) and Home's Send FAB plus Recent activity (last 3). Verified: Android gates.
- Task 11: Android share target ("Send to Mac" for one or many files of any type): queues silently with a toast when connected, otherwise "Connect to your Mac first" and opens the app. Verified: Android gates.
- Task 12: Android Settings → Files (save folder with "Use default", ask-each-time switch, problem text) and an optional first-run setup step "Where should files from your Mac go?". Verified: `SetupFlowTest` (10) and the Android gates.

## 2026-10-13 — Plan 2b written (file transfer UI and platform glue)
- **What:** wrote `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`. It has 13 tasks:
  - **Engine changes, both sides:** pending incoming offers become visible, cancellable rows, and the save-location question gets the offer's details. Android also retries paused transfers while connected.
  - **Mac:** app wiring with entitlements, the Files page, the Received-files settings and the design-A popover.
  - **Android:** pure helpers, the MediaStore/SAF/ask save targets, notifications, the Files tab with the Home Send button, the share target, Settings → Files and a setup step.
  - **Wrap-up:** a final task with the owner's hands-on checklist.
- **Why:** this is the second half of Plan 2. It also folds in the 2a review minors that 2b needed (save-location API gaps, transfers stuck "Paused" while connected).
- **Branch:** `feat/file-transfer`.
- **Verified:** docs only.

## 2026-10-13 — Plan 2a: file transfer engine (done on feat/file-transfer)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md` (engine); Plan 2b (UI and platform glue) follows.
- **Verified:**
  - `swift test`: 127 tests.
  - The Mac app build succeeds.
  - `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` passes.
  - `./scripts/cross-platform-test.sh` passed twice in a row.
  - No warnings anywhere.
- **Execution:** Tasks 1–2 were run subagent-driven with per-task reviews. At the owner's request, Tasks 3–14 were run natively, with one whole-branch review at the end.
- Task 1: `TrustedPeerMiddleware` is global; every route except `POST /v1/pair` (and the not-found responder) requires the paired phone. Verified: `swift test`, including `strangerIsForbiddenEverywhereExceptPair`.
- Task 2: `transfer.*` messages in both codecs with 7 fixtures; `PROTOCOL.md` documents them and the `/v1/files` routes. Verified: both suites.
- Task 3: `Broadcast` fan-out; `ConnectionHub.statusUpdates` is multi-subscriber (replays the current status), and the new `ConnectionHub.messages` forwards control messages. Verified: `swift test` and the app build.
- Task 4: Swift `FileNaming`, `ByteRanges`, `TransferText`; shared vectors `file-naming.json` and `transfer-text.json`. Verified: `swift test`.
- Task 5: transfer model, JSON stores, rate smoothing, and checksummed `PartFileWriter`/`SourceReader` (resume re-hash, session supersede, disk-full detection). Verified: `swift test`.
- Task 6: `TransferManager` receiving: offer → part file → verify → rename; re-offer resume; outcome replay; 24 h cleanup; history 100; queued cancels. Verified: `TransferReceiveTests`.
- Task 7: `TransferManager` sending: FIFO queue with 3 slots, offer/accept/reject/result, re-offer on reconnect, source bookmarks, retry. Verified: `TransferSendTests`.
- Task 8: `HEAD`/`PUT`/`GET /v1/files/:id` with Upload-Offset resume, 409 on offset mismatch, Range/206, empty 206 at the end, 404 for wrong direction. Verified: `FileRouteTests`, `swift test`, app build.
- Task 9: Kotlin `FileNaming`, `TransferText`, `TransferRate`, passing the same shared vectors as Swift. Verified: `:core:test`.
- Task 10: Kotlin `TransferClient` (HEAD/PUT/GET with resume headers, running SHA-256, cancel → CancellationException, local vs network errors) and `FileEndpoint`; `MockMac` now speaks HTTP/1.1 like the real Mac. Verified: `TransferClientTest` against the mTLS MockWebServer.
- Task 11: `TransferEngine` receiving (offer → part → GET with resume → verify in either order → finish), replay, cancel, 24 h cleanup; `JsonFileTransferStore`, `FolderSaveTarget`. Verified: `TransferEngineReceiveTest`, `TransferStoreTest` (stable over 3 reruns).
- Task 12: `TransferEngine` sending: FIFO with 3 slots, HEAD-offset resume with prefix re-hash, straight-to-finish when the Mac has everything, re-offer on reconnect and on 404, retry, queued cancels. Verified: `TransferEngineSendTest` (stable over 3 reruns).
- Task 13: cross-platform test sends 24 MiB each way, drops the connection mid-file, and checks both resumed and match byte for byte; the file part runs inside the existing pairing test. Verified: `./scripts/cross-platform-test.sh`.
- Final review fixes: Mac claims incoming names case-insensitively (APFS), including two save panels picking the same file; Android `TransferEngine.load()` merges with files queued meanwhile; a resumed transfer waits for its cancelled predecessor to stop. Verified: new regression tests RED→GREEN, `swift test` 129, Android gates, cross-platform script.

## 2026-10-13 — File transfer review points resolved; Plan 2a (engine) written
- **What:**
  - The owner accepted the suggested answer for all 8 review points. They're folded into the spec, which gains a §8 list of rulings, and recorded in DECISIONS.md.
  - Three refinements came up while planning: an unknown re-offered id starts over instead of being rejected; downloads always make a `GET`, so `finish` is always sent; OkHttp uses 60 s read/write timeouts. They're in the spec (§2.2, §4.2, §8) and DECISIONS.md.
  - Split the plan, at the owner's request. Wrote Plan 2a, the engine (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md`, 14 tasks); Plan 2b (UI and platform glue) comes next.
  - Updated the README, ROADMAP and STATUS with the next steps.
- **Branch:** `main` (docs only).
- **Verified:** docs only; nothing to build.

## 2026-10-13 — File transfer spec reviewed; handoff made usable from Claude Desktop
- **What:**
  - Reviewed the Plan 2 spec against the code (`MacDroidServer`, `ConnectionHub`, `TlsClientFactory`, entitlements).
  - Recorded 8 open points, each with a suggested answer, in ROADMAP.md "Next": the Android default save folder (MediaStore vs SAF), share-target URI grants, Mac source bookmarks for resume after a relaunch, the resume handshake, the scope of the 3-transfer limit, the offer timeout, HTTP and naming edge cases, and plan-level details.
  - README: added a "Rules for whoever picks this up" section that repeats the key `CLAUDE.md` rules, because Claude Desktop doesn't load `CLAUDE.md` automatically. Updated the next action and the round table.
  - STATUS: "In flight" now points to the open points.
- **Why:** to settle these gaps before planning, and so any Claude session (Code or Desktop) can pick up from this folder alone.
- **Branch:** `main` (docs only).
- **Verified:** docs only; nothing to build.

## 2026-10-13 — File transfer spec (Plan 2) approved; roadmap reordered
- **What:**
  - Wrote `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`.
  - File transfer is now Plan 2, notifications Plan 3 and clipboard Plan 4.
  - Updated the handoff (README round table, ROADMAP "Next" with exact next steps, STATUS "In flight", DECISIONS).
- **Key choices:**
  - auto-save on receive
  - full resumable transfers
  - drag-drop and picker on the Mac, with the Finder Share extension deferred
  - Control Center-style popover
  - Android share target, Files tab, Photo Picker and SAF
- **Next:** write the implementation plan (writing-plans skill), then execute it subagent-driven.

## 2026-10-13 — App icons merged to main
- **What:** merged `feat/app-icons` into `main`.
- **Verified:** the owner checked the icon on the Mac and the phone. It shows normally in the Dock, with no grey tile.

## 2026-10-13 — App icons: the peeking droid
- **What:** the app icon for both apps is a minimal, flat green droid peeking over a MacBook lid, gripping the edge with two little hands, on a peach background. It is the name *Mac + Droid* as a character, chosen by the owner over generic sync or device glyphs.
  - **Source art:** `design/icon/` (`macdroid-icon-mac.svg`, `macdroid-icon-monochrome.svg`).
  - **Mac:**
    - `mac/MacDroid/Assets.xcassets/AppIcon.appiconset`: 10 PNGs, 16 to 1024 px on Apple's 824/1024 grid.
    - The PNGs are rendered by `scripts/render-icons.sh`, which uses `scripts/render-icon.swift` and AppKit's built-in SVG support.
    - `project.yml` sets `ASSETCATALOG_COMPILER_APPICON_NAME`.
  - **Android:**
    - An adaptive vector icon: background colour, a foreground scaled into the 66dp safe zone, and a monochrome layer for themed icons.
    - Manifest `icon`/`roundIcon`.
    - The status-bar icon `ic_stat_macdroid` is now the droid silhouette.
  - The menu-bar status icons are unchanged (they are SF Symbols that follow connection state).
- **Branch:** `feat/app-icons` (now merged).
- **Verified:**
  - Both apps build with no warnings, and the Android unit tests pass.
  - The icons render correctly (checked circle, squircle and themed masks).
  - Installed on the phone, and the Mac app relaunched. The owner confirmed it looks right.
- **Watch:** macOS 26 may put legacy (non-Icon-Composer) icons inside a grey tile. If so, add an Icon Composer `.icon` version.

## 2026-10-13 — UI/UX redesign merged to main
- **What:** merged `feat/ui-ux-redesign` into `main` (merge commit 1428d95).
- **Verified:** the owner ran the hands-on checklist on the Mac and the Nothing Phone (3), and everything passed.

## 2026-10-13 — Handoff folder and the keep-it-current rule
- **What:**
  - Added `CLAUDE.md` and `docs/handoff/` (README, ARCHITECTURE, STATUS, DEV-GUIDE, ROADMAP, DECISIONS, CHANGELOG).
  - Added the rule that every change updates the handoff in the same commit.
- **Why:** so any person or Claude Code session can see what has been built and why.
- **Branch:** `feat/ui-ux-redesign`.

## 2026-10-13 — UI/UX redesign (both apps)
- **Mac:**
  - Menu-bar popover plus a sidebar window (Phone, coming-soon Notifications/Clipboard/Files, Settings General/Security/About).
  - Relaunching the app opens the window. The Dock icon shows only while the window is open.
  - Pairing screen with a QR code, countdown, New code, an expired state and retry; "Pair a Phone" button after Forget.
  - Persistent warning banners.
- **Android:**
  - Material You theme.
  - Pairing-centred setup wizard (Welcome → Local network → Pair → Notifications → Battery).
  - 5-tab bottom navigation. Home shows a live status card: countdown, Retry now, Pair again, Connect.
  - Settings: keep-connected switch, permissions and battery rows, forget with confirmation.
- **Core changes:**
  - Additive only: `StatusPresentation.swift` (Mac) and `ConnectionState.Waiting.retryAtNanos` (Android).
  - Connection behaviour is unchanged.
- **Branch:** `feat/ui-ux-redesign`, commits 74d5cc1..ccf88a7. Every task was reviewed, the final review was clean, and all build gates are green.
- **Pending:** the owner's hands-on checklist, then merge to `main`.

## 2026-10-12 — Plan 1: protocol and connection core
- **Protocol:** shared protocol and fixtures; Swift and Kotlin codecs.
- **Mac:**
  - TLS identity in the Keychain.
  - Pairing manager: 120 s window, 128-bit code.
  - Bonjour.
  - Hummingbird mutual-TLS HTTPS/WS server and session hub.
  - Dev server.
- **Android:**
  - Pinned mutual-TLS OkHttp client.
  - Pairing client.
  - Connection manager with backoff, nudges and NSD locator.
  - Keystore identity, foreground service and boot receiver.
  - Basic UI.
- **Cross-platform test script:** the Swift server against the Kotlin client.
- **Merged to `main`** (merge commit 04a48a4). The owner verified pairing, reconnect, battery and forget/re-pair on the Nothing Phone (3).
