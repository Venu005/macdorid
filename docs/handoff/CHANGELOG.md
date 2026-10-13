# Changelog

Newest first. Every change to the repo adds an entry here (see "Keep the handoff current" in `/CLAUDE.md`).

Format: `## YYYY-MM-DD — title`, then what changed, why, the branch or commits, and how it was verified.

## 2026-10-13 — Plan 2a: file transfer engine (in progress on feat/file-transfer)
- **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md` (engine); Plan 2b (UI and platform glue) follows.
- Task 1: `TrustedPeerMiddleware` is global; every route except `POST /v1/pair` (and the not-found responder) requires the paired phone. Verified: `swift test`, including `strangerIsForbiddenEverywhereExceptPair`.
- Task 2: `transfer.*` messages in both codecs with 7 fixtures; `PROTOCOL.md` documents them and the `/v1/files` routes. Verified: both suites.
- Task 3: `Broadcast` fan-out; `ConnectionHub.statusUpdates` is multi-subscriber (replays the current status), and the new `ConnectionHub.messages` forwards control messages. Verified: `swift test` and the app build.
- Task 4: Swift `FileNaming`, `ByteRanges`, `TransferText`; shared vectors `file-naming.json` and `transfer-text.json`. Verified: `swift test`.
- Task 5: transfer model, JSON stores, rate smoothing, and checksummed `PartFileWriter`/`SourceReader` (resume re-hash, session supersede, disk-full detection). Verified: `swift test`.
- Task 6: `TransferManager` receiving: offer → part file → verify → rename; re-offer resume; outcome replay; 24 h cleanup; history 100; queued cancels. Verified: `TransferReceiveTests`.

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
