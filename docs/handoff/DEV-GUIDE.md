# Developer guide

## Toolchains (owner's machine)
- macOS 26, Xcode 27 (Swift 6.4), XcodeGen (`brew install xcodegen`)
- JDK 21, Gradle 9.8 (wrapper committed), AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21
- Android SDK: `/opt/homebrew/share/android-commandlinetools` (platform 37). `android/local.properties` holds `sdk.dir` and is gitignored.

## Build and test
```bash
# Mac core (59 tests)
cd mac/MacDroidCore && swift test

# Mac app (generates MacDroid.xcodeproj, which is not committed)
cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build
open mac/build/Build/Products/Debug/MacDroid.app

# Android
cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug

# Swift server ↔ Kotlin client end to end (pairing, battery, and 24 MiB files both ways with a mid-file disconnect; ~1 min)
./scripts/cross-platform-test.sh
# The dev server alone: --receive-dir <dir> saves files from the phone there; --send-on-connect <file> sends that file when a phone connects
#   mac/MacDroidCore/.build/debug/macdroid-devserver --host 127.0.0.1 --port 47999 --receive-dir /tmp/in --send-on-connect /tmp/out.bin

# App icon: edit design/icon/*.svg, then regenerate the Mac PNGs (Android uses vector drawables directly)
./scripts/render-icons.sh
```

## Phone (wireless debugging)
1. On the phone: Settings → System → Developer options → Wireless debugging → *Pair device with pairing code*.
2. On the Mac: run `adb pair <ip>:<pairing-port>` and type the code yourself. Then run `adb connect <ip>:<port>`, using the port from the main screen.
3. `adb devices` may list the phone twice (IP and an `_adb-tls-connect` mDNS alias). Pick one with `ANDROID_SERIAL`.
4. On-device test: `./gradlew :app:connectedDebugAndroidTest` (KeystoreTlsTest). Gradle installs and then **uninstalls** a temporary build, so the app icon appears and disappears. The task may exit 1 even with 0 failures because of the duplicate adb entry; confirm with `adb shell am instrument`.
5. Don't `pm clear`, grant permissions or tap on the owner's phone from automation.

## Gotchas
- **App icon sources:** the source art is `design/icon/`.
  - Mac PNGs are generated, so don't hand-edit `AppIcon.appiconset`.
  - The Android vectors in `res/drawable/ic_launcher_*.xml` mirror the SVG paths (100-unit frame, scaled 0.72 and offset 18 into the 108dp canvas). Update both when the art changes.
  - AppKit's SVG renderer ignores filters such as drop shadows, so keep the art flat.
- **Hummingbird + tracing pin:** Hummingbird 2.27 imports `ServiceContextModule`, which swift-distributed-tracing 1.6 removed. `Package.swift` pins tracing `"1.3.0"..<"1.6.0"` and links the `Tracing` product to silence an unused-dependency warning. Keep both.
- **Shutdown log noise:** Hummingbird logs `Server shutdown error: Already closed` when its task is cancelled (tests, quit). It's harmless.
- **No client certs with macOS `curl`:** `/usr/bin/curl` (SecureTransport) can't present PEM client certificates. Use `openssl s_client -cert … -key …` or the Swift/Kotlin tests.
- **WS client SNI:** Swift `WebSocketClient` needs a hostname (`localhost`), not an IP literal.
- **Window not opened automatically:** in an `LSUIElement` app a SwiftUI `Window` scene is not opened at launch. `MenuBarLabel` (always alive) opens it via `WindowRequest`. `SceneBuilder` rejects `if #available`.
- **Hidden menu-bar icon:** on a MacBook with a notch it can be hidden. Launching the app again opens the window (`applicationShouldHandleReopen`).
- **Keychain prompt:** ad-hoc signed dev builds change signature on each rebuild, so macOS asks to allow keychain access. Click "Always Allow".
- **Android 17 local network:** apps targeting API 37 need runtime `ACCESS_LOCAL_NETWORK` before LAN sockets or NSD. Setup requests it first, and the service and boot receiver refuse to run without it.
- **NSD addresses:** `getHost()` on API 34+ is `hostAddresses[0]`. The locator scans every address for IPv4. `stopServiceResolution` exists only on 34+.
- **Gradle wrapper:** `gradle-wrapper.properties` has `validateDistributionUrl=false` (environment workaround). Restore validation and add `distributionSha256Sum` before publishing.
- **Scratch folder:** `.superpowers/` is gitignored scratch for agent workflows (ledgers, review packages, screenshots, brainstorm mockups).

- **Sandbox file access:** received files go to `~/Downloads/MacDroid` (Downloads entitlement) or a folder chosen in Settings (a security-scoped bookmark, kept open while the app runs). Outgoing files are remembered as read-only security-scoped bookmarks, so a resume survives a relaunch.
- **xcodebuild noise:** lines about CoreSimulator being out of date, `DVTCoreDeviceCore`, and `appintentsmetadataprocessor` are environment logs, not compiler output. Judge a build by `** BUILD SUCCEEDED **` and by `.swift:line:col: warning|error` lines.

- **Share target and URI grants:** shared URIs are handed to `ConnectionService` in an intent with `FLAG_GRANT_READ_URI_PERMISSION`. Files without a persistable grant are copied to `cache/outgoing/` only up to 100 MB, and the copies are deleted when the transfer finishes.

## Conventions
- Swift Testing (`@Test`, `#expect`) on the Mac and JUnit4 on Android; TDD for logic.
- Pure presentation and state logic lives in testable types; views stay thin.
- Commit messages are conventional style (`feat(mac): …`, `fix(android): …`).
