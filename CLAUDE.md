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
