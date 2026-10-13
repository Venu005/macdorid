<p align="center">
  <img src="design/icon/macdroid-icon-mac.svg" width="128" alt="MacDroid icon: a green droid peeking over a MacBook lid">
</p>

<h1 align="center">MacDroid</h1>

<p align="center">
  Your Android phone and your Mac, working together over your own Wi-Fi.<br>
  No account, no cloud — the two devices talk directly, end-to-end encrypted.
</p>

---

MacDroid is a pair of native apps: a **Mac menu-bar app** and an **Android app**. You pair them once by scanning a QR code. After that they find each other on your local network and stay connected in the background, so you can see your phone's battery on the Mac and send files either way. Phone notifications and a shared clipboard come in later updates.

It's built first for personal use (developed on a Nothing Phone (3) running Android 17 and a MacBook Air running macOS 26). It's designed to be publishable on the Mac App Store and Google Play: the Mac app is sandboxed, and the Android app uses only Play-friendly permissions.

## Features

| | Status |
|---|---|
| **Pairing** by QR code (or manual entry with fingerprint check), forget & re-pair on either side | ✅ |
| **Always-on connection** over local Wi-Fi with auto-reconnect (Wi-Fi drops, sleep/wake, reboots) | ✅ |
| **Phone battery** level and charging state in the Mac menu bar | ✅ |
| **File transfer, both ways** — resumable, SHA-256 checked, up to 3 at once | ✅ |
| Android notifications mirrored to the Mac (reply, dismiss) | Planned (Plan 3) |
| Clipboard sync (Mac → phone automatic, phone → Mac on demand) | Planned (Plan 4) |
| "Share → Send to phone" from Finder | Planned |

### File transfer

**From the Mac:**
- Drop files on the menu-bar popover or the Files page, or use **Send Files…**.
- See progress, speed and time left. Cancel or **Retry** from the list.

**From the phone:**
- Choose **Send to Mac** in any app's share sheet.
- Or use the **Files** tab or Home's **Send** button, which open the system Photo Picker or file picker.

**Receiving:**
- Files save automatically: `~/Downloads/MacDroid` on the Mac and `Download/MacDroid` on the phone.
- Each side has its own setting to pick another folder or to **ask where to save each time**.
- A notification tells you a file arrived, with **Open** (and **Show in Finder** on the Mac).

**Reliability:**
- Transfers survive Wi-Fi drops, sleep and app restarts. They pause, then resume from the bytes already on disk.
- Every file is verified with a SHA-256 checksum before it's moved into place. Partial files are named `*.macdroid-part` until then.
- Incoming file names are sanitised (no path traversal, no hidden files, safe characters). Clashes get ` (1)`, ` (2)`, …
- A received file you later deleted shows **"Deleted from this Mac/phone"** instead of failing to open.

## How it works

```
 Android phone (always the client)                         Mac (the server, advertised as _macdroid._tcp)
 ─────────────────────────────────                         ──────────────────────────────────────────────
 finds the Mac via NSD / Bonjour  ─────────────────────────▶  BonjourAdvertiser
 OkHttp, mutual TLS 1.3, pinned:
   WSS  /v1/ws             (control messages, JSON)  ─────▶  ConnectionHub
   POST /v1/pair           (only while pairing is open) ──▶  PairingManager
   HEAD/PUT/GET /v1/files/{id}  (file bytes, resumable) ──▶  TransferManager
```

- **The Mac is the server; the phone is always the client.** The phone never listens on a port, which keeps Android's background rules simple.
- **Control** messages (hello, battery, `transfer.offer/accept/finish/result/…`) travel as JSON over a WebSocket. **File bytes** go over HTTPS. Uploads resume with `HEAD` + `Content-Range`; downloads resume with `Range`.
- **The wire format is a contract.** It's defined in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md), with JSON fixtures that both the Swift and the Kotlin test suites decode and re-encode.

## Security and privacy

- **Nothing leaves your network.** There's no account, no server and no analytics.
- **Mutual TLS 1.3** protects everything, including file contents. Each device has its own long-lived key (Mac: Keychain; phone: Android Keystore, where the key never leaves the hardware).
- **Certificate pinning on both sides.** Each device trusts only the other's SHA-256 certificate fingerprint, learned once during pairing.
- **Pairing** uses a QR code with a 128-bit one-time code, valid for 120 seconds. The Mac stores the phone's fingerprint from the TLS session itself, never from anything the phone claims.
- **Only the paired phone gets in.** Every HTTP route except pairing rejects any other certificate (403). Transfer ids are random, and ids can't be probed across directions (404).
- **Sandbox-friendly file access.**
  - Mac: it reads only the files you pick or drop, and writes only to Downloads or a folder you choose.
  - Android: it uses the Photo Picker, the system file pickers and MediaStore, with no broad storage permission.

