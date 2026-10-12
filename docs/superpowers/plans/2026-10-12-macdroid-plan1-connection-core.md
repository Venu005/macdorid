# MacDroid Plan 1 — Protocol & Connection Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sandboxed Mac menu-bar app and an Android app that pair via QR code, then hold a mutually-authenticated TLS 1.3 WebSocket connection that auto-reconnects and shows the phone's battery on the Mac.

**Architecture:** The Mac runs a Hummingbird 2 HTTPS + WebSocket server (mutual TLS, certificate pinning by SHA-256 fingerprint) and advertises it over Bonjour. The Android phone is always the client: OkHttp with a pinned trust manager and a client certificate whose key lives in the Android Keystore. Shared JSON fixtures in `protocol/fixtures` keep the Swift and Kotlin codecs identical. This is Plan 1 of 4 for round 1; Plans 2–4 (notifications, clipboard, file transfer) build on it.

**Tech Stack:**
- **Mac:** Swift 6, SwiftUI, Hummingbird 2.27 + HummingbirdWebSocket 2.8, swift-certificates, swift-crypto, dnssd, XcodeGen.
- **Android:** Kotlin 2.4.21, AGP 9.4.1 (built-in Kotlin), Gradle 9.8, Jetpack Compose (BOM 2026.09.00), OkHttp 5.5, kotlinx.serialization 1.11, CameraX 1.6.2, ML Kit barcode 17.3.0.

**Spec:** `docs/superpowers/specs/2026-10-12-macdroid-round1-design.md`

## Global Constraints

- **macOS:** minimum macOS 14; the app is sandboxed (`com.apple.security.app-sandbox`, `network.server`, `network.client`).
- **Mac Info.plist:** `NSLocalNetworkUsageDescription`, `NSBonjourServices = [_macdroid._tcp]`, `LSUIElement = YES`.
- **Android:** minSdk 29 (Android 10), compileSdk/targetSdk 37 (Android 17).
- **Android permissions:**
  - `ACCESS_LOCAL_NETWORK` is requested at runtime on API 37+ before pairing.
  - Never use SMS permissions, `MANAGE_EXTERNAL_STORAGE`, Accessibility, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **TLS:** TLS 1.3 only, mutual TLS on every connection. Each side trusts a peer only when the SHA-256 fingerprint of its leaf certificate (lowercase hex of the DER bytes) matches the stored value.
- **Pairing:**
  - The pairing window lasts 120 s.
  - The pairing code is 16 random bytes encoded as RFC 4648 base32 with no padding (26 characters).
  - Codes are compared in constant time.
  - The Mac stores the phone fingerprint taken from the TLS session, never from the request body.
  - Unpaired certificates may reach only `POST /v1/pair`, and only while the window is open.
- **Bonjour:** service type `_macdroid._tcp`, TXT records `v=1` and `fp=<first 16 hex of the Mac fingerprint>`.
- **Messages:** WebSocket text frames shaped `{"v":1,"type":…,"id":…,"body":{…}}`.
  - Unknown types are ignored.
  - Malformed frames are dropped without closing the connection.
- **Liveness:** WebSocket pings every 15 s from both sides.
- **Reconnect backoff:** starts at 1 s, doubles, caps at 30 s. It resets after a successful connection, on a network change, or when NSD finds the Mac at a new address.
- **Identifiers:**
  - Mac bundle id: `dev.macdroid.mac`
  - Android application id: `dev.macdroid.app`
  - Kotlin core package: `dev.macdroid.core`
- **Hummingbird pin:** the Swift package must pin `swift-distributed-tracing` to `"1.3.0"..<"1.6.0"`. Hummingbird 2.27.1 imports `ServiceContextModule`, which tracing 1.6.0 removed (verified: the build fails without the pin).

## Review Focus

These are the inputs most likely to hurt a real user that the spec implies but does not spell out. Each has a pinning test in the task named.

1. **The Mac changes IP or port** (DHCP lease, or a restart picks a new ephemeral port). The phone must rediscover it via NSD, connect to the new address, and persist it. → Task 11, `movesToAddressReportedByLocator`.
2. **A pairing code is reused** after pairing completed, or after expiry. It must be refused (403) and must not replace the paired phone. → Task 3, `codeCannotBeReusedAfterPairing`, and Task 6, `pairingRejectedAfterWindowCloses`.
3. **The phone re-pairs with a new key** (app reinstalled, so a new Keystore key). The new fingerprint replaces the old one, and the old certificate is rejected. → Task 3, `repairingReplacesOldFingerprint`.
4. **The Mac has several interfaces** (VPN `utun`, `bridge100`, loopback, link-local). The QR code must carry the LAN address. → Task 4, `prefersEthernetLikeInterface` / `skipsLinkLocal`.
5. **The phone reconnects before the Mac notices the old socket died.** The newest session wins, and the old session ending must not flip the status to disconnected. → Task 5, `newSessionReplacesOldWithoutFlicker`.

---

## File Structure

```
macdroid/
  .gitignore                                   (modify: Xcode project + build dirs)
  protocol/
    PROTOCOL.md                                transport, endpoints, envelope, message table
    fixtures/hello.json                        canonical messages shared by Swift + Kotlin tests
    fixtures/device.status.json
    fixtures/unknown-type.json
    fixtures/pair.request.json
    fixtures/pair.response.json
    fixtures/pairing-uri.txt                   exact QR URI both sides must agree on
  scripts/cross-platform-test.sh               dev server + Kotlin client end-to-end test
  mac/
    project.yml                                XcodeGen spec for the app target
    MacDroidCore/                              Swift package (all testable logic)
      Package.swift
      Sources/MacDroidCore/Protocol/Messages.swift        message structs + Message enum
      Sources/MacDroidCore/Protocol/MessageCodec.swift    JSON envelope encode/decode
      Sources/MacDroidCore/Security/Identity.swift        P-256 key + self-signed cert, Fingerprint helpers
      Sources/MacDroidCore/Security/IdentityStore.swift   Keychain + in-memory identity storage
      Sources/MacDroidCore/Pairing/PairedDeviceStore.swift  persisted paired phone
      Sources/MacDroidCore/Pairing/PairingManager.swift   window/code/trust state machine (actor)
      Sources/MacDroidCore/Pairing/PairingInvite.swift    QR URI builder
      Sources/MacDroidCore/Network/LocalAddress.swift     pick LAN IPv4 for the QR
      Sources/MacDroidCore/Network/BonjourAdvertiser.swift  dnssd registration
      Sources/MacDroidCore/Session/ConnectionHub.swift    one phone session: hello, status, send
      Sources/MacDroidCore/Server/MacDroidServer.swift    Hummingbird mTLS server, routes, WS bridge
      Sources/MacDroidDevServer/main.swift                headless server for cross-platform tests
      Tests/MacDroidCoreTests/TestSupport.swift           fixtures loader, FakeClock, eventually()
      Tests/MacDroidCoreTests/ProtocolFixtureTests.swift
      Tests/MacDroidCoreTests/IdentityTests.swift
      Tests/MacDroidCoreTests/PairingManagerTests.swift
      Tests/MacDroidCoreTests/PairingInviteTests.swift
      Tests/MacDroidCoreTests/NetworkTests.swift
      Tests/MacDroidCoreTests/ConnectionHubTests.swift
      Tests/MacDroidCoreTests/ServerTests.swift
    MacDroid/                                  app target (UI only)
      MacDroidApp.swift  AppModel.swift  MenuContentView.swift  SettingsView.swift  PairingView.swift  QRCode.swift
  android/
    settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradle/wrapper/*
    core/                                      pure Kotlin/JVM module (all testable logic)
      build.gradle.kts
      src/main/kotlin/dev/macdroid/core/protocol/Messages.kt
      src/main/kotlin/dev/macdroid/core/protocol/MessageCodec.kt
      src/main/kotlin/dev/macdroid/core/tls/Fingerprint.kt
      src/main/kotlin/dev/macdroid/core/tls/TrustManagers.kt     pinned + capturing trust managers
      src/main/kotlin/dev/macdroid/core/tls/TlsClientFactory.kt  ClientIdentity, key manager, OkHttp builder
      src/main/kotlin/dev/macdroid/core/tls/TrustFailures.kt
      src/main/kotlin/dev/macdroid/core/pairing/PairingInvite.kt
      src/main/kotlin/dev/macdroid/core/pairing/PairingStore.kt
      src/main/kotlin/dev/macdroid/core/pairing/PairingClient.kt
      src/main/kotlin/dev/macdroid/core/connection/Backoff.kt
      src/main/kotlin/dev/macdroid/core/connection/MacLocator.kt
      src/main/kotlin/dev/macdroid/core/connection/ConnectionManager.kt
      src/test/kotlin/dev/macdroid/core/…        one test file per source file + TestSupport.kt
    app/                                       Android-only glue + UI
      build.gradle.kts
      src/main/AndroidManifest.xml
      src/main/res/drawable/ic_stat_macdroid.xml
      src/main/java/dev/macdroid/app/MacDroidApp.kt        Application + AppGraph
      src/main/java/dev/macdroid/app/platform/KeystoreIdentity.kt
      src/main/java/dev/macdroid/app/platform/PrefsPairingStore.kt
      src/main/java/dev/macdroid/app/platform/NsdMacLocator.kt
      src/main/java/dev/macdroid/app/platform/DeviceInfo.kt
      src/main/java/dev/macdroid/app/service/ConnectionService.kt  (+ NetworkWatcher, BatteryMonitor, BootReceiver, Notifications, StatusText)
      src/main/java/dev/macdroid/app/ui/MainActivity.kt  OnboardingScreens.kt  QrScanner.kt  HomeScreen.kt  PairingViewModel.kt
      src/test/java/dev/macdroid/app/StatusTextTest.kt
      src/androidTest/java/dev/macdroid/app/KeystoreTlsTest.kt
```

---

## Task 1: Protocol fixtures and Swift message codec

**Files:**
- Create: `protocol/PROTOCOL.md`, `protocol/fixtures/{hello.json,device.status.json,unknown-type.json,pair.request.json,pair.response.json,pairing-uri.txt}`
- Create: `mac/MacDroidCore/Package.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift`, `.../Protocol/MessageCodec.swift`
- Create: `mac/MacDroidCore/Tests/MacDroidCoreTests/TestSupport.swift`, `.../ProtocolFixtureTests.swift`
- Modify: `.gitignore`

**Interfaces:**
- Produces:
  - `ProtocolInfo.version: Int` (= 1)
  - `ProtocolInfo.serviceType: String` (= `"_macdroid._tcp"`)
  - Structs: `Hello(protocol:app:device:features:)`, `DeviceStatus(battery:charging:)`, `PairRequest(code:deviceName:model:)`, `PairResponse(macName:)`, `ServerInfo(macName:protocol:)`
  - `enum Message { case hello(Hello), deviceStatus(DeviceStatus), unknown(type: String) }`
  - `MessageCodec.encode(_ message: Message, id: String = …) throws -> String`
  - `MessageCodec.decode(_ text: String) throws -> Message`
  - `MessageCodecError.malformed`, `.cannotEncodeUnknown(String)`
  - Test helpers: `Fixtures.text(_:)`, `Fixtures.jsonObject(_:)`, `Fixtures.id(of:)`, `FakeClock`, `eventually(timeout:_:)`

- [ ] **Step 1: Create the shared fixtures and protocol doc**

`protocol/fixtures/hello.json`:
```json
{
  "v": 1,
  "type": "hello",
  "id": "6f1c2a0e-0000-4000-8000-000000000001",
  "body": { "protocol": 1, "app": "0.1.0", "device": "Nothing Phone (3)", "features": [] }
}
```

`protocol/fixtures/device.status.json`:
```json
{
  "v": 1,
  "type": "device.status",
  "id": "6f1c2a0e-0000-4000-8000-000000000002",
  "body": { "battery": 82, "charging": true }
}
```

`protocol/fixtures/unknown-type.json`:
```json
{
  "v": 1,
  "type": "future.thing",
  "id": "6f1c2a0e-0000-4000-8000-000000000003",
  "body": { "anything": 1 }
}
```

`protocol/fixtures/pair.request.json`:
```json
{ "code": "MZXW6YTBOI2DGNBVGY3TQOJQGE", "deviceName": "My Phone", "model": "Nothing Phone (3)" }
```

`protocol/fixtures/pair.response.json`:
```json
{ "macName": "MacBook Pro" }
```

`protocol/fixtures/pairing-uri.txt` (single line, no trailing spaces):
```
macdroid://pair?host=192.168.1.20&port=47999&fp=8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a&code=MZXW6YTBOI2DGNBVGY3TQOJQGE&name=Sam%27s%20Mac%2BPro%20%E2%9C%A8
```

`protocol/PROTOCOL.md`:
````markdown
# MacDroid Protocol (v1)

The Mac is the server; the phone is always the client. Everything runs over TLS 1.3 with mutual
authentication. Each side pins the other's certificate by SHA-256 fingerprint (lowercase hex of
the DER-encoded leaf certificate). Fixtures in `fixtures/` are canonical: both apps' tests decode
and re-encode them.

## Discovery
Bonjour/DNS-SD service `_macdroid._tcp`. TXT: `v=1`, `fp=<first 16 hex chars of Mac fingerprint>`.

## Pairing QR
`macdroid://pair?host=<ipv4>&port=<port>&fp=<64 hex>&code=<base32>&name=<Mac name>`
Values are percent-encoded; everything outside `A-Z a-z 0-9 - . _ ~` is encoded (so `+` is `%2B`).
See `fixtures/pairing-uri.txt`.

## HTTP endpoints
| Method | Path | Who | Body |
|---|---|---|---|
| POST | `/v1/pair` | any client cert, only while the pairing window is open | `pair.request.json` → 200 `pair.response.json`, 403 if code wrong/expired |
| GET | `/v1/info` | paired phone only | `{"macName": "...", "protocol": 1}` |
| GET (upgrade) | `/v1/ws` | paired phone only | WebSocket control channel |

## Control channel
Text frames: `{"v":1,"type":"<type>","id":"<uuid>","body":{...}}`. Unknown types are ignored;
malformed frames are dropped without closing the connection. Both sides send `hello` first.

| Type | Direction | Body |
|---|---|---|
| `hello` | both | `protocol`, `app`, `device`, `features[]` |
| `device.status` | phone → Mac | `battery` (0–100), `charging` |

Notification, clipboard and transfer messages are added by later plans (see the round-1 spec §4.4).
````

- [ ] **Step 2: Create the Swift package manifest and gitignore entries**

`mac/MacDroidCore/Package.swift`:
```swift
// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "MacDroidCore",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "MacDroidCore", targets: ["MacDroidCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/hummingbird-project/hummingbird.git", from: "2.27.0"),
        .package(url: "https://github.com/hummingbird-project/hummingbird-websocket.git", from: "2.8.0"),
        .package(url: "https://github.com/hummingbird-project/swift-websocket.git", from: "1.6.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.80.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.30.0"),
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.21.0"),
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"6.0.0"),
        // Hummingbird 2.27.1 imports ServiceContextModule, which swift-distributed-tracing 1.6.0 removed.
        .package(url: "https://github.com/apple/swift-distributed-tracing.git", "1.3.0"..<"1.6.0"),
    ],
    targets: [
        .target(
            name: "MacDroidCore",
            dependencies: [
                .product(name: "Hummingbird", package: "hummingbird"),
                .product(name: "HummingbirdTLS", package: "hummingbird"),
                .product(name: "HummingbirdWebSocket", package: "hummingbird-websocket"),
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOConcurrencyHelpers", package: "swift-nio"),
                .product(name: "NIOSSL", package: "swift-nio-ssl"),
                .product(name: "X509", package: "swift-certificates"),
                .product(name: "Crypto", package: "swift-crypto"),
            ]
        ),
        .testTarget(
            name: "MacDroidCoreTests",
            dependencies: [
                "MacDroidCore",
                .product(name: "HummingbirdTesting", package: "hummingbird"),
                .product(name: "WSClient", package: "swift-websocket"),
            ]
        ),
    ]
)
```

Append to `.gitignore`:
```
# Generated by XcodeGen / xcodebuild
mac/MacDroid.xcodeproj/
mac/build/
```

- [ ] **Step 3: Write the test support and failing codec tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/TestSupport.swift`:
```swift
import Foundation
import NIOConcurrencyHelpers
import Testing

enum Fixtures {
    static let directory: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // → Tests/MacDroidCoreTests
        .deletingLastPathComponent() // → Tests
        .deletingLastPathComponent() // → MacDroidCore
        .deletingLastPathComponent() // → mac
        .deletingLastPathComponent() // → repo root
        .appending(path: "protocol/fixtures")

    static func text(_ name: String) throws -> String {
        try String(contentsOf: directory.appending(path: name), encoding: .utf8)
    }

    static func jsonObject(_ text: String) throws -> NSDictionary {
        try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? NSDictionary)
    }

    static func id(of text: String) throws -> String {
        try #require(jsonObject(text)["id"] as? String)
    }
}

final class FakeClock: Sendable {
    private let box = NIOLockedValueBox(Date(timeIntervalSince1970: 1_000_000))
    var now: Date { box.withLockedValue { $0 } }
    func advance(_ seconds: TimeInterval) { box.withLockedValue { $0 += seconds } }
}

