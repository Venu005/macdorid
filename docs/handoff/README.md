# MacDroid handoff

Read this first if you're picking the project up, whether you're a person or a Claude Code session.

## What MacDroid is
A clone of **LinkMyMac** (Android ↔ Mac integration). The phone and Mac pair once by QR code, then stay connected over local Wi-Fi with mutual TLS. The full feature set is planned in rounds:

| Round | Scope | State |
|---|---|---|
| Plan 1 | Protocol + connection core: pairing, encrypted link, auto-reconnect, battery on Mac | **Done, merged to `main`** |
| UI/UX redesign | Native Mac (menu-bar popover + sidebar window) and Material 3 Android (setup wizard + 5 tabs) | **Done, merged to `main`** (verified by the owner) |
| Plan 2 | Android notifications mirrored to the Mac (reply, dismiss) | Not started |
| Plan 3 | Clipboard sync (Mac→phone automatic, phone→Mac on demand) | Not started |
| Plan 4 | File transfer both ways (HTTPS PUT/GET, resume, checksums) | Not started |
| Later | Photo gallery, SMS, screen mirroring, webcam/mic, USB transport | Out of scope so far |

## Documents
- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit, the trust model and the main components on each side.
- [STATUS.md](STATUS.md): what works, what's verified, and what's pending right now.
- [DEV-GUIDE.md](DEV-GUIDE.md): toolchains, build, test, run, device setup and known gotchas.
- [ROADMAP.md](ROADMAP.md): next rounds and the required groundwork for each.
- [DECISIONS.md](DECISIONS.md): the key decisions and why, so they don't get relitigated.
- [CHANGELOG.md](CHANGELOG.md): a dated log of every change; read the newest entries first.

## Source-of-truth documents
- Round-1 spec: [../superpowers/specs/2026-10-12-macdroid-round1-design.md](../superpowers/specs/2026-10-12-macdroid-round1-design.md). It covers all of round 1: connection, notifications, clipboard and files.
- UI redesign spec: [../superpowers/specs/2026-10-13-macdroid-ui-ux-redesign-design.md](../superpowers/specs/2026-10-13-macdroid-ui-ux-redesign-design.md)
- Protocol: [../../protocol/PROTOCOL.md](../../protocol/PROTOCOL.md) plus `protocol/fixtures/`
- Implementation plans: `docs/superpowers/plans/`. Each has a `*-followups.md` listing rulings and deferred items.

## Owner context
- Device: Nothing Phone (3), Android 17 (API 37), connected over adb **wireless debugging**. Mac: MacBook Air, macOS 26, Xcode 27.
- Goal: personal use first, then publish on the Mac App Store and Google Play. Design choices already respect sandbox and Play policy (see DECISIONS.md).
- Preference: native platform look (macOS-native on Mac, Material You on Android).