## Requirements

| | |
|---|---|
| Mac | macOS 14 or later |
| Android | Android 10 (API 29) or later; on Android 17 the app asks for the *local network* permission first |
| Network | Both devices on the same local network |

## Building and running

**Toolchain** (what the project is developed with):
- Xcode 27 (Swift 6.4) and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)
- JDK 21 and the Android SDK (platform 37)

The Gradle wrapper is committed.

### Mac app

```bash
cd mac
xcodegen generate                      # creates MacDroid.xcodeproj (not committed)
xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build
open build/Build/Products/Debug/MacDroid.app
```

MacDroid lives in the menu bar. Open it again (or press ⌘O in the popover) to show its window.

Development builds are ad-hoc signed, which has two side effects:
- macOS asks for keychain access after each rebuild. Choose **Always Allow**.
- Files queued before a rebuild may not resume after it.

### Android app

Create `android/local.properties` with `sdk.dir=/path/to/android/sdk`, then:

```bash
cd android
./gradlew :app:assembleDebug
ANDROID_SERIAL=<device> ./gradlew :app:installDebug
```

### Pair them

1. On the Mac, open MacDroid → **Phone** → **Pair a Phone** to show a QR code.
2. On the phone, open MacDroid and follow the setup: allow local network access, then scan the code.
3. You're done: the Mac shows your phone's battery, and the **Files** pages on both sides can send files.

## Tests

```bash
# Mac core: protocol, pairing, server, transfer engine (Swift Testing)
cd mac/MacDroidCore && swift test

# Android: core library and app unit tests, plus a debug build
cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

# End to end: the real Swift server against the real Kotlin client.
# It pairs, then sends 24 MiB each way, cuts the connection mid-file,
# and checks both files resume and match byte for byte.
./scripts/cross-platform-test.sh
```

**On-device tests** (Keystore TLS, MediaStore part files) live in `android/app/src/androidTest`. Run them with `am instrument` as described in the [developer guide](docs/handoff/DEV-GUIDE.md). Gradle's `connectedDebugAndroidTest` uninstalls the app afterwards, which loses your pairing.

All builds are expected to pass with **no warnings**.

## Project layout

```
mac/
  MacDroidCore/          Swift package with all testable Mac logic: protocol, TLS identity, pairing,
                         Bonjour, Hummingbird HTTPS/WebSocket server, connection hub, transfer engine
  MacDroid/              SwiftUI app (menu-bar popover + window); views stay thin
  project.yml            XcodeGen spec
android/
  core/                  Pure Kotlin/JVM: protocol, pinned mTLS client, pairing, connection manager,
                         transfer client and engine
  app/                   Android glue and Jetpack Compose UI (Material You)
protocol/                PROTOCOL.md and the shared JSON fixtures both test suites use
scripts/                 Cross-platform test and icon rendering
design/icon/             App icon source art (SVG)
docs/handoff/            Living project docs: status, architecture, decisions, dev guide, changelog
docs/superpowers/        Design specs, implementation plans and follow-up lists
```

## Documentation

- [**Handoff**](docs/handoff/README.md): start here to work on the project.
  - [Status](docs/handoff/STATUS.md)
  - [Architecture](docs/handoff/ARCHITECTURE.md)
  - [Developer guide](docs/handoff/DEV-GUIDE.md)
  - [Roadmap](docs/handoff/ROADMAP.md)
  - [Decisions](docs/handoff/DECISIONS.md)
  - [Changelog](docs/handoff/CHANGELOG.md)
- [**Protocol**](protocol/PROTOCOL.md): every message and HTTP route.
- [**Design specs**](docs/superpowers/specs/): round 1, the UI redesign and file transfer.

## Roadmap

1. ✅ Connection core: pairing, encrypted link, auto-reconnect, battery
2. ✅ Native UI (macOS menu bar + window, Material You on Android) and app icons
3. ✅ File transfer both ways
4. ⏭ Notifications mirrored to the Mac, with reply and dismiss
5. Clipboard sync
6. Finder "Share → Send to phone"
7. Later: photo gallery, SMS, screen mirroring, phone as webcam, USB transport

## Built with

- **Mac:** Swift, SwiftUI, [Hummingbird 2](https://github.com/hummingbird-project/hummingbird), SwiftNIO and NIOSSL, swift-certificates, swift-crypto
- **Android:** Kotlin, Jetpack Compose (Material 3), OkHttp, kotlinx.serialization, kotlinx.coroutines, CameraX and ML Kit (QR scanning)