/// Polls `condition` until it is true or the timeout passes (then records a test failure).
func eventually(
    timeout: Duration = .seconds(3),
    _ condition: @Sendable () async throws -> Bool
) async throws {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if try await condition() { return }
        try await Task.sleep(for: .milliseconds(10))
    }
    Issue.record("condition not met within \(timeout)")
}
```

`mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift`:
```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct ProtocolFixtureTests {
    @Test func helloFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("hello.json"))
        #expect(message == .hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: [])))
    }

    @Test func deviceStatusFixtureDecodes() throws {
        let message = try MessageCodec.decode(try Fixtures.text("device.status.json"))
        #expect(message == .deviceStatus(DeviceStatus(battery: 82, charging: true)))
    }

    @Test(arguments: ["hello.json", "device.status.json"])
    func fixturesRoundTrip(name: String) throws {
        let text = try Fixtures.text(name)
        let reencoded = try MessageCodec.encode(try MessageCodec.decode(text), id: try Fixtures.id(of: text))
        #expect(try Fixtures.jsonObject(reencoded) == Fixtures.jsonObject(text))
    }

    @Test func unknownTypeIsReportedNotRejected() throws {
        #expect(try MessageCodec.decode(try Fixtures.text("unknown-type.json")) == .unknown(type: "future.thing"))
    }

    @Test(arguments: ["not json", "{}", #"{"type":"hello","body":{"protocol":"x"}}"#, "[1,2]"])
    func malformedFramesThrow(text: String) {
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }

    @Test func encodingUnknownThrows() {
        #expect(throws: MessageCodecError.cannotEncodeUnknown("x")) { try MessageCodec.encode(.unknown(type: "x")) }
    }

    @Test func pairBodiesMatchFixtures() throws {
        let request = try JSONDecoder().decode(PairRequest.self, from: Data(try Fixtures.text("pair.request.json").utf8))
        #expect(request == PairRequest(code: "MZXW6YTBOI2DGNBVGY3TQOJQGE", deviceName: "My Phone", model: "Nothing Phone (3)"))
        let response = try JSONDecoder().decode(PairResponse.self, from: Data(try Fixtures.text("pair.response.json").utf8))
        #expect(response == PairResponse(macName: "MacBook Pro"))
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter ProtocolFixtureTests`
Expected: compile errors, e.g. `cannot find 'MessageCodec' in scope` (the first build also downloads the dependencies).

- [ ] **Step 5: Implement messages and codec**

`mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift`:
```swift
import Foundation

public enum ProtocolInfo {
    public static let version = 1
    public static let serviceType = "_macdroid._tcp"
}

public struct Hello: Codable, Equatable, Sendable {
    public var `protocol`: Int
    public var app: String
    public var device: String
    public var features: [String]

    public init(protocol protocolVersion: Int = ProtocolInfo.version, app: String, device: String, features: [String]) {
        self.protocol = protocolVersion
        self.app = app
        self.device = device
        self.features = features
    }
}

public struct DeviceStatus: Codable, Equatable, Sendable {
    public var battery: Int
    public var charging: Bool

    public init(battery: Int, charging: Bool) {
        self.battery = battery
        self.charging = charging
    }
}

public struct PairRequest: Codable, Equatable, Sendable {
    public var code: String
    public var deviceName: String
    public var model: String

    public init(code: String, deviceName: String, model: String) {
        self.code = code
        self.deviceName = deviceName
        self.model = model
    }
}

public struct PairResponse: Codable, Equatable, Sendable {
    public var macName: String

    public init(macName: String) { self.macName = macName }
}

public struct ServerInfo: Codable, Equatable, Sendable {
    public var macName: String
    public var `protocol`: Int

    public init(macName: String, protocol protocolVersion: Int = ProtocolInfo.version) {
        self.macName = macName
        self.protocol = protocolVersion
    }
}

/// A control-channel message. Later plans add cases (notifications, clipboard, transfers).
public enum Message: Equatable, Sendable {
    case hello(Hello)
    case deviceStatus(DeviceStatus)
    /// A type this version doesn't understand. Decoded so callers can ignore it; never encoded.
    case unknown(type: String)
}
```

`mac/MacDroidCore/Sources/MacDroidCore/Protocol/MessageCodec.swift`:
```swift
import Foundation

public enum MessageCodecError: Error, Equatable {
    case malformed
    case cannotEncodeUnknown(String)
}

public enum MessageCodec {
    private struct Header: Decodable { let type: String }
    private struct Incoming<Body: Decodable>: Decodable { let body: Body }
    private struct Outgoing<Body: Encodable>: Encodable {
        let v: Int
        let type: String
        let id: String
        let body: Body
    }

    public static func encode(_ message: Message, id: String = UUID().uuidString.lowercased()) throws -> String {
        switch message {
        case .hello(let body): return try encode(type: "hello", id: id, body: body)
        case .deviceStatus(let body): return try encode(type: "device.status", id: id, body: body)
        case .unknown(let type): throw MessageCodecError.cannotEncodeUnknown(type)
        }
    }

    public static func decode(_ text: String) throws -> Message {
        let data = Data(text.utf8)
        let decoder = JSONDecoder()
        do {
            switch try decoder.decode(Header.self, from: data).type {
            case "hello": return .hello(try decoder.decode(Incoming<Hello>.self, from: data).body)
            case "device.status": return .deviceStatus(try decoder.decode(Incoming<DeviceStatus>.self, from: data).body)
            case let other: return .unknown(type: other)
            }
        } catch {
            throw MessageCodecError.malformed
        }
    }

    private static func encode<Body: Encodable>(type: String, id: String, body: Body) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(Outgoing(v: ProtocolInfo.version, type: type, id: id, body: body))
        return String(decoding: data, as: UTF8.self)
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter ProtocolFixtureTests`
Expected: all tests pass. If the build fails with `no such module 'ServiceContextModule'`, check that the tracing pin from Step 2 is present and run `swift package update`.

- [ ] **Step 7: Commit**

```bash
git add .gitignore protocol mac/MacDroidCore
git commit -m "feat(protocol): shared fixtures and Swift message codec"
```

---

## Task 2: Mac identity (key, self-signed certificate, fingerprint, Keychain)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Security/Identity.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Security/IdentityStore.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/IdentityTests.swift`

**Interfaces:**
- Produces:
  - `Fingerprint`:
    - `sha256Hex(_ bytes:) -> String` (lowercase, 64 chars)
    - `of(_ cert: NIOSSLCertificate) throws -> String`
    - `short(_:) -> String` (first 16 chars)
    - `display(_:) -> String` (e.g. `"BA78 16BF 8F01 CFEA"`)
  - `struct Identity: Sendable`:
    - properties: `privateKey: P256.Signing.PrivateKey`, `certificateDER: [UInt8]`, `fingerprint: String`
    - `static generate(commonName:now:) throws -> Identity`
    - `nioCertificate() throws -> NIOSSLCertificate`, `nioPrivateKey() throws -> NIOSSLPrivateKey`, `certificatePEM() throws -> String`
  - `protocol IdentityStore: Sendable { func load() throws -> Identity?; func save(_:) throws }`, plus the extension `loadOrCreate(commonName:) throws -> Identity`
  - `InMemoryIdentityStore()`
  - `KeychainIdentityStore(service: String = "dev.macdroid.identity")`, with `delete()`

- [ ] **Step 1: Write the failing tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/IdentityTests.swift`:
```swift
import Foundation
import Testing
import X509
@testable import MacDroidCore

@Suite struct IdentityTests {
    @Test func fingerprintOfKnownBytes() {
        #expect(Fingerprint.sha256Hex(Array("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #expect(Fingerprint.short("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == "ba7816bf8f01cfea")
        #expect(Fingerprint.display("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == "BA78 16BF 8F01 CFEA")
    }

    @Test func generatedIdentityIsSelfSignedWithCommonName() throws {
        let identity = try Identity.generate(commonName: "Test Mac")
        let certificate = try Certificate(derEncoded: identity.certificateDER)
        #expect(certificate.subject == certificate.issuer)
        #expect(certificate.subject.description.contains("Test Mac"))
        #expect(identity.fingerprint.count == 64)
        #expect(identity.fingerprint == identity.fingerprint.lowercased())
    }

    @Test func nioConversionsKeepTheFingerprint() throws {
        let identity = try Identity.generate(commonName: "Test Mac")
        #expect(try Fingerprint.of(identity.nioCertificate()) == identity.fingerprint)
        _ = try identity.nioPrivateKey()
        #expect(try identity.certificatePEM().hasPrefix("-----BEGIN CERTIFICATE-----"))
    }

    @Test func identitiesAreUnique() throws {
        #expect(try Identity.generate(commonName: "A").fingerprint != Identity.generate(commonName: "A").fingerprint)
    }

    @Test func loadOrCreateReturnsTheSameIdentity() throws {
        let store = InMemoryIdentityStore()
        let first = try store.loadOrCreate(commonName: "Mac")
        let second = try store.loadOrCreate(commonName: "Mac")
        #expect(first.fingerprint == second.fingerprint)
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["CI"] == nil, "touches the login keychain"))
    func keychainRoundTrip() throws {
        let store = KeychainIdentityStore(service: "dev.macdroid.tests.\(UUID().uuidString)")
        defer { store.delete() }
        #expect(try store.load() == nil)
        let identity = try Identity.generate(commonName: "Keychain Test")
        try store.save(identity)
        let loaded = try #require(try store.load())
        #expect(loaded.fingerprint == identity.fingerprint)
        #expect(loaded.privateKey.rawRepresentation == identity.privateKey.rawRepresentation)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter IdentityTests`
Expected: compile errors (`cannot find 'Fingerprint' in scope`).

- [ ] **Step 3: Implement Identity**

`mac/MacDroidCore/Sources/MacDroidCore/Security/Identity.swift`:
```swift
import Crypto
import Foundation
import NIOSSL
import SwiftASN1
import X509

public enum Fingerprint {
    /// Lowercase hex SHA-256 — the value both apps pin.
    public static func sha256Hex<Bytes: Sequence>(_ bytes: Bytes) -> String where Bytes.Element == UInt8 {
        SHA256.hash(data: Array(bytes)).map { String(format: "%02x", $0) }.joined()
    }

    public static func of(_ certificate: NIOSSLCertificate) throws -> String {
        sha256Hex(try certificate.toDERBytes())
    }

    /// First 16 hex characters; used in the Bonjour TXT record.
    public static func short(_ fingerprint: String) -> String {
        String(fingerprint.prefix(16))
    }

    /// Grouped form both apps display for manual comparison, e.g. "8A3B A983 C438 8481".
    public static func display(_ fingerprint: String) -> String {
        let characters = Array(short(fingerprint).uppercased())
        return stride(from: 0, to: characters.count, by: 4)
            .map { String(characters[$0..<min($0 + 4, characters.count)]) }
            .joined(separator: " ")
    }
}

/// The Mac's long-lived TLS identity: a P-256 key and a self-signed certificate.
public struct Identity: Sendable {
    public let privateKey: P256.Signing.PrivateKey
    public let certificateDER: [UInt8]

    public init(privateKey: P256.Signing.PrivateKey, certificateDER: [UInt8]) {
        self.privateKey = privateKey
        self.certificateDER = certificateDER
    }

    public static func generate(commonName: String, now: Date = Date()) throws -> Identity {
        let key = P256.Signing.PrivateKey()
        let name = try DistinguishedName { CommonName(commonName) }
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: .init(key.publicKey),
            notValidBefore: now.addingTimeInterval(-86_400),
            notValidAfter: now.addingTimeInterval(86_400 * 365 * 20),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
            },
            issuerPrivateKey: .init(key)
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return Identity(privateKey: key, certificateDER: serializer.serializedBytes)
    }

    public var fingerprint: String { Fingerprint.sha256Hex(certificateDER) }

    public func nioCertificate() throws -> NIOSSLCertificate {
        try NIOSSLCertificate(bytes: certificateDER, format: .der)
    }

    public func nioPrivateKey() throws -> NIOSSLPrivateKey {
        try NIOSSLPrivateKey(bytes: Array(privateKey.derRepresentation), format: .der)
    }

    public func certificatePEM() throws -> String {
        try Certificate(derEncoded: certificateDER).serializeAsPEM().pemString
    }
}
```

- [ ] **Step 4: Implement the identity stores**

`mac/MacDroidCore/Sources/MacDroidCore/Security/IdentityStore.swift`:
```swift
import Crypto
import Foundation
import NIOConcurrencyHelpers
import Security

public protocol IdentityStore: Sendable {
    func load() throws -> Identity?
    func save(_ identity: Identity) throws
}

extension IdentityStore {
    public func loadOrCreate(commonName: String) throws -> Identity {
        if let existing = try load() { return existing }
        let identity = try Identity.generate(commonName: commonName)
        try save(identity)
        return identity
    }
}

public final class InMemoryIdentityStore: IdentityStore {
    private let box = NIOLockedValueBox<Identity?>(nil)

    public init() {}
    public func load() throws -> Identity? { box.withLockedValue { $0 } }
    public func save(_ identity: Identity) throws { box.withLockedValue { $0 = identity } }
}

public enum KeychainError: Error, Equatable {
    case unexpectedStatus(OSStatus)
}

/// Stores the identity as two generic-password items. Uses the file-based login keychain so an
/// ad-hoc signed development build works; after a rebuild macOS may ask once to allow access.
public final class KeychainIdentityStore: IdentityStore {
    private static let keyAccount = "private-key"
    private static let certificateAccount = "certificate"
    private let service: String

    public init(service: String = "dev.macdroid.identity") {
        self.service = service
    }

    public func load() throws -> Identity? {
        guard let keyData = try read(account: Self.keyAccount),
              let certificateData = try read(account: Self.certificateAccount) else { return nil }
        return Identity(
            privateKey: try P256.Signing.PrivateKey(rawRepresentation: keyData),
            certificateDER: Array(certificateData)
        )
    }

    public func save(_ identity: Identity) throws {
        try write(account: Self.keyAccount, data: identity.privateKey.rawRepresentation)
        try write(account: Self.certificateAccount, data: Data(identity.certificateDER))
    }

    public func delete() {
        for account in [Self.keyAccount, Self.certificateAccount] {
            SecItemDelete(baseQuery(account: account) as CFDictionary)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
        return result as? Data
    }

    private func write(account: String, data: Data) throws {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter IdentityTests`
Expected: all pass. If macOS shows a keychain prompt for `keychainRoundTrip`, click Allow.

- [ ] **Step 6: Commit**

```bash
git add mac/MacDroidCore
git commit -m "feat(mac): TLS identity generation, fingerprints and keychain store"
```

---

## Task 3: Pairing state machine

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairedDeviceStore.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairingManager.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/PairingManagerTests.swift`

**Interfaces:**
- Consumes: `FakeClock` (Task 1 test support)
- Produces:
  - `struct PairedDevice: Codable, Equatable, Sendable { fingerprint, name, model: String; pairedAt: Date }`
  - `protocol PairedDeviceStore: Sendable { func load() -> PairedDevice?; func save(_: PairedDevice?) }`
  - Store implementations: `InMemoryPairedDeviceStore(initial:)`, `UserDefaultsPairedDeviceStore(defaults:key:)`
  - Value types: `struct PairingWindow { code: String; expiresAt: Date }`, `enum PairingError { windowClosed, codeMismatch }`, `enum PairingEvent { windowOpened(PairingWindow), windowClosed, paired(PairedDevice), forgotten }`
  - `actor PairingManager`:
    - `init(store:windowDuration:now:)`
    - `nonisolated events: AsyncStream<PairingEvent>` (single consumer: the app model)
    - `pairedDevice: PairedDevice?`
    - `openWindow() -> PairingWindow`, `closeWindow()`, `currentWindow() -> PairingWindow?`
    - `isTrusted(fingerprint:) -> Bool`, `acceptsHandshake(fingerprint:) -> Bool`
    - `completePairing(code:fingerprint:deviceName:model:) throws -> PairedDevice`
    - `forget()`
    - `static makeCode() -> String`, `static base32(_:) -> String`, `static constantTimeEquals(_:_:) -> Bool`

- [ ] **Step 1: Write the failing tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/PairingManagerTests.swift`:
```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct PairingManagerTests {
    let clock = FakeClock()
    let store = InMemoryPairedDeviceStore()
    var manager: PairingManager { PairingManager(store: store, windowDuration: 120, now: { [clock] in clock.now }) }

    @Test func base32MatchesRFC4648Vectors() {
        #expect(PairingManager.base32(Array("foo".utf8)) == "MZXW6")
        #expect(PairingManager.base32(Array("foobar".utf8)) == "MZXW6YTBOI")
        #expect(PairingManager.makeCode().count == 26)
        #expect(PairingManager.makeCode() != PairingManager.makeCode())
    }

    @Test func constantTimeEquals() {
        #expect(PairingManager.constantTimeEquals("ABC", "ABC"))
        #expect(!PairingManager.constantTimeEquals("ABC", "ABD"))
        #expect(!PairingManager.constantTimeEquals("ABC", "ABCD"))
    }

    @Test func windowExpiresAfterDuration() async {
        let manager = manager
        let window = await manager.openWindow()
        #expect(window.expiresAt == clock.now.addingTimeInterval(120))
        clock.advance(119)
        #expect(await manager.currentWindow() == window)
        clock.advance(1)
        #expect(await manager.currentWindow() == nil)
    }

    @Test func correctCodePairsAndPersists() async throws {
        let manager = manager
        let window = await manager.openWindow()
        let device = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "Phone", model: "Nothing")
        #expect(device.fingerprint == "aa")
        #expect(store.load() == device)
        #expect(await manager.isTrusted(fingerprint: "aa"))
        #expect(await manager.currentWindow() == nil)
    }

    @Test func wrongCodeIsRejectedAndWindowStaysOpen() async {
        let manager = manager
        _ = await manager.openWindow()
        await #expect(throws: PairingError.codeMismatch) {
            try await manager.completePairing(code: "WRONG", fingerprint: "aa", deviceName: "P", model: "M")
        }
        #expect(await manager.currentWindow() != nil)
        #expect(await manager.pairedDevice == nil)
    }

    @Test func expiredWindowRejectsPairing() async {
        let manager = manager
        let window = await manager.openWindow()
        clock.advance(121)
        await #expect(throws: PairingError.windowClosed) {
            try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        }
    }

    // Review Focus #2
    @Test func codeCannotBeReusedAfterPairing() async throws {
        let manager = manager
        let window = await manager.openWindow()
        _ = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        await #expect(throws: PairingError.windowClosed) {
            try await manager.completePairing(code: window.code, fingerprint: "bb", deviceName: "Evil", model: "M")
        }
        #expect(await manager.isTrusted(fingerprint: "aa"))
        #expect(await !manager.isTrusted(fingerprint: "bb"))
    }

    // Review Focus #3
    @Test func repairingReplacesOldFingerprint() async throws {
        let manager = manager
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "old", deviceName: "P", model: "M")
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "new", deviceName: "P", model: "M")
        #expect(await !manager.isTrusted(fingerprint: "old"))
        #expect(await manager.isTrusted(fingerprint: "new"))
        #expect(store.load()?.fingerprint == "new")
    }

    @Test func handshakeAcceptance() async throws {
        let manager = manager
        #expect(await !manager.acceptsHandshake(fingerprint: "stranger"))
        let window = await manager.openWindow()
        #expect(await manager.acceptsHandshake(fingerprint: "stranger"))
        _ = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        #expect(await manager.acceptsHandshake(fingerprint: "aa"))
        #expect(await !manager.acceptsHandshake(fingerprint: "stranger"))
    }

    @Test func forgetClearsTrust() async throws {
        let manager = manager
        _ = try await manager.completePairing(code: await manager.openWindow().code, fingerprint: "aa", deviceName: "P", model: "M")
        await manager.forget()
        #expect(await !manager.isTrusted(fingerprint: "aa"))
        #expect(store.load() == nil)
    }

    @Test func loadsPairedDeviceFromStore() async {
        let device = PairedDevice(fingerprint: "aa", name: "P", model: "M", pairedAt: clock.now)
        let manager = PairingManager(store: InMemoryPairedDeviceStore(initial: device), now: { [clock] in clock.now })
        #expect(await manager.isTrusted(fingerprint: "aa"))
    }

    @Test func emitsEvents() async throws {
        let manager = manager
        var events = manager.events.makeAsyncIterator()
        let window = await manager.openWindow()
        #expect(await events.next() == .windowOpened(window))
        let device = try await manager.completePairing(code: window.code, fingerprint: "aa", deviceName: "P", model: "M")
        #expect(await events.next() == .windowClosed)
        #expect(await events.next() == .paired(device))
    }

    @Test func userDefaultsStoreRoundTrip() throws {
        let defaults = try #require(UserDefaults(suiteName: "dev.macdroid.tests.\(UUID().uuidString)"))
        let store = UserDefaultsPairedDeviceStore(defaults: defaults)
        let device = PairedDevice(fingerprint: "aa", name: "Phone", model: "M", pairedAt: Date(timeIntervalSince1970: 5))
        store.save(device)
        #expect(UserDefaultsPairedDeviceStore(defaults: defaults).load() == device)
        store.save(nil)
        #expect(store.load() == nil)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter PairingManagerTests`
Expected: compile errors (`cannot find 'PairingManager' in scope`).

- [ ] **Step 3: Implement the paired device store**

`mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairedDeviceStore.swift`:
```swift
import Foundation
import NIOConcurrencyHelpers

public struct PairedDevice: Codable, Equatable, Sendable {
    public var fingerprint: String
    public var name: String
    public var model: String
    public var pairedAt: Date

    public init(fingerprint: String, name: String, model: String, pairedAt: Date) {
        self.fingerprint = fingerprint
        self.name = name
        self.model = model
        self.pairedAt = pairedAt
    }
}

public protocol PairedDeviceStore: Sendable {
    func load() -> PairedDevice?
    func save(_ device: PairedDevice?)
}

public final class InMemoryPairedDeviceStore: PairedDeviceStore {
    private let box: NIOLockedValueBox<PairedDevice?>

    public init(initial: PairedDevice? = nil) { box = NIOLockedValueBox(initial) }
    public func load() -> PairedDevice? { box.withLockedValue { $0 } }
    public func save(_ device: PairedDevice?) { box.withLockedValue { $0 = device } }
}

/// `UserDefaults` is documented as thread-safe, hence `@unchecked Sendable`.
public final class UserDefaultsPairedDeviceStore: PairedDeviceStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "pairedDevice") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> PairedDevice? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(PairedDevice.self, from: $0) }
    }

    public func save(_ device: PairedDevice?) {
        if let device, let data = try? JSONEncoder().encode(device) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}
```

- [ ] **Step 4: Implement the pairing manager**

`mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairingManager.swift`:
```swift
import Foundation

public struct PairingWindow: Equatable, Sendable {
    public let code: String
    public let expiresAt: Date
}

public enum PairingError: Error, Equatable {
    case windowClosed
    case codeMismatch
}

public enum PairingEvent: Equatable, Sendable {
    case windowOpened(PairingWindow)
    case windowClosed
    case paired(PairedDevice)
    case forgotten
}

/// Owns who the Mac trusts. Exactly one paired phone; re-pairing replaces it.
public actor PairingManager {
    /// Single-consumer stream of state changes (the app model listens).
    public nonisolated let events: AsyncStream<PairingEvent>
    private let continuation: AsyncStream<PairingEvent>.Continuation
    private let store: any PairedDeviceStore
    private let windowDuration: TimeInterval
    private let now: @Sendable () -> Date
    private var window: PairingWindow?
    public private(set) var pairedDevice: PairedDevice?

    public init(
        store: any PairedDeviceStore,
        windowDuration: TimeInterval = 120,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        let (stream, continuation) = AsyncStream.makeStream(of: PairingEvent.self)
        self.events = stream
        self.continuation = continuation
        self.store = store
        self.windowDuration = windowDuration
        self.now = now
        self.pairedDevice = store.load()
    }

    @discardableResult
    public func openWindow() -> PairingWindow {
        let opened = PairingWindow(code: Self.makeCode(), expiresAt: now().addingTimeInterval(windowDuration))
        window = opened
        continuation.yield(.windowOpened(opened))
        return opened
    }

    public func closeWindow() {
        guard window != nil else { return }
        window = nil
        continuation.yield(.windowClosed)
    }

    /// The open window, or nil. Expiry is detected lazily here.
    public func currentWindow() -> PairingWindow? {
        guard let window else { return nil }
        if now() >= window.expiresAt {
            closeWindow()
            return nil
        }
        return window
    }

    public func isTrusted(fingerprint: String) -> Bool {
        pairedDevice?.fingerprint == fingerprint
    }

    /// TLS-level gate: the paired phone always, anyone else only while pairing is open.
    public func acceptsHandshake(fingerprint: String) -> Bool {
        isTrusted(fingerprint: fingerprint) || currentWindow() != nil
    }

    public func completePairing(code: String, fingerprint: String, deviceName: String, model: String) throws -> PairedDevice {
        guard let window = currentWindow() else { throw PairingError.windowClosed }
        guard Self.constantTimeEquals(code, window.code) else { throw PairingError.codeMismatch }
        let device = PairedDevice(fingerprint: fingerprint, name: deviceName, model: model, pairedAt: now())
        pairedDevice = device
        store.save(device)
        closeWindow()
        continuation.yield(.paired(device))
        return device
    }

    public func forget() {
        pairedDevice = nil
        store.save(nil)
        continuation.yield(.forgotten)
    }

    public static func makeCode() -> String {
        var generator = SystemRandomNumberGenerator()
        return base32((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }

    /// RFC 4648 base32, no padding.
    static func base32(_ bytes: [UInt8]) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var output = ""
        var buffer = 0
        var bits = 0
        for byte in bytes {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                output.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
            buffer &= (1 << bits) - 1
        }
        if bits > 0 { output.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return output
    }

    static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for index in a.indices { difference |= a[index] ^ b[index] }
        return difference == 0
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter PairingManagerTests`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add mac/MacDroidCore
git commit -m "feat(mac): pairing window, code check and paired-device persistence"
```

---

## Task 4: Pairing invite URI, LAN address selection, Bonjour advertising

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairingInvite.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Network/LocalAddress.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Network/BonjourAdvertiser.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/PairingInviteTests.swift`, `.../NetworkTests.swift`

**Interfaces:**
- Consumes: `Fixtures.text("pairing-uri.txt")`, `ProtocolInfo.serviceType`
- Produces:
  - `struct PairingInvite { host: String; port: Int; fingerprint: String; code: String; name: String }`, with computed properties `uri: String` and `displayCode: String`
  - `LocalAddress.primaryIPv4() -> String?`
  - Internals exposed to tests: `LocalAddress.Candidate(interface:address:)`, `LocalAddress.pick(from:) -> String?`
  - `final class BonjourAdvertiser`: `start(name:port:txt:serviceType:) throws`, `stop()`, `static encodeTXT(_:) -> [UInt8]`
  - `BonjourError.registrationFailed(Int32)`

- [ ] **Step 1: Write the failing tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/PairingInviteTests.swift`:
```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct PairingInviteTests {
    let invite = PairingInvite(
        host: "192.168.1.20",
        port: 47999,
        fingerprint: "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a",
        code: "MZXW6YTBOI2DGNBVGY3TQOJQGE",
        name: "Sam's Mac+Pro ✨"
    )

    @Test func uriMatchesSharedFixture() throws {
        let expected = try Fixtures.text("pairing-uri.txt").trimmingCharacters(in: .whitespacesAndNewlines)
        #expect(invite.uri == expected)
    }

    @Test func displayCodeIsGroupedInFours() {
        #expect(invite.displayCode == "MZXW 6YTB OI2D GNBV GY3T QOJQ GE")
    }
}
```

`mac/MacDroidCore/Tests/MacDroidCoreTests/NetworkTests.swift`:
```swift
import Testing
@testable import MacDroidCore

@Suite struct NetworkTests {
    typealias C = LocalAddress.Candidate

    // Review Focus #4
    @Test func prefersEthernetLikeInterface() {
        let candidates = [
            C(interface: "lo0", address: "127.0.0.1"),
            C(interface: "utun3", address: "10.8.0.2"),
            C(interface: "bridge100", address: "192.168.64.1"),
            C(interface: "en1", address: "192.168.1.31"),
            C(interface: "en0", address: "192.168.1.20"),
        ]
        #expect(LocalAddress.pick(from: candidates) == "192.168.1.20")
    }

    @Test func skipsLinkLocal() {
        let candidates = [C(interface: "en0", address: "169.254.10.1"), C(interface: "en5", address: "192.168.1.30")]
        #expect(LocalAddress.pick(from: candidates) == "192.168.1.30")
    }

    @Test func fallsBackToOtherInterfaces() {
        let candidates = [C(interface: "lo0", address: "127.0.0.1"), C(interface: "ipsec0", address: "10.0.0.2"), C(interface: "foo0", address: "10.1.1.1")]
        #expect(LocalAddress.pick(from: candidates) == "10.1.1.1")
    }

    @Test func nilWhenOnlyLoopback() {
        #expect(LocalAddress.pick(from: [C(interface: "lo0", address: "127.0.0.1")]) == nil)
    }

    @Test func enumeratesInterfacesWithoutCrashing() {
        _ = LocalAddress.primaryIPv4()
    }

    @Test func txtRecordEncoding() {
        let bytes = BonjourAdvertiser.encodeTXT(["v": "1", "fp": "8a3ba983c4388481"])
        #expect(bytes == [19] + Array("fp=8a3ba983c4388481".utf8) + [3] + Array("v=1".utf8))
    }

    @Test func advertiserRegistersAndStops() throws {
        let advertiser = BonjourAdvertiser()
        try advertiser.start(name: "MacDroid Test", port: 47_998, txt: ["v": "1"])
        advertiser.stop()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter "PairingInviteTests|NetworkTests"`
Expected: compile errors (`cannot find 'PairingInvite' in scope`).

- [ ] **Step 3: Implement PairingInvite**

`mac/MacDroidCore/Sources/MacDroidCore/Pairing/PairingInvite.swift`:
```swift
import Foundation

/// What the QR code carries. Must stay byte-identical with `protocol/fixtures/pairing-uri.txt`.
public struct PairingInvite: Equatable, Sendable {
    public var host: String
    public var port: Int
    public var fingerprint: String
    public var code: String
    public var name: String

    public init(host: String, port: Int, fingerprint: String, code: String, name: String) {
        self.host = host
        self.port = port
        self.fingerprint = fingerprint
        self.code = code
        self.name = name
    }

    public var uri: String {
        let fields = [("host", host), ("port", String(port)), ("fp", fingerprint), ("code", code), ("name", name)]
        let query = fields.map { "\($0.0)=\(Self.percentEncode($0.1))" }.joined(separator: "&")
        return "macdroid://pair?\(query)"
    }

    /// Code in groups of four for typing on the phone, e.g. "MZXW 6YTB …".
    public var displayCode: String {
        let characters = Array(code)
        return stride(from: 0, to: characters.count, by: 4)
            .map { String(characters[$0..<min($0 + 4, characters.count)]) }
            .joined(separator: " ")
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    /// Encodes everything outside RFC 3986 "unreserved" (so `+` becomes `%2B`, never a space).
    static func percentEncode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }
}
```

- [ ] **Step 4: Implement LocalAddress**

`mac/MacDroidCore/Sources/MacDroidCore/Network/LocalAddress.swift`:
```swift
import Darwin

public enum LocalAddress {
    public struct Candidate: Equatable, Sendable {
        public var interface: String
        public var address: String

        public init(interface: String, address: String) {
            self.interface = interface
            self.address = address
        }
    }

    /// The IPv4 address the phone should dial: a LAN interface, never VPN/loopback/link-local.
    public static func primaryIPv4() -> String? { pick(from: interfaces()) }

    static let excludedPrefixes = ["lo", "utun", "ipsec", "ppp", "awdl", "llw", "bridge", "gif", "stf", "anpi", "ap"]

    static func pick(from candidates: [Candidate]) -> String? {
        let usable = candidates.filter { candidate in
            !candidate.address.hasPrefix("127.")
                && !candidate.address.hasPrefix("169.254.")
                && !excludedPrefixes.contains { candidate.interface.hasPrefix($0) }
        }
        let ethernetLike = usable
            .filter { $0.interface.hasPrefix("en") }
            .sorted { interfaceIndex($0.interface) < interfaceIndex($1.interface) }
        return (ethernetLike.first ?? usable.first)?.address
    }

    static func interfaceIndex(_ name: String) -> Int {
        Int(name.drop { !$0.isNumber }) ?? Int.max
    }

    static func interfaces() -> [Candidate] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var result: [Candidate] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(pointer.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0,
                  let address = pointer.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let hostString = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            result.append(Candidate(interface: String(cString: pointer.pointee.ifa_name), address: hostString))
        }
        return result
    }
}
```

- [ ] **Step 5: Implement BonjourAdvertiser**

`mac/MacDroidCore/Sources/MacDroidCore/Network/BonjourAdvertiser.swift`:
```swift
import dnssd
import Foundation

public enum BonjourError: Error, Equatable {
    case registrationFailed(Int32)
}

/// Advertises the server with DNS-SD. All state is confined to `queue`, hence `@unchecked Sendable`.
public final class BonjourAdvertiser: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.macdroid.bonjour")
    private var service: DNSServiceRef?

    public init() {}

    public func start(name: String, port: UInt16, txt: [String: String], serviceType: String = ProtocolInfo.serviceType) throws {
        try queue.sync {
            stopLocked()
            let record = Self.encodeTXT(txt)
            var reference: DNSServiceRef?
            let error = record.withUnsafeBytes { buffer in
                DNSServiceRegister(&reference, 0, 0, name, serviceType, nil, nil, port.bigEndian,
                                   UInt16(buffer.count), buffer.baseAddress, nil, nil)
            }
            guard error == kDNSServiceErr_NoError, let reference else {
                throw BonjourError.registrationFailed(error)
            }
            DNSServiceSetDispatchQueue(reference, queue)
            service = reference
        }
    }

    public func stop() {
        queue.sync { stopLocked() }
    }

    private func stopLocked() {
        if let service { DNSServiceRefDeallocate(service) }
        service = nil
    }

    deinit {
        if let service { DNSServiceRefDeallocate(service) }
    }

    /// DNS-SD TXT wire format: length-prefixed "key=value" strings, keys sorted for stable output.
    static func encodeTXT(_ entries: [String: String]) -> [UInt8] {
        entries.sorted { $0.key < $1.key }.flatMap { key, value -> [UInt8] in
            let bytes = Array("\(key)=\(value)".utf8.prefix(255))
            return [UInt8(bytes.count)] + bytes
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter "PairingInviteTests|NetworkTests"`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add mac/MacDroidCore
git commit -m "feat(mac): pairing invite URI, LAN address picking and Bonjour advertising"
```

---

## Task 5: ConnectionHub (one phone session)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Session/ConnectionHub.swift`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ConnectionHubTests.swift`

**Interfaces:**
- Consumes: `MessageCodec`, `Message`, `Hello`, `eventually` (test)
- Produces:
  - `protocol MessageTransport: Sendable { func send(_ text: String) async throws; func close() async }`
  - `struct PeerInfo: Equatable, Sendable { device, app: String; features: [String]; battery: Int?; charging: Bool? }`
  - `enum ConnectionStatus: Equatable, Sendable { case disconnected, connected(PeerInfo) }`
  - `enum HubError: Error { case notConnected }`
  - `actor ConnectionHub`:
    - `init(macName:appVersion:features:)`
    - `nonisolated statusUpdates: AsyncStream<ConnectionStatus>`
    - `status: ConnectionStatus`
    - `run(transport:incoming: AsyncThrowingStream<String, any Error>) async`
    - `send(_ message: Message) async throws`
    - `disconnect() async`

- [ ] **Step 1: Write the failing tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/ConnectionHubTests.swift`:
```swift
import Foundation
import Testing
@testable import MacDroidCore

/// A transport whose incoming stream ends when closed, like a real WebSocket.
actor FakeTransport: MessageTransport {
    private(set) var sent: [String] = []
    private(set) var closed = false
    let incoming: AsyncThrowingStream<String, any Error>
    private let continuation: AsyncThrowingStream<String, any Error>.Continuation

    init() {
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
        self.incoming = stream
        self.continuation = continuation
    }

    func send(_ text: String) async throws { sent.append(text) }
    func close() async { closed = true; continuation.finish() }
    nonisolated func receive(_ message: Message) throws { continuation.yield(try MessageCodec.encode(message)) }
    nonisolated func receiveRaw(_ text: String) { continuation.yield(text) }
    nonisolated func hangUp() { continuation.finish() }
}

@Suite struct ConnectionHubTests {
    let phoneHello = Message.hello(Hello(app: "0.1.0", device: "Nothing Phone (3)", features: []))

    func start(_ hub: ConnectionHub, _ transport: FakeTransport) -> Task<Void, Never> {
        Task { await hub.run(transport: transport, incoming: transport.incoming) }
    }

    @Test func sendsHelloFirst() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try await eventually { await !transport.sent.isEmpty }
        #expect(try MessageCodec.decode(await transport.sent[0]) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: [])))
        transport.hangUp(); await task.value
    }

    @Test func connectedAfterPhoneHelloAndBatteryUpdates() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 64, charging: true)))
        try await eventually {
            await hub.status == .connected(PeerInfo(device: "Nothing Phone (3)", app: "0.1.0", features: [], battery: 64, charging: true))
        }
        transport.hangUp(); await task.value
        #expect(await hub.status == .disconnected)
    }

    @Test func malformedAndUnknownFramesAreIgnored() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        transport.receiveRaw("garbage")
        transport.receiveRaw(#"{"v":1,"type":"future.thing","id":"x","body":{}}"#)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 10, charging: false)))
        try await eventually {
            if case .connected(let peer) = await hub.status { return peer.battery == 10 }
            return false
        }
        transport.hangUp(); await task.value
    }

    // Review Focus #5
    @Test func newSessionReplacesOldWithoutFlicker() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let first = FakeTransport()
        let firstTask = start(hub, first)
        try first.receive(phoneHello)
        try await eventually { if case .connected = await hub.status { return true }; return false }

        let second = FakeTransport()
        let secondTask = start(hub, second)
        try await eventually { await first.closed }
        try second.receive(.hello(Hello(app: "0.1.1", device: "Nothing Phone (3)", features: [])))
        await firstTask.value
        try await eventually {
            if case .connected(let peer) = await hub.status { return peer.app == "0.1.1" }
            return false
        }
        try await Task.sleep(for: .milliseconds(50))
        if case .connected(let peer) = await hub.status { #expect(peer.app == "0.1.1") } else { Issue.record("status flipped") }
        second.hangUp(); await secondTask.value
    }

    @Test func sendRequiresSession() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        await #expect(throws: HubError.notConnected) { try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false))) }
        let transport = FakeTransport()
        let task = start(hub, transport)
        try await eventually { await !transport.sent.isEmpty }
        try await hub.send(.deviceStatus(DeviceStatus(battery: 1, charging: false)))
        #expect(await transport.sent.count == 2)
        await hub.disconnect()
        await task.value
        #expect(await transport.closed)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter ConnectionHubTests`
Expected: compile errors (`cannot find type 'MessageTransport'`).

- [ ] **Step 3: Implement ConnectionHub**

`mac/MacDroidCore/Sources/MacDroidCore/Session/ConnectionHub.swift`:
```swift
import Foundation
import Logging

public protocol MessageTransport: Sendable {
    func send(_ text: String) async throws
    func close() async
}

public struct PeerInfo: Equatable, Sendable {
    public var device: String
    public var app: String
    public var features: [String]
    public var battery: Int?
    public var charging: Bool?

    public init(device: String, app: String, features: [String], battery: Int? = nil, charging: Bool? = nil) {
        self.device = device
        self.app = app
        self.features = features
        self.battery = battery
        self.charging = charging
    }
}

public enum ConnectionStatus: Equatable, Sendable {
    case disconnected
    case connected(PeerInfo)
}

public enum HubError: Error, Equatable {
    case notConnected
}

/// Holds the single active phone session. A newer session always replaces an older one.
public actor ConnectionHub {
    public nonisolated let statusUpdates: AsyncStream<ConnectionStatus>
    private let statusContinuation: AsyncStream<ConnectionStatus>.Continuation
    private let hello: Hello
    private let logger = Logger(label: "dev.macdroid.hub")
    private var current: (id: UUID, transport: any MessageTransport)?

    public private(set) var status: ConnectionStatus = .disconnected {
        didSet { if status != oldValue { statusContinuation.yield(status) } }
    }

    public init(macName: String, appVersion: String, features: [String] = []) {
        let (stream, continuation) = AsyncStream.makeStream(of: ConnectionStatus.self)
        self.statusUpdates = stream
        self.statusContinuation = continuation
        self.hello = Hello(app: appVersion, device: macName, features: features)
    }

    /// Runs one session until `incoming` ends.
    public func run(transport: any MessageTransport, incoming: AsyncThrowingStream<String, any Error>) async {
        let id = UUID()
        let previous = current
        current = (id, transport)
        if previous != nil { status = .disconnected }
        if let previous { await previous.transport.close() }

        do {
            try await transport.send(MessageCodec.encode(.hello(hello)))
            for try await text in incoming { handle(text, session: id) }
        } catch {
            logger.debug("session ended: \(error)")
        }
        endSession(id)
    }

    public func send(_ message: Message) async throws {
        guard let transport = current?.transport else { throw HubError.notConnected }
        try await transport.send(MessageCodec.encode(message))
    }

    public func disconnect() async {
        guard let session = current else { return }
        current = nil
        status = .disconnected
        await session.transport.close()
    }

    private func handle(_ text: String, session: UUID) {
        guard current?.id == session else { return }
        let message: Message
        do {
            message = try MessageCodec.decode(text)
        } catch {
            logger.warning("dropping malformed frame")
            return
        }
        switch message {
        case .hello(let peer):
            status = .connected(PeerInfo(device: peer.device, app: peer.app, features: peer.features))
        case .deviceStatus(let update):
            if case .connected(var peer) = status {
                peer.battery = update.battery
                peer.charging = update.charging
                status = .connected(peer)
            }
        case .unknown(let type):
            logger.debug("ignoring message type \(type)")
        }
    }

    private func endSession(_ id: UUID) {
        guard current?.id == id else { return }
        current = nil
        status = .disconnected
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter ConnectionHubTests`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add mac/MacDroidCore
git commit -m "feat(mac): connection hub for the phone session"
```

---

## Task 6: Mutual-TLS server, routes, WebSocket bridge, dev server

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Server/MacDroidServer.swift`
- Create: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`
- Modify: `mac/MacDroidCore/Package.swift` (add the executable product and target)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ServerTests.swift`

**Interfaces:**
- Consumes: `Identity`, `Fingerprint`, `PairingManager`, `ConnectionHub`, `MessageTransport`, `PairRequest`, `PairResponse`, `ServerInfo`, `PairingInvite`, `LocalAddress`
- Produces:
  - `struct ServerConfiguration { host: String = "0.0.0.0"; port: Int = 0 }`
  - `final class MacDroidServer: Sendable`:
    - `init(identity:pairing:hub:macName:configuration:)`
    - `run(onRunning: @escaping @Sendable (Int) async -> Void) async throws` (returns when the task is cancelled; `onRunning` receives the bound port)
    - `static let maxMessageSize = 1 << 20`
  - Executable `macdroid-devserver [--host H] [--port P]`. It prints lines starting with `PAIRING_URI `, `LISTENING `, and `STATUS connected device=<name> battery=<n|->` or `STATUS disconnected`.

- [ ] **Step 1: Write the failing integration tests**

`mac/MacDroidCore/Tests/MacDroidCoreTests/ServerTests.swift`:
```swift
import Foundation
import HummingbirdTesting
import Logging
import NIOCore
import NIOSSL
import Testing
import WSClient
@testable import MacDroidCore

struct ServerHarness {
    let port: Int
    let pairing: PairingManager
    let hub: ConnectionHub

    static func run(
        pairedPhone: Identity? = nil,
        _ body: (ServerHarness) async throws -> Void
    ) async throws {
        let store = InMemoryPairedDeviceStore()
        if let pairedPhone {
            store.save(PairedDevice(fingerprint: pairedPhone.fingerprint, name: "Phone", model: "Test", pairedAt: Date()))
        }
        let pairing = PairingManager(store: store)
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let server = MacDroidServer(
            identity: try Identity.generate(commonName: "Test Mac"),
            pairing: pairing, hub: hub, macName: "Test Mac",
            configuration: ServerConfiguration(host: "127.0.0.1", port: 0)
        )
        let (ports, portContinuation) = AsyncStream.makeStream(of: Int.self)
        let task = Task { try await server.run { port in portContinuation.yield(port) } }
        var iterator = ports.makeAsyncIterator()
        let port = try #require(await iterator.next())
        do {
            try await body(ServerHarness(port: port, pairing: pairing, hub: hub))
        } catch {
            task.cancel(); _ = await task.result
            throw error
        }
        task.cancel(); _ = await task.result
    }

    func client(_ phone: Identity) throws -> TestClient {
        let client = TestClient(host: "localhost", port: port, configuration: .init(tlsConfiguration: try phone.clientTLS(), serverName: "localhost"))
        client.connect()
        return client
    }
}

extension Identity {
    func clientTLS() throws -> TLSConfiguration {
        var tls = TLSConfiguration.makeClientConfiguration()
        tls.certificateVerification = .none // the Android client pins; these tests exercise the server side
        tls.minimumTLSVersion = .tlsv13
        tls.certificateChain = [.certificate(try nioCertificate())]
        tls.privateKey = .privateKey(try nioPrivateKey())
        return tls
    }
}

func pairBody(code: String) -> ByteBuffer {
    ByteBuffer(string: #"{"code":"\#(code)","deviceName":"Phone","model":"Nothing Phone (3)"}"#)
}

@Suite struct ServerTests {
    @Test func pairsWithCorrectCodeWhileWindowOpen() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run { harness in
            let window = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            let response = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            #expect(response.status == .ok)
            let decoded = try JSONDecoder().decode(PairResponse.self, from: Data(String(buffer: try #require(response.body)).utf8))
            #expect(decoded == PairResponse(macName: "Test Mac"))
            #expect(await harness.pairing.isTrusted(fingerprint: phone.fingerprint))
            try await client.shutdown()
        }
    }

    @Test func wrongCodeIsForbidden() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            let response = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: "WRONG"))
            #expect(response.status == .forbidden)
            #expect(await !harness.pairing.isTrusted(fingerprint: phone.fingerprint))
            try await client.shutdown()
        }
    }

    // Review Focus #2
    @Test func pairingRejectedAfterWindowCloses() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let intruder = try Identity.generate(commonName: "Intruder")
        try await ServerHarness.run { harness in
            let window = await harness.pairing.openWindow()
            let client = try harness.client(phone)
            _ = try await client.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            try await client.shutdown()
            // Window is now closed, so an unknown certificate fails the TLS handshake.
            await #expect(throws: (any Error).self) {
                let other = try harness.client(intruder)
                _ = try await other.post("/v1/pair", headers: [.contentType: "application/json"], body: pairBody(code: window.code))
            }
            #expect(await harness.pairing.isTrusted(fingerprint: phone.fingerprint))
        }
    }

    @Test func unpairedCertificateCannotReachOtherRoutes() async throws {
        let stranger = try Identity.generate(commonName: "Stranger")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow() // handshake allowed, routes are not
            let client = try harness.client(stranger)
            #expect(try await client.get("/v1/info").status == .forbidden)
            try await client.shutdown()
        }
    }

    @Test func pairedPhoneGetsInfo() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run(pairedPhone: phone) { harness in
            let client = try harness.client(phone)
            let response = try await client.get("/v1/info")
            #expect(response.status == .ok)
            #expect(try JSONDecoder().decode(ServerInfo.self, from: Data(String(buffer: try #require(response.body)).utf8)) == ServerInfo(macName: "Test Mac"))
            try await client.shutdown()
        }
    }

    @Test func pairedPhoneOpensWebSocketAndExchangesHello() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run(pairedPhone: phone) { harness in
            try await WebSocketClient.connect(
                url: "wss://localhost:\(harness.port)/v1/ws",
                tlsConfiguration: try phone.clientTLS(),
                logger: Logger(label: "test")
            ) { inbound, outbound, _ in
                var messages = inbound.messages(maxSize: 1 << 20).makeAsyncIterator()
                guard case .text(let first)? = try await messages.next() else { Issue.record("no hello"); return }
                #expect(try MessageCodec.decode(first) == .hello(Hello(app: "0.1.0", device: "Test Mac", features: [])))
                try await outbound.write(.text(MessageCodec.encode(.hello(Hello(app: "0.1.0", device: "Phone", features: [])))))
                try await eventually { if case .connected = await harness.hub.status { return true }; return false }
                try await outbound.close(.normalClosure, reason: nil)
            }
            try await eventually { await harness.hub.status == .disconnected }
        }
    }

    @Test func unpairedPhoneCannotUpgrade() async throws {
        let stranger = try Identity.generate(commonName: "Stranger")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow()
            await #expect(throws: (any Error).self) {
                try await WebSocketClient.connect(
                    url: "wss://localhost:\(harness.port)/v1/ws",
                    tlsConfiguration: try stranger.clientTLS(),
                    logger: Logger(label: "test")
                ) { _, _, _ in Issue.record("upgrade should be refused") }
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter ServerTests`
Expected: compile errors (`cannot find 'MacDroidServer' in scope`).

- [ ] **Step 3: Implement the server**

`mac/MacDroidCore/Sources/MacDroidCore/Server/MacDroidServer.swift`:
```swift
import Foundation
import Hummingbird
import HummingbirdTLS
import HummingbirdWebSocket
import Logging
import NIOCore
import NIOSSL

public struct ServerConfiguration: Sendable {
    public var host: String
    public var port: Int

    public init(host: String = "0.0.0.0", port: Int = 0) {
        self.host = host
        self.port = port
    }
}

public struct MacDroidRequestContext: RequestContext {
    public var coreContext: CoreRequestContextStorage
    public let channel: any Channel

    public init(source: ApplicationRequestContextSource) {
        self.coreContext = .init(source: source)
        self.channel = source.channel
    }

    /// Fingerprint of the client certificate presented in this TLS session.
    func peerFingerprint() async throws -> String? {
        guard let certificate = try await channel.nioSSL_peerCertificate().get() else { return nil }
        return try Fingerprint.of(certificate)
    }
}

extension PairResponse: ResponseEncodable {}
extension ServerInfo: ResponseEncodable {}

/// Rejects requests whose client certificate is not the paired phone.
struct TrustedPeerMiddleware: RouterMiddleware {
    typealias Context = MacDroidRequestContext
    let pairing: PairingManager

    func handle(_ request: Request, context: Context, next: (Request, Context) async throws -> Response) async throws -> Response {
        guard let fingerprint = try await context.peerFingerprint(),
              await pairing.isTrusted(fingerprint: fingerprint) else {
            throw HTTPError(.forbidden)
        }
        return try await next(request, context)
    }
}

struct WebSocketTransport: MessageTransport {
    let outbound: WebSocketOutboundWriter

    func send(_ text: String) async throws { try await outbound.write(.text(text)) }
    func close() async { try? await outbound.close(.normalClosure, reason: nil) }
}

public final class MacDroidServer: Sendable {
    public static let maxMessageSize = 1 << 20

    private let identity: Identity
    private let pairing: PairingManager
    private let hub: ConnectionHub
    private let macName: String
    private let configuration: ServerConfiguration
    private let logger = Logger(label: "dev.macdroid.server")

    public init(identity: Identity, pairing: PairingManager, hub: ConnectionHub, macName: String, configuration: ServerConfiguration = .init()) {
        self.identity = identity
        self.pairing = pairing
        self.hub = hub
        self.macName = macName
        self.configuration = configuration
    }

    /// Runs until the calling task is cancelled. `onRunning` receives the bound port.
    public func run(onRunning: @escaping @Sendable (Int) async -> Void = { _ in }) async throws {
        var tls = TLSConfiguration.makeServerConfiguration(
            certificateChain: [.certificate(try identity.nioCertificate())],
            privateKey: .privateKey(try identity.nioPrivateKey())
        )
        tls.minimumTLSVersion = .tlsv13
        tls.certificateVerification = .noHostnameVerification // require a client certificate

        let pairing = self.pairing
        let hub = self.hub
        let app = Application(
            router: makeRouter(),
            server: try .tls(
                .http1WebSocketUpgrade(
                    configuration: .init(maxFrameSize: Self.maxMessageSize, autoPing: .enabled(timePeriod: .seconds(15)))
                ) { request, channel, _ in
                    guard request.path == "/v1/ws",
                          let certificate = try await channel.nioSSL_peerCertificate().get(),
                          await pairing.isTrusted(fingerprint: try Fingerprint.of(certificate)) else {
                        return .dontUpgrade
                    }
                    return .upgrade([:]) { inbound, outbound, _ in
                        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self)
                        let session = Task { await hub.run(transport: WebSocketTransport(outbound: outbound), incoming: stream) }
                        do {
                            for try await message in inbound.messages(maxSize: Self.maxMessageSize) {
                                if case .text(let text) = message { continuation.yield(text) }
                            }
                            continuation.finish()
                        } catch {
                            continuation.finish(throwing: error)
                        }
                        await session.value
                    }
                },
                configuration: TLSChannelConfiguration(
                    tlsConfiguration: tls,
                    customAsyncVerificationCallback: { certificates in
                        guard let leaf = certificates.first else { return .failed }
                        return await pairing.acceptsHandshake(fingerprint: try Fingerprint.of(leaf)) ? .certificateVerified : .failed
                    }
                )
            ),
            configuration: .init(address: .hostname(configuration.host, port: configuration.port)),
            onServerRunning: { channel in await onRunning(channel.localAddress?.port ?? 0) },
            logger: logger
        )
        try await app.runService(gracefulShutdownSignals: [])
    }

    private func makeRouter() -> Router<MacDroidRequestContext> {
        let router = Router(context: MacDroidRequestContext.self)
        let pairing = self.pairing
        let macName = self.macName

        router.post("/v1/pair") { request, context -> PairResponse in
            let body = try await request.decode(as: PairRequest.self, context: context)
            guard let fingerprint = try await context.peerFingerprint() else { throw HTTPError(.forbidden) }
            do {
                _ = try await pairing.completePairing(code: body.code, fingerprint: fingerprint, deviceName: body.deviceName, model: body.model)
            } catch is PairingError {
                throw HTTPError(.forbidden)
            }
            return PairResponse(macName: macName)
        }

        router.group("/v1")
            .add(middleware: TrustedPeerMiddleware(pairing: pairing))
            .get("info") { _, _ -> ServerInfo in ServerInfo(macName: macName) }

        return router
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter ServerTests`
Expected: all pass. Hummingbird logs `Server shutdown error: Already closed` at teardown; that comes from cancelling the task and is harmless.

- [ ] **Step 5: Add the dev server executable**

Modify `mac/MacDroidCore/Package.swift`. Add to `products`:
```swift
        .executable(name: "macdroid-devserver", targets: ["MacDroidDevServer"]),
```
and add to `targets` (before the test target):
```swift
        .executableTarget(name: "MacDroidDevServer", dependencies: ["MacDroidCore"]),
```

`mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`:
```swift
import Foundation
import MacDroidCore

// Headless server for the cross-platform test: fresh identity, pairing window open for 10 minutes.
setvbuf(stdout, nil, _IONBF, 0)

let arguments = CommandLine.arguments
func value(after flag: String) -> String? {
    guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

let macName = "MacDroid Dev Server"
let host = value(after: "--host") ?? LocalAddress.primaryIPv4() ?? "127.0.0.1"
let port = Int(value(after: "--port") ?? "") ?? 47999
let identity = try Identity.generate(commonName: macName)
let pairing = PairingManager(store: InMemoryPairedDeviceStore(), windowDuration: 600)
let hub = ConnectionHub(macName: macName, appVersion: "dev")

let window = await pairing.openWindow()
print("PAIRING_URI \(PairingInvite(host: host, port: port, fingerprint: identity.fingerprint, code: window.code, name: macName).uri)")

let statusPrinter = Task {
    for await status in hub.statusUpdates {
        switch status {
        case .disconnected: print("STATUS disconnected")
        case .connected(let peer): print("STATUS connected device=\(peer.device) battery=\(peer.battery.map(String.init) ?? "-")")
        }
    }
}

let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName,
                            configuration: ServerConfiguration(host: "0.0.0.0", port: port))
try await server.run { boundPort in print("LISTENING \(boundPort)") }
statusPrinter.cancel()
```

- [ ] **Step 6: Build and smoke-test the dev server**

Run:
```bash
cd mac/MacDroidCore && swift build --product macdroid-devserver && (./.build/debug/macdroid-devserver --host 127.0.0.1 > /tmp/macdroid-dev.log 2>&1 &) && sleep 2 && cat /tmp/macdroid-dev.log; pkill -f macdroid-devserver
```
Expected: the output contains `PAIRING_URI macdroid://pair?host=127.0.0.1&port=47999&fp=…` and `LISTENING 47999`.

- [ ] **Step 7: Run the whole Swift suite and commit**

Run: `cd mac/MacDroidCore && swift test`
Expected: all suites pass.

```bash
git add mac/MacDroidCore
git commit -m "feat(mac): mutual-TLS HTTPS/WebSocket server with pairing and dev server"
```

---

## Task 7: Mac menu-bar app

**Files:**
- Create: `mac/project.yml`
- Create: `mac/MacDroid/MacDroidApp.swift`, `AppModel.swift`, `MenuContentView.swift`, `SettingsView.swift`, `PairingView.swift`, `QRCode.swift`

**Interfaces:**
- Consumes (all from `MacDroidCore`):
  - Security: `KeychainIdentityStore`, `Identity`, `Fingerprint`
  - Pairing: `UserDefaultsPairedDeviceStore`, `PairingManager` (`openWindow`, `closeWindow`, `currentWindow`, `forget`, `events`), `PairingInvite`
  - Session and server: `ConnectionHub` (`statusUpdates`, `disconnect`), `MacDroidServer`
  - Network: `BonjourAdvertiser`, `LocalAddress`
- Produces: `MacDroid.app` (bundle id `dev.macdroid.mac`). There are no automated UI tests; Step 4 verifies it by hand.

- [ ] **Step 1: Install XcodeGen and write the project spec**

Run: `brew install xcodegen`

`mac/project.yml`:
```yaml
name: MacDroid
options:
  bundleIdPrefix: dev.macdroid
  deploymentTarget:
    macOS: "14.0"
packages:
  MacDroidCore:
    path: MacDroidCore
targets:
  MacDroid:
    type: application
    platform: macOS
    sources: [MacDroid]
    dependencies:
      - package: MacDroidCore
    info:
      path: MacDroid/Info.plist
      properties:
        CFBundleDisplayName: MacDroid
        CFBundleShortVersionString: "0.1.0"
        CFBundleVersion: "1"
        LSUIElement: true
        NSLocalNetworkUsageDescription: MacDroid connects to your Android phone over your local network.
        NSBonjourServices: [_macdroid._tcp]
    entitlements:
      path: MacDroid/MacDroid.entitlements
      properties:
        com.apple.security.app-sandbox: true
        com.apple.security.network.server: true
        com.apple.security.network.client: true
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: dev.macdroid.mac
        SWIFT_VERSION: "6.0"
        CODE_SIGN_STYLE: Automatic
        CODE_SIGN_IDENTITY: "-"
        ENABLE_HARDENED_RUNTIME: YES
```

- [ ] **Step 2: Write the app sources**

`mac/MacDroid/MacDroidApp.swift`:
```swift
import SwiftUI

@main
struct MacDroidApp: App {
    @State private var model: AppModel

    init() {
        let model = AppModel()
        model.start()
        _model = State(initialValue: model)
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContentView(model: model)
        } label: {
            Image(systemName: model.statusSymbol)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView(model: model)
        }
    }
}
```

`mac/MacDroid/AppModel.swift`:
```swift
import Foundation
import MacDroidCore
import Observation

@MainActor @Observable
final class AppModel {
    private(set) var status: ConnectionStatus = .disconnected
    private(set) var pairedDevice: PairedDevice?
    private(set) var invite: PairingInvite?
    private(set) var inviteExpiresAt: Date?
    private(set) var listeningPort: Int?
    private(set) var errorMessage: String?
    let macName: String
    let fingerprint: String

    @ObservationIgnored private let identity: Identity
    @ObservationIgnored private let pairing: PairingManager
    @ObservationIgnored private let hub: ConnectionHub
    @ObservationIgnored private let advertiser = BonjourAdvertiser()
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init() {
        let name = Host.current().localizedName ?? "Mac"
        macName = name
        var startupError: String?
        let loaded: Identity
        do {
            loaded = try KeychainIdentityStore().loadOrCreate(commonName: name)
        } catch {
            startupError = "Keychain unavailable (\(error)). Pairing will not survive a restart."
            loaded = try! Identity.generate(commonName: name)
        }
        identity = loaded
        fingerprint = loaded.fingerprint
        let store = UserDefaultsPairedDeviceStore()
        pairedDevice = store.load()
        pairing = PairingManager(store: store)
        hub = ConnectionHub(macName: name, appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0")
        errorMessage = startupError
    }

    var statusSymbol: String {
        switch (pairedDevice, status) {
        case (nil, _): "iphone.slash"
        case (_, .connected): "iphone.radiowaves.left.and.right"
        case (_, .disconnected): "iphone"
        }
    }

    func start() {
        guard tasks.isEmpty else { return }
        let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName)
        tasks.append(Task { [weak self] in
            do {
                try await server.run { port in await self?.serverStarted(port: port) }
            } catch {
                self?.errorMessage = "Server stopped: \(error)"
            }
        })
        tasks.append(Task { [weak self, hub] in
            for await status in hub.statusUpdates { self?.status = status }
        })
        tasks.append(Task { [weak self, pairing] in
            for await event in pairing.events { self?.handle(event) }
        })
    }

    func beginPairing() async {
        guard let port = listeningPort else { errorMessage = "Server is still starting — try again in a moment."; return }
        guard let host = LocalAddress.primaryIPv4() else { errorMessage = "No network connection. Join Wi-Fi and try again."; return }
        let window = await pairing.openWindow()
        invite = PairingInvite(host: host, port: port, fingerprint: fingerprint, code: window.code, name: macName)
        inviteExpiresAt = window.expiresAt
        let delay = max(0, window.expiresAt.timeIntervalSinceNow)
        Task { [pairing] in
            try? await Task.sleep(for: .seconds(delay))
            _ = await pairing.currentWindow() // closes the window on expiry → .windowClosed event
        }
    }

    func cancelPairing() async { await pairing.closeWindow() }

    func forgetPhone() async {
        await pairing.forget()
        await hub.disconnect()
    }

    private func serverStarted(port: Int) {
        listeningPort = port
        do {
            try advertiser.start(name: macName, port: UInt16(port), txt: ["v": "1", "fp": Fingerprint.short(fingerprint)])
        } catch {
            errorMessage = "Bonjour advertising failed: \(error)"
        }
    }

    private func handle(_ event: PairingEvent) {
        switch event {
        case .windowOpened: break
        case .windowClosed: invite = nil; inviteExpiresAt = nil
        case .paired(let device): pairedDevice = device
        case .forgotten: pairedDevice = nil
        }
    }
}
```

`mac/MacDroid/MenuContentView.swift`:
```swift
import AppKit
import MacDroidCore
import SwiftUI

struct MenuContentView: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MacDroid").font(.headline)
            statusRow
            if let error = model.errorMessage {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            Divider()
            HStack {
                SettingsLink {
                    Text(model.pairedDevice == nil ? "Pair a Phone…" : "Settings…")
                }
                .simultaneousGesture(TapGesture().onEnded { NSApp.activate() })
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 300)
    }

    @ViewBuilder private var statusRow: some View {
        switch (model.pairedDevice, model.status) {
        case (nil, _):
            Label("No phone paired", systemImage: "iphone.slash")
        case (_, .connected(let peer)):
            Label {
                VStack(alignment: .leading) {
                    Text(peer.device)
                    if let battery = peer.battery {
                        Text("Battery \(battery)%\(peer.charging == true ? " · charging" : "")")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            } icon: {
                Image(systemName: "iphone.radiowaves.left.and.right")
            }
        case (let device?, .disconnected):
            Label("\(device.name) — not connected", systemImage: "iphone")
        }
    }
}
```

`mac/MacDroid/SettingsView.swift`:
```swift
import MacDroidCore
import ServiceManagement
import SwiftUI

struct SettingsView: View {
    let model: AppModel

    var body: some View {
        TabView {
            PairingView(model: model)
                .tabItem { Label("Pairing", systemImage: "qrcode") }
            GeneralSettingsView(model: model)
                .tabItem { Label("General", systemImage: "gearshape") }
        }
        .frame(width: 460, height: 560)
    }
}

struct GeneralSettingsView: View {
    let model: AppModel
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var errorText: String?

    var body: some View {
        Form {
            Toggle("Launch MacDroid at login", isOn: $launchAtLogin)
                .onChange(of: launchAtLogin) { _, enabled in
                    do {
                        if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                        errorText = nil
                    } catch {
                        errorText = error.localizedDescription
                    }
                }
            if let errorText { Text(errorText).foregroundStyle(.red) }
            LabeledContent("This Mac's fingerprint", value: Fingerprint.display(model.fingerprint))
                .font(.system(.body, design: .monospaced))
        }
        .padding()
    }
}
```

`mac/MacDroid/PairingView.swift`:
```swift
import MacDroidCore
import SwiftUI

struct PairingView: View {
    let model: AppModel

    var body: some View {
        VStack(spacing: 16) {
            if let device = model.pairedDevice {
                Label("Paired with \(device.name) (\(device.model))", systemImage: "checkmark.seal")
                Button("Forget This Phone", role: .destructive) { Task { await model.forgetPhone() } }
                Divider()
            }
            if let invite = model.invite {
                if let image = QRCode.image(for: invite.uri) {
                    Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                }
                Text("Scan with the MacDroid app on your phone").font(.callout)
                if let expires = model.inviteExpiresAt, expires > Date() {
                    Text(timerInterval: Date()...expires, countsDown: true)
                        .monospacedDigit().foregroundStyle(.secondary)
                }
                GroupBox("Can't scan? Enter these on the phone") {
                    Grid(alignment: .leading, verticalSpacing: 6) {
                        GridRow { Text("Address"); Text("\(invite.host):\(String(invite.port))").textSelection(.enabled) }
                        GridRow { Text("Code"); Text(invite.displayCode).textSelection(.enabled) }
                        GridRow { Text("Fingerprint"); Text(Fingerprint.display(invite.fingerprint)) }
                    }
                    .font(.system(.body, design: .monospaced))
                }
                Button("Cancel") { Task { await model.cancelPairing() } }
            } else {
                Button(model.pairedDevice == nil ? "Pair a Phone" : "Pair a Different Phone") {
                    Task { await model.beginPairing() }
                }
                .buttonStyle(.borderedProminent)
            }
            if let error = model.errorMessage {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
```

`mac/MacDroid/QRCode.swift`:
```swift
import AppKit
import CoreImage.CIFilterBuiltins

enum QRCode {
    static func image(for string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cgImage = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}
```

- [ ] **Step 3: Generate and build**

Run:
```bash
cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build 2>&1 | tail -3
```
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Verify by hand**

1. Run `open mac/build/Build/Products/Debug/MacDroid.app`. A phone-with-slash icon appears in the menu bar. Allow the Local Network prompt if it appears.
2. Run `dns-sd -B _macdroid._tcp` in a terminal. Expected: an `Add` line with this Mac's name. Press Ctrl-C.
3. Run `dns-sd -L "<that name>" _macdroid._tcp`. Expected: the TXT record shows `fp=` followed by 16 hex characters, plus `v=1`.
4. Open the menu-bar icon, click **Pair a Phone…**, then **Pair a Phone**. Expected: a QR code, a 2:00 countdown, and the manual details. Clicking **Cancel** hides them.
5. Run `openssl s_client -connect 127.0.0.1:<port from dns-sd -L> </dev/null`. Expected: the handshake fails with an alert, because no client certificate was sent.

- [ ] **Step 5: Commit**

```bash
git add mac/project.yml mac/MacDroid
git commit -m "feat(mac): menu-bar app with pairing QR, status and launch at login"
```

---

## Task 8: Android project scaffold and Kotlin message codec

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/local.properties` (gitignored), Gradle wrapper
- Create: `android/core/build.gradle.kts`
- Create: `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt`, `.../protocol/MessageCodec.kt`
- Create: `android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt`, `.../protocol/MessageCodecTest.kt`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt` (placeholder)

**Interfaces:**
- Consumes: `protocol/fixtures/*.json` (Task 1)
- Produces:
  - Body classes (`@Serializable`): `HelloBody(protocol = 1, app, device, features = [])`, `DeviceStatusBody(battery, charging)`, `PairRequest(code, deviceName, model)`, `PairResponse(macName)`, `ServerInfo(macName, protocol)`
  - `sealed interface Message { Hello(body), DeviceStatus(body), Unknown(type) }`
  - `MessageCodec.encode(message, id = uuid): String` (throws `IllegalArgumentException` for `Unknown`)
  - `MessageCodec.decode(text): Message` (throws `MalformedMessageException`)
  - `MessageCodec.json: Json`, `MessageCodec.PROTOCOL_VERSION`
  - Test helpers: `Fixtures.text(name)`, `Fixtures.id(text)`

- [ ] **Step 1: Create the Gradle build**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MacDroid"
include(":core", ":app")
```

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
```

`android/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android/local.properties` (not committed; `.gitignore` already lists `local.properties`):
```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Generate the wrapper: `cd android && gradle wrapper --gradle-version 9.8.0`

`android/core/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.5.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}

tasks.test {
    systemProperty("fixturesDir", rootProject.file("../protocol/fixtures").absolutePath)
    providers.environmentVariable("MACDROID_DEV_URI").orNull?.let { systemProperty("macdroidDevUri", it) }
}
```

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.macdroid.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.macdroid.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}
```

`android/app/src/main/AndroidManifest.xml` (placeholder; Task 13 replaces it):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="MacDroid" android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".ui.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt` (placeholder; Task 14 replaces it):
```kotlin
package dev.macdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("MacDroid") }
    }
}
```

- [ ] **Step 2: Write the failing codec tests**

`android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt`:
```kotlin
package dev.macdroid.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

object Fixtures {
    private val dir = File(System.getProperty("fixturesDir") ?: error("fixturesDir system property not set"))
    fun text(name: String): String = File(dir, name).readText()
    fun id(text: String): String = Json.parseToJsonElement(text).jsonObject.getValue("id").jsonPrimitive.content
}

/** Polls [condition] until true or fails after [timeoutMs]. */
fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        Thread.sleep(20)
    }
    fail("condition not met within ${timeoutMs}ms")
}
```

`android/core/src/test/kotlin/dev/macdroid/core/protocol/MessageCodecTest.kt`:
```kotlin
package dev.macdroid.core.protocol

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageCodecTest {
    @Test fun helloFixtureDecodes() {
        assertEquals(
            Message.Hello(HelloBody(protocol = 1, app = "0.1.0", device = "Nothing Phone (3)", features = emptyList())),
            MessageCodec.decode(Fixtures.text("hello.json")),
        )
    }

    @Test fun deviceStatusFixtureDecodes() {
        assertEquals(Message.DeviceStatus(DeviceStatusBody(82, true)), MessageCodec.decode(Fixtures.text("device.status.json")))
    }

    @Test fun fixturesRoundTrip() {
        for (name in listOf("hello.json", "device.status.json")) {
            val text = Fixtures.text(name)
            val reencoded = MessageCodec.encode(MessageCodec.decode(text), id = Fixtures.id(text))
            assertEquals(name, Json.parseToJsonElement(text), Json.parseToJsonElement(reencoded))
        }
    }

    @Test fun unknownTypeIsReported() {
        assertEquals(Message.Unknown("future.thing"), MessageCodec.decode(Fixtures.text("unknown-type.json")))
    }

    @Test fun malformedFramesThrow() {
        for (text in listOf("not json", "{}", """{"type":"hello","body":{"protocol":"x"}}""", "[1,2]", """{"type":"hello"}""")) {
            assertThrows(text, MalformedMessageException::class.java) { MessageCodec.decode(text) }
        }
    }

    @Test fun encodingUnknownThrows() {
        assertThrows(IllegalArgumentException::class.java) { MessageCodec.encode(Message.Unknown("x")) }
    }

    @Test fun pairBodiesMatchFixtures() {
        assertEquals(
            PairRequest("MZXW6YTBOI2DGNBVGY3TQOJQGE", "My Phone", "Nothing Phone (3)"),
            MessageCodec.json.decodeFromString(PairRequest.serializer(), Fixtures.text("pair.request.json")),
        )
        assertEquals(PairResponse("MacBook Pro"), MessageCodec.json.decodeFromString(PairResponse.serializer(), Fixtures.text("pair.response.json")))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd android && ./gradlew :core:test`
Expected: compilation fails with `Unresolved reference 'MessageCodec'`.

- [ ] **Step 4: Implement messages and codec**

`android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt`:
```kotlin
package dev.macdroid.core.protocol

import kotlinx.serialization.Serializable

@Serializable
data class HelloBody(
    val protocol: Int = MessageCodec.PROTOCOL_VERSION,
    val app: String,
    val device: String,
    val features: List<String> = emptyList(),
)

@Serializable
data class DeviceStatusBody(val battery: Int, val charging: Boolean)

@Serializable
data class PairRequest(val code: String, val deviceName: String, val model: String)

@Serializable
data class PairResponse(val macName: String)

@Serializable
data class ServerInfo(val macName: String, val protocol: Int)

/** A control-channel message. Later plans add notification, clipboard and transfer types. */
sealed interface Message {
    data class Hello(val body: HelloBody) : Message
    data class DeviceStatus(val body: DeviceStatusBody) : Message
    /** A type this version doesn't understand; decoded so callers can ignore it, never encoded. */
    data class Unknown(val type: String) : Message
}
```

`android/core/src/main/kotlin/dev/macdroid/core/protocol/MessageCodec.kt`:
```kotlin
package dev.macdroid.core.protocol

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class MalformedMessageException(cause: Throwable? = null) : Exception("malformed message", cause)

object MessageCodec {
    const val PROTOCOL_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: Message, id: String = UUID.randomUUID().toString()): String {
        val (type, body) = when (message) {
            is Message.Hello -> "hello" to json.encodeToJsonElement(HelloBody.serializer(), message.body)
            is Message.DeviceStatus -> "device.status" to json.encodeToJsonElement(DeviceStatusBody.serializer(), message.body)
            is Message.Unknown -> throw IllegalArgumentException("cannot encode unknown message type ${message.type}")
        }
        return buildJsonObject {
            put("v", PROTOCOL_VERSION)
            put("type", type)
            put("id", id)
            put("body", body)
        }.toString()
    }

    fun decode(text: String): Message {
        try {
            val envelope = json.parseToJsonElement(text).jsonObject
            val type = (envelope["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw MalformedMessageException()
            val body = envelope["body"] ?: JsonObject(emptyMap())
            return when (type) {
                "hello" -> Message.Hello(json.decodeFromJsonElement(HelloBody.serializer(), body))
                "device.status" -> Message.DeviceStatus(json.decodeFromJsonElement(DeviceStatusBody.serializer(), body))
                else -> Message.Unknown(type)
            }
        } catch (e: IllegalArgumentException) { // includes SerializationException
            throw MalformedMessageException(e)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass, and check that the app builds**

Run: `cd android && ./gradlew :core:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android
git commit -m "feat(android): Gradle project and Kotlin message codec sharing protocol fixtures"
```

---

## Task 9: Android TLS core (pinning, client identity, trust failures)

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/tls/Fingerprint.kt`, `TrustManagers.kt`, `TlsClientFactory.kt`, `TrustFailures.kt`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt` (add `MockMac`)
- Test: `android/core/src/test/kotlin/dev/macdroid/core/tls/TlsTest.kt`

**Interfaces:**
- Consumes: `MessageCodec`, `Message`, `HelloBody` (Task 8)
- Produces:
  - `Fingerprint`: `sha256Hex(ByteArray)`, `of(X509Certificate)`, `short(String)`, `display(String)`
  - `data class ClientIdentity(privateKey: PrivateKey, certificate: X509Certificate)`, with a `fingerprint` property
  - Trust managers: `PinnedTrustManager(expectedFingerprint)`, `CapturingTrustManager` (property `captured: String?`)
  - `TlsClientFactory`: `create(identity, trustManager, base = OkHttpClient()): OkHttpClient` and `pinned(identity, fingerprint, base = OkHttpClient())`
  - `TrustFailures.isTrustFailure(Throwable): Boolean`
  - Test helper `MockMac(macName)`:
    - Properties: `server`, `fingerprint`, `phoneIdentity`, `received: LinkedBlockingQueue<Message>`
    - Methods: `start()`, `pairedMac()` (added in Task 10), `enqueueJson(code, body)`, `enqueueSession(closeAfterHello)`, `close()`

- [ ] **Step 1: Add the MockMac test helper**

Append to `android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt`:
```kotlin
// --- MockMac: an mTLS MockWebServer that behaves like the Mac ---

/**
 * A MockWebServer with its own self-signed certificate that requires the phone's client certificate,
 * mirroring the real Mac server's mutual TLS.
 */
class MockMac(val macName: String = "Test Mac") : java.io.Closeable {
    private val serverCert = okhttp3.tls.HeldCertificate.Builder().ecdsa256().commonName("mac").build()
    private val phoneCert = okhttp3.tls.HeldCertificate.Builder().ecdsa256().commonName("phone").build()
    val phoneIdentity = dev.macdroid.core.tls.ClientIdentity(phoneCert.keyPair.private, phoneCert.certificate)
    val fingerprint: String = dev.macdroid.core.tls.Fingerprint.of(serverCert.certificate)
    val received = java.util.concurrent.LinkedBlockingQueue<dev.macdroid.core.protocol.Message>()
    val server = mockwebserver3.MockWebServer().apply {
        useHttps(
            okhttp3.tls.HandshakeCertificates.Builder()
                .heldCertificate(serverCert)
                .addTrustedCertificate(phoneCert.certificate)
                .build()
                .sslSocketFactory(),
        )
        requireClientAuth()
    }

    fun start() = server.start()

    fun enqueueJson(code: Int, body: String) {
        server.enqueue(mockwebserver3.MockResponse.Builder().code(code).body(body).build())
    }

    /** Next request upgrades to a WebSocket that answers the phone's hello with the Mac's hello. */
    fun enqueueSession(closeAfterHello: Boolean = false) {
        server.enqueue(
            mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val message = dev.macdroid.core.protocol.MessageCodec.decode(text)
                    received.add(message)
                    if (message is dev.macdroid.core.protocol.Message.Hello) {
                        webSocket.send(
                            dev.macdroid.core.protocol.MessageCodec.encode(
                                dev.macdroid.core.protocol.Message.Hello(
                                    dev.macdroid.core.protocol.HelloBody(app = "0.1.0", device = macName),
                                ),
                            ),
                        )
                        if (closeAfterHello) webSocket.close(1000, "bye")
                    }
                }
            }).build(),
        )
    }

    override fun close() = server.close()
}
```

- [ ] **Step 2: Write the failing tests**

`android/core/src/test/kotlin/dev/macdroid/core/tls/TlsTest.kt`:
```kotlin
package dev.macdroid.core.tls

