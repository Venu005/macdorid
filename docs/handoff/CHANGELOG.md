# Changelog

Newest first. Every change to the repo adds an entry here (see "Keep the handoff current" in `/CLAUDE.md`).

Format: `## YYYY-MM-DD — title`, then what changed, why, the branch or commits, and how it was verified.

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
