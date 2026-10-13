# MacDroid

A LinkMyMac-style app pair: a sandboxed **Mac menu-bar app** and an **Android app** that pair by QR code and talk directly over local Wi-Fi. No account, no cloud. Built first for personal use (Nothing Phone (3), Android 17), designed to be publishable later (Mac App Store, Google Play).

**Start here:** read [docs/handoff/README.md](docs/handoff/README.md). It links to architecture, current status, the dev guide, the roadmap and the decision log.

## Repo map
- `mac/MacDroidCore/`: Swift package with all testable Mac logic (protocol, TLS identity, pairing, Bonjour, server, session hub, presentation). Run tests with `swift test`.
- `mac/MacDroid/`: SwiftUI app target (UI only). `mac/project.yml` is the XcodeGen spec. The `.xcodeproj` is generated and not committed.
- `android/core/`: pure Kotlin/JVM module (protocol, pinned mTLS client, pairing, connection manager). Run `./gradlew :core:test`.
- `android/app/`: Android glue and Compose UI.
- `protocol/`: `PROTOCOL.md` plus JSON fixtures that both Swift and Kotlin tests decode. This is the single source of truth for the wire format.
- `docs/superpowers/specs|plans/`: design specs, implementation plans and follow-up lists.
- `scripts/cross-platform-test.sh`: the real Swift server against the real Kotlin client.

## Keep the handoff current (required)
Every change to this repo must update `docs/handoff/` in the **same commit or PR** as the change. That covers features, fixes, refactors, dependency or tooling changes, and decisions. Someone opening the folder should always be able to tell what has been built.
- **CHANGELOG.md:** always add a dated entry at the top with what changed, why, the branch, and how it was verified.
- **STATUS.md:** update it when something becomes working, verified, pending or known-broken, and when branches are merged or created.
- **ARCHITECTURE.md:** update it when components, files, message types, routes, the trust model or extension points change.
- **DEV-GUIDE.md:** update it when build, test or run steps, toolchains, device setup or gotchas change.
- **ROADMAP.md:** update it when a round starts or finishes, or scope changes.
- **DECISIONS.md:** update it when a meaningful design decision or ruling is made, with the reason and the rejected alternatives.
- **README.md:** update the round table when a round's state changes.

A change isn't done until the handoff reflects it. If nothing in the handoff needs to change, still add the CHANGELOG entry.

## Rules of the road
- The protocol is a contract. Changing a message means updating `protocol/fixtures` and both codecs, and both test suites must pass.
- Keep logic in `MacDroidCore` / `android/core` (tested) and views thin.
- Security model:
  - Mutual TLS 1.3, with each side pinning the other's SHA-256 certificate fingerprint.
  - The Mac stores the phone's fingerprint from the TLS session, never from a request body.
  - Unpaired certificates may only reach `POST /v1/pair`, and only while the pairing window is open.
  - Don't weaken any of this.
- Android 17 requires the `ACCESS_LOCAL_NETWORK` runtime permission before any LAN socket or NSD.
- Workflow used so far (superpowers skills): brainstorm → spec → plan → subagent-driven execution with per-task review. New features follow the same path.
- Build gates:
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build`
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`

  All must pass with no warnings.