import dev.macdroid.core.MockMac
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTest {
    @Test fun fingerprintMatchesSwiftVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Fingerprint.sha256Hex("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea", Fingerprint.short("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertEquals("BA78 16BF 8F01 CFEA", Fingerprint.display("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test fun pinnedTrustManagerChecksLeafFingerprint() {
        val cert = HeldCertificate.Builder().ecdsa256().build().certificate
        PinnedTrustManager(Fingerprint.of(cert)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) { PinnedTrustManager("00".repeat(32)).checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { PinnedTrustManager(Fingerprint.of(cert)).checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
    }

    @Test fun capturingTrustManagerRecordsFingerprint() {
        val cert = HeldCertificate.Builder().ecdsa256().build().certificate
        val capture = CapturingTrustManager()
        capture.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertEquals(Fingerprint.of(cert), capture.captured)
    }

    @Test fun pinnedClientPresentsIdentityOverTls13() {
        MockMac().use { mac ->
            mac.enqueueJson(200, "{}")
            mac.start()
            val client = TlsClientFactory.pinned(mac.phoneIdentity, mac.fingerprint)
            client.newCall(Request.Builder().url(mac.server.url("/v1/info")).build()).execute().use { assertEquals(200, it.code) }
            val handshake = mac.server.takeRequest().handshake
            assertEquals(mac.phoneIdentity.certificate, handshake?.peerCertificates?.first())
            assertEquals(TlsVersion.TLS_1_3, handshake?.tlsVersion)
        }
    }

    @Test fun wrongPinIsATrustFailure() {
        MockMac().use { mac ->
            mac.start()
            val client = TlsClientFactory.pinned(mac.phoneIdentity, "00".repeat(32))
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(mac.server.url("/v1/info")).build()).execute()
            }
            assertTrue(TrustFailures.isTrustFailure(error))
        }
    }

    @Test fun trustFailureClassification() {
        assertTrue(TrustFailures.isTrustFailure(SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertTrue(TrustFailures.isTrustFailure(IOException("wrapped", CertificateException("fingerprint mismatch"))))
        assertTrue(TrustFailures.isTrustFailure(javax.net.ssl.SSLException("error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN")))
        assertFalse(TrustFailures.isTrustFailure(IOException("Connection refused")))
        assertFalse(TrustFailures.isTrustFailure(java.net.ProtocolException("Expected HTTP 101 response but was '500 Server Error'")))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.tls.*'`
Expected: compilation fails (`Unresolved reference 'Fingerprint'`).

- [ ] **Step 4: Implement the TLS core**

`android/core/src/main/kotlin/dev/macdroid/core/tls/Fingerprint.kt`:
```kotlin
package dev.macdroid.core.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

object Fingerprint {
    /** Lowercase hex SHA-256 — the value both apps pin. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun of(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /** First 16 hex chars, as published in the Mac's Bonjour TXT record. */
    fun short(fingerprint: String): String = fingerprint.take(16)

    /** Grouped form both apps show for manual comparison, e.g. "8A3B A983 C438 8481". */
    fun display(fingerprint: String): String = short(fingerprint).uppercase().chunked(4).joinToString(" ")
}
```

`android/core/src/main/kotlin/dev/macdroid/core/tls/TrustManagers.kt`:
```kotlin
package dev.macdroid.core.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Trusts exactly one server certificate, identified by its SHA-256 fingerprint. */
class PinnedTrustManager(private val expectedFingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        if (Fingerprint.of(leaf) != expectedFingerprint) throw CertificateException("certificate fingerprint mismatch")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts any server certificate but records its fingerprint. Only for the manual-pairing probe,
 * whose result the user must confirm against the fingerprint shown on the Mac.
 */
class CapturingTrustManager : X509TrustManager {
    @Volatile var captured: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        captured = Fingerprint.of(chain.firstOrNull() ?: throw CertificateException("empty certificate chain"))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
```

`android/core/src/main/kotlin/dev/macdroid/core/tls/TlsClientFactory.kt`:
```kotlin
package dev.macdroid.core.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion

/** The phone's TLS client identity. On Android the key lives in the Keystore. */
data class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String get() = Fingerprint.of(certificate)
}

/** Always offers the one client identity. */
internal class FixedKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?) = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?) = identity.privateKey
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object { const val ALIAS = "macdroid" }
}

object TlsClientFactory {
    private val TLS_1_3_ONLY = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
        .tlsVersions(TlsVersion.TLS_1_3)
        .build()

    fun create(identity: ClientIdentity, trustManager: X509TrustManager, base: OkHttpClient = OkHttpClient()): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(identity)), arrayOf(trustManager), null)
        }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            // The Mac has no CA-issued name; its identity is the pinned fingerprint, so hostnames are not checked.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(TLS_1_3_ONLY))
            .build()
    }

    fun pinned(identity: ClientIdentity, fingerprint: String, base: OkHttpClient = OkHttpClient()): OkHttpClient =
        create(identity, PinnedTrustManager(fingerprint), base)
}
```

`android/core/src/main/kotlin/dev/macdroid/core/tls/TrustFailures.kt`:
```kotlin
package dev.macdroid.core.tls

import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** Distinguishes "wrong Mac / Mac doesn't know this phone" from ordinary network failures. */
object TrustFailures {
    private val alertMarkers = listOf("certificate_unknown", "bad_certificate", "certificate_required", "unknown_ca", "access_denied")

    fun isTrustFailure(error: Throwable): Boolean {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is CertificateException || current is SSLPeerUnverifiedException) return true
            val message = current.message?.lowercase()
            if (current is SSLException && message != null && alertMarkers.any { it in message }) return true
            current = current.cause
        }
        return false
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.tls.*'`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/core
git commit -m "feat(android): pinned mutual-TLS OkHttp client and trust-failure detection"
```

---

## Task 10: Android pairing (invite parsing, store, pairing client)

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/pairing/PairingInvite.kt`, `PairingStore.kt`, `PairingClient.kt`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/TestSupport.kt` (add `MockMac.pairedMac()`)
- Test: `android/core/src/test/kotlin/dev/macdroid/core/pairing/PairingTest.kt`

**Interfaces:**
- Consumes: `TlsClientFactory`, `CapturingTrustManager`, `TrustFailures`, `ClientIdentity`, `MessageCodec.json`, `PairRequest`, `PairResponse`, `Fixtures`, `MockMac`
- Produces:
  - `data class PairingInvite(host, port, fingerprint, code, name)`, with `PairingInvite.parse(uri): PairingInvite?` and `PairingInvite.normalizeCode(raw): String`
  - `@Serializable data class PairedMac(fingerprint, name, host, port)`
  - `interface PairingStore { val paired: StateFlow<PairedMac?>; fun save(mac); fun clear() }`, implemented by `InMemoryPairingStore(initial)`
  - `class PairingClient(identity, base = OkHttpClient())`:
    - `suspend pair(invite, deviceName, model): PairResult`
    - `suspend probeFingerprint(host, port): String?`
  - `sealed interface PairResult { Success(mac), CodeRejected, FingerprintMismatch, Failed(reason) }`

- [ ] **Step 1: Add the `pairedMac()` helper**

In `TestSupport.kt`, add this method inside `class MockMac` (after `start()`):
```kotlin
    fun pairedMac() = dev.macdroid.core.pairing.PairedMac(fingerprint, macName, server.hostName, server.port)
```

- [ ] **Step 2: Write the failing tests**

`android/core/src/test/kotlin/dev/macdroid/core/pairing/PairingTest.kt`:
```kotlin
package dev.macdroid.core.pairing

import dev.macdroid.core.Fixtures
import dev.macdroid.core.MockMac
import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.protocol.PairRequest
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    @Test fun parsesSharedFixtureUri() {
        val invite = PairingInvite.parse(Fixtures.text("pairing-uri.txt"))
        assertEquals(
            PairingInvite(
                host = "192.168.1.20",
                port = 47999,
                fingerprint = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a",
                code = "MZXW6YTBOI2DGNBVGY3TQOJQGE",
                name = "Sam's Mac+Pro ✨",
            ),
            invite,
        )
    }

    @Test fun rejectsInvalidUris() {
        val fp = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a"
        assertNull(PairingInvite.parse("https://pair?host=1.2.3.4&port=1&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=1&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=70000&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=1.2.3.4&port=1&fp=NOTHEX&code=A"))
        assertNull(PairingInvite.parse("macdroid://pair?host=&port=1&fp=$fp&code=A"))
        assertNull(PairingInvite.parse("not a uri at all"))
    }

    @Test fun normalizesTypedCodes() {
        assertEquals("MZXW6YTBOI2D", PairingInvite.normalizeCode(" mzxw 6ytb-oi2d "))
    }

    private fun invite(mac: MockMac, code: String = "CODE") =
        PairingInvite(mac.server.hostName, mac.server.port, mac.fingerprint, code, "Test Mac")

    @Test fun successfulPairingReturnsMacAndSendsRequest() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(200, """{"macName":"Studio"}""")
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac), "My Phone", "Nothing Phone (3)")
            assertEquals(PairResult.Success(PairedMac(mac.fingerprint, "Studio", mac.server.hostName, mac.server.port)), result)
            val recorded = mac.server.takeRequest()
            assertEquals("/v1/pair", recorded.url.encodedPath)
            assertEquals(
                PairRequest("CODE", "My Phone", "Nothing Phone (3)"),
                MessageCodec.json.decodeFromString(PairRequest.serializer(), recorded.body!!.utf8()),
            )
        }
    }

    @Test fun forbiddenMeansCodeRejected() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(403, "")
            mac.start()
            assertEquals(PairResult.CodeRejected, PairingClient(mac.phoneIdentity).pair(invite(mac), "P", "M"))
        }
    }

    @Test fun wrongFingerprintIsMismatch() = runBlocking {
        MockMac().use { mac ->
            mac.start()
            val result = PairingClient(mac.phoneIdentity).pair(invite(mac).copy(fingerprint = "00".repeat(32)), "P", "M")
            assertEquals(PairResult.FingerprintMismatch, result)
        }
    }

    @Test fun unreachableMacIsFailed() = runBlocking {
        val deadPort = ServerSocket(0).use { it.localPort }
        val identity = MockMac().use { it.phoneIdentity }
        val result = PairingClient(identity).pair(PairingInvite("127.0.0.1", deadPort, "00".repeat(32), "C", "Mac"), "P", "M")
        assertTrue(result is PairResult.Failed)
    }

    @Test fun probeCapturesServerFingerprint() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueJson(403, "")
            mac.start()
            assertEquals(mac.fingerprint, PairingClient(mac.phoneIdentity).probeFingerprint(mac.server.hostName, mac.server.port))
        }
    }

    @Test fun inMemoryStoreEmitsChanges() {
        val store = InMemoryPairingStore()
        val mac = PairedMac("aa", "Mac", "h", 1)
        store.save(mac)
        assertEquals(mac, store.paired.value)
        store.clear()
        assertNull(store.paired.value)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.pairing.*'`
Expected: compilation fails (`Unresolved reference 'PairingInvite'`).

- [ ] **Step 4: Implement pairing**

`android/core/src/main/kotlin/dev/macdroid/core/pairing/PairingInvite.kt`:
```kotlin
package dev.macdroid.core.pairing

import java.net.URI
import java.net.URLDecoder

/** Contents of the Mac's pairing QR code (see protocol/fixtures/pairing-uri.txt). */
data class PairingInvite(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val name: String,
) {
    companion object {
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        fun parse(uri: String): PairingInvite? {
            val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (parsed.scheme != "macdroid" || parsed.host != "pair") return null
            val params = parsed.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }.associate { part ->
                val split = part.indexOf('=')
                if (split < 0) part to "" else part.substring(0, split) to decode(part.substring(split + 1))
            }
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = params["fp"]?.takeIf { HEX_64.matches(it) } ?: return null
            val code = normalizeCode(params["code"].orEmpty()).takeIf { it.isNotEmpty() } ?: return null
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: "Mac"
            return PairingInvite(host, port, fingerprint, code, name)
        }

        /** Accepts codes typed with spaces, dashes or lowercase. */
        fun normalizeCode(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

        // The String-charset overload works on every Android version (the Charset one needs API 33).
        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }
}
```

`android/core/src/main/kotlin/dev/macdroid/core/pairing/PairingStore.kt`:
```kotlin
package dev.macdroid.core.pairing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** The Mac this phone trusts, plus its last known address. */
@Serializable
data class PairedMac(val fingerprint: String, val name: String, val host: String, val port: Int)

interface PairingStore {
    val paired: StateFlow<PairedMac?>
    fun save(mac: PairedMac)
    fun clear()
}

class InMemoryPairingStore(initial: PairedMac? = null) : PairingStore {
    private val state = MutableStateFlow(initial)
    override val paired: StateFlow<PairedMac?> = state.asStateFlow()
    override fun save(mac: PairedMac) { state.value = mac }
    override fun clear() { state.value = null }
}
```

`android/core/src/main/kotlin/dev/macdroid/core/pairing/PairingClient.kt`:
```kotlin
package dev.macdroid.core.pairing

import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.protocol.PairRequest
import dev.macdroid.core.protocol.PairResponse
import dev.macdroid.core.tls.CapturingTrustManager
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import dev.macdroid.core.tls.TrustFailures
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface PairResult {
    data class Success(val mac: PairedMac) : PairResult
    /** The Mac answered 403: wrong code or the pairing window closed. */
    data object CodeRejected : PairResult
    /** The server's certificate is not the one in the QR code (or it refused our certificate). */
    data object FingerprintMismatch : PairResult
    data class Failed(val reason: String) : PairResult
}

class PairingClient(private val identity: ClientIdentity, private val base: OkHttpClient = OkHttpClient()) {

    suspend fun pair(invite: PairingInvite, deviceName: String, model: String): PairResult = withContext(Dispatchers.IO) {
        try {
            val client = TlsClientFactory.pinned(identity, invite.fingerprint, base)
            val body = MessageCodec.json.encodeToString(PairRequest.serializer(), PairRequest(invite.code, deviceName, model))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url(invite.host, invite.port, "v1/pair")).post(body).build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val reply = MessageCodec.json.decodeFromString(PairResponse.serializer(), response.body.string())
                        PairResult.Success(PairedMac(invite.fingerprint, reply.macName, invite.host, invite.port))
                    }
                    response.code == 403 -> PairResult.CodeRejected
                    else -> PairResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            if (TrustFailures.isTrustFailure(e)) PairResult.FingerprintMismatch else PairResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            PairResult.Failed("unexpected reply from Mac")
        } catch (e: IllegalArgumentException) {
            PairResult.Failed("invalid address")
        }
    }

    /**
     * Manual pairing: completes a TLS handshake without trusting the server, returning its
     * fingerprint for the user to compare with the Mac. Null if the Mac can't be reached.
     */
    suspend fun probeFingerprint(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        val capture = CapturingTrustManager()
        runCatching {
            TlsClientFactory.create(identity, capture, base)
                .newCall(Request.Builder().url(url(host, port, "v1/info")).build())
                .execute()
                .close()
        }
        capture.captured
    }

    private fun url(host: String, port: Int, path: String) =
        HttpUrl.Builder().scheme("https").host(host).port(port).addPathSegments(path).build()
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.pairing.*'`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add android/core
git commit -m "feat(android): pairing invite parsing, pairing client and store"
```

---

## Task 11: Android connection manager (WebSocket, reconnect, discovery hook)

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/connection/Backoff.kt`, `MacLocator.kt`, `ConnectionManager.kt`
- Test: `android/core/src/test/kotlin/dev/macdroid/core/connection/ConnectionManagerTest.kt`

**Interfaces:**
- Consumes: `PairingStore`, `PairedMac`, `ClientIdentity`, `TlsClientFactory`, `TrustFailures`, `Fingerprint`, `MessageCodec`, `Message`, `HelloBody`, `MockMac`, `eventually`
- Produces:
  - `class Backoff(initialMs = 1000, maxMs = 30000)`: `next(): Long`, `reset()`
  - `data class MacAddress(host, port)`
  - `fun interface MacLocator { fun appearances(fingerprint: String): Flow<MacAddress> }`, with companion members `MacLocator.None` and `MacLocator.txtMatches(attributes: Map<String, ByteArray?>, fingerprint): Boolean`
  - `sealed interface ConnectionState { Stopped, NotPaired, Connecting, Connected(macName), Waiting(retryInMs), TrustFailure(detail) }`
  - `class ConnectionManager(store, identity: () -> ClientIdentity, locator, hello: () -> HelloBody, baseClient = OkHttpClient(), backoff = Backoff(), dispatcher = Dispatchers.IO)`:
    - Flows: `state: StateFlow<ConnectionState>`, `incoming: SharedFlow<Message>`
    - Methods: `start()`, `stop()`, `nudge()`, `send(message): Boolean`

- [ ] **Step 1: Write the failing tests**

`android/core/src/test/kotlin/dev/macdroid/core/connection/ConnectionManagerTest.kt`:
```kotlin
package dev.macdroid.core.connection

import dev.macdroid.core.MockMac
import dev.macdroid.core.eventually
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.Message
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {
    private val phoneHello = HelloBody(app = "0.1.0", device = "Test Phone")

    private fun manager(mac: MockMac, store: PairingStore, locator: MacLocator = MacLocator.None, backoff: Backoff = Backoff(50, 200)) =
        ConnectionManager(store, { mac.phoneIdentity }, locator, { phoneHello }, backoff = backoff)

    private suspend fun ConnectionManager.awaitState(timeoutMs: Long = 5_000, predicate: (ConnectionState) -> Boolean) =
        withTimeout(timeoutMs) { state.first(predicate) }

    @Test fun backoffDoublesAndCaps() {
        val backoff = Backoff(1_000, 30_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { backoff.next() })
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }

    @Test fun txtRecordMatching() {
        val fp = "8a3ba983c43884812d5198df10a6891c193f3fb67d1e5b44c6902e9fde37b45a"
        assertTrue(MacLocator.txtMatches(mapOf("fp" to "8a3ba983c4388481".toByteArray(), "v" to "1".toByteArray()), fp))
        assertFalse(MacLocator.txtMatches(mapOf("fp" to "0000000000000000".toByteArray()), fp))
        assertFalse(MacLocator.txtMatches(emptyMap(), fp))
    }

    @Test fun connectsAndExchangesHello() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                assertEquals(ConnectionState.Connected("Test Mac"), manager.awaitState { it is ConnectionState.Connected })
                assertEquals(Message.Hello(phoneHello), mac.received.poll(5, TimeUnit.SECONDS))
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun sendDeliversMessages() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Connected }
                assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(55, false))))
                mac.received.poll(5, TimeUnit.SECONDS) // hello
                assertEquals(Message.DeviceStatus(DeviceStatusBody(55, false)), mac.received.poll(5, TimeUnit.SECONDS))
            } finally {
                manager.stop()
            }
            assertFalse(manager.send(Message.DeviceStatus(DeviceStatusBody(1, false))))
            assertEquals(ConnectionState.Stopped, manager.state.value)
        }
    }

    @Test fun waitsWhileNotPaired() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val store = InMemoryPairingStore()
            val manager = manager(mac, store)
            manager.start()
            try {
                manager.awaitState { it == ConnectionState.NotPaired }
                store.save(mac.pairedMac())
                manager.awaitState { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun reconnectsAfterMacCloses() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession(closeAfterHello = true)
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()))
            manager.start()
            try {
                eventually { mac.server.requestCount >= 2 }
                manager.awaitState { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun wrongPinIsTrustFailure() = runBlocking {
        MockMac().use { mac ->
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac().copy(fingerprint = "00".repeat(32))))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.TrustFailure }
            } finally {
                manager.stop()
            }
        }
    }

    // Review Focus #1
    @Test fun movesToAddressReportedByLocator() = runBlocking {
        MockMac().use { mac ->
            mac.enqueueSession()
            mac.start()
            val deadPort = ServerSocket(0).use { it.localPort }
            val store = InMemoryPairingStore(mac.pairedMac().copy(port = deadPort))
            val locator = MacLocator { flowOf(MacAddress(mac.server.hostName, mac.server.port)) }
            val manager = manager(mac, store, locator, backoff = Backoff(10_000, 10_000))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Connected }
                assertEquals(mac.server.port, store.paired.value?.port)
            } finally {
                manager.stop()
            }
        }
    }

    @Test fun nudgeRetriesImmediately() = runBlocking {
        MockMac().use { mac ->
            mac.server.enqueue(MockResponse.Builder().code(500).build())
            mac.enqueueSession()
            mac.start()
            val manager = manager(mac, InMemoryPairingStore(mac.pairedMac()), backoff = Backoff(10_000, 10_000))
            manager.start()
            try {
                manager.awaitState { it is ConnectionState.Waiting }
                manager.nudge()
                manager.awaitState(timeoutMs = 2_000) { it is ConnectionState.Connected }
            } finally {
                manager.stop()
            }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :core:test --tests 'dev.macdroid.core.connection.*'`
Expected: compilation fails (`Unresolved reference 'ConnectionManager'`).

- [ ] **Step 3: Implement Backoff and MacLocator**

`android/core/src/main/kotlin/dev/macdroid/core/connection/Backoff.kt`:
```kotlin
package dev.macdroid.core.connection

/** Exponential backoff: initial, ×2 each step, capped. Used only from the connection loop. */
class Backoff(private val initialMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var current = initialMs

    fun next(): Long {
        val value = current
        current = (current * 2).coerceAtMost(maxMs)
        return value
    }

    fun reset() {
        current = initialMs
    }
}
```

`android/core/src/main/kotlin/dev/macdroid/core/connection/MacLocator.kt`:
```kotlin
package dev.macdroid.core.connection

import dev.macdroid.core.tls.Fingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MacAddress(val host: String, val port: Int)

/** Finds the paired Mac on the network. Cold flow: collecting starts discovery, cancelling stops it. */
fun interface MacLocator {
    fun appearances(fingerprint: String): Flow<MacAddress>

    companion object {
        val None = MacLocator { emptyFlow() }

        /** The Mac advertises `fp=<first 16 hex of its fingerprint>` in its Bonjour TXT record. */
        fun txtMatches(attributes: Map<String, ByteArray?>, fingerprint: String): Boolean =
            attributes["fp"]?.toString(Charsets.UTF_8) == Fingerprint.short(fingerprint)
    }
}
```

- [ ] **Step 4: Implement ConnectionManager**

`android/core/src/main/kotlin/dev/macdroid/core/connection/ConnectionManager.kt`:
```kotlin
package dev.macdroid.core.connection

import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.MalformedMessageException
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.MessageCodec
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import dev.macdroid.core.tls.TrustFailures
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data object NotPaired : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val macName: String) : ConnectionState
    data class Waiting(val retryInMs: Long) : ConnectionState
    /** The Mac's certificate isn't the pinned one, or the Mac rejected ours. The user must re-pair. */
    data class TrustFailure(val detail: String) : ConnectionState
}

/** Keeps one WebSocket to the paired Mac alive, reconnecting with backoff. */
class ConnectionManager(
    private val store: PairingStore,
    private val identity: () -> ClientIdentity,
    private val locator: MacLocator,
    private val hello: () -> HelloBody,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val backoff: Backoff = Backoff(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incoming: SharedFlow<Message> = _incoming.asSharedFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(SupervisorJob() + dispatcher).launch { runLoop() }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "stopped")
        socket = null
        _state.value = ConnectionState.Stopped
    }

    /** Retry now (network changed, permission granted, …). */
    fun nudge() {
        nudges.trySend(Unit)
    }

    fun send(message: Message): Boolean = socket?.send(MessageCodec.encode(message)) ?: false

    /** Only the live loop may publish state, so a cancelled loop can't overwrite `Stopped`. */
    private suspend fun setState(value: ConnectionState) {
        if (currentCoroutineContext()[Job]?.isActive == true) _state.value = value
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val mac = store.paired.value
            if (mac == null) {
                setState(ConnectionState.NotPaired)
                store.paired.first { it != null }
                backoff.reset()
                continue
            }
            if (state.value !is ConnectionState.TrustFailure) setState(ConnectionState.Connecting)
            val outcome = try {
                connectOnce(mac)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed
            }
            when (outcome) {
                is Outcome.Closed -> if (outcome.wasOpen) backoff.reset()
                is Outcome.Trust -> setState(ConnectionState.TrustFailure(outcome.detail))
                Outcome.Failed -> Unit
            }
            val delayMs = backoff.next()
            if (state.value !is ConnectionState.TrustFailure) setState(ConnectionState.Waiting(delayMs))
            when (val wake = waitForRetry(mac, delayMs)) {
                is Wake.Found -> {
                    store.save(mac.copy(host = wake.address.host, port = wake.address.port))
                    backoff.reset()
                }
                Wake.Nudged -> backoff.reset()
                null -> Unit
            }
        }
    }

    private sealed interface Wake {
        data object Nudged : Wake
        data class Found(val address: MacAddress) : Wake
    }

    /** Sleeps up to [delayMs], waking early on a nudge or when the Mac shows up at a new address. */
    private suspend fun waitForRetry(mac: PairedMac, delayMs: Long): Wake? {
        val current = MacAddress(mac.host, mac.port)
        return withTimeoutOrNull(delayMs) {
            merge<Wake>(
                nudges.receiveAsFlow().map { Wake.Nudged },
                locator.appearances(mac.fingerprint).filter { it != current }.map { Wake.Found(it) },
            ).first()
        }
    }

    private sealed interface Outcome {
        data class Closed(val wasOpen: Boolean) : Outcome
        data class Trust(val detail: String) : Outcome
        data object Failed : Outcome
    }

    private sealed interface SocketEvent {
        data class Open(val socket: WebSocket) : SocketEvent
        data class Text(val text: String) : SocketEvent
        data object Closed : SocketEvent
        data class Failure(val error: Throwable) : SocketEvent
    }

    private suspend fun connectOnce(mac: PairedMac): Outcome {
        val client = TlsClientFactory.pinned(identity(), mac.fingerprint, baseClient).newBuilder()
            .pingInterval(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val url = HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).addPathSegments("v1/ws").build()
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(SocketEvent.Open(webSocket)) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(SocketEvent.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(SocketEvent.Closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(SocketEvent.Failure(t)) }
        })
        var open = false
        try {
            for (event in events) {
                when (event) {
                    is SocketEvent.Open -> {
                        open = true
                        socket = event.socket
                        event.socket.send(MessageCodec.encode(Message.Hello(hello())))
                    }
                    is SocketEvent.Text -> handleText(event.text, mac)
                    SocketEvent.Closed -> return Outcome.Closed(open)
                    is SocketEvent.Failure -> return when {
                        open -> Outcome.Closed(true)
                        TrustFailures.isTrustFailure(event.error) -> Outcome.Trust(event.error.message ?: "certificate rejected")
                        else -> Outcome.Failed
                    }
                }
            }
            return Outcome.Failed
        } finally {
            socket = null
            webSocket.cancel()
        }
    }

    private suspend fun handleText(text: String, mac: PairedMac) {
        val message = try {
            MessageCodec.decode(text)
        } catch (e: MalformedMessageException) {
            return
        }
        if (message is Message.Hello) {
            setState(ConnectionState.Connected(message.body.device))
            if (message.body.device != mac.name) store.save(mac.copy(name = message.body.device))
        }
        _incoming.emit(message)
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd android && ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL` (all core tests).

- [ ] **Step 6: Commit**

```bash
git add android/core
git commit -m "feat(android): connection manager with backoff, nudges and locator-driven reconnect"
```

---

## Task 12: Cross-platform test (real Mac server ↔ Kotlin client)

**Files:**
- Create: `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`
- Create: `scripts/cross-platform-test.sh`

**Interfaces:**
- Consumes: the `macdroid-devserver` executable (Task 6) and its output lines (`PAIRING_URI`, `LISTENING`, `STATUS connected device=… battery=…`); `PairingInvite`, `PairingClient`, `ConnectionManager`, `InMemoryPairingStore`, `MacLocator.None`
- Produces: `scripts/cross-platform-test.sh`, which exits 0 only if the Kotlin client pairs with the Swift server, connects, and delivers a battery update.

- [ ] **Step 1: Write the test (skipped unless the script provides the URI)**

`android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`:
```kotlin
package dev.macdroid.core

import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.connection.MacLocator
import dev.macdroid.core.pairing.InMemoryPairingStore
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import dev.macdroid.core.protocol.DeviceStatusBody
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.tls.ClientIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run via scripts/cross-platform-test.sh, which starts the Swift dev server and sets the URI. */
class CrossPlatformTest {
    private fun newIdentity(name: String) = HeldCertificate.Builder().ecdsa256().commonName(name).build()
        .let { ClientIdentity(it.keyPair.private, it.certificate) }

    @Test fun pairsConnectsAndReportsBatteryToSwiftServer() = runBlocking {
        val uri = System.getProperty("macdroidDevUri")
        assumeTrue("set MACDROID_DEV_URI (see scripts/cross-platform-test.sh)", uri != null)
        val invite = requireNotNull(PairingInvite.parse(uri!!)) { "bad URI: $uri" }
        val phone = newIdentity("MacDroid Test Phone")

        val result = PairingClient(phone).pair(invite, "MacDroid Test Phone", "JVM")
        assertTrue("pairing failed: $result", result is PairResult.Success)

        // The window is closed now: a different phone must not get in.
        assertTrue(PairingClient(newIdentity("Intruder")).pair(invite, "Intruder", "JVM") !is PairResult.Success)

        val manager = ConnectionManager(
            InMemoryPairingStore((result as PairResult.Success).mac),
            { phone },
            MacLocator.None,
            { HelloBody(app = "test", device = "MacDroid Test Phone") },
        )
        manager.start()
        try {
            assertEquals(ConnectionState.Connected("MacDroid Dev Server"), withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } })
            assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(77, false))))
            delay(500)
        } finally {
            manager.stop()
        }
    }
}
```

- [ ] **Step 2: Write the script**

`scripts/cross-platform-test.sh`:
```bash
#!/usr/bin/env bash
# Starts the Swift dev server, runs the Kotlin client against it, and checks the server saw the phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$(mktemp)"

(cd "$ROOT/mac/MacDroidCore" && swift build --product macdroid-devserver >/dev/null)
"$ROOT/mac/MacDroidCore/.build/debug/macdroid-devserver" --host 127.0.0.1 --port 47999 >"$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; rm -f "$LOG"' EXIT

for _ in $(seq 1 50); do grep -q '^LISTENING' "$LOG" && break; sleep 0.2; done
URI="$(grep '^PAIRING_URI' "$LOG" | cut -d' ' -f2)"
[ -n "$URI" ] || { echo "dev server did not start:"; cat "$LOG"; exit 1; }

(cd "$ROOT/android" && MACDROID_DEV_URI="$URI" ./gradlew -q :core:test --tests 'dev.macdroid.core.CrossPlatformTest' --rerun)

grep -q 'STATUS connected device=MacDroid Test Phone' "$LOG" || { echo "server never saw the phone"; cat "$LOG"; exit 1; }
grep -q 'battery=77' "$LOG" || { echo "server never got the battery update"; cat "$LOG"; exit 1; }
echo "cross-platform test passed"
```

Run: `chmod +x scripts/cross-platform-test.sh`

- [ ] **Step 3: Run it**

Run: `./scripts/cross-platform-test.sh`
Expected: the last line is `cross-platform test passed`. Running `./gradlew :core:test` without the script reports `CrossPlatformTest` as skipped, not failed.

- [ ] **Step 4: Commit**

```bash
git add scripts android/core
git commit -m "test: cross-platform pairing and connection between Swift server and Kotlin client"
```

---

## Task 13: Android platform layer (Keystore, persistence, NSD, foreground service)

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml` (full manifest)
- Create: `android/app/src/main/res/drawable/ic_stat_macdroid.xml`
- Create: `android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/platform/KeystoreIdentity.kt`, `PrefsPairingStore.kt`, `NsdMacLocator.kt`, `DeviceInfo.kt`
- Create: `android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt`, `ServiceSupport.kt`
- Test: `android/app/src/test/java/dev/macdroid/app/StatusTextTest.kt`, `android/app/src/androidTest/java/dev/macdroid/app/KeystoreTlsTest.kt`

**Interfaces:**
- Consumes: all of `:core`
- Produces:
  - `MacDroidApp` (Application) and `AppGraph`:
    - Properties: `httpClient`, `pairingStore`, `identity`, `connectionManager`, `uiPrefs`
    - Method: `pairingClient()`
  - `val Context.graph: AppGraph`
  - Platform helpers:
    - `KeystoreIdentity.getOrCreate(): ClientIdentity`
    - `PrefsPairingStore(context)`
    - `NsdMacLocator(context)`
    - `DeviceInfo.name(context)`, `DeviceInfo.model()`
  - `ConnectionService`: `start(context)`, `stop(context)`, `ACTION_DISCONNECT`
  - `StatusText.title(ConnectionState): String`
  - Supporting classes: `NetworkWatcher`, `BatteryMonitor.statusFlow(context)`, `BootReceiver`, `Notifications`

- [ ] **Step 1: Write the failing unit test**

`android/app/src/test/java/dev/macdroid/app/StatusTextTest.kt`:
```kotlin
package dev.macdroid.app

import dev.macdroid.app.service.StatusText
import dev.macdroid.core.connection.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test fun titles() {
        assertEquals("Connected to Studio", StatusText.title(ConnectionState.Connected("Studio")))
        assertEquals("Mac not reachable — retrying in 2s", StatusText.title(ConnectionState.Waiting(1_500)))
        assertEquals("Connecting…", StatusText.title(ConnectionState.Connecting))
        assertEquals("Not paired", StatusText.title(ConnectionState.NotPaired))
        assertEquals("Disconnected", StatusText.title(ConnectionState.Stopped))
        assertEquals("Pairing problem — open MacDroid to re-pair", StatusText.title(ConnectionState.TrustFailure("x")))
    }
}
```

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference 'StatusText'`).

