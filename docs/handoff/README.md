# MacDroid handoff

> **Picking up?** The next action is in [ROADMAP.md](ROADMAP.md) under "Next":
> 1. File transfer (Plan 2), notifications (Plan 3) and clipboard (Plan 4) are all **merged to `main`** (version 0.4.0).
> 2. Still open: the 2 GB+ file-transfer check, and the 9 deferred minors in `docs/superpowers/plans/2026-10-13-macdroid-notifications-clipboard-followups.md`.
> 3. Small wins (Plan 5) and photos (Plan 6) are merged to `main`. The 9 photos minors are fixed and merged (`2807ce9`). Screen mirroring and remote control (Plans 7 and 8) are merged too (`75ac85b`); their minors are in `docs/superpowers/plans/2026-10-14-macdroid-mirroring-followups.md`.
> 4. The Finder Share extension is parked (see ROADMAP; one open question). Next options: a planning session to rank the later rounds (photo gallery, screen mirroring, SMS, webcam, USB), or the deferred minors.

Read this first if you're picking the project up, whether you're a person, a Claude Code session or a Claude Desktop session.

## Rules for whoever picks this up
These rules are also in the repo's `/CLAUDE.md`. Claude Desktop doesn't load that file automatically, so they're repeated here.
- **Keep this folder current.** Every change, including docs-only changes and decisions, updates `docs/handoff/` in the same commit: always a dated entry at the top of [CHANGELOG.md](CHANGELOG.md), plus whichever of STATUS, ARCHITECTURE, DEV-GUIDE, ROADMAP, DECISIONS and this README the change affects.
- **The protocol is a contract.** `protocol/PROTOCOL.md` and `protocol/fixtures/` change together with both codecs (Swift and Kotlin), and both test suites must pass.
- **Don't weaken security.** Mutual TLS 1.3 with fingerprint pinning on both sides. The Mac takes the phone's fingerprint from the TLS session, never from a request body. Unpaired certificates may only reach `POST /v1/pair`, and only while the pairing window is open.
- **Process:** brainstorm → spec → plan → subagent-driven execution with per-task review → owner's hands-on check → merge.
- **Build gates** (all must pass with no warnings): see [DEV-GUIDE.md](DEV-GUIDE.md).

## What MacDroid is
A clone of **LinkMyMac** (Android ↔ Mac integration). The phone and Mac pair once by QR code, then stay connected over local Wi-Fi with mutual TLS. The full feature set is planned in rounds:

| Round | Scope | State |
|---|---|---|
| Plan 1 | Protocol + connection core: pairing, encrypted link, auto-reconnect, battery on Mac | **Done, merged to `main`** |
| UI/UX redesign | Native Mac (menu-bar popover + sidebar window) and Material 3 Android (setup wizard + 5 tabs) | **Done, merged to `main`** (verified by the owner) |
| App icons | Peeking-droid icon for both apps | **Done, merged to `main`** |
| Plan 2 | File transfer both ways (drag-drop, share sheet, resume, checksums) | **Done, merged to `main`** (owner-verified; the 2 GB+ check is still to do) |
| Plan 3 | Android notifications mirrored to the Mac (reply, buttons, dismiss both ways, mutes) | **Done, merged to `main`** (owner-verified) |
| Plan 4 | Clipboard sync (Mac→phone automatic, phone→Mac on demand, Mac history) | **Done, merged to `main`** (owner-verified) |
| Plan 5 | Small wins: ring my phone, open links on the other device, media controls | **Done, merged to `main`** (owner-tested) |
| Plan 6 | Photos: browse the phone's photos and videos on the Mac (albums, day grid, Quick Look, Save to Mac, drag out), full or limited access | **Done, merged to `main`** (follow-up minors merged too) |
| Plans 7–8 | Screen mirroring with phone audio (own window, 50–80 ms target) and remote control via an accessibility service | **Done, merged to `main`** (2026-10-15, `75ac85b`); owner tried it; checklist and minors in the follow-ups file |
| Plan 10 | Calls on the Mac: incoming call panel (Answer, Decline, Hang up), number pad and `tel:` links (audio stays on the phone) | **Done, merged to `main`** (2026-10-15, `c9ae68f`); dialing and links owner-tried; Answer/Decline/Hang up still to confirm |
| Plan 11 | Backlog clean-up: every open deferred minor plus cheap hardening (backup exclusions, update restart, Gradle checksum, keychain, Bonjour) | **In progress** on `fix/backlog` |
| Later | SMS, screen mirroring, webcam/mic, USB transport | Out of scope so far |

## Documents
- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit, the trust model and the main components on each side.
- [STATUS.md](STATUS.md): what works, what's verified, and what's pending right now.
- [DEV-GUIDE.md](DEV-GUIDE.md): toolchains, build, test, run, device setup and known gotchas.
- [ROADMAP.md](ROADMAP.md): next rounds and the required groundwork for each.
- [DECISIONS.md](DECISIONS.md): the key decisions and why, so they don't get relitigated.
- [CHANGELOG.md](CHANGELOG.md): a dated log of every change; read the newest entries first.

## Source-of-truth documents
- **Notifications + clipboard spec:** [../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md](../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md)
- **File transfer spec:** [../superpowers/specs/2026-10-13-macdroid-file-transfer-design.md](../superpowers/specs/2026-10-13-macdroid-file-transfer-design.md)
- Round-1 spec: [../superpowers/specs/2026-10-12-macdroid-round1-design.md](../superpowers/specs/2026-10-12-macdroid-round1-design.md). It covers all of round 1: connection, notifications, clipboard and files.
- UI redesign spec: [../superpowers/specs/2026-10-13-macdroid-ui-ux-redesign-design.md](../superpowers/specs/2026-10-13-macdroid-ui-ux-redesign-design.md)
- Protocol: [../../protocol/PROTOCOL.md](../../protocol/PROTOCOL.md) plus `protocol/fixtures/`
- **File transfer plans (done):** [2a, engine](../superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md), [2b, UI and platform glue](../superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md), and [follow-ups with the open hands-on checklist](../superpowers/plans/2026-10-13-macdroid-file-transfer-followups.md).
- Implementation plans: `docs/superpowers/plans/`. Each has a `*-followups.md` listing rulings and deferred items.

## Owner context
- Device: Nothing Phone (3), Android 17 (API 37), connected over adb **wireless debugging**. Mac: MacBook Air, macOS 26, Xcode 27.
- Goal: personal use first, then publish on the Mac App Store and Google Play. Design choices already respect sandbox and Play policy (see DECISIONS.md).
- Preference: native platform look (macOS-native on Mac, Material You on Android).
