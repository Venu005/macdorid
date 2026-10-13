# MacDroid handoff

> **Picking up?** The next action is in [ROADMAP.md](ROADMAP.md) under "Next":
> 1. File transfer (Plans 2a and 2b) is **merged to `main`**.
> 2. The owner verified the hands-on checklist except a 2 GB+ file (they'll check it later).
> 3. Plans 3 and 4 (notifications and clipboard) share one spec ([../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md](../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md), self-reviewed, fixes in §8). Both plans are written: [Plan 3](../superpowers/plans/2026-10-13-macdroid-notifications-3.md) (11 tasks) and [Plan 4](../superpowers/plans/2026-10-13-macdroid-clipboard-4.md) (11 tasks).
> 4. Next: owner reviews the plans → execute Plan 3 then Plan 4 subagent-driven on `feat/notifications-clipboard` → owner checklist → merge.

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
| Plan 3 | Android notifications mirrored to the Mac (reply, buttons, dismiss both ways, mutes) | Done on `feat/notifications-clipboard`; owner check after Plan 4 |
| Plan 4 | Clipboard sync (Mac→phone automatic, phone→Mac on demand, Mac history) | Spec + plan written; not started |
| Later | Photo gallery, SMS, screen mirroring, webcam/mic, USB transport | Out of scope so far |

## Documents
- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit, the trust model and the main components on each side.
- [STATUS.md](STATUS.md): what works, what's verified, and what's pending right now.
- [DEV-GUIDE.md](DEV-GUIDE.md): toolchains, build, test, run, device setup and known gotchas.
- [ROADMAP.md](ROADMAP.md): next rounds and the required groundwork for each.
- [DECISIONS.md](DECISIONS.md): the key decisions and why, so they don't get relitigated.
- [CHANGELOG.md](CHANGELOG.md): a dated log of every change; read the newest entries first.

## Source-of-truth documents
- **Notifications + clipboard spec (next up):** [../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md](../superpowers/specs/2026-10-13-macdroid-notifications-clipboard-design.md)
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