- [ ] **Step 2: Write the manifest and icon**

`android/app/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <!-- Android 17 (API 37): runtime permission for LAN sockets and NSD -->
    <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".MacDroidApp"
        android:label="MacDroid"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity android:name=".ui.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.ConnectionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <!-- BOOT_COMPLETED is a protected broadcast; only the system can send it. -->
        <receiver android:name=".service.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

`android/app/src/main/res/drawable/ic_stat_macdroid.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M7,2h10a2,2 0,0 1,2 2v16a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V4a2,2 0,0 1,2 -2zM7,5v13h10V5H7z" />
</vector>
```

- [ ] **Step 3: Implement the platform classes**

`android/app/src/main/java/dev/macdroid/app/platform/KeystoreIdentity.kt`:
```kotlin
package dev.macdroid.app.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.macdroid.core.tls.ClientIdentity
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** The phone's TLS identity. The private key never leaves the Android Keystore. */
object KeystoreIdentity {
    private const val ALIAS = "macdroid-identity"
    private const val DAY_MS = 86_400_000L

    @Synchronized
    fun getOrCreate(): ClientIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate()
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return ClientIdentity(entry.privateKey, entry.certificate as X509Certificate)
    }

    private fun generate() {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS stacks sign either pre-hashed (NONE) or with SHA-256 depending on version; allow both.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=MacDroid Phone"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - DAY_MS))
            .setCertificateNotAfter(Date(now + 20 * 365 * DAY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }
}
```

`android/app/src/main/java/dev/macdroid/app/platform/PrefsPairingStore.kt`:
```kotlin
package dev.macdroid.app.platform

import android.content.Context
import dev.macdroid.core.pairing.PairedMac
import dev.macdroid.core.pairing.PairingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val paired: StateFlow<PairedMac?> = state.asStateFlow()

    override fun save(mac: PairedMac) {
        prefs.edit().putString(KEY, Json.encodeToString(PairedMac.serializer(), mac)).apply()
        state.value = mac
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
        state.value = null
    }

    private fun load(): PairedMac? = prefs.getString(KEY, null)?.let {
        runCatching { Json.decodeFromString(PairedMac.serializer(), it) }.getOrNull()
    }

    private companion object { const val KEY = "pairedMac" }
}
```

`android/app/src/main/java/dev/macdroid/app/platform/NsdMacLocator.kt`:
```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.macdroid.core.connection.MacAddress
import dev.macdroid.core.connection.MacLocator
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Finds the paired Mac's `_macdroid._tcp` advertisement and resolves it to an address. */
class NsdMacLocator(context: Context) : MacLocator {
    private val nsd = context.getSystemService(NsdManager::class.java)

    override fun appearances(fingerprint: String): Flow<MacAddress> = callbackFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        // resolveService handles one request at a time, so resolve sequentially.
        val resolver = launch {
            for (info in found) {
                val resolved = resolve(info) ?: continue
                @Suppress("DEPRECATION") val host = resolved.host?.hostAddress ?: continue
                if (MacLocator.txtMatches(resolved.attributes, fingerprint)) send(MacAddress(host, resolved.port))
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.cancel()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (continuation.isActive) continuation.resume(serviceInfo)
            }
        })
    }

    private companion object { const val SERVICE_TYPE = "_macdroid._tcp" }
}
```

`android/app/src/main/java/dev/macdroid/app/platform/DeviceInfo.kt`:
```kotlin
package dev.macdroid.app.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceInfo {
    fun name(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun model(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
```

`android/app/src/main/java/dev/macdroid/app/MacDroidApp.kt`:
```kotlin
package dev.macdroid.app

import android.app.Application
import android.content.Context
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.app.platform.KeystoreIdentity
import dev.macdroid.app.platform.NsdMacLocator
import dev.macdroid.app.platform.PrefsPairingStore
import dev.macdroid.core.connection.ConnectionManager
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.protocol.HelloBody
import dev.macdroid.core.tls.ClientIdentity
import okhttp3.OkHttpClient

class MacDroidApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as MacDroidApp).graph

/** Process-wide singletons. */
class AppGraph(private val context: Context) {
    val httpClient = OkHttpClient()
    val pairingStore: PairingStore = PrefsPairingStore(context)
    val identity: ClientIdentity by lazy { KeystoreIdentity.getOrCreate() }
    val uiPrefs = UiPrefs(context)
    val connectionManager = ConnectionManager(
        store = pairingStore,
        identity = { identity },
        locator = NsdMacLocator(context),
        hello = { HelloBody(app = BuildConfig.VERSION_NAME, device = DeviceInfo.name(context)) },
        baseClient = httpClient,
    )

    fun pairingClient() = PairingClient(identity, httpClient)
}

class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var setupDone: Boolean
        get() = prefs.getBoolean("setupDone", false)
        set(value) { prefs.edit().putBoolean("setupDone", value).apply() }
}
```

`android/app/src/main/java/dev/macdroid/app/service/ServiceSupport.kt`:
```kotlin
package dev.macdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.R
import dev.macdroid.app.graph
import dev.macdroid.app.ui.MainActivity
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.DeviceStatusBody
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object StatusText {
    fun title(state: ConnectionState): String = when (state) {
        ConnectionState.Stopped -> "Disconnected"
        ConnectionState.NotPaired -> "Not paired"
        ConnectionState.Connecting -> "Connecting…"
        is ConnectionState.Connected -> "Connected to ${state.macName}"
        is ConnectionState.Waiting -> "Mac not reachable — retrying in ${(state.retryInMs + 999) / 1000}s"
        is ConnectionState.TrustFailure -> "Pairing problem — open MacDroid to re-pair"
    }
}

object Notifications {
    const val CHANNEL_ID = "connection"
    const val SERVICE_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Connection status", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun status(context: Context, state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(
            context, 1,
            Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_macdroid)
            .setContentTitle(StatusText.title(state))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}

/** Calls [onAvailable] whenever the default network (re)appears, e.g. Wi-Fi reconnects. */
class NetworkWatcher(context: Context, private val onAvailable: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)
    fun stop() { runCatching { connectivity.unregisterNetworkCallback(callback) } }
}

object BatteryMonitor {
    fun statusFlow(context: Context): Flow<DeviceStatusBody> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { trySend(parse(intent)) }
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { trySend(parse(it)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun parse(intent: Intent): DeviceStatusBody {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return DeviceStatusBody(percent, charging)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.graph.pairingStore.paired.value != null) {
            ConnectionService.start(context)
        }
    }
}
```

`android/app/src/main/java/dev/macdroid/app/service/ConnectionService.kt`:
```kotlin
package dev.macdroid.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.macdroid.app.graph
import dev.macdroid.core.connection.ConnectionState
import dev.macdroid.core.protocol.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps the connection to the Mac alive while the app is in the background. */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var networkWatcher: NetworkWatcher

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        ServiceCompat.startForeground(
            this, Notifications.SERVICE_ID,
            Notifications.status(this, ConnectionState.Connecting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val manager = graph.connectionManager
        manager.start()
        networkWatcher = NetworkWatcher(this) { manager.nudge() }.also { it.start() }

        val notifications = getSystemService(NotificationManager::class.java)
        scope.launch {
            manager.state.collect { notifications.notify(Notifications.SERVICE_ID, Notifications.status(this@ConnectionService, it)) }
        }
        scope.launch {
            combine(manager.state, BatteryMonitor.statusFlow(this@ConnectionService)) { state, battery -> state to battery }
                .collect { (state, battery) -> if (state is ConnectionState.Connected) manager.send(Message.DeviceStatus(battery)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        networkWatcher.stop()
        scope.cancel()
        graph.connectionManager.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISCONNECT = "dev.macdroid.app.DISCONNECT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
```

- [ ] **Step 4: Write the on-device Keystore TLS test**

`android/app/src/androidTest/java/dev/macdroid/app/KeystoreTlsTest.kt`:
```kotlin
package dev.macdroid.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.macdroid.app.platform.KeystoreIdentity
import dev.macdroid.core.tls.Fingerprint
import dev.macdroid.core.tls.TlsClientFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Proves a Keystore-backed EC key can authenticate a TLS 1.3 client handshake on this device. */
@RunWith(AndroidJUnit4::class)
class KeystoreTlsTest {
    @Test fun keystoreIdentityAuthenticatesOverTls13() {
        val identity = KeystoreIdentity.getOrCreate()
        val serverCert = HeldCertificate.Builder().ecdsa256().commonName("mac").build()
        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(identity.certificate).build().sslSocketFactory(),
        )
        server.requireClientAuth()
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.start()
        try {
            val client = TlsClientFactory.pinned(identity, Fingerprint.of(serverCert.certificate))
            client.newCall(Request.Builder().url(server.url("/v1/info")).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(identity.certificate, server.takeRequest().handshake?.peerCertificates?.first())
        } finally {
            server.close()
        }
    }

    @Test fun identityIsStable() {
        assertEquals(KeystoreIdentity.getOrCreate().fingerprint, KeystoreIdentity.getOrCreate().fingerprint)
    }
}
```

- [ ] **Step 5: Run the tests and build**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

Connect the Nothing Phone (3) with USB debugging enabled (`adb devices` lists it), then run `cd android && ./gradlew :app:connectedDebugAndroidTest`.
Expected: both `KeystoreTlsTest` tests pass. If `keystoreIdentityAuthenticatesOverTls13` fails with a signature or `NO_COMMON_SIGNATURE_ALGORITHMS` error, the Keystore key's digests are the cause. Keep `DIGEST_NONE` and `DIGEST_SHA256`, and report the exact exception before changing anything else.

- [ ] **Step 6: Commit**

```bash
git add android/app
git commit -m "feat(android): keystore identity, NSD discovery and connection foreground service"
```

---

## Task 14: Android UI (permissions, pairing, home) and end-to-end check

**Files:**
- Modify: `android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt` (replace the placeholder)
- Create: `android/app/src/main/java/dev/macdroid/app/ui/PairingViewModel.kt`, `OnboardingScreens.kt`, `QrScanner.kt`, `HomeScreen.kt`

**Interfaces:**
- Consumes:
  - From `AppGraph`: `pairingStore`, `connectionManager`, `uiPrefs`, `pairingClient()`
  - From the app: `ConnectionService.start/stop`, `StatusText`, `DeviceInfo`
  - From `:core`: `PairingInvite`, `PairResult`, `Fingerprint`
- Produces: the launchable app UI. Behaviour is checked by the manual end-to-end checklist in Step 3.

- [ ] **Step 1: Write the UI**

`android/app/src/main/java/dev/macdroid/app/ui/PairingViewModel.kt`:
```kotlin
package dev.macdroid.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.macdroid.app.graph
import dev.macdroid.app.platform.DeviceInfo
import dev.macdroid.core.pairing.PairResult
import dev.macdroid.core.pairing.PairingClient
import dev.macdroid.core.pairing.PairingInvite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface UiState {
        data object Idle : UiState
        data object Working : UiState
        data class Confirm(val host: String, val port: Int, val code: String, val fingerprint: String) : UiState
        data class Error(val message: String) : UiState
    }

    private val graph = application.graph
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onScanned(raw: String) {
        if (_state.value != UiState.Idle) return
        val invite = PairingInvite.parse(raw)
        if (invite == null) {
            _state.value = UiState.Error("That QR code isn't a MacDroid pairing code.")
            return
        }
        pair(invite)
    }

    /** Manual entry. Accepts "host:port" in the address field when the port field is empty. */
    fun onManual(hostInput: String, portInput: String, codeInput: String) {
        val trimmed = hostInput.trim()
        val (host, portText) = if (portInput.isBlank() && trimmed.count { it == ':' } == 1) {
            trimmed.substringBefore(':') to trimmed.substringAfter(':')
        } else {
            trimmed to portInput.trim()
        }
        val port = portText.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            _state.value = UiState.Error("Enter the address and port exactly as your Mac shows them.")
            return
        }
        _state.value = UiState.Working
        viewModelScope.launch {
            val fingerprint = client().probeFingerprint(host, port)
            _state.value = if (fingerprint == null) {
                UiState.Error("Couldn't reach a Mac at $host:$port. Check the address and that pairing is open on the Mac.")
            } else {
                UiState.Confirm(host, port, PairingInvite.normalizeCode(codeInput), fingerprint)
            }
        }
    }

    fun confirmFingerprint() {
        val confirm = _state.value as? UiState.Confirm ?: return
        _state.value = UiState.Idle
        pair(PairingInvite(confirm.host, confirm.port, confirm.fingerprint, confirm.code, "Mac"))
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    private fun pair(invite: PairingInvite) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = client().pair(invite, DeviceInfo.name(app), DeviceInfo.model())
            _state.value = when (result) {
                is PairResult.Success -> {
                    graph.pairingStore.save(result.mac)
                    UiState.Idle
                }
                PairResult.CodeRejected -> UiState.Error("The Mac rejected the code — it may have expired. Choose Pair a Phone on the Mac again.")
                PairResult.FingerprintMismatch -> UiState.Error("Security check failed: this isn't the Mac that showed the code.")
                is PairResult.Failed -> UiState.Error("Couldn't reach the Mac (${result.reason}). Make sure both devices are on the same Wi-Fi.")
            }
        }
    }

    /** Creating the Keystore identity the first time takes a moment, so keep it off the main thread. */
    private suspend fun client(): PairingClient = withContext(Dispatchers.Default) { graph.pairingClient() }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/OnboardingScreens.kt`:
```kotlin
package dev.macdroid.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.macdroid.core.tls.Fingerprint

object Permissions {
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    val needsLocalNetwork: Boolean get() = Build.VERSION.SDK_INT >= 37
    val needsNotifications: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocalNetwork(context: Context) = !needsLocalNetwork || granted(context, ACCESS_LOCAL_NETWORK)
    fun hasNotifications(context: Context) = !needsNotifications || granted(context, Manifest.permission.POST_NOTIFICATIONS)
}

@Composable
fun StepScaffold(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
fun LocalNetworkScreen(onResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    StepScaffold(
        "Allow local network access",
        "MacDroid talks to your Mac directly over your Wi-Fi — nothing goes through the internet. Android needs your permission for that.",
    ) {
        Button(onClick = { launcher.launch(Permissions.ACCESS_LOCAL_NETWORK) }) { Text("Allow") }
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("Open app settings") }
    }
}

@Composable
fun PairScreen(viewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("choose") }
    when (mode) {
        "scan" -> QrScannerScreen(enabled = state == PairingViewModel.UiState.Idle, onResult = viewModel::onScanned, onCancel = { mode = "choose" })
        "manual" -> ManualPairScreen(viewModel, onCancel = { mode = "choose" })
        else -> StepScaffold(
            "Pair with your Mac",
            "On your Mac, click the MacDroid icon in the menu bar, choose Pair a Phone, then scan the QR code it shows.",
        ) {
            Button(onClick = { mode = "scan" }) { Text("Scan QR code") }
            TextButton(onClick = { mode = "manual" }) { Text("Enter details manually") }
        }
    }
    PairingDialogs(state, onDismiss = viewModel::reset, onConfirm = viewModel::confirmFingerprint)
}

@Composable
private fun ManualPairScreen(viewModel: PairingViewModel, onCancel: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    StepScaffold("Enter pairing details", "Type the address and code shown under “Can't scan?” on your Mac.") {
        OutlinedTextField(host, { host = it }, label = { Text("Address (e.g. 192.168.1.20)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.onManual(host, port, code) }, enabled = host.isNotBlank() && code.isNotBlank()) { Text("Connect") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}

@Composable
private fun PairingDialogs(state: PairingViewModel.UiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    when (state) {
        PairingViewModel.UiState.Working -> AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("Pairing…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        is PairingViewModel.UiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            title = { Text("Couldn't pair") }, text = { Text(state.message) },
        )
        is PairingViewModel.UiState.Confirm -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onConfirm) { Text("They match") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            title = { Text("Check the fingerprint") },
            text = { Text("Does your Mac show this fingerprint?\n\n${Fingerprint.display(state.fingerprint)}") },
        )
        PairingViewModel.UiState.Idle -> Unit
    }
}

@Composable
fun FinishSetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val notificationsOk = remember(version) { Permissions.hasNotifications(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    StepScaffold("Almost done", "Two more things keep MacDroid connected in the background.") {
        if (Permissions.needsNotifications) {
            Text("Show MacDroid's status notification", style = MaterialTheme.typography.titleMedium)
            if (notificationsOk) {
                Text("✓ Allowed")
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }
        }
        Text("Let MacDroid run in the background", style = MaterialTheme.typography.titleMedium)
        Text(
            "Find MacDroid in the list and set it to “Not optimized”. On Nothing phones also open App info → Battery and choose “Unrestricted”.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }) { Text("Open battery settings") }
        Button(onClick = onDone) { Text("Done") }
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/QrScanner.kt`:
```kotlin
package dev.macdroid.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(enabled: Boolean, onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(Permissions.granted(context, Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) launcher.launch(Manifest.permission.CAMERA) }
    Column(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraPreview(enabled, onResult, Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Camera permission is needed to scan the code.")
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(16.dp)) { Text("Back") }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(enabled: Boolean, onResult: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnResult by rememberUpdatedState(onResult)
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(Unit) {
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(modifier = modifier, factory = { viewContext ->
        val previewView = PreviewView(viewContext)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || !currentEnabled) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstNotNullOfOrNull { code -> code.rawValue?.takeIf { it.startsWith("macdroid://") } }
                            ?.let { currentOnResult(it) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(viewContext))
        previewView
    })
}
```

`android/app/src/main/java/dev/macdroid/app/ui/HomeScreen.kt`:
```kotlin
package dev.macdroid.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.AppGraph
import dev.macdroid.app.service.StatusText
import dev.macdroid.core.connection.ConnectionState

@Composable
fun HomeScreen(graph: AppGraph, onForget: () -> Unit) {
    val state by graph.connectionManager.state.collectAsStateWithLifecycle()
    val paired by graph.pairingStore.paired.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MacDroid", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(StatusText.title(state), style = MaterialTheme.typography.titleMedium)
                paired?.let { Text("${it.name} · ${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (state is ConnectionState.TrustFailure) {
            Text(
                "Your Mac no longer recognises this phone (or a different Mac answered). Tap Forget, then pair again.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(onClick = onForget) { Text("Forget this Mac") }
    }
}
```

`android/app/src/main/java/dev/macdroid/app/ui/MainActivity.kt` (replaces the placeholder):
```kotlin
package dev.macdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.macdroid.app.AppGraph
import dev.macdroid.app.graph
import dev.macdroid.app.service.ConnectionService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppRoot(graph) }
            }
        }
    }
}

@Composable
fun AppRoot(graph: AppGraph) {
    val context = LocalContext.current
    val paired by graph.pairingStore.paired.collectAsStateWithLifecycle()
    var permissionCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionCheck++ }
    val localNetworkOk = remember(permissionCheck) { Permissions.hasLocalNetwork(context) }
    var setupDone by remember { mutableStateOf(graph.uiPrefs.setupDone) }

    LaunchedEffect(paired != null, localNetworkOk) {
        if (paired != null && localNetworkOk) {
            ConnectionService.start(context)
            graph.connectionManager.nudge()
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            !localNetworkOk -> LocalNetworkScreen(onResult = { permissionCheck++ })
            paired == null -> PairScreen()
            !setupDone -> FinishSetupScreen(onDone = {
                graph.uiPrefs.setupDone = true
                setupDone = true
            })
            else -> HomeScreen(graph, onForget = {
                ConnectionService.stop(context)
                graph.connectionManager.stop()
                graph.pairingStore.clear()
                graph.uiPrefs.setupDone = false
                setupDone = false
            })
        }
    }
}
```

- [ ] **Step 2: Build and install**

Run: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest && ./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL`, and `Installed on 1 device`.

- [ ] **Step 3: Run the manual end-to-end checklist on the Nothing Phone (3) and the Mac**

Start the Mac app from Task 7 (`open mac/build/Build/Products/Debug/MacDroid.app`) and keep the phone on the same Wi-Fi. Record pass or fail for each item:

1. Open MacDroid on the phone. The "Allow local network access" screen appears; tap **Allow**. Expected: the Pair screen.
2. On the Mac, choose menu bar → **Pair a Phone…** → **Pair a Phone**. On the phone, tap **Scan QR code** and point it at the QR. Expected: the phone moves to "Almost done", and the Mac's Pairing tab shows "Paired with <phone name>".
3. Allow notifications, open battery settings and set MacDroid to *Not optimized*, then tap **Done**. Expected: Home shows "Connected to <Mac name>", and the Mac's menu shows the phone with its battery percentage.
4. Plug in the charger. Expected: the Mac shows "· charging" within a few seconds.
5. Turn phone Wi-Fi off for 10 s, then back on. Expected: "Connected" again within about 5 s.
6. Quit MacDroid on the Mac and reopen it (it gets a new port). Expected: the phone reconnects within 30 s, through NSD.
7. Put the Mac to sleep for 1 minute, then wake it. Expected: the phone reconnects.
8. Reboot the phone and unlock it, without opening MacDroid. Expected: the status notification appears and the Mac shows the phone connected.
9. On the Mac, click **Forget This Phone**. Expected: the phone shows "Pairing problem — open MacDroid to re-pair" plus the re-pair hint.
10. On the phone, tap **Forget this Mac**, then use **Enter details manually** with the Mac's "Can't scan?" values. Expected: the fingerprint dialog matches the Mac, and confirming pairs successfully.
11. With the Mac's pairing window closed, scan an old QR screenshot. Expected: "The Mac rejected the code…" or "Security check failed…", and no change on the Mac.

- [ ] **Step 4: Commit**

```bash
git add android/app
git commit -m "feat(android): onboarding, QR and manual pairing, and home screen"
```

---

## Self-Review Notes

- **Spec coverage (Plan 1 scope):**
  - §4.1 identity and pairing: Tasks 2, 3, 6, 10, 14. This includes the manual fallback with fingerprint comparison (Tasks 10 and 14).
  - §4.2 TLS rules: Tasks 6 and 9.
  - §4.3 discovery and reconnect: Tasks 4, 7, 11 and 13 (NSD, network callback, backoff, foreground service, battery-optimisation step).
  - §4.4 envelope, `hello` and `device.status`: Tasks 1 and 8.
  - §6 UI shell: Tasks 7 and 14.
  - §7 error rows for connection, certificate, pairing and permissions: Tasks 6, 11 and 14.
  - §8 testing: shared fixtures, unit tests, integration tests (Tasks 6 and 12), and the manual checklist (Task 14).
- **Deferred to Plans 2–4 by design:**
  - Notification, clipboard and transfer messages and the routes that carry them.
  - Popover tabs, Share extension and Share target, the Quick Settings tile, and the download-folder onboarding step.
