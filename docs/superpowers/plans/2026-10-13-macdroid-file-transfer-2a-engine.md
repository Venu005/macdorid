# MacDroid File Transfer — Plan 2a: Transfer Engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete, tested file-transfer engine on both sides: protocol messages, the security groundwork, the Mac `TransferManager` with its HTTPS file routes, and the Android `TransferClient` + `TransferEngine`. The proof is a cross-platform test that sends real files both ways and kills the connection mid-file.

**Architecture:**
- **Control** goes over the existing WebSocket (`transfer.offer|accept|reject|finish|result|cancel`). **Bytes** go over HTTPS on `/v1/files/{id}`, and the phone always makes the request: `PUT` for uploads, `GET` for downloads.
- **Mac:**
  - `TransferManager` (actor) owns every record, the part files, persistence and history.
  - Routes in `MacDroidServer` call into it.
  - `ConnectionHub` gains multi-subscriber `statusUpdates` and a `messages` stream, both built on a small `Broadcast` type.
- **Android:**
  - `TransferEngine` (pure JVM, in `android/core`) does the same job. All state changes run on one confined dispatcher, and byte copying runs on IO.
  - Platform specifics (MediaStore, SAF, content URIs) sit behind `SaveTarget`, `PartFile` and `SourceOpener`. Plan 2b implements them; this plan ships a `FolderSaveTarget` and a `FileSourceOpener` used by the tests and the cross-platform run.
- **Plan 2b** adds the UI and platform glue (Mac Files page and popover, Android Files tab, share target, settings, notifications) on top of these APIs.

**Tech Stack:**
- **Mac:** Swift 6.4, Hummingbird 2.27 (streaming request and response bodies), swift-crypto `SHA256`, Swift Testing.
- **Android core:** Kotlin 2.4.21, OkHttp 5.5.0, kotlinx-serialization 1.11, coroutines 1.11, JUnit4, mockwebserver3.

**Spec:** `docs/superpowers/specs/2026-10-13-macdroid-file-transfer-design.md`. Read §2 (protocol) and §8 (review rulings) before starting.

## Global Constraints

- **Branch:** `feat/file-transfer`, created from `main` in Task 1.
- **Handoff rule (from `/CLAUDE.md`):** every task's commit also updates `docs/handoff/`.
  - Task 1 creates a CHANGELOG entry at the top headed `## 2026-10-13 — Plan 2a: file transfer engine (in progress on feat/file-transfer)`.
  - Every later task adds one bullet under that entry, naming the task and how it was verified.
  - Each task's "Handoff" step names any other handoff file to update.
- **Protocol is a contract:** every message has a fixture in `protocol/fixtures/` that both codecs decode and round-trip. `protocol/PROTOCOL.md` changes in the same task.
- **Security:**
  - Every route except `POST /v1/pair` requires the paired phone (global `TrustedPeerMiddleware`).
  - `/v1/files/{id}` answers 404 for an unknown id or one in the wrong direction.
  - Don't weaken mTLS or pinning.
- **Transfer rules (spec §2):**
  - Transfer ids are lowercase UUID strings made by the sender.
  - At most **3 active outgoing transfers per side**, with the rest queued FIFO.
  - Part files are `<final name>.macdroid-part` in the destination folder.
  - SHA-256 is lowercase hex of the whole file.
  - Persisted state is kept for **24 h**; older state is cleaned up at launch.
  - History keeps the **last 100** entries, newest first.
- **Resume rules (spec §2.2, §8):**
  - The sender re-sends `transfer.offer` with the same id after a reconnect.
  - The receiver accepts a known id again, treats an unknown id as a new offer, and replays the outcome (`result` or `cancel`) for an id in its history.
  - Uploads: `HEAD` returns `Upload-Offset`. The first `PUT` carries no `Content-Range`; a resumed `PUT` carries `Content-Range: bytes N-(size-1)/size`. A mismatched offset gets `409`. When the offset equals the size there's no `PUT`, and the sender goes straight to `finish`.
  - Downloads: the phone always sends `GET`, with `Range: bytes=N-` only when N > 0. When N equals the size, the Mac answers `206` with an empty body. The Mac sends `finish` after every `GET` that reaches the end of the file.
  - A cancel that can't be sent is queued and sent after reconnecting.
- **Name sanitising (spec §2.3):**
  1. Keep the last path component (split on `/` and `\`).
  2. Drop control characters (U+0000–U+001F, U+007F) and `: * ? " < > |`.
  3. Trim spaces, then strip all leading dots, then trim spaces again.
  4. An empty result becomes `file`.
  5. Cap at 200 UTF-8 bytes, keeping an extension of up to 20 bytes.
  - On a clash, append ` (1)`, ` (2)`, … before the extension.
- **Buffers:** 1 MiB reads on both sides. OkHttp file client: no call timeout; 60 s read and write timeouts.
- **Build gates (all must pass with no warnings)** before each commit that touches that side:
  - `cd mac/MacDroidCore && swift test`
  - `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build` (Tasks 1, 3 and 8, which change APIs the app uses)
  - `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
- **Swift paths:** use `url.path(percentEncoded: false)`, never the deprecated `url.path`.

## Review Focus

1. **A re-offer of an id the receiver already finished** (its `result` was lost in a disconnect) must replay the result, not create a second copy of the file. → Task 6 `reofferOfFinishedIdReplaysResult`; Task 11 `reofferOfDoneIdReplaysResult`.
2. **A transfer interrupted mid-file** must resume from the bytes on disk, with the final checksum still matching. It must not restart and must not corrupt the file, including after a Mac relaunch. → Task 6 `relaunchResumesAndChecksumStillMatches`; Task 11 `interruptedDownloadResumesFromPartLength`; Task 12 `resumedUploadSendsOnlyTheTail`; Task 13 cross-platform kill-and-resume.
3. **Hostile names from the phone** (`../../x`, `C:\x`, `.bashrc`, NUL, 300-byte names) must land as a plain, visible file inside the save folder, never outside it. → Task 4 fixture `file-naming.json`; Task 6 `hostileNameLandsInsideFolder`.
4. **Two files with the same name arriving at once** must not overwrite each other. → Task 6 `simultaneousSameNameOffersGetDistinctNames`; Task 11 `folderTargetNeverReusesAName`.
5. **Zero-byte files** must complete in both directions instead of hanging while waiting for bytes. → Task 6 `zeroByteFileCompletes`; Task 8 `getAtEndReturnsEmpty206AndFinish`; Task 11 `zeroByteDownloadCompletes`; Task 12 `macAlreadyHasEverythingGoesStraightToFinish`.

---

## File Structure

```
protocol/
  PROTOCOL.md                                     (modify: transfer messages + file routes)
  fixtures/transfer.offer.json  transfer.accept.json  transfer.reject.json  transfer.finish.json
           transfer.result.json  transfer.result.failed.json  transfer.cancel.json      (new)
  fixtures/file-naming.json  transfer-text.json                                         (new, shared test vectors)
mac/MacDroidCore/Sources/MacDroidCore/
  Protocol/Messages.swift  MessageCodec.swift     (modify: transfer bodies + cases)
  Session/Broadcast.swift                          (new: multi-subscriber AsyncStream fan-out)
  Session/ConnectionHub.swift                      (modify: Broadcast status, messages stream)
  Server/MacDroidServer.swift                      (modify: global middleware, transfers param)
  Server/FileRoutes.swift                          (new: HEAD/PUT/GET /v1/files/:id)
  Transfer/FileNaming.swift  ByteRanges.swift  TransferText.swift          (new, pure)
  Transfer/TransferModel.swift  JSONFileStore.swift  TransferRate.swift  Checksum.swift  (new)
  Transfer/PartFileWriter.swift  SourceReader.swift                          (new, file I/O)
  Transfer/TransferManager.swift                   (new: core + receiving)
  Transfer/TransferManager+Sending.swift           (new: sending)
mac/MacDroidCore/Sources/MacDroidDevServer/main.swift   (modify: transfers, --receive-dir, --send-on-connect)
mac/MacDroidCore/Tests/MacDroidCoreTests/
  TestSupport.swift (modify)  ServerTests.swift (modify)  ProtocolFixtureTests.swift (modify)  ConnectionHubTests.swift (modify)
  BroadcastTests.swift  TransferPureTests.swift  TransferIOTests.swift
  TransferReceiveTests.swift  TransferSendTests.swift  FileRouteTests.swift                (new)
android/core/src/main/kotlin/dev/macdroid/core/
  protocol/Messages.kt  MessageCodec.kt            (modify)
  transfer/FileNaming.kt  TransferText.kt  TransferRate.kt                   (new, pure)
  transfer/TransferClient.kt  FileEndpoint.kt                                 (new: HTTP)
  transfer/TransferModel.kt  TransferStore.kt  SaveTarget.kt  FolderSaveTarget.kt  (new)
  transfer/TransferEngine.kt                                                  (new)
android/core/src/test/kotlin/dev/macdroid/core/
  protocol/MessageCodecTest.kt (modify)  CrossPlatformTest.kt (modify)  TestSupport.kt (modify)
  transfer/TransferPureTest.kt  TransferClientTest.kt  TransferStoreTest.kt
  transfer/FakeMacFiles.kt  TransferEngineReceiveTest.kt  TransferEngineSendTest.kt     (new)
android/core/build.gradle.kts                      (modify: pass cross-platform env vars)
scripts/cross-platform-test.sh                     (modify: files both ways, kill mid-file)
docs/handoff/*                                     (modify: every task)
```

---
### Task 1: Global `TrustedPeerMiddleware` (branch + handoff entry)

TLS 1.3 session resumption skips the TLS verify callback. So every HTTP route except `POST /v1/pair` must check the peer at the app layer, including routes added later and the not-found responder. In Hummingbird 2, `router.add(middleware:)` applies to every route registered **after** it and to the not-found responder.

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Server/MacDroidServer.swift` (`makeRouter()`)
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ServerTests.swift`
- Modify: `docs/handoff/CHANGELOG.md`, `docs/handoff/STATUS.md`, `docs/handoff/ARCHITECTURE.md`

**Interfaces:**
- Consumes: the existing `TrustedPeerMiddleware`, `ServerHarness`.
- Produces: the guarantee that any route registered after `router.add(middleware:)` in `makeRouter()` requires the paired phone. Task 8 relies on this.

- [ ] **Step 1: Create the branch**

```bash
git checkout main && git pull --ff-only && git checkout -b feat/file-transfer
```

- [ ] **Step 2: Write the failing tests** (append inside `ServerTests`)

```swift
    // Spec §2.4: every route except /v1/pair needs the paired phone, including ones that don't exist yet.
    @Test func strangerIsForbiddenEverywhereExceptPair() async throws {
        let stranger = try Identity.generate(commonName: "Stranger")
        try await ServerHarness.run { harness in
            _ = await harness.pairing.openWindow() // lets the handshake through; routes must still refuse
            let client = try harness.client(stranger)
            for path in ["/v1/info", "/v1/files/abc", "/v1/nothing-here", "/"] {
                #expect(try await client.get(path).status == .forbidden, "GET \(path)")
            }
            #expect(try await client.put("/v1/files/abc", body: ByteBuffer(string: "x")).status == .forbidden)
            #expect(try await client.head("/v1/files/abc").status == .forbidden)
            try await client.shutdown()
        }
    }

    @Test func pairedPhoneGetsNotFoundForUnknownRoute() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        try await ServerHarness.run(pairedPhone: phone) { harness in
            let client = try harness.client(phone)
            #expect(try await client.get("/v1/nothing-here").status == .notFound)
            try await client.shutdown()
        }
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter ServerTests`
Expected: `strangerIsForbiddenEverywhereExceptPair` FAILS (unknown paths return 404 to a stranger instead of 403).

- [ ] **Step 4: Make the middleware global**

In `makeRouter()`, replace the `router.group("/v1")…` block with:

```swift
        // Every route registered after this line, and the not-found responder, requires the paired phone.
        // TLS 1.3 session resumption skips the handshake callback, so this app-layer check is what keeps strangers out.
        router.add(middleware: TrustedPeerMiddleware(pairing: pairing))
        router.get("/v1/info") { _, _ -> ServerInfo in ServerInfo(macName: macName) }
```

(`router.post("/v1/pair")` stays registered *before* this line, so it remains reachable while the pairing window is open.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test`
Expected: all tests pass (59 earlier + 2 new).

- [ ] **Step 6: Handoff**
  - **CHANGELOG.md:** add at the top:
    ```markdown
    ## 2026-10-13 — Plan 2a: file transfer engine (in progress on feat/file-transfer)
    - **Plan:** `docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2a-engine.md` (engine); Plan 2b (UI and platform glue) follows.
    - Task 1: `TrustedPeerMiddleware` is global; every route except `POST /v1/pair` (and the not-found responder) requires the paired phone. Verified: `swift test`, including `strangerIsForbiddenEverywhereExceptPair`.
    ```
  - **STATUS.md:** under "Branches", add `feat/file-transfer: Plan 2a in progress`.
  - **ARCHITECTURE.md:**
    - In the topology block, change `TrustedPeerMiddleware (paired phone only)` to `TrustedPeerMiddleware (global: everything but /v1/pair)`.
    - Under "Extension points", replace the "make `TrustedPeerMiddleware` global first" bullet with "Routes registered after `router.add(middleware:)` in `makeRouter()` are automatically paired-phone-only".

- [ ] **Step 7: Commit**

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "fix(mac): make TrustedPeerMiddleware global (only /v1/pair exempt)"
```

---

### Task 2: Protocol — transfer messages in both codecs

**Files:**
- Create: `protocol/fixtures/transfer.offer.json`, `transfer.accept.json`, `transfer.reject.json`, `transfer.finish.json`, `transfer.result.json`, `transfer.result.failed.json`, `transfer.cancel.json`
- Modify: `protocol/PROTOCOL.md`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Protocol/Messages.swift`, `MessageCodec.swift`, `Session/ConnectionHub.swift` (exhaustive switch)
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/protocol/Messages.kt`, `MessageCodec.kt`
- Test: `mac/MacDroidCore/Tests/MacDroidCoreTests/ProtocolFixtureTests.swift`, `android/core/src/test/kotlin/dev/macdroid/core/protocol/MessageCodecTest.kt`

**Interfaces:**
- Produces (Swift):
  - `TransferOffer(transferId: String, name: String, size: Int64, mime: String)`
  - `TransferID(transferId:)`, used by accept and cancel
  - `TransferRejectReason { noSpace = "no_space", declined, invalid }`, with `TransferReject(transferId:reason:)`
  - `TransferFinish(transferId:sha256:)`
  - `TransferResultError { checksumMismatch = "checksum_mismatch", noSpace = "no_space", io }`, with `TransferResult(transferId:ok:error:)`
  - `Message` cases `.transferOffer`, `.transferAccept`, `.transferReject`, `.transferFinish`, `.transferResult`, `.transferCancel`
- Produces (Kotlin):
  - `TransferOfferBody`, `TransferIdBody`, `TransferRejectReason { NoSpace, Declined, Invalid }`, `TransferRejectBody`, `TransferFinishBody`, `TransferResultError { ChecksumMismatch, NoSpace, Io }`, `TransferResultBody(transferId, ok, error = null)`
  - `Message.TransferOffer|TransferAccept|TransferReject|TransferFinish|TransferResult|TransferCancel(body)`

- [ ] **Step 1: Add the fixtures**

`protocol/fixtures/transfer.offer.json`
```json
{
  "v": 1,
  "type": "transfer.offer",
  "id": "6f1c2a0e-0000-4000-8000-000000000003",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90", "name": "IMG_2041.jpg", "size": 3145728, "mime": "image/jpeg" }
}
```
`protocol/fixtures/transfer.accept.json`
```json
{
  "v": 1,
  "type": "transfer.accept",
  "id": "6f1c2a0e-0000-4000-8000-000000000004",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90" }
}
```
`protocol/fixtures/transfer.reject.json`
```json
{
  "v": 1,
  "type": "transfer.reject",
  "id": "6f1c2a0e-0000-4000-8000-000000000005",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90", "reason": "no_space" }
}
```
`protocol/fixtures/transfer.finish.json`
```json
{
  "v": 1,
  "type": "transfer.finish",
  "id": "6f1c2a0e-0000-4000-8000-000000000006",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90", "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" }
}
```
`protocol/fixtures/transfer.result.json`
```json
{
  "v": 1,
  "type": "transfer.result",
  "id": "6f1c2a0e-0000-4000-8000-000000000007",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90", "ok": true }
}
```
`protocol/fixtures/transfer.result.failed.json`
```json
{
  "v": 1,
  "type": "transfer.result",
  "id": "6f1c2a0e-0000-4000-8000-000000000008",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90", "ok": false, "error": "checksum_mismatch" }
}
```
`protocol/fixtures/transfer.cancel.json`
```json
{
  "v": 1,
  "type": "transfer.cancel",
  "id": "6f1c2a0e-0000-4000-8000-000000000009",
  "body": { "transferId": "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90" }
}
```

- [ ] **Step 2: Write the failing Swift tests** (in `ProtocolFixtureTests`)

Replace the `fixturesRoundTrip` arguments and add the decode test:

```swift
    static let messageFixtures = [
        "hello.json", "device.status.json",
        "transfer.offer.json", "transfer.accept.json", "transfer.reject.json", "transfer.finish.json",
        "transfer.result.json", "transfer.result.failed.json", "transfer.cancel.json",
    ]

    @Test(arguments: messageFixtures)
    func fixturesRoundTrip(name: String) throws {
        let text = try Fixtures.text(name)
        let reencoded = try MessageCodec.encode(try MessageCodec.decode(text), id: try Fixtures.id(of: text))
        #expect(try Fixtures.jsonObject(reencoded) == Fixtures.jsonObject(text))
    }

    @Test func transferFixturesDecode() throws {
        let id = "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90"
        #expect(try MessageCodec.decode(Fixtures.text("transfer.offer.json")) == .transferOffer(TransferOffer(transferId: id, name: "IMG_2041.jpg", size: 3_145_728, mime: "image/jpeg")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.accept.json")) == .transferAccept(TransferID(transferId: id)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.reject.json")) == .transferReject(TransferReject(transferId: id, reason: .noSpace)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.finish.json")) == .transferFinish(TransferFinish(transferId: id, sha256: "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.json")) == .transferResult(TransferResult(transferId: id, ok: true)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.result.failed.json")) == .transferResult(TransferResult(transferId: id, ok: false, error: .checksumMismatch)))
        #expect(try MessageCodec.decode(Fixtures.text("transfer.cancel.json")) == .transferCancel(TransferID(transferId: id)))
    }

    @Test func unknownRejectReasonIsMalformed() {
        let text = #"{"v":1,"type":"transfer.reject","id":"x","body":{"transferId":"t","reason":"bored"}}"#
        #expect(throws: MessageCodecError.malformed) { try MessageCodec.decode(text) }
    }
```

- [ ] **Step 3: Write the failing Kotlin tests** (in `MessageCodecTest`)

Replace `fixturesRoundTrip` and add the decode test:

```kotlin
    private val messageFixtures = listOf(
        "hello.json", "device.status.json",
        "transfer.offer.json", "transfer.accept.json", "transfer.reject.json", "transfer.finish.json",
        "transfer.result.json", "transfer.result.failed.json", "transfer.cancel.json",
    )

    @Test fun fixturesRoundTrip() {
        for (name in messageFixtures) {
            val text = Fixtures.text(name)
            val reencoded = MessageCodec.encode(MessageCodec.decode(text), id = Fixtures.id(text))
            assertEquals(name, Json.parseToJsonElement(text), Json.parseToJsonElement(reencoded))
        }
    }

    @Test fun transferFixturesDecode() {
        val id = "0b7e4f52-8c1d-4e0a-9f3b-2d6a1c5e7f90"
        assertEquals(Message.TransferOffer(TransferOfferBody(id, "IMG_2041.jpg", 3_145_728, "image/jpeg")), MessageCodec.decode(Fixtures.text("transfer.offer.json")))
        assertEquals(Message.TransferAccept(TransferIdBody(id)), MessageCodec.decode(Fixtures.text("transfer.accept.json")))
        assertEquals(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.NoSpace)), MessageCodec.decode(Fixtures.text("transfer.reject.json")))
        assertEquals(Message.TransferFinish(TransferFinishBody(id, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")), MessageCodec.decode(Fixtures.text("transfer.finish.json")))
        assertEquals(Message.TransferResult(TransferResultBody(id, ok = true)), MessageCodec.decode(Fixtures.text("transfer.result.json")))
        assertEquals(Message.TransferResult(TransferResultBody(id, ok = false, error = TransferResultError.ChecksumMismatch)), MessageCodec.decode(Fixtures.text("transfer.result.failed.json")))
        assertEquals(Message.TransferCancel(TransferIdBody(id)), MessageCodec.decode(Fixtures.text("transfer.cancel.json")))
    }

    @Test fun unknownRejectReasonIsMalformed() {
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode("""{"v":1,"type":"transfer.reject","id":"x","body":{"transferId":"t","reason":"bored"}}""")
        }
    }
```

- [ ] **Step 4: Run both to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter ProtocolFixtureTests` → compile error (`TransferOffer` not defined).
Run: `cd android && ./gradlew :core:test --tests '*MessageCodecTest*'` → compile error.

- [ ] **Step 5: Swift types** (append to `Messages.swift`, before `Message`)

```swift
public struct TransferOffer: Codable, Equatable, Sendable {
    public var transferId: String
    public var name: String
    public var size: Int64
    public var mime: String

    public init(transferId: String, name: String, size: Int64, mime: String) {
        self.transferId = transferId
        self.name = name
        self.size = size
        self.mime = mime
    }
}

/// Body of `transfer.accept` and `transfer.cancel`.
public struct TransferID: Codable, Equatable, Sendable {
    public var transferId: String

    public init(transferId: String) { self.transferId = transferId }
}

public enum TransferRejectReason: String, Codable, Sendable {
    case noSpace = "no_space"
    case declined
    case invalid
}

public struct TransferReject: Codable, Equatable, Sendable {
    public var transferId: String
    public var reason: TransferRejectReason

    public init(transferId: String, reason: TransferRejectReason) {
        self.transferId = transferId
        self.reason = reason
    }
}

public struct TransferFinish: Codable, Equatable, Sendable {
    public var transferId: String
    /// Lowercase hex SHA-256 of the whole file.
    public var sha256: String

    public init(transferId: String, sha256: String) {
        self.transferId = transferId
        self.sha256 = sha256
    }
}

public enum TransferResultError: String, Codable, Sendable {
    case checksumMismatch = "checksum_mismatch"
    case noSpace = "no_space"
    case io
}

public struct TransferResult: Codable, Equatable, Sendable {
    public var transferId: String
    public var ok: Bool
    public var error: TransferResultError?

    public init(transferId: String, ok: Bool, error: TransferResultError? = nil) {
        self.transferId = transferId
        self.ok = ok
        self.error = error
    }
}
```

Add the cases to `Message` (after `deviceStatus`):

```swift
    case transferOffer(TransferOffer)
    case transferAccept(TransferID)
    case transferReject(TransferReject)
    case transferFinish(TransferFinish)
    case transferResult(TransferResult)
    case transferCancel(TransferID)
```

- [ ] **Step 6: Swift codec** (`MessageCodec.swift`)

In `encode`, after the `deviceStatus` case:

```swift
        case .transferOffer(let body): return try encode(type: "transfer.offer", id: id, body: body)
        case .transferAccept(let body): return try encode(type: "transfer.accept", id: id, body: body)
        case .transferReject(let body): return try encode(type: "transfer.reject", id: id, body: body)
        case .transferFinish(let body): return try encode(type: "transfer.finish", id: id, body: body)
        case .transferResult(let body): return try encode(type: "transfer.result", id: id, body: body)
        case .transferCancel(let body): return try encode(type: "transfer.cancel", id: id, body: body)
```

In `decode`, after the `device.status` case:

```swift
            case "transfer.offer": return .transferOffer(try decoder.decode(Incoming<TransferOffer>.self, from: data).body)
            case "transfer.accept": return .transferAccept(try decoder.decode(Incoming<TransferID>.self, from: data).body)
            case "transfer.reject": return .transferReject(try decoder.decode(Incoming<TransferReject>.self, from: data).body)
            case "transfer.finish": return .transferFinish(try decoder.decode(Incoming<TransferFinish>.self, from: data).body)
            case "transfer.result": return .transferResult(try decoder.decode(Incoming<TransferResult>.self, from: data).body)
            case "transfer.cancel": return .transferCancel(try decoder.decode(Incoming<TransferID>.self, from: data).body)
```

In `ConnectionHub.handle(_:session:)`, keep the switch exhaustive (Task 3 replaces this arm):

```swift
        case .transferOffer, .transferAccept, .transferReject, .transferFinish, .transferResult, .transferCancel:
            logger.debug("transfer message received before the transfer manager exists")
```

- [ ] **Step 7: Kotlin types** (`Messages.kt`)

Add `import kotlinx.serialization.SerialName`, then the bodies:

```kotlin
@Serializable
data class TransferOfferBody(val transferId: String, val name: String, val size: Long, val mime: String)

/** Body of `transfer.accept` and `transfer.cancel`. */
@Serializable
data class TransferIdBody(val transferId: String)

@Serializable
enum class TransferRejectReason {
    @SerialName("no_space") NoSpace,
    @SerialName("declined") Declined,
    @SerialName("invalid") Invalid,
}

@Serializable
data class TransferRejectBody(val transferId: String, val reason: TransferRejectReason)

/** [sha256] is the lowercase hex SHA-256 of the whole file. */
@Serializable
data class TransferFinishBody(val transferId: String, val sha256: String)

@Serializable
enum class TransferResultError {
    @SerialName("checksum_mismatch") ChecksumMismatch,
    @SerialName("no_space") NoSpace,
    @SerialName("io") Io,
}

@Serializable
data class TransferResultBody(val transferId: String, val ok: Boolean, val error: TransferResultError? = null)
```

Add to `Message` (after `DeviceStatus`):

```kotlin
    data class TransferOffer(val body: TransferOfferBody) : Message
    data class TransferAccept(val body: TransferIdBody) : Message
    data class TransferReject(val body: TransferRejectBody) : Message
    data class TransferFinish(val body: TransferFinishBody) : Message
    data class TransferResult(val body: TransferResultBody) : Message
    data class TransferCancel(val body: TransferIdBody) : Message
```

- [ ] **Step 8: Kotlin codec** (`MessageCodec.kt`)

Set `explicitNulls = false` in the `Json { }` block, so a null `error` is omitted (matching Swift's `encodeIfPresent`):

```kotlin
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
```

In `encode`, after `DeviceStatus`:

```kotlin
            is Message.TransferOffer -> "transfer.offer" to json.encodeToJsonElement(TransferOfferBody.serializer(), message.body)
            is Message.TransferAccept -> "transfer.accept" to json.encodeToJsonElement(TransferIdBody.serializer(), message.body)
            is Message.TransferReject -> "transfer.reject" to json.encodeToJsonElement(TransferRejectBody.serializer(), message.body)
            is Message.TransferFinish -> "transfer.finish" to json.encodeToJsonElement(TransferFinishBody.serializer(), message.body)
            is Message.TransferResult -> "transfer.result" to json.encodeToJsonElement(TransferResultBody.serializer(), message.body)
            is Message.TransferCancel -> "transfer.cancel" to json.encodeToJsonElement(TransferIdBody.serializer(), message.body)
```

In `decode`, after `"device.status"`:

```kotlin
                "transfer.offer" -> Message.TransferOffer(json.decodeFromJsonElement(TransferOfferBody.serializer(), body))
                "transfer.accept" -> Message.TransferAccept(json.decodeFromJsonElement(TransferIdBody.serializer(), body))
                "transfer.reject" -> Message.TransferReject(json.decodeFromJsonElement(TransferRejectBody.serializer(), body))
                "transfer.finish" -> Message.TransferFinish(json.decodeFromJsonElement(TransferFinishBody.serializer(), body))
                "transfer.result" -> Message.TransferResult(json.decodeFromJsonElement(TransferResultBody.serializer(), body))
                "transfer.cancel" -> Message.TransferCancel(json.decodeFromJsonElement(TransferIdBody.serializer(), body))
```

- [ ] **Step 9: Update `protocol/PROTOCOL.md`**

Add these rows to the HTTP endpoints table:

```markdown
| HEAD | `/v1/files/{transferId}` | paired phone only | Phone → Mac upload: 200 with header `Upload-Offset: N` (bytes already on the Mac's disk); 404 for an unknown id or a Mac → phone transfer |
| PUT | `/v1/files/{transferId}` | paired phone only | Raw file bytes. No `Content-Range` means offset 0; a resume sends `Content-Range: bytes N-(size-1)/size`. 200 + `Upload-Offset`; 409 + `Upload-Offset` if N isn't the Mac's offset; 507 if the Mac's disk is full |
| GET | `/v1/files/{transferId}` | paired phone only | Mac → phone download. `Range: bytes=N-` only when N > 0 → 206 (+ `Content-Range` when N < size; empty body when N == size). 404 for an unknown id or a phone → Mac transfer; 410 if the Mac's file is gone |
```

Replace the last line ("Notification, clipboard and transfer messages are added by later plans…") with:

```markdown
| `transfer.offer` | sender → receiver | `transferId` (lowercase UUID), `name`, `size` (bytes), `mime` |
| `transfer.accept` | receiver → sender | `transferId` |
| `transfer.reject` | receiver → sender | `transferId`, `reason` (`no_space` \| `declined` \| `invalid`) |
| `transfer.finish` | sender → receiver | `transferId`, `sha256` (lowercase hex of the whole file) |
| `transfer.result` | receiver → sender | `transferId`, `ok`, `error` (omitted when ok; `checksum_mismatch` \| `no_space` \| `io`) |
| `transfer.cancel` | either | `transferId` |

### Transfer rules
- The phone always makes the HTTP request: `PUT` for phone → Mac, `GET` for Mac → phone. Bytes go over HTTPS, never the WebSocket.
- **Resume after a reconnect:** the sender re-sends `transfer.offer` with the same id. The receiver accepts a known id again, treats an unknown id as a new offer (starting from byte 0), and replays the outcome for an id in its history (`transfer.result`, or `transfer.cancel` if it was cancelled).
- **Uploads:** `HEAD` first; when `Upload-Offset` equals `size` there's no `PUT`, and the phone sends `finish`.
- **Downloads:** the phone always sends `GET`. After every `GET` that reaches the end of the file, the Mac sends `finish`. The phone verifies once it has every byte and the `finish`, in either order.
- Partial files are `<name>.macdroid-part` until verified. At most 3 outgoing transfers per side are active; the rest wait in a FIFO queue.
- File names from the other side are sanitised; see `fixtures/file-naming.json`.
```

- [ ] **Step 10: Run all tests on both sides**

Run: `cd mac/MacDroidCore && swift test` → all pass.
Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, no warnings.

- [ ] **Step 11: Handoff**
  - **CHANGELOG.md:** add the bullet: "Task 2: `transfer.*` messages in both codecs with 7 fixtures; `PROTOCOL.md` documents them and the `/v1/files` routes. Verified: both suites."
  - **ARCHITECTURE.md:** in "Topology → Messages", list the six `transfer.*` types after `device.status`.

- [ ] **Step 12: Commit**

```bash
git add protocol mac/MacDroidCore android/core docs/handoff
git commit -m "feat(protocol): transfer.offer/accept/reject/finish/result/cancel"
```

---

### Task 3: `Broadcast` and `ConnectionHub` multi-subscriber streams

Today `ConnectionHub.statusUpdates` is a single-consumer `AsyncStream`, and messages other than hello/status are dropped. The app, the transfer manager and the dev server each need their own subscription. So does the hub's new `messages` stream.

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Session/Broadcast.swift`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Session/ConnectionHub.swift`
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/BroadcastTests.swift`; modify `ConnectionHubTests.swift`, `TestSupport.swift`

**Interfaces:**
- Produces:
  - `final class Broadcast<Element: Sendable>: Sendable` with:
    - `init(replayLatest: Bool, initial: Element? = nil, bufferingPolicy: AsyncStream<Element>.Continuation.BufferingPolicy = .unbounded)`
    - `func stream() -> AsyncStream<Element>`
    - `func yield(_:)`
    - `func finish()`
    - `var subscriberCount: Int` (internal)
  - `ConnectionHub.statusUpdates: AsyncStream<ConnectionStatus>`: now `nonisolated var`. Each access is a new subscription that starts with the current status.
  - `ConnectionHub.messages: AsyncStream<Message>`: every decoded message except `hello`, `device.status` and unknown types, in arrival order.
  - Test helper `actor Collector<T: Sendable>` with `items` and `append(_:)`.

- [ ] **Step 1: Add the test helper** (append to `TestSupport.swift`)

```swift
/// Collects values from a stream so tests can poll them with `eventually`.
actor Collector<T: Sendable> {
    private(set) var items: [T] = []
    func append(_ item: T) { items.append(item) }
}
```

- [ ] **Step 2: Write the failing tests**

`BroadcastTests.swift`:

```swift
import Testing
@testable import MacDroidCore

@Suite struct BroadcastTests {
    @Test func everySubscriberGetsEveryValue() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let a = Collector<Int>(), b = Collector<Int>()
        let streamA = broadcast.stream(), streamB = broadcast.stream()
        let tasks = [
            Task { for await value in streamA { await a.append(value) } },
            Task { for await value in streamB { await b.append(value) } },
        ]
        broadcast.yield(1)
        broadcast.yield(2)
        try await eventually {
            let (first, second) = (await a.items, await b.items)
            return first == [1, 2] && second == [1, 2]
        }
        tasks.forEach { $0.cancel() }
    }

    @Test func lateSubscriberGetsLatestWhenReplaying() async throws {
        let broadcast = Broadcast<String>(replayLatest: true, initial: "start")
        broadcast.yield("newest")
        var iterator = broadcast.stream().makeAsyncIterator()
        #expect(await iterator.next() == "newest")
    }

    @Test func noReplayWithoutReplayLatest() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        broadcast.yield(1)
        let seen = Collector<Int>()
        let stream = broadcast.stream()
        let task = Task { for await value in stream { await seen.append(value) } }
        broadcast.yield(2)
        try await eventually { await seen.items == [2] }
        task.cancel()
    }

    @Test func cancelledSubscriberIsRemoved() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let stream = broadcast.stream()
        let task = Task { for await _ in stream {} }
        #expect(broadcast.subscriberCount == 1)
        task.cancel()
        try await eventually { broadcast.subscriberCount == 0 }
    }

    @Test func finishEndsEveryStream() async {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let stream = broadcast.stream()
        broadcast.finish()
        var iterator = stream.makeAsyncIterator()
        #expect(await iterator.next() == nil)
    }
}
```

Append to `ConnectionHubTests`:

```swift
    @Test func twoStatusSubscribersBothSeeTheConnection() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let first = Collector<ConnectionStatus>(), second = Collector<ConnectionStatus>()
        let streamA = hub.statusUpdates, streamB = hub.statusUpdates
        let listeners = [
            Task { for await status in streamA { await first.append(status) } },
            Task { for await status in streamB { await second.append(status) } },
        ]
        let transport = FakeTransport()
        let task = start(hub, transport)
        try transport.receive(phoneHello)
        let connected = ConnectionStatus.connected(PeerInfo(device: "Nothing Phone (3)", app: "0.1.0", features: []))
        try await eventually {
            let (a, b) = (await first.items, await second.items)
            return a.contains(connected) && b.contains(connected)
        }
        #expect(await first.items.first == .disconnected) // each subscription starts with the current status
        transport.hangUp(); await task.value
        listeners.forEach { $0.cancel() }
    }

    @Test func transferMessagesReachTheMessagesStream() async throws {
        let hub = ConnectionHub(macName: "Test Mac", appVersion: "0.1.0")
        let received = Collector<Message>()
        let stream = hub.messages
        let listener = Task { for await message in stream { await received.append(message) } }
        let transport = FakeTransport()
        let task = start(hub, transport)
        let offer = Message.transferOffer(TransferOffer(transferId: "t1", name: "a.txt", size: 3, mime: "text/plain"))
        try transport.receive(phoneHello)
        try transport.receive(.deviceStatus(DeviceStatus(battery: 50, charging: false)))
        try transport.receive(offer)
        try await eventually { await received.items == [offer] } // hello and device.status are not forwarded
        transport.hangUp(); await task.value
        listener.cancel()
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter "BroadcastTests|ConnectionHubTests"`
Expected: compile errors (`Broadcast` and `hub.messages` not defined).

- [ ] **Step 4: Implement `Broadcast.swift`**

```swift
import NIOConcurrencyHelpers

/// Fans values out to any number of `AsyncStream` subscribers.
/// With `replayLatest`, a new subscriber first receives the most recent value (or `initial`).
final class Broadcast<Element: Sendable>: Sendable {
    private struct State {
        var latest: Element?
        var continuations: [UInt64: AsyncStream<Element>.Continuation] = [:]
        var nextID: UInt64 = 0
    }

    private let state: NIOLockedValueBox<State>
    private let replayLatest: Bool
    private let bufferingPolicy: AsyncStream<Element>.Continuation.BufferingPolicy

    init(replayLatest: Bool, initial: Element? = nil, bufferingPolicy: AsyncStream<Element>.Continuation.BufferingPolicy = .unbounded) {
        self.state = NIOLockedValueBox(State(latest: initial))
        self.replayLatest = replayLatest
        self.bufferingPolicy = bufferingPolicy
    }

    /// A new subscription; it ends when the consumer stops iterating or `finish()` is called.
    func stream() -> AsyncStream<Element> {
        let (stream, continuation) = AsyncStream.makeStream(of: Element.self, bufferingPolicy: bufferingPolicy)
        let id: UInt64 = state.withLockedValue { state in
            let id = state.nextID
            state.nextID += 1
            if replayLatest, let latest = state.latest { continuation.yield(latest) }
            state.continuations[id] = continuation
            return id
        }
        continuation.onTermination = { [weak self] _ in
            self?.state.withLockedValue { _ = $0.continuations.removeValue(forKey: id) }
        }
        return stream
    }

    func yield(_ value: Element) {
        // Yield under the lock so a subscriber joining concurrently can't see values out of order.
        state.withLockedValue { state in
            state.latest = value
            for continuation in state.continuations.values { continuation.yield(value) }
        }
    }

    func finish() {
        // finish() runs onTermination synchronously, which takes the lock, so call it outside.
        let continuations = state.withLockedValue { state in
            defer { state.continuations.removeAll() }
            return Array(state.continuations.values)
        }
        for continuation in continuations { continuation.finish() }
    }

    var subscriberCount: Int { state.withLockedValue { $0.continuations.count } }
}
```

- [ ] **Step 5: Use it in `ConnectionHub`**

Replace the `statusUpdates` and `statusContinuation` stored properties and the `status` property with:

```swift
    private let statusBroadcast = Broadcast<ConnectionStatus>(replayLatest: true, initial: .disconnected)
    private let messageBroadcast = Broadcast<Message>(replayLatest: false)

    /// Status changes. Each access is a new subscription that starts with the current status.
    public nonisolated var statusUpdates: AsyncStream<ConnectionStatus> { statusBroadcast.stream() }

    /// Control messages for feature managers (transfers now; notifications and clipboard later), in arrival order.
    /// `hello`, `device.status` and unknown types are handled here and not forwarded.
    public nonisolated var messages: AsyncStream<Message> { messageBroadcast.stream() }

    public private(set) var status: ConnectionStatus = .disconnected {
        didSet { if status != oldValue { statusBroadcast.yield(status) } }
    }
```

In `init`, delete the two lines that made the old stream (`let (stream, continuation) = …`, `self.statusUpdates = stream`, `self.statusContinuation = continuation`).

In `handle(_:session:)`, replace the transfer arm from Task 2 with:

```swift
        case .transferOffer, .transferAccept, .transferReject, .transferFinish, .transferResult, .transferCancel:
            messageBroadcast.yield(message)
```

(`AppModel` and the dev server keep compiling unchanged: they read `hub.statusUpdates` once, inside a task.)

- [ ] **Step 6: Run all tests and the app build**

Run: `cd mac/MacDroidCore && swift test` → all pass.
Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build` → BUILD SUCCEEDED, no warnings.

- [ ] **Step 7: Handoff**
  - **CHANGELOG.md:** add the bullet: "Task 3: `Broadcast` fan-out; `ConnectionHub.statusUpdates` is multi-subscriber (replays the current status), and the new `ConnectionHub.messages` forwards control messages. Verified: `swift test` and the app build."
  - **ARCHITECTURE.md:**
    - In the Mac components table, add a "Session" row: `Session/Broadcast.swift` — multi-subscriber stream fan-out.
    - Under "Extension points", replace the "single-consumer status stream and no incoming-message hook" sentence with "Feature managers subscribe to `ConnectionHub.messages` and `statusUpdates` (each access is an independent subscription)".

- [ ] **Step 8: Commit**

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): multi-subscriber hub status and messages streams"
```

---
### Task 4: Swift pure helpers — `FileNaming`, `ByteRanges`, `TransferText` (shared fixtures)

The naming rules and the progress text must behave identically on both sides. So their test vectors live in `protocol/fixtures/`, and Task 9 runs the same files on Kotlin.

**Files:**
- Create: `protocol/fixtures/file-naming.json`, `protocol/fixtures/transfer-text.json`
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/FileNaming.swift`, `ByteRanges.swift`, `TransferText.swift`
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferPureTests.swift`

**Interfaces:**
- Produces:
  - `enum FileNaming`: `maxBytes = 200`, `partSuffix = ".macdroid-part"`, `sanitize(_:) -> String`, `unique(_:isTaken:) -> String`, and internal `splitExtension(_:) -> (base: String, ext: String)` and `truncateUTF8(_:maxBytes:)`.
  - `enum ByteRanges`:
    - `parseRangeStart(_:) -> Int64?` parses `bytes=N-`.
    - `parseContentRange(_:) -> (start: Int64, end: Int64, total: Int64)?` parses `bytes N-M/T`.
  - `enum TransferText`: `bytes(_ n: Int64) -> String` and `line(done: Int64, total: Int64, bytesPerSecond: Double?) -> String`.

- [ ] **Step 1: Add the shared fixtures**

`protocol/fixtures/file-naming.json`:

```json
{
  "sanitize": [
    { "in": "photo.jpg", "out": "photo.jpg" },
    { "in": "../../etc/passwd", "out": "passwd" },
    { "in": "C:\\Users\\me\\report.pdf", "out": "report.pdf" },
    { "in": "dir/", "out": "file" },
    { "in": ".hidden", "out": "hidden" },
    { "in": "  .profile", "out": "profile" },
    { "in": "...", "out": "file" },
    { "in": "", "out": "file" },
    { "in": "a\u0000b.txt", "out": "ab.txt" },
    { "in": "tab\tname\n.txt", "out": "tabname.txt" },
    { "in": "what?*:<>|\".txt", "out": "what.txt" },
    { "in": "  spaced  .txt ", "out": "spaced  .txt" },
    { "in": "résumé 2026.pdf", "out": "résumé 2026.pdf" }
  ],
  "unique": [
    { "name": "a.txt", "taken": [], "out": "a.txt" },
    { "name": "a.txt", "taken": ["a.txt"], "out": "a (1).txt" },
    { "name": "a.txt", "taken": ["a.txt", "a (1).txt"], "out": "a (2).txt" },
    { "name": "README", "taken": ["README"], "out": "README (1)" },
    { "name": "archive.tar.gz", "taken": ["archive.tar.gz"], "out": "archive.tar (1).gz" }
  ]
}
```

`protocol/fixtures/transfer-text.json`:

```json
{
  "bytes": [
    { "in": 0, "out": "0 bytes" },
    { "in": 512, "out": "512 bytes" },
    { "in": 1500, "out": "1.5 KB" },
    { "in": 3145728, "out": "3.1 MB" },
    { "in": 2100000000, "out": "2.1 GB" }
  ],
  "lines": [
    { "done": 1300000000, "total": 2100000000, "rate": 48000000, "out": "1.3 of 2.1 GB · 48 MB/s · 17 s left" },
    { "done": 0, "total": 5000000, "rate": null, "out": "0.0 of 5.0 MB" },
    { "done": 2500000, "total": 5000000, "rate": 1200000, "out": "2.5 of 5.0 MB · 1.2 MB/s · 3 s left" },
    { "done": 100, "total": 512, "rate": 50, "out": "100 of 512 bytes · 50 bytes/s · 9 s left" },
    { "done": 1000000000, "total": 9000000000, "rate": 2000000, "out": "1.0 of 9.0 GB · 2.0 MB/s · 1 h 7 min left" },
    { "done": 0, "total": 300000, "rate": 20000, "out": "0.0 of 300.0 KB · 20 KB/s · 15 s left" },
    { "done": 1000000, "total": 1000000000, "rate": 5000000, "out": "0.0 of 1.0 GB · 5.0 MB/s · 4 min left" },
    { "done": 512, "total": 512, "rate": 50, "out": "512 of 512 bytes · 50 bytes/s" }
  ]
}
```

- [ ] **Step 2: Write the failing tests** (`TransferPureTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

private struct NamingVectors: Decodable {
    struct Sanitize: Decodable { let `in`: String; let out: String }
    struct Unique: Decodable { let name: String; let taken: [String]; let out: String }
    let sanitize: [Sanitize]
    let unique: [Unique]
}

private struct TextVectors: Decodable {
    struct Bytes: Decodable { let `in`: Int64; let out: String }
    struct Line: Decodable { let done: Int64; let total: Int64; let rate: Double?; let out: String }
    let bytes: [Bytes]
    let lines: [Line]
}

private func vectors<T: Decodable>(_ name: String, as: T.Type) throws -> T {
    try JSONDecoder().decode(T.self, from: Data(try Fixtures.text(name).utf8))
}

@Suite struct FileNamingTests {
    @Test func sharedSanitizeVectors() throws {
        for vector in try vectors("file-naming.json", as: NamingVectors.self).sanitize {
            #expect(FileNaming.sanitize(vector.in) == vector.out, "sanitize(\(vector.in.debugDescription))")
        }
    }

    @Test func sharedUniqueVectors() throws {
        for vector in try vectors("file-naming.json", as: NamingVectors.self).unique {
            #expect(FileNaming.unique(vector.name) { vector.taken.contains($0) } == vector.out)
        }
    }

    @Test func longNamesKeepTheirExtensionWithin200Bytes() {
        let name = FileNaming.sanitize(String(repeating: "a", count: 250) + ".txt")
        #expect(name == String(repeating: "a", count: 196) + ".txt")
        let multibyte = FileNaming.sanitize(String(repeating: "é", count: 150) + ".jpg") // é is 2 bytes
        #expect(multibyte == String(repeating: "é", count: 98) + ".jpg")
        #expect(multibyte.utf8.count == 200)
    }

    @Test func overlongExtensionIsTreatedAsPartOfTheName() {
        let name = FileNaming.sanitize("x." + String(repeating: "e", count: 300))
        #expect(name.utf8.count == 200)
        #expect(name.hasPrefix("x.eee"))
    }

    @Test func uniqueStaysWithin200Bytes() {
        let long = String(repeating: "b", count: 196) + ".txt"
        let next = FileNaming.unique(long) { $0 == long }
        #expect(next == String(repeating: "b", count: 192) + " (1).txt")
        #expect(next.utf8.count == 200)
    }
}

@Suite struct ByteRangesTests {
    @Test func parsesOpenEndedRange() {
        #expect(ByteRanges.parseRangeStart("bytes=0-") == 0)
        #expect(ByteRanges.parseRangeStart("bytes=1048576-") == 1_048_576)
        #expect(ByteRanges.parseRangeStart("bytes=5-10") == nil) // only the open-ended form is used
        #expect(ByteRanges.parseRangeStart("bytes=-5") == nil)
        #expect(ByteRanges.parseRangeStart("items=5-") == nil)
    }

    @Test func parsesContentRange() throws {
        let range = try #require(ByteRanges.parseContentRange("bytes 100-199/200"))
        #expect(range.start == 100 && range.end == 199 && range.total == 200)
        #expect(ByteRanges.parseContentRange("bytes 100-99/200") == nil)
        #expect(ByteRanges.parseContentRange("bytes 100-200/200") == nil)
        #expect(ByteRanges.parseContentRange("bytes */200") == nil)
        #expect(ByteRanges.parseContentRange("bytes -1-5/10") == nil)
    }
}

@Suite struct TransferTextTests {
    @Test func sharedVectors() throws {
        let text = try vectors("transfer-text.json", as: TextVectors.self)
        for vector in text.bytes { #expect(TransferText.bytes(vector.in) == vector.out) }
        for vector in text.lines {
            #expect(TransferText.line(done: vector.done, total: vector.total, bytesPerSecond: vector.rate) == vector.out)
        }
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter "FileNamingTests|ByteRangesTests|TransferTextTests"`
Expected: compile errors (types not defined).

- [ ] **Step 4: Implement `FileNaming.swift`**

```swift
import Foundation

/// Safe, collision-free names for received files (spec §2.3). Kotlin mirrors this; shared vectors: `file-naming.json`.
public enum FileNaming {
    public static let maxBytes = 200
    public static let partSuffix = ".macdroid-part"
    static let maxExtensionBytes = 20
    private static let unsafe: Set<Unicode.Scalar> = [":", "*", "?", "\"", "<", ">", "|"]

    public static func sanitize(_ raw: String) -> String {
        let last = raw.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        var scalars = String.UnicodeScalarView()
        for scalar in last.unicodeScalars where scalar.value >= 0x20 && scalar.value != 0x7F && !unsafe.contains(scalar) {
            scalars.append(scalar)
        }
        var name = String(scalars).trimmingCharacters(in: CharacterSet(charactersIn: " "))
        while name.hasPrefix(".") { name.removeFirst() }
        name = name.trimmingCharacters(in: CharacterSet(charactersIn: " "))
        if name.isEmpty { return "file" }
        if name.utf8.count <= maxBytes { return name }
        let (base, ext) = splitExtension(name)
        return truncateUTF8(base, maxBytes: maxBytes - ext.utf8.count) + ext
    }

    /// `name`, or `name (1)`, `name (2)`, … (before the extension), whichever `isTaken` rejects first.
    public static func unique(_ name: String, isTaken: (String) -> Bool) -> String {
        guard isTaken(name) else { return name }
        let (base, ext) = splitExtension(name)
        var counter = 1
        while true {
            let suffix = " (\(counter))"
            let candidate = truncateUTF8(base, maxBytes: maxBytes - suffix.utf8.count - ext.utf8.count) + suffix + ext
            if !isTaken(candidate) { return candidate }
            counter += 1
        }
    }

    /// Splits at the last dot. A leading dot or an extension over 20 bytes counts as no extension.
    static func splitExtension(_ name: String) -> (base: String, ext: String) {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return (name, "") }
        let ext = String(name[dot...])
        guard ext.utf8.count <= maxExtensionBytes else { return (name, "") }
        return (String(name[..<dot]), ext)
    }

    /// The longest prefix of whole Unicode scalars that fits in `maxBytes` UTF-8 bytes.
    static func truncateUTF8(_ text: String, maxBytes: Int) -> String {
        var scalars = String.UnicodeScalarView()
        var bytes = 0
        for scalar in text.unicodeScalars {
            let length = scalar.utf8.count
            if bytes + length > maxBytes { break }
            scalars.append(scalar)
            bytes += length
        }
        return String(scalars)
    }
}
```

- [ ] **Step 5: Implement `ByteRanges.swift`**

```swift
/// Parses the two range headers MacDroid uses (spec §2.2).
public enum ByteRanges {
    /// `Range: bytes=N-` → N. Other range forms aren't used by MacDroid and return nil.
    public static func parseRangeStart(_ header: String) -> Int64? {
        guard header.hasPrefix("bytes="), header.hasSuffix("-") else { return nil }
        let number = header.dropFirst("bytes=".count).dropLast()
        guard let start = Int64(number), start >= 0 else { return nil }
        return start
    }

    /// `Content-Range: bytes N-M/T` → (N, M, T), requiring 0 ≤ N ≤ M < T.
    public static func parseContentRange(_ header: String) -> (start: Int64, end: Int64, total: Int64)? {
        guard header.hasPrefix("bytes ") else { return nil }
        let parts = header.dropFirst("bytes ".count).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let total = Int64(parts[1]) else { return nil }
        let bounds = parts[0].split(separator: "-", omittingEmptySubsequences: false)
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]),
              start >= 0, start <= end, end < total else { return nil }
        return (start, end, total)
    }
}
```

- [ ] **Step 6: Implement `TransferText.swift`**

```swift
import Foundation

/// Progress text such as "1.3 of 2.1 GB · 48 MB/s · 17 s left" (decimal units). Kotlin mirrors this; vectors: `transfer-text.json`.
public enum TransferText {
    public static func bytes(_ count: Int64) -> String {
        guard let unit = unit(for: count) else { return "\(count) bytes" }
        return "\(oneDecimal(Double(count) / unit.divisor)) \(unit.label)"
    }

    public static func line(done: Int64, total: Int64, bytesPerSecond: Double?) -> String {
        var parts = [progress(done: done, total: total)]
        if let rate = bytesPerSecond, rate > 0 {
            parts.append(speed(rate))
            let remaining = total - done
            if remaining > 0 { parts.append(timeLeft(seconds: Double(remaining) / rate)) }
        }
        return parts.joined(separator: " · ")
    }

    static func progress(done: Int64, total: Int64) -> String {
        guard let unit = unit(for: total) else { return "\(done) of \(total) bytes" }
        return "\(oneDecimal(Double(done) / unit.divisor)) of \(oneDecimal(Double(total) / unit.divisor)) \(unit.label)"
    }

    static func speed(_ rate: Double) -> String {
        if rate >= 1_000_000 {
            let value = rate / 1_000_000
            return value >= 10 ? "\(String(format: "%.0f", value)) MB/s" : "\(oneDecimal(value)) MB/s"
        }
        if rate >= 1_000 { return "\(String(format: "%.0f", rate / 1_000)) KB/s" }
        return "\(Int64(rate)) bytes/s"
    }

    static func timeLeft(seconds: Double) -> String {
        let total = Int64(seconds.rounded(.up))
        if total < 60 { return "\(total) s left" }
        if total < 3600 { return "\(Int64((Double(total) / 60).rounded(.up))) min left" }
        let minutes = Int64((Double(total % 3600) / 60).rounded(.up))
        return "\(total / 3600) h \(minutes) min left"
    }

    private static func unit(for count: Int64) -> (divisor: Double, label: String)? {
        if count >= 1_000_000_000 { return (1_000_000_000, "GB") }
        if count >= 1_000_000 { return (1_000_000, "MB") }
        if count >= 1_000 { return (1_000, "KB") }
        return nil
    }

    private static func oneDecimal(_ value: Double) -> String { String(format: "%.1f", value) }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter "FileNamingTests|ByteRangesTests|TransferTextTests"` → all pass. Then run `swift test` → all pass.

- [ ] **Step 8: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 4: Swift `FileNaming`, `ByteRanges`, `TransferText`; shared vectors `file-naming.json` and `transfer-text.json`. Verified: `swift test`."

```bash
git add protocol/fixtures mac/MacDroidCore docs/handoff
git commit -m "feat(mac): file naming, range parsing and progress text with shared vectors"
```

---
### Task 5: Swift transfer model, stores, rate, and checksummed file I/O

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferModel.swift`, `JSONFileStore.swift`, `TransferRate.swift`, `Checksum.swift`, `PartFileWriter.swift`, `SourceReader.swift`
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferIOTests.swift`; modify `TestSupport.swift`

**Interfaces:**
- Produces:
  - `TransferDirection` with `.toPhone` and `.fromPhone`.
  - `TransferFailure` with `.checksumMismatch`, `.noSpace`, `.io`, `.declined`, `.invalid`, `.sourceUnavailable`, `.expired`, plus:
    - `resultError: TransferResultError`
    - `init(_ reason: TransferRejectReason)`
    - `init(_ error: TransferResultError?)`
  - `TransferPhase` with `.queued`, `.offered`, `.active`, `.paused`, `.verifying`, `.done`, `.failed(TransferFailure)`, `.cancelled(byPeer: Bool)`, plus `isFinished` and `occupiesSlot`.
  - `struct TransferRecord` (`Codable, Equatable, Sendable, Identifiable`):
    - fields `id`, `direction`, `name`, `size`, `mime`, `phase`, `bytesDone`, `expectedSHA256`, `sourceBookmark`, `folder`, `replacesExisting`, `savedURL`, `createdAt`, `updatedAt`
    - computed `partURL: URL?` = `folder/<name>.macdroid-part`
  - `TransferSnapshot` (`active`, `history`, `rates: [String: Double]`, `static let empty`).
  - `TransferEvent` with `.received(TransferRecord)`.
  - `struct JSONFileStore<Value: Codable>: Sendable` with `init(url:)`, `load() -> Value?` and `save(_:) throws` (atomic).
  - `struct TransferRate` with `record(bytes:at:)` and `bytesPerSecond: Double?`.
  - `enum Checksum` with `sha256Hex(_ data: Data) -> String`, and `SHA256.Digest.hex`.
  - `enum PartFileError: Error` with `.superseded`, `.tooLarge`, `.noSpace`, `.io`, plus `static func classify(_:) -> PartFileError`.
  - `actor PartFileWriter`:
    - `init(url:expectedSize:)`
    - `open() throws`, which creates the file or re-hashes it
    - `beginSession() throws -> Int`
    - `append(_ data: Data, session: Int) throws`
    - `size: Int64`, `flush()`, `digest() -> String`, `close()`
  - `final class SourceReader: @unchecked Sendable`:
    - `init(url:offset:) throws`, which hashes bytes before `offset`
    - `next(maxBytes:) throws -> Data?`
    - `position`, `size`, `digest()`, `close()`

- [ ] **Step 1: Add the temp-directory helper** (append to `TestSupport.swift`)

```swift
func makeTemporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appending(path: "macdroid-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// Deterministic bytes, so failures are reproducible.
func testBytes(_ count: Int, seed: UInt8 = 7) -> Data {
    Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ Int(seed)) })
}
```

- [ ] **Step 2: Write the failing tests** (`TransferIOTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferModelTests {
    @Test func recordRoundTripsThroughJSONFileStore() throws {
        let dir = try makeTemporaryDirectory()
        let store = JSONFileStore<[TransferRecord]>(url: dir.appending(path: "t.json"))
        #expect(store.load() == nil)
        let record = TransferRecord(id: "t1", direction: .fromPhone, name: "a.txt", size: 3, mime: "text/plain",
                                    phase: .failed(.checksumMismatch), folder: dir, createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 2))
        try store.save([record, TransferRecord(id: "t2", direction: .toPhone, name: "b", size: 0, mime: "x", phase: .cancelled(byPeer: true),
                                               createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 2))])
        #expect(store.load()?.map(\.phase) == [.failed(.checksumMismatch), .cancelled(byPeer: true)])
        #expect(store.load()?.first?.partURL == dir.appending(path: "a.txt.macdroid-part"))
    }

    @Test func corruptStoreLoadsAsNil() throws {
        let url = try makeTemporaryDirectory().appending(path: "t.json")
        try Data("not json".utf8).write(to: url)
        #expect(JSONFileStore<[TransferRecord]>(url: url).load() == nil)
    }

    @Test func phaseFlags() {
        #expect(TransferPhase.done.isFinished && TransferPhase.failed(.io).isFinished && TransferPhase.cancelled(byPeer: false).isFinished)
        #expect(!TransferPhase.paused.isFinished)
        #expect([TransferPhase.offered, .active, .paused, .verifying].allSatisfy(\.occupiesSlot))
        #expect(!TransferPhase.queued.occupiesSlot)
    }

    @Test func rateIsSmoothedAndSampledAtMostTwiceASecond() {
        var rate = TransferRate()
        let start = Date(timeIntervalSince1970: 0)
        rate.record(bytes: 0, at: start)
        #expect(rate.bytesPerSecond == nil)
        rate.record(bytes: 500_000, at: start.addingTimeInterval(0.2)) // too soon: ignored
        #expect(rate.bytesPerSecond == nil)
        rate.record(bytes: 1_000_000, at: start.addingTimeInterval(1))
        #expect(rate.bytesPerSecond == 1_000_000)
        rate.record(bytes: 3_000_000, at: start.addingTimeInterval(2))
        #expect(abs((rate.bytesPerSecond ?? 0) - 1_300_000) < 0.001) // 0.7 × 1 MB/s + 0.3 × 2 MB/s
    }
}

@Suite struct PartFileTests {
    @Test func writerHashesEverythingItAppends() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.bin.macdroid-part")
        let data = testBytes(3_000_000)
        let writer = PartFileWriter(url: url, expectedSize: Int64(data.count))
        let session = try await writer.beginSession()
        try await writer.append(data.prefix(1_000_000), session: session)
        try await writer.append(data.dropFirst(1_000_000), session: session)
        await writer.flush()
        #expect(await writer.size == 3_000_000)
        #expect(await writer.digest() == Checksum.sha256Hex(data))
        #expect(try Data(contentsOf: url) == data)
    }

    @Test func reopenedWriterRehashesExistingBytes() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.bin.macdroid-part")
        let data = testBytes(2_500_000)
        try data.prefix(1_234_567).write(to: url)
        let writer = PartFileWriter(url: url, expectedSize: Int64(data.count))
        let session = try await writer.beginSession()
        #expect(await writer.size == 1_234_567)
        try await writer.append(data.dropFirst(1_234_567), session: session)
        #expect(await writer.digest() == Checksum.sha256Hex(data))
    }

    @Test func newSessionSupersedesOldOne() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.macdroid-part")
        let writer = PartFileWriter(url: url, expectedSize: 10)
        let old = try await writer.beginSession()
        _ = try await writer.beginSession()
        await #expect(throws: PartFileError.superseded) { try await writer.append(Data([1]), session: old) }
    }

    @Test func refusesMoreThanTheOfferedSize() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.macdroid-part")
        let writer = PartFileWriter(url: url, expectedSize: 2)
        let session = try await writer.beginSession()
        await #expect(throws: PartFileError.tooLarge) { try await writer.append(Data([1, 2, 3]), session: session) }
    }

    @Test func diskFullErrorsAreRecognised() {
        #expect(PartFileError.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))) == .noSpace)
        #expect(PartFileError.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)) == .noSpace)
        let wrapped = NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError,
                              userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))])
        #expect(PartFileError.classify(wrapped) == .noSpace)
        #expect(PartFileError.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(EIO))) == .io)
    }

    @Test func readerFromOffsetStillDigestsTheWholeFile() throws {
        let url = try makeTemporaryDirectory().appending(path: "src.bin")
        let data = testBytes(2_200_000)
        try data.write(to: url)
        let reader = try SourceReader(url: url, offset: 1_500_000)
        var tail = Data()
        while let chunk = try reader.next() { tail.append(chunk) }
        #expect(tail == data.dropFirst(1_500_000))
        #expect(reader.position == 2_200_000)
        #expect(reader.digest() == Checksum.sha256Hex(data))
        reader.close()
    }

    @Test func readerAtEndReturnsNothingButFullDigest() throws {
        let url = try makeTemporaryDirectory().appending(path: "src.bin")
        let data = testBytes(10)
        try data.write(to: url)
        let reader = try SourceReader(url: url, offset: 10)
        #expect(try reader.next() == nil)
        #expect(reader.digest() == Checksum.sha256Hex(data))
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter "TransferModelTests|PartFileTests"` → compile errors.

- [ ] **Step 4: Implement `TransferModel.swift`**

```swift
import Foundation

public enum TransferDirection: String, Codable, Sendable {
    case toPhone, fromPhone
}

public enum TransferFailure: String, Codable, Sendable {
    case checksumMismatch, noSpace, io, declined, invalid, sourceUnavailable, expired

    /// The `transfer.result` error that reports this failure to the sender.
    var resultError: TransferResultError {
        switch self {
        case .checksumMismatch: .checksumMismatch
        case .noSpace: .noSpace
        default: .io
        }
    }

    init(_ reason: TransferRejectReason) {
        switch reason {
        case .noSpace: self = .noSpace
        case .declined: self = .declined
        case .invalid: self = .invalid
        }
    }

    init(_ error: TransferResultError?) {
        switch error {
        case .checksumMismatch: self = .checksumMismatch
        case .noSpace: self = .noSpace
        case .io, nil: self = .io
        }
    }
}

public enum TransferPhase: Codable, Equatable, Sendable {
    /// Outgoing, waiting for one of the 3 slots.
    case queued
    /// Outgoing: offer sent, waiting for the phone. Incoming: deciding where to save.
    case offered
    case active
    /// Disconnected; resumes when the phone is back.
    case paused
    /// All bytes moved; waiting for the checksum verdict.
    case verifying
    case done
    case failed(TransferFailure)
    case cancelled(byPeer: Bool)

    public var isFinished: Bool {
        switch self {
        case .done, .failed, .cancelled: true
        default: false
        }
    }

    /// Counts toward the 3-active-outgoing limit.
    public var occupiesSlot: Bool {
        switch self {
        case .offered, .active, .paused, .verifying: true
        default: false
        }
    }
}

public struct TransferRecord: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var direction: TransferDirection
    /// Outgoing: the file's name. Incoming: the final file name in `folder`.
    public var name: String
    public var size: Int64
    public var mime: String
    public var phase: TransferPhase
    public var bytesDone: Int64
    /// Incoming: the SHA-256 from `transfer.finish`.
    public var expectedSHA256: String?
    /// Outgoing: a bookmark to the source file (security-scoped in the app), so a resume survives a relaunch.
    public var sourceBookmark: Data?
    /// Incoming: the destination folder.
    public var folder: URL?
    /// Incoming: the user picked this exact file in a save panel, so an existing file there is replaced.
    public var replacesExisting: Bool
    /// Incoming: the finished file.
    public var savedURL: URL?
    public var createdAt: Date
    public var updatedAt: Date

    public init(
        id: String, direction: TransferDirection, name: String, size: Int64, mime: String, phase: TransferPhase,
        bytesDone: Int64 = 0, expectedSHA256: String? = nil, sourceBookmark: Data? = nil, folder: URL? = nil,
        replacesExisting: Bool = false, savedURL: URL? = nil, createdAt: Date, updatedAt: Date
    ) {
        self.id = id
        self.direction = direction
        self.name = name
        self.size = size
        self.mime = mime
        self.phase = phase
        self.bytesDone = bytesDone
        self.expectedSHA256 = expectedSHA256
        self.sourceBookmark = sourceBookmark
        self.folder = folder
        self.replacesExisting = replacesExisting
        self.savedURL = savedURL
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /// Incoming: where bytes land until the checksum is verified.
    public var partURL: URL? { folder?.appending(path: name + FileNaming.partSuffix) }
}

public struct TransferSnapshot: Equatable, Sendable {
    public var active: [TransferRecord]
    /// Finished transfers, newest first (at most 100).
    public var history: [TransferRecord]
    /// Smoothed bytes per second for transfers moving data right now, by id.
    public var rates: [String: Double]

    public static let empty = TransferSnapshot(active: [], history: [], rates: [:])
}

public enum TransferEvent: Equatable, Sendable {
    /// A file from the phone was verified and saved (`savedURL` is set).
    case received(TransferRecord)
}
```

- [ ] **Step 5: Implement `JSONFileStore.swift`, `TransferRate.swift` and `Checksum.swift`**

```swift
import Foundation

/// One Codable value in a JSON file, written atomically. A missing or unreadable file loads as nil.
public struct JSONFileStore<Value: Codable>: Sendable {
    public let url: URL

    public init(url: URL) { self.url = url }

    public func load() -> Value? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Value.self, from: data)
    }

    public func save(_ value: Value) throws {
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
    }
}
```

```swift
import Foundation

/// Smoothed speed from (bytes so far, time) samples, sampled at most twice a second.
public struct TransferRate: Sendable {
    private var lastBytes: Int64?
    private var lastTime: Date?
    public private(set) var bytesPerSecond: Double?

    public init() {}

    public mutating func record(bytes: Int64, at time: Date) {
        guard let lastBytes, let lastTime else {
            self.lastBytes = bytes
            self.lastTime = time
            return
        }
        let elapsed = time.timeIntervalSince(lastTime)
        guard elapsed >= 0.5 else { return }
        let instant = Double(bytes - lastBytes) / elapsed
        bytesPerSecond = bytesPerSecond.map { 0.7 * $0 + 0.3 * instant } ?? instant
        self.lastBytes = bytes
        self.lastTime = time
    }
}
```

```swift
import Crypto
import Foundation

enum Checksum {
    static func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).hex }
}

extension SHA256.Digest {
    /// Lowercase hex, the form used on the wire.
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
```

- [ ] **Step 6: Implement `PartFileWriter.swift`**

```swift
import Crypto
import Foundation

enum PartFileError: Error, Equatable {
    /// A newer upload session took over this file.
    case superseded
    /// More bytes than the offered size.
    case tooLarge
    case noSpace
    case io

    static func classify(_ error: any Error) -> PartFileError {
        let ns = error as NSError
        if ns.domain == NSPOSIXErrorDomain, ns.code == Int(ENOSPC) { return .noSpace }
        if ns.domain == NSCocoaErrorDomain, ns.code == NSFileWriteOutOfSpaceError { return .noSpace }
        if let underlying = ns.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSPOSIXErrorDomain, underlying.code == Int(ENOSPC) {
            return .noSpace
        }
        return .io
    }
}

/// Appends to an incoming `.macdroid-part` file and keeps a running SHA-256 of everything in it.
/// Each upload request is a session. A new session (HEAD or PUT) supersedes the old one, whose writes then fail,
/// so a stale connection can never interleave bytes with a resumed one.
actor PartFileWriter {
    let url: URL
    let expectedSize: Int64
    private var handle: FileHandle?
    private var hasher = SHA256()
    private(set) var size: Int64 = 0
    private var session = 0

    init(url: URL, expectedSize: Int64) {
        self.url = url
        self.expectedSize = expectedSize
    }

    /// Opens the file, creating it if needed, and re-hashes bytes already there (a resume after a relaunch).
    func open() throws {
        guard handle == nil else { return }
        let path = url.path(percentEncoded: false)
        if !FileManager.default.fileExists(atPath: path) {
            guard FileManager.default.createFile(atPath: path, contents: nil) else { throw PartFileError.io }
        }
        do {
            let handle = try FileHandle(forUpdating: url)
            var hasher = SHA256()
            var total: Int64 = 0
            while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
                hasher.update(data: chunk)
                total += Int64(chunk.count)
            }
            self.handle = handle
            self.hasher = hasher
            self.size = total
        } catch {
            throw PartFileError.classify(error)
        }
    }

    func beginSession() throws -> Int {
        try open()
        session += 1
        return session
    }

    func append(_ data: Data, session: Int) throws {
        guard session == self.session else { throw PartFileError.superseded }
        guard size + Int64(data.count) <= expectedSize else { throw PartFileError.tooLarge }
        guard let handle else { throw PartFileError.io }
        do {
            try handle.write(contentsOf: data)
        } catch {
            throw PartFileError.classify(error)
        }
        hasher.update(data: data)
        size += Int64(data.count)
    }

    func flush() { try? handle?.synchronize() }

    func digest() -> String { hasher.finalize().hex }

    func close() {
        try? handle?.close()
        handle = nil
    }
}
```

- [ ] **Step 7: Implement `SourceReader.swift`**

```swift
import Crypto
import Foundation

enum SourceReaderError: Error {
    case badOffset
    /// The file is shorter than when the transfer started.
    case changed
}

/// Reads an outgoing file from `offset` onward. The running SHA-256 already covers bytes before `offset`,
/// so `digest()` is the whole file's checksum once reading reaches the end.
/// Used by one response-writer task at a time.
final class SourceReader: @unchecked Sendable {
    let size: Int64
    private(set) var position: Int64
    private let handle: FileHandle
    private var hasher = SHA256()

    init(url: URL, offset: Int64) throws {
        handle = try FileHandle(forReadingFrom: url)
        size = Int64(try handle.seekToEnd())
        guard offset >= 0, offset <= size else { throw SourceReaderError.badOffset }
        try handle.seek(toOffset: 0)
        var remaining = offset
        while remaining > 0 {
            let chunk = try handle.read(upToCount: Int(min(remaining, 1 << 20))) ?? Data()
            if chunk.isEmpty { throw SourceReaderError.changed }
            hasher.update(data: chunk)
            remaining -= Int64(chunk.count)
        }
        position = offset
    }

    /// The next chunk (up to `maxBytes`), or nil at the end of the file.
    func next(maxBytes: Int = 1 << 20) throws -> Data? {
        guard position < size else { return nil }
        let chunk = try handle.read(upToCount: Int(min(Int64(maxBytes), size - position))) ?? Data()
        if chunk.isEmpty { throw SourceReaderError.changed }
        hasher.update(data: chunk)
        position += Int64(chunk.count)
        return chunk
    }

    func digest() -> String { hasher.finalize().hex }

    func close() { try? handle.close() }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter "TransferModelTests|PartFileTests"` → pass. Then run `swift test` → all pass, no warnings.

- [ ] **Step 9: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 5: transfer model, JSON stores, rate smoothing, and checksummed `PartFileWriter`/`SourceReader` (resume re-hash, session supersede, disk-full detection). Verified: `swift test`."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): transfer model, stores and checksummed part-file I/O"
```

---
### Task 6: `TransferManager` — core and receiving (phone → Mac)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager.swift`
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferReceiveTests.swift`; modify `TestSupport.swift`

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Produces (public, for the app and Plan 2b):
  - `enum SaveLocation { case folder(URL), file(URL) }`
  - `protocol ReceiveLocationProvider: Sendable { func saveLocation(forName: String, size: Int64) async -> SaveLocation? }`
  - `struct AccessedFile: Sendable { url: URL; stop: @Sendable () -> Void }`
  - `protocol SourceAccess: Sendable { bookmark(for:) throws -> Data; resolve(_:) throws -> AccessedFile }`, with `PlainSourceAccess`
  - `actor TransferManager`:
    - `init(stateDirectory:location:sourceAccess:freeSpace:now:send:)`
    - `attach(to: ConnectionHub)`
    - `snapshots: AsyncStream<TransferSnapshot>`, `events: AsyncStream<TransferEvent>`, `snapshot`
    - `cancel(id:)`
    - `static volumeFreeSpace(_:)`
- Produces (internal, for routes and tests):
  - `handle(_ message: Message) async`
  - `connectionChanged(_ connected: Bool) async`
  - `uploadOffset(id:) async -> Int64?`
  - `beginUpload(id:start:) async throws -> Upload`
  - `progress(id:bytesDone:) -> Bool`
  - `uploadFailed(id:_:) async`
  - `RouteError`, `Upload { writer, session }`, `Download { file, size }`
  - Stored state used by Task 7: `active`, `history`, `isConnected`, `outbox`, `sourceAccess`, `send`, `now`, plus helpers `update(_:_:)`, `archive(_:)`, `persist()`, `publish(force:)`, `sendOrQueue(_:)`, `sendOrDrop(_:)`, `discard(_:as:)`

- [ ] **Step 1: Add test helpers** (append to `TestSupport.swift`)

```swift
/// Stands in for the WebSocket to the phone: records what the Mac sends, and fails sends while offline.
actor FakePhoneLink {
    private(set) var sent: [Message] = []
    private(set) var online = true
    func send(_ message: Message) throws {
        guard online else { throw HubError.notConnected }
        sent.append(message)
    }
    func setOnline(_ value: Bool) { online = value }
}

struct FixedLocation: ReceiveLocationProvider {
    let location: SaveLocation?
    init(_ location: SaveLocation?) { self.location = location }
    func saveLocation(forName name: String, size: Int64) async -> SaveLocation? { location }
}

/// Fresh state and Downloads folders, a fake phone link and a fake clock for TransferManager tests.
struct TransferFixture {
    let state: URL
    let downloads: URL
    let phone = FakePhoneLink()
    let clock = FakeClock()

    init() throws {
        let root = try makeTemporaryDirectory()
        state = root.appending(path: "state", directoryHint: .isDirectory)
        downloads = root.appending(path: "Downloads/MacDroid", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
    }

    func manager(location: (any ReceiveLocationProvider)? = nil, freeSpace: @escaping @Sendable (URL) -> Int64? = { _ in nil }) -> TransferManager {
        let phone = self.phone, clock = self.clock
        return TransferManager(
            stateDirectory: state, location: location ?? FixedLocation(.folder(downloads)),
            freeSpace: freeSpace, now: { clock.now }, send: { try await phone.send($0) }
        )
    }

    func file(_ name: String) -> URL { downloads.appending(path: name) }
    func exists(_ name: String) -> Bool { FileManager.default.fileExists(atPath: file(name).path(percentEncoded: false)) }
}

func makeOffer(_ id: String, name: String = "a.txt", size: Int64, mime: String = "text/plain") -> Message {
    .transferOffer(TransferOffer(transferId: id, name: name, size: size, mime: mime))
}

/// Uploads `data` the way the PUT route does.
func upload(_ data: Data, to manager: TransferManager, id: String, from start: Int64 = 0) async throws {
    let upload = try await manager.beginUpload(id: id, start: start)
    try await upload.writer.append(data, session: upload.session)
    await upload.writer.flush()
}

extension Message {
    var acceptedID: String? { if case .transferAccept(let body) = self { return body.transferId }; return nil }
    var rejection: TransferReject? { if case .transferReject(let body) = self { return body }; return nil }
    var result: TransferResult? { if case .transferResult(let body) = self { return body }; return nil }
    var offerBody: TransferOffer? { if case .transferOffer(let body) = self { return body }; return nil }
    var finishBody: TransferFinish? { if case .transferFinish(let body) = self { return body }; return nil }
    var cancelledID: String? { if case .transferCancel(let body) = self { return body.transferId }; return nil }
}
```

- [ ] **Step 2: Write the failing tests** (`TransferReceiveTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferReceiveTests {
    @Test func acceptsOfferIntoAPartFile() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "IMG.jpg", size: 10))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "t1" } }
        #expect(f.exists("IMG.jpg.macdroid-part"))
        #expect(await manager.snapshot.active.map(\.phase) == [.active])
    }

    @Test func uploadThenFinishVerifiesRenamesAndReports() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        let events = Collector<TransferEvent>()
        let stream = manager.events
        let listener = Task { for await event in stream { await events.append(event) } }
        let data = testBytes(300_000)
        await manager.handle(makeOffer("t1", name: "a.bin", size: Int64(data.count)))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("a.bin")) == data)
        #expect(!f.exists("a.bin.macdroid-part"))
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: true) })
        #expect(await manager.snapshot.history.first?.phase == .done)
        #expect(await manager.snapshot.history.first?.savedURL == f.file("a.bin"))
        try await eventually { await events.items.count == 1 }
        listener.cancel()
    }

    @Test func checksumMismatchDeletesThePartAndReportsIt() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 4))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(Data([1, 2, 3, 4]), to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: String(repeating: "0", count: 64))))
        #expect(!f.exists("a.txt.macdroid-part") && !f.exists("a.txt"))
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: false, error: .checksumMismatch) })
        #expect(await manager.snapshot.history.first?.phase == .failed(.checksumMismatch))
    }

    @Test func rejectsWhenThereIsNotEnoughSpace() async throws {
        let f = try TransferFixture()
        let manager = f.manager(freeSpace: { _ in 10 })
        await manager.handle(makeOffer("t1", size: 100))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "t1", reason: .noSpace) } }
        #expect(await manager.snapshot.history.first?.phase == .failed(.noSpace))
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    @Test func declinedWhenNoLocationIsChosen() async throws {
        let f = try TransferFixture()
        let manager = f.manager(location: FixedLocation(nil))
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "t1", reason: .declined) } }
    }

    // Review Focus #3
    @Test func hostileNameLandsInsideFolder() async throws {
        let f = try TransferFixture()
        try Data("old".utf8).write(to: f.file("passwd"))
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "../../etc/passwd", size: 1))
        try await eventually { await manager.snapshot.active.first?.name == "passwd (1)" }
        #expect(f.exists("passwd (1).macdroid-part"))
    }

    // Review Focus #4
    @Test func simultaneousSameNameOffersGetDistinctNames() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "same.txt", size: 1))
        await manager.handle(makeOffer("t2", name: "same.txt", size: 1))
        try await eventually { await manager.snapshot.active.count == 2 }
        #expect(Set(await manager.snapshot.active.map(\.name)) == ["same.txt", "same (1).txt"])
    }

    @Test func savePanelChoiceReplacesThatFile() async throws {
        let f = try TransferFixture()
        try Data("old".utf8).write(to: f.file("chosen.txt"))
        let manager = f.manager(location: FixedLocation(.file(f.file("chosen.txt"))))
        let data = Data("new".utf8)
        await manager.handle(makeOffer("t1", name: "whatever.txt", size: 3))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("chosen.txt")) == data)
    }

    @Test func uploadOffsetReportsBytesOnDiskAndRejectsAWrongStart() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(testBytes(100), to: manager, id: "t1")
        #expect(await manager.uploadOffset(id: "t1") == 100)
        await #expect(throws: TransferManager.RouteError.offsetMismatch(100)) { _ = try await manager.beginUpload(id: "t1", start: 50) }
        #expect(await manager.uploadOffset(id: "unknown") == nil)
    }

    @Test func headSupersedesAnOlderUpload() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        let stale = try await manager.beginUpload(id: "t1", start: 0)
        _ = await manager.uploadOffset(id: "t1")
        await #expect(throws: PartFileError.superseded) { try await stale.writer.append(Data([1]), session: stale.session) }
    }

    @Test func reofferOfAKnownIdAcceptsAgainAndKeepsBytes() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(testBytes(120), to: manager, id: "t1")
        await manager.connectionChanged(false)
        #expect(await manager.snapshot.active.first?.phase == .paused)
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await f.phone.sent.filter { $0.acceptedID == "t1" }.count == 2 }
        #expect(await manager.uploadOffset(id: "t1") == 120)
        #expect(await manager.snapshot.active.first?.phase == .active)
    }

    // Review Focus #1
    @Test func reofferOfFinishedIdReplaysResult() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        let data = Data("hi".utf8)
        await manager.handle(makeOffer("t1", size: 2))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        await manager.handle(makeOffer("t1", size: 2))
        try await eventually { await f.phone.sent.filter { $0.result?.ok == true }.count == 2 }
        #expect(!f.exists("a (1).txt.macdroid-part"))
        #expect(await manager.snapshot.active.isEmpty)
    }

    @Test func peerCancelDeletesThePart() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        await manager.handle(.transferCancel(TransferID(transferId: "t1")))
        #expect(!f.exists("a.txt.macdroid-part"))
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: true))
    }

    @Test func localCancelWhileOfflineIsSentAfterReconnect() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        await f.phone.setOnline(false)
        await manager.connectionChanged(false)
        await manager.cancel(id: "t1")
        #expect(await !f.phone.sent.contains { $0.cancelledID == "t1" })
        await f.phone.setOnline(true)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.contains { $0.cancelledID == "t1" })
    }

    @Test func diskFullMidTransferReportsNoSpace() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        await manager.uploadFailed(id: "t1", .noSpace)
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: false, error: .noSpace) })
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    // Review Focus #5
    @Test func zeroByteFileCompletes() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "empty.txt", size: 0))
        try await eventually { await manager.snapshot.active.count == 1 }
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(Data()))))
        #expect(f.exists("empty.txt"))
        #expect(await manager.snapshot.history.first?.phase == .done)
    }

    // Review Focus #2
    @Test func relaunchResumesAndChecksumStillMatches() async throws {
        let f = try TransferFixture()
        let data = testBytes(500_000)
        do {
            let first = f.manager()
            await first.handle(makeOffer("t1", name: "big.bin", size: Int64(data.count)))
            try await eventually { await first.snapshot.active.count == 1 }
            try await upload(data.prefix(200_000), to: first, id: "t1")
        }
        f.clock.advance(3600)
        let second = f.manager() // relaunch: state comes back from disk
        #expect(await second.snapshot.active.map(\.phase) == [.paused])
        await second.handle(makeOffer("t1", name: "big.bin", size: Int64(data.count)))
        try await eventually { await f.phone.sent.filter { $0.acceptedID == "t1" }.count == 2 }
        #expect(await second.uploadOffset(id: "t1") == 200_000)
        try await upload(data.dropFirst(200_000), to: second, id: "t1", from: 200_000)
        await second.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("big.bin")) == data)
    }

    @Test func stateOlderThan24HoursIsCleanedAtLaunch() async throws {
        let f = try TransferFixture()
        do {
            let first = f.manager()
            await first.handle(makeOffer("t1", size: 10))
            try await eventually { await first.snapshot.active.count == 1 }
        }
        f.clock.advance(25 * 3600)
        let second = f.manager()
        #expect(await second.snapshot.active.isEmpty)
        #expect(await second.snapshot.history.first?.phase == .failed(.expired))
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    @Test func historyKeepsTheLatest100() async throws {
        let f = try TransferFixture()
        let manager = f.manager(location: FixedLocation(nil))
        for index in 0..<105 { await manager.handle(makeOffer("t\(index)", size: 1)) }
        try await eventually { await manager.snapshot.history.count == 100 } // 105 declined; the oldest 5 dropped
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter TransferReceiveTests` → compile errors (`TransferManager` not defined).

- [ ] **Step 4: Implement `TransferManager.swift`**

```swift
import Foundation
import Logging

/// Where an incoming file goes.
public enum SaveLocation: Equatable, Sendable {
    /// Pick a free name in this folder.
    case folder(URL)
    /// The user chose this exact file in a save panel; replace it if it exists.
    case file(URL)
}

/// Decides where incoming files are saved. Nil declines the file.
public protocol ReceiveLocationProvider: Sendable {
    func saveLocation(forName name: String, size: Int64) async -> SaveLocation?
}

/// A resolved source file plus the call that ends access to it (security scope in the sandboxed app).
public struct AccessedFile: Sendable {
    public let url: URL
    public let stop: @Sendable () -> Void

    public init(url: URL, stop: @escaping @Sendable () -> Void) {
        self.url = url
        self.stop = stop
    }
}

/// Keeps access to outgoing files across relaunches: bookmark when queued, resolve when the phone downloads.
public protocol SourceAccess: Sendable {
    func bookmark(for url: URL) throws -> Data
    func resolve(_ bookmark: Data) throws -> AccessedFile
}

/// Plain bookmarks (tests, dev server). The sandboxed app uses security-scoped ones.
public struct PlainSourceAccess: SourceAccess {
    public init() {}
    public func bookmark(for url: URL) throws -> Data { try url.bookmarkData() }
    public func resolve(_ bookmark: Data) throws -> AccessedFile {
        var stale = false
        return AccessedFile(url: try URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale), stop: {})
    }
}

/// Runs file transfers with the phone (spec §2–3): the control handshake, part files, checksums,
/// the 3-outgoing limit, persistence (24 h) and history (last 100).
public actor TransferManager {
    public static let maxActiveOutgoing = 3
    public static let historyLimit = 100
    public static let stateLifetime: TimeInterval = 24 * 60 * 60

    enum RouteError: Error, Equatable {
        case notFound
        case offsetMismatch(Int64)
        case rangeNotSatisfiable
        case sourceUnavailable
    }

    struct Upload: Sendable {
        let writer: PartFileWriter
        let session: Int
    }

    struct Download: Sendable {
        let file: AccessedFile
        let size: Int64
    }

    public nonisolated var snapshots: AsyncStream<TransferSnapshot> { snapshotBroadcast.stream() }
    public nonisolated var events: AsyncStream<TransferEvent> { eventBroadcast.stream() }
    public private(set) var snapshot: TransferSnapshot

    private let snapshotBroadcast: Broadcast<TransferSnapshot>
    let eventBroadcast = Broadcast<TransferEvent>(replayLatest: false)
    private let activeStore: JSONFileStore<[TransferRecord]>
    private let historyStore: JSONFileStore<[TransferRecord]>
    private let location: any ReceiveLocationProvider
    let sourceAccess: any SourceAccess
    private let freeSpace: @Sendable (URL) -> Int64?
    let now: @Sendable () -> Date
    let send: @Sendable (Message) async throws -> Void
    private let logger = Logger(label: "dev.macdroid.transfers")

    var active: [TransferRecord]
    var history: [TransferRecord]
    var isConnected = false
    var outbox: [Message] = []
    private var writers: [String: PartFileWriter] = [:]
    private var rates: [String: TransferRate] = [:]
    private var pendingOffers: Set<String> = []
    private var cancelledOffers: Set<String> = []
    private var lastPublish = Date.distantPast
    private var tasks: [Task<Void, Never>] = []

    public init(
        stateDirectory: URL,
        location: any ReceiveLocationProvider,
        sourceAccess: any SourceAccess = PlainSourceAccess(),
        freeSpace: @escaping @Sendable (URL) -> Int64? = TransferManager.volumeFreeSpace,
        now: @escaping @Sendable () -> Date = { Date() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        try? FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true)
        let activeStore = JSONFileStore<[TransferRecord]>(url: stateDirectory.appending(path: "transfers.json"))
        let historyStore = JSONFileStore<[TransferRecord]>(url: stateDirectory.appending(path: "history.json"))
        let (active, history) = Self.restore(active: activeStore.load() ?? [], history: historyStore.load() ?? [], now: now())
        try? activeStore.save(active)
        try? historyStore.save(history)
        let snapshot = TransferSnapshot(active: active, history: history, rates: [:])
        self.activeStore = activeStore
        self.historyStore = historyStore
        self.location = location
        self.sourceAccess = sourceAccess
        self.freeSpace = freeSpace
        self.now = now
        self.send = send
        self.active = active
        self.history = history
        self.snapshot = snapshot
        self.snapshotBroadcast = Broadcast(replayLatest: true, initial: snapshot, bufferingPolicy: .bufferingNewest(1))
    }

    /// Free bytes on the volume holding `url` (for the "important" usage class, which counts purgeable space).
    public static func volumeFreeSpace(_ url: URL) -> Int64? {
        (try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
    }

    /// Restores persisted state: anything older than 24 h is cleaned up, and interrupted transfers wait to resume.
    static func restore(active stored: [TransferRecord], history storedHistory: [TransferRecord], now: Date) -> ([TransferRecord], [TransferRecord]) {
        var active: [TransferRecord] = []
        var history = storedHistory
        let cutoff = now.addingTimeInterval(-stateLifetime)
        for var record in stored {
            if record.updatedAt < cutoff {
                if let part = record.partURL { try? FileManager.default.removeItem(at: part) }
                record.phase = .failed(.expired)
                record.updatedAt = now
                history.insert(record, at: 0)
            } else {
                if record.phase != .queued { record.phase = .paused }
                active.append(record)
            }
        }
        return (active, Array(history.prefix(historyLimit)))
    }

    /// Feeds the hub's control messages and connection changes into this manager.
    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses {
                if case .connected = status { await self?.connectionChanged(true) } else { await self?.connectionChanged(false) }
            }
        })
    }

    func handle(_ message: Message) async {
        switch message {
        case .transferOffer(let offer): await receiveOffer(offer)
        case .transferFinish(let body): await receiveFinish(body)
        case .transferCancel(let body): await peerCancelled(body.transferId)
        default: break
        }
    }

    /// Battery updates also arrive as status changes, so only real transitions count.
    func connectionChanged(_ connected: Bool) async {
        guard connected != isConnected else { return }
        isConnected = connected
        guard connected else {
            for index in active.indices where active[index].phase == .active || active[index].phase == .verifying {
                active[index].phase = .paused
            }
            rates.removeAll()
            persist()
            publish(force: true)
            return
        }
        let queued = outbox
        outbox.removeAll()
        for message in queued { await sendOrQueue(message) }
    }

    /// Local cancel from the UI.
    public func cancel(id: String) async {
        guard let record = active.first(where: { $0.id == id }) else { return }
        await discard(record, as: .cancelled(byPeer: false))
        if record.phase != .queued { await sendOrQueue(.transferCancel(TransferID(transferId: id))) }
    }

    // MARK: Receiving (phone → Mac)

    private func receiveOffer(_ offer: TransferOffer) async {
        let id = offer.transferId
        if let record = active.first(where: { $0.id == id }) {
            guard record.direction == .fromPhone else { return }
            // A re-offer after a reconnect: accept again; the phone resumes from what's on disk (HEAD).
            update(id) { $0.phase = .active }
            await sendOrDrop(.transferAccept(TransferID(transferId: id)))
            return
        }
        if let past = history.first(where: { $0.id == id }) {
            await replayOutcome(of: past)
            return
        }
        guard offer.size >= 0 else {
            await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .invalid)))
            return
        }
        guard !pendingOffers.contains(id) else { return }
        pendingOffers.insert(id)
        // Deciding may wait on a save panel, so it must not hold up other control messages.
        Task { await self.decide(offer) }
    }

    private func decide(_ offer: TransferOffer) async {
        let id = offer.transferId
        let name = FileNaming.sanitize(offer.name)
        let decision = await location.saveLocation(forName: name, size: offer.size)
        pendingOffers.remove(id)
        if cancelledOffers.remove(id) != nil { return } // the phone cancelled while we were asking
        guard let decision else {
            await reject(offer, name: name, reason: .declined)
            return
        }
        let folder: URL
        let finalName: String
        let replaces: Bool
        switch decision {
        case .folder(let url):
            folder = url
            finalName = FileNaming.unique(name) { isTaken($0, in: url) }
            replaces = false
        case .file(let url):
            folder = url.deletingLastPathComponent()
            finalName = url.lastPathComponent
            replaces = true
        }
        if let free = freeSpace(folder), free < offer.size {
            await reject(offer, name: finalName, reason: .noSpace)
            return
        }
        // Claim the name before any await, so a concurrent offer with the same name picks another.
        active.append(TransferRecord(
            id: id, direction: .fromPhone, name: finalName, size: offer.size, mime: offer.mime, phase: .active,
            folder: folder, replacesExisting: replaces, createdAt: now(), updatedAt: now()
        ))
        guard let writer = incomingWriter(id) else { return }
        do {
            try await writer.open()
        } catch {
            logger.warning("can't create part file in \(folder.path(percentEncoded: false)): \(error)")
            active.removeAll { $0.id == id }
            writers[id] = nil
            await reject(offer, name: finalName, reason: .declined)
            return
        }
        persist()
        publish(force: true)
        await sendOrDrop(.transferAccept(TransferID(transferId: id)))
    }

    private func isTaken(_ name: String, in folder: URL) -> Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: folder.appending(path: name).path(percentEncoded: false))
            || fm.fileExists(atPath: folder.appending(path: name + FileNaming.partSuffix).path(percentEncoded: false))
            || active.contains { $0.direction == .fromPhone && $0.folder == folder && $0.name == name }
    }

    private func reject(_ offer: TransferOffer, name: String, reason: TransferRejectReason) async {
        archive(TransferRecord(
            id: offer.transferId, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime,
            phase: .failed(TransferFailure(reason)), createdAt: now(), updatedAt: now()
        ))
        await sendOrDrop(.transferReject(TransferReject(transferId: offer.transferId, reason: reason)))
    }

    /// The sender re-offered an id we already finished (its result got lost): tell it the outcome again.
    private func replayOutcome(of record: TransferRecord) async {
        let id = record.id
        switch record.phase {
        case .done:
            await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: true)))
        case .failed(.declined):
            await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .declined)))
        case .failed(let failure):
            await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: false, error: failure.resultError)))
        case .cancelled:
            await sendOrDrop(.transferCancel(TransferID(transferId: id)))
        default:
            break
        }
    }

    private func incomingWriter(_ id: String) -> PartFileWriter? {
        if let writer = writers[id] { return writer }
        guard let record = active.first(where: { $0.id == id && $0.direction == .fromPhone }),
              !record.phase.isFinished, let part = record.partURL else { return nil }
        let writer = PartFileWriter(url: part, expectedSize: record.size)
        writers[id] = writer
        return writer
    }

    /// HEAD: bytes of an incoming file already on disk. Supersedes any upload still running for it.
    func uploadOffset(id: String) async -> Int64? {
        guard let writer = incomingWriter(id) else { return nil }
        do {
            _ = try await writer.beginSession()
        } catch {
            forget(id) // the part file is unreachable: a re-offer will start over
            return nil
        }
        return await writer.size
    }

    /// PUT: starts a new upload session at `start`, which must equal the bytes already on disk.
    func beginUpload(id: String, start: Int64) async throws -> Upload {
        guard let writer = incomingWriter(id) else { throw RouteError.notFound }
        let session: Int
        do {
            session = try await writer.beginSession()
        } catch {
            forget(id)
            throw RouteError.notFound
        }
        let current = await writer.size
        guard current == start else { throw RouteError.offsetMismatch(current) }
        update(id) {
            $0.phase = .active
            $0.bytesDone = current
        }
        return Upload(writer: writer, session: session)
    }

    /// Bytes moved (either direction). Returns false once the transfer is gone (cancelled), so the route stops.
    func progress(id: String, bytesDone: Int64) -> Bool {
        guard let index = active.firstIndex(where: { $0.id == id }) else { return false }
        active[index].bytesDone = bytesDone
        rates[id, default: TransferRate()].record(bytes: bytesDone, at: now())
        publish(force: false)
        return true
    }

    func uploadFailed(id: String, _ failure: TransferFailure) async {
        await failIncoming(id, failure)
    }

    private func receiveFinish(_ body: TransferFinish) async {
        let id = body.transferId
        guard let record = active.first(where: { $0.id == id && $0.direction == .fromPhone }),
              record.phase != .verifying, let writer = incomingWriter(id) else { return }
        update(id) {
            $0.phase = .verifying
            $0.expectedSHA256 = body.sha256
        }
        do {
            try await writer.open()
        } catch {
            await failIncoming(id, .io)
            return
        }
        await writer.flush()
        let size = await writer.size
        let digest = await writer.digest()
        guard size == record.size else {
            await failIncoming(id, .io)
            return
        }
        guard digest == body.sha256.lowercased() else {
            await failIncoming(id, .checksumMismatch)
            return
        }
        await writer.close()
        writers[id] = nil
        guard let current = active.first(where: { $0.id == id }), let folder = current.folder, let part = current.partURL else { return }
        let fm = FileManager.default
        var name = current.name
        do {
            let target = folder.appending(path: name)
            if fm.fileExists(atPath: target.path(percentEncoded: false)) {
                if current.replacesExisting {
                    try fm.removeItem(at: target)
                } else {
                    name = FileNaming.unique(name) { isTaken($0, in: folder) }
                }
            }
            try fm.moveItem(at: part, to: folder.appending(path: name))
        } catch {
            await failIncoming(id, .io)
            return
        }
        guard var done = active.first(where: { $0.id == id }) else { return }
        done.name = name
        done.phase = .done
        done.bytesDone = done.size
        done.savedURL = folder.appending(path: name)
        archive(done)
        eventBroadcast.yield(.received(done))
        await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: true)))
    }

    private func failIncoming(_ id: String, _ failure: TransferFailure) async {
        guard let record = active.first(where: { $0.id == id }) else { return }
        if let writer = writers.removeValue(forKey: id) { await writer.close() }
        if let part = record.partURL { try? FileManager.default.removeItem(at: part) }
        var failed = record
        failed.phase = .failed(failure)
        archive(failed)
        await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: false, error: failure.resultError)))
    }

    private func peerCancelled(_ id: String) async {
        if pendingOffers.contains(id) {
            cancelledOffers.insert(id)
            return
        }
        guard let record = active.first(where: { $0.id == id }) else { return }
        await discard(record, as: .cancelled(byPeer: true))
    }

    /// Drops a record without an outcome, so a re-offer of its id starts over.
    private func forget(_ id: String) {
        writers[id] = nil
        active.removeAll { $0.id == id }
        persist()
        publish(force: true)
    }

    // MARK: Shared helpers

    func discard(_ record: TransferRecord, as phase: TransferPhase) async {
        if let writer = writers.removeValue(forKey: record.id) { await writer.close() }
        if record.direction == .fromPhone, let part = record.partURL { try? FileManager.default.removeItem(at: part) }
        var ended = record
        ended.phase = phase
        archive(ended)
    }

    func update(_ id: String, _ change: (inout TransferRecord) -> Void) {
        guard let index = active.firstIndex(where: { $0.id == id }) else { return }
        change(&active[index])
        active[index].updatedAt = now()
        persist()
        publish(force: true)
    }

    /// Moves a finished record from active to the front of history.
    func archive(_ record: TransferRecord) {
        var ended = record
        ended.updatedAt = now()
        active.removeAll { $0.id == record.id }
        history.removeAll { $0.id == record.id }
        history.insert(ended, at: 0)
        if history.count > Self.historyLimit { history.removeLast(history.count - Self.historyLimit) }
        rates[record.id] = nil
        persist()
        publish(force: true)
    }

    func persist() {
        try? activeStore.save(active)
        try? historyStore.save(history)
    }

    /// Progress publishes at most 4 times a second; phase changes publish immediately.
    func publish(force: Bool) {
        let time = now()
        guard force || time.timeIntervalSince(lastPublish) >= 0.25 else { return }
        lastPublish = time
        snapshot = TransferSnapshot(active: active, history: history, rates: rates.compactMapValues(\.bytesPerSecond))
        snapshotBroadcast.yield(snapshot)
    }

    func clearRate(_ id: String) { rates[id] = nil }

    func sendOrDrop(_ message: Message) async {
        try? await send(message)
    }

    /// Cancels must reach the phone eventually, so they wait for the next connection.
    func sendOrQueue(_ message: Message) async {
        do {
            try await send(message)
        } catch {
            outbox.append(message)
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter TransferReceiveTests` → all pass. Then run `swift test` → all pass, no warnings.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 6: `TransferManager` receiving: offer → part file → verify → rename; re-offer resume; outcome replay; 24 h cleanup; history 100; queued cancels. Verified: `TransferReceiveTests`."
  - **ARCHITECTURE.md:** add a "Transfer" row to the Mac components table: `Transfer/` (`TransferManager` actor, `PartFileWriter`, `SourceReader`, `FileNaming`, stores) — "File transfers: handshake, part files, checksums, resume, history".

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): TransferManager receiving with resume, replay and cleanup"
```

---
### Task 7: `TransferManager` — sending (Mac → phone)

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager+Sending.swift`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Transfer/TransferManager.swift` (`handle`, `connectionChanged`, `cancel`, `peerCancelled`)
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/TransferSendTests.swift`

**Interfaces:**
- Consumes: Task 6 internals (`active`, `history`, `update`, `archive`, `persist`, `publish`, `sendOrQueue`, `sendOrDrop`, `clearRate`, `sourceAccess`, `isConnected`, `now`, `send`).
- Produces:
  - Public: `send(files: [URL]) async -> [String]` and `retry(id:) async`.
  - Internal:
    - `pump() async` and `resumeOutgoing() async`
    - `beginDownload(id:from:) async throws -> Download`
    - `downloadCompleted(id:sha256:) async`
    - `sourceFailed(id:) async`
    - `static mimeType(for:) -> String`

- [ ] **Step 1: Write the failing tests** (`TransferSendTests.swift`)

```swift
import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferSendTests {
    func sources(_ f: TransferFixture, count: Int, size: Int = 1_000) throws -> [URL] {
        let dir = f.state.deletingLastPathComponent().appending(path: "outbox", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try (0..<count).map { index in
            let url = dir.appending(path: "file\(index).jpg")
            try testBytes(size, seed: UInt8(index)).write(to: url)
            return url
        }
    }

    @Test func offersAtMostThreeAndQueuesTheRest() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 5))
        #expect(ids.count == 5)
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == Array(ids.prefix(3)))
        #expect(await manager.snapshot.active.map(\.phase) == [.offered, .offered, .offered, .queued, .queued])
        #expect(await f.phone.sent.compactMap(\.offerBody).first?.mime == "image/jpeg")
    }

    @Test func finishingOneStartsTheNextInOrder() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 4))
        await manager.handle(.transferAccept(TransferID(transferId: ids[0])))
        await manager.handle(.transferResult(TransferResult(transferId: ids[0], ok: true)))
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == ids)
        #expect(await manager.snapshot.history.first?.phase == .done)
    }

    @Test func nothingIsOfferedUntilConnected() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        _ = await manager.send(files: try sources(f, count: 2))
        #expect(await f.phone.sent.isEmpty)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 2)
    }

    @Test func reconnectReoffersTheSameId() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        await manager.handle(.transferAccept(TransferID(transferId: id)))
        await manager.connectionChanged(false)
        #expect(await manager.snapshot.active.first?.phase == .paused)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == [id, id])
    }

    @Test func downloadThenFinishCarriesTheWholeFileChecksum() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = try sources(f, count: 1, size: 50_000)[0]
        let id = try #require(await manager.send(files: [source]).first)
        await manager.handle(.transferAccept(TransferID(transferId: id)))
        let download = try await manager.beginDownload(id: id, from: 20_000)
        let reader = try SourceReader(url: download.file.url, offset: 20_000)
        while try reader.next() != nil {}
        await manager.downloadCompleted(id: id, sha256: reader.digest())
        let expected = Checksum.sha256Hex(try Data(contentsOf: source))
        #expect(await f.phone.sent.compactMap(\.finishBody) == [TransferFinish(transferId: id, sha256: expected)])
        #expect(await manager.snapshot.active.first?.phase == .verifying)
        await manager.handle(.transferResult(TransferResult(transferId: id, ok: false, error: .checksumMismatch)))
        #expect(await manager.snapshot.history.first?.phase == .failed(.checksumMismatch))
    }

    @Test func getMayArriveBeforeTheAcceptIsProcessed() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        _ = try await manager.beginDownload(id: id, from: 0) // still .offered: allowed
        #expect(await manager.snapshot.active.first?.phase == .active)
    }

    @Test func downloadRangeBeyondTheSizeIsRejected() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1, size: 10)).first)
        await #expect(throws: TransferManager.RouteError.rangeNotSatisfiable) { _ = try await manager.beginDownload(id: id, from: 11) }
    }

    @Test func wrongDirectionOrUnknownIdIsNotFound() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("incoming", size: 5))
        try await eventually { await manager.snapshot.active.count == 1 }
        await #expect(throws: TransferManager.RouteError.notFound) { _ = try await manager.beginDownload(id: "incoming", from: 0) }
        await #expect(throws: TransferManager.RouteError.notFound) { _ = try await manager.beginDownload(id: "nope", from: 0) }
    }

    @Test func missingSourceFailsAndTellsThePhone() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = try sources(f, count: 1)[0]
        let id = try #require(await manager.send(files: [source]).first)
        try FileManager.default.removeItem(at: source)
        await #expect(throws: TransferManager.RouteError.sourceUnavailable) { _ = try await manager.beginDownload(id: id, from: 0) }
        #expect(await manager.snapshot.history.first?.phase == .failed(.sourceUnavailable))
        #expect(await f.phone.sent.contains { $0.cancelledID == id })
    }

    @Test func rejectFailsTheTransferAndFreesTheSlot() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 4))
        await manager.handle(.transferReject(TransferReject(transferId: ids[1], reason: .noSpace)))
        #expect(await manager.snapshot.history.first?.phase == .failed(.noSpace))
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 4)
    }

    @Test func peerCancelAndLocalCancelFreeSlots() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 5))
        await manager.handle(.transferCancel(TransferID(transferId: ids[0])))
        await manager.cancel(id: ids[1])
        #expect(await f.phone.sent.contains { $0.cancelledID == ids[1] })
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 5)
        #expect(await manager.snapshot.history.map(\.phase).contains(.cancelled(byPeer: true)))
    }

    @Test func retryRequeuesWithANewId() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        await manager.handle(.transferReject(TransferReject(transferId: id, reason: .declined)))
        await manager.retry(id: id)
        let offers = await f.phone.sent.compactMap(\.offerBody)
        #expect(offers.count == 2 && offers[1].transferId != id && offers[1].name == offers[0].name)
    }

    @Test func foldersAreSkipped() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        #expect(await manager.send(files: [f.downloads]).isEmpty)
    }

    @Test func mimeComesFromTheExtension() {
        #expect(TransferManager.mimeType(for: URL(fileURLWithPath: "/x/a.pdf")) == "application/pdf")
        #expect(TransferManager.mimeType(for: URL(fileURLWithPath: "/x/noext")) == "application/octet-stream")
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter TransferSendTests` → compile errors (`send(files:)` not defined).

- [ ] **Step 3: Implement `TransferManager+Sending.swift`**

```swift
import Foundation
import UniformTypeIdentifiers

extension TransferManager {
    /// Queues files to send to the phone and returns their transfer ids. Folders and unreadable files are skipped.
    public func send(files urls: [URL]) async -> [String] {
        var ids: [String] = []
        for url in urls {
            guard let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey]),
                  values.isDirectory != true, let size = values.fileSize,
                  let bookmark = try? sourceAccess.bookmark(for: url) else { continue }
            let record = TransferRecord(
                id: Self.newID(), direction: .toPhone, name: url.lastPathComponent, size: Int64(size),
                mime: Self.mimeType(for: url), phase: .queued, sourceBookmark: bookmark, createdAt: now(), updatedAt: now()
            )
            active.append(record)
            ids.append(record.id)
        }
        persist()
        publish(force: true)
        await pump()
        return ids
    }

    /// Sends a failed or cancelled outgoing file again, as a new transfer.
    public func retry(id: String) async {
        guard let old = history.first(where: { $0.id == id && $0.direction == .toPhone }), let bookmark = old.sourceBookmark else { return }
        active.append(TransferRecord(
            id: Self.newID(), direction: .toPhone, name: old.name, size: old.size, mime: old.mime,
            phase: .queued, sourceBookmark: bookmark, createdAt: now(), updatedAt: now()
        ))
        persist()
        publish(force: true)
        await pump()
    }

    /// Offers queued files while fewer than 3 outgoing transfers hold a slot (spec §2.3).
    func pump() async {
        while isConnected,
              active.filter({ $0.direction == .toPhone && $0.phase.occupiesSlot }).count < Self.maxActiveOutgoing,
              let next = active.first(where: { $0.direction == .toPhone && $0.phase == .queued }) {
            update(next.id) { $0.phase = .offered }
            await offer(next)
        }
    }

    /// After a reconnect: offer every unfinished outgoing transfer again under its id, then fill free slots.
    func resumeOutgoing() async {
        for record in active where record.direction == .toPhone && record.phase.occupiesSlot {
            update(record.id) { $0.phase = .offered }
            await offer(record)
        }
        await pump()
    }

    private func offer(_ record: TransferRecord) async {
        do {
            try await send(.transferOffer(TransferOffer(transferId: record.id, name: record.name, size: record.size, mime: record.mime)))
        } catch {
            update(record.id) { $0.phase = .paused }
        }
    }

    func accepted(_ id: String) {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }),
              record.phase == .offered || record.phase == .paused else { return }
        update(id) { $0.phase = .active }
    }

    func rejected(_ body: TransferReject) async {
        guard var record = active.first(where: { $0.id == body.transferId && $0.direction == .toPhone }) else { return }
        record.phase = .failed(TransferFailure(body.reason))
        archive(record)
        await pump()
    }

    func resulted(_ body: TransferResult) async {
        guard var record = active.first(where: { $0.id == body.transferId && $0.direction == .toPhone }) else { return }
        if body.ok {
            record.phase = .done
            record.bytesDone = record.size
        } else {
            record.phase = .failed(TransferFailure(body.error))
        }
        archive(record)
        await pump()
    }

    /// GET: resolves the source for a download from `start`. The phone may GET before we've processed its accept.
    func beginDownload(id: String, from start: Int64) async throws -> Download {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }),
              record.phase != .queued, !record.phase.isFinished else { throw RouteError.notFound }
        guard start >= 0, start <= record.size else { throw RouteError.rangeNotSatisfiable }
        guard let bookmark = record.sourceBookmark, let file = try? sourceAccess.resolve(bookmark) else {
            await sourceFailed(id: id)
            throw RouteError.sourceUnavailable
        }
        let size = (try? file.url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init)
        guard size == record.size else {
            file.stop()
            await sourceFailed(id: id)
            throw RouteError.sourceUnavailable
        }
        update(id) {
            $0.phase = .active
            $0.bytesDone = start
        }
        return Download(file: file, size: record.size)
    }

    /// A GET reached the end of the file: tell the phone the checksum. Sent after every complete GET (spec §2.2).
    func downloadCompleted(id: String, sha256: String) async {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }), !record.phase.isFinished else { return }
        update(id) {
            $0.phase = .verifying
            $0.bytesDone = $0.size
        }
        clearRate(id)
        await sendOrDrop(.transferFinish(TransferFinish(transferId: id, sha256: sha256)))
    }

    /// The source file is gone or changed: fail the transfer and tell the phone to stop.
    func sourceFailed(id: String) async {
        guard var record = active.first(where: { $0.id == id }) else { return }
        record.phase = .failed(.sourceUnavailable)
        archive(record)
        await sendOrQueue(.transferCancel(TransferID(transferId: id)))
        await pump()
    }

    static func mimeType(for url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    static func newID() -> String { UUID().uuidString.lowercased() }
}
```

- [ ] **Step 4: Wire sending into the core** (`TransferManager.swift`)

Replace `handle(_:)`:

```swift
    func handle(_ message: Message) async {
        switch message {
        case .transferOffer(let offer): await receiveOffer(offer)
        case .transferAccept(let body): accepted(body.transferId)
        case .transferReject(let body): await rejected(body)
        case .transferFinish(let body): await receiveFinish(body)
        case .transferResult(let body): await resulted(body)
        case .transferCancel(let body): await peerCancelled(body.transferId)
        default: break
        }
    }
```

At the end of `connectionChanged(_:)` (after the outbox loop), add `await resumeOutgoing()`.

At the end of `cancel(id:)` and of `peerCancelled(_:)` (after `discard`), add `await pump()`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd mac/MacDroidCore && swift test --filter "TransferSendTests|TransferReceiveTests"` → all pass. Then run `swift test` → all pass, no warnings.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 7: `TransferManager` sending: FIFO queue with 3 slots, offer/accept/reject/result, re-offer on reconnect, source bookmarks, retry. Verified: `TransferSendTests`."

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): TransferManager sending with queue, re-offer and retry"
```

---

### Task 8: File routes — `HEAD`/`PUT`/`GET /v1/files/:id`

**Files:**
- Create: `mac/MacDroidCore/Sources/MacDroidCore/Server/FileRoutes.swift`
- Modify: `mac/MacDroidCore/Sources/MacDroidCore/Server/MacDroidServer.swift` (`transfers` parameter, register routes)
- Test: create `mac/MacDroidCore/Tests/MacDroidCoreTests/FileRouteTests.swift`; modify `ServerTests.swift` (`ServerHarness`)

**Interfaces:**
- Consumes: `TransferManager` internals from Tasks 6–7, `ByteRanges`, `SourceReader`, `PartFileError`.
- Produces:
  - `MacDroidServer.init(identity:pairing:hub:macName:configuration:transfers:)`, where `transfers: TransferManager? = nil`. Without a manager, the file routes aren't registered.
  - `MacDroidServer.uploadOffsetField` (`Upload-Offset`).
  - `ServerHarness.run(pairedPhone:transfers:_:)`.

- [ ] **Step 1: Let the harness take a manager** (in `ServerTests.swift`)

Change `ServerHarness.run` to:

```swift
    static func run(
        pairedPhone: Identity? = nil,
        transfers: TransferManager? = nil,
        _ body: (ServerHarness) async throws -> Void
    ) async throws {
```

and pass `transfers: transfers` as the last argument of `MacDroidServer(…)` in it.

- [ ] **Step 2: Write the failing tests** (`FileRouteTests.swift`)

```swift
import Foundation
import HTTPTypes
import HummingbirdTesting
import NIOCore
import Testing
@testable import MacDroidCore

@Suite struct FileRouteTests {
    let offset = HTTPField.Name("Upload-Offset")!

    @Test func uploadResumesWithHeadAndContentRange() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        let data = testBytes(300_000)
        await manager.handle(makeOffer("t1", name: "up.bin", size: Int64(data.count)))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            let first = try await client.put("/v1/files/t1", body: ByteBuffer(bytes: data.prefix(100_000)))
            #expect(first.status == .ok)
            #expect(first.headers[offset] == "100000")
            #expect(try await client.head("/v1/files/t1").headers[offset] == "100000")
            let wrong = try await client.put("/v1/files/t1", headers: [.contentRange: "bytes 50-299999/300000"], body: ByteBuffer(bytes: data.dropFirst(50)))
            #expect(wrong.status == .conflict)
            #expect(wrong.headers[offset] == "100000")
            let rest = try await client.put("/v1/files/t1", headers: [.contentRange: "bytes 100000-299999/300000"], body: ByteBuffer(bytes: data.dropFirst(100_000)))
            #expect(rest.headers[offset] == "300000")
            try await client.shutdown()
        }
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("up.bin")) == data)
    }

    @Test func downloadServesRangesAndSendsFinish() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = f.state.deletingLastPathComponent().appending(path: "down.bin")
        let data = testBytes(250_000)
        try data.write(to: source)
        let id = try #require(await manager.send(files: [source]).first)
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            let whole = try await client.get("/v1/files/\(id)")
            #expect(whole.status == .ok)
            #expect(Data(try #require(whole.body).readableBytesView) == data)
            let tail = try await client.get("/v1/files/\(id)", headers: [.range: "bytes=200000-"])
            #expect(tail.status == .partialContent)
            #expect(tail.headers[.contentRange] == "bytes 200000-249999/250000")
            #expect(Data(try #require(tail.body).readableBytesView) == data.dropFirst(200_000))
            try await client.shutdown()
        }
        let finishes = await f.phone.sent.compactMap(\.finishBody)
        #expect(finishes.count == 2 && finishes.allSatisfy { $0.sha256 == Checksum.sha256Hex(data) })
    }

    // Review Focus #5
    @Test func getAtEndReturnsEmpty206AndFinish() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let dir = f.state.deletingLastPathComponent()
        let small = dir.appending(path: "small.txt")
        let empty = dir.appending(path: "empty.txt")
        try Data("hello".utf8).write(to: small)
        try Data().write(to: empty)
        let ids = await manager.send(files: [small, empty])
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            // The phone already has all 5 bytes: an empty 206, then finish.
            let atEnd = try await client.get("/v1/files/\(ids[0])", headers: [.range: "bytes=5-"])
            #expect(atEnd.status == .partialContent)
            #expect((atEnd.body?.readableBytes ?? 0) == 0)
            // A zero-byte file: a plain empty 200, then finish.
            let zero = try await client.get("/v1/files/\(ids[1])")
            #expect(zero.status == .ok)
            #expect((zero.body?.readableBytes ?? 0) == 0)
            try await client.shutdown()
        }
        let finishes = await f.phone.sent.compactMap(\.finishBody)
        #expect(finishes.first { $0.transferId == ids[0] }?.sha256 == Checksum.sha256Hex(Data("hello".utf8)))
        #expect(finishes.first { $0.transferId == ids[1] }?.sha256 == Checksum.sha256Hex(Data()))
    }

    @Test func wrongDirectionAndUnknownIdsAreNotFound() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("incoming", size: 3))
        try await eventually { await manager.snapshot.active.count == 1 }
        let source = f.state.deletingLastPathComponent().appending(path: "o.bin")
        try testBytes(3).write(to: source)
        let outgoing = try #require(await manager.send(files: [source]).first)
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            #expect(try await client.get("/v1/files/incoming").status == .notFound)
            #expect(try await client.head("/v1/files/\(outgoing)").status == .notFound)
            #expect(try await client.put("/v1/files/\(outgoing)", body: ByteBuffer(string: "abc")).status == .notFound)
            #expect(try await client.get("/v1/files/unknown").status == .notFound)
            try await client.shutdown()
        }
    }

    @Test func malformedRangeHeadersAreBadRequests() async throws {
        let phone = try Identity.generate(commonName: "Phone")
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await manager.snapshot.active.count == 1 }
        try await ServerHarness.run(pairedPhone: phone, transfers: manager) { harness in
            let client = try harness.client(phone)
            #expect(try await client.put("/v1/files/t1", headers: [.contentRange: "nonsense"], body: ByteBuffer(string: "x")).status == .badRequest)
            #expect(try await client.get("/v1/files/t1", headers: [.range: "bytes=1-2"]).status == .badRequest)
            try await client.shutdown()
        }
    }
}
```

If `HTTPTypes` doesn't resolve as an import in the test target, drop that line (`HTTPField` is re-exported by `HummingbirdTesting`).

- [ ] **Step 3: Run them to verify they fail**

Run: `cd mac/MacDroidCore && swift test --filter FileRouteTests` → compile errors (no `transfers:` parameter).

- [ ] **Step 4: Implement `FileRoutes.swift`**

```swift
import Foundation
import Hummingbird
import NIOCore

extension MacDroidServer {
    static let uploadOffsetField = HTTPField.Name("Upload-Offset")!

    /// `/v1/files/:id` (spec §2.2). Registered after the global TrustedPeerMiddleware, so only the paired phone gets here.
    static func addFileRoutes(to router: Router<MacDroidRequestContext>, transfers: TransferManager) {
        router.head("/v1/files/:id") { _, context -> Response in
            let id = try context.parameters.require("id")
            guard let offset = await transfers.uploadOffset(id: id) else { throw HTTPError(.notFound) }
            return Response(status: .ok, headers: [MacDroidServer.uploadOffsetField: String(offset)])
        }
        router.put("/v1/files/:id") { request, context -> Response in
            try await receiveUpload(request, context: context, transfers: transfers)
        }
        router.get("/v1/files/:id") { request, context -> Response in
            try await serveDownload(request, context: context, transfers: transfers)
        }
    }

    private static func receiveUpload(_ request: Request, context: MacDroidRequestContext, transfers: TransferManager) async throws -> Response {
        let id = try context.parameters.require("id")
        var start: Int64 = 0
        if let header = request.headers[.contentRange] {
            guard let range = ByteRanges.parseContentRange(header) else { throw HTTPError(.badRequest) }
            start = range.start
        }
        let upload: TransferManager.Upload
        do {
            upload = try await transfers.beginUpload(id: id, start: start)
        } catch TransferManager.RouteError.offsetMismatch(let current) {
            return Response(status: .conflict, headers: [uploadOffsetField: String(current)])
        } catch {
            throw HTTPError(.notFound)
        }
        do {
            for try await buffer in request.body {
                try await upload.writer.append(Data(buffer.readableBytesView), session: upload.session)
                guard await transfers.progress(id: id, bytesDone: await upload.writer.size) else { throw HTTPError(.gone) }
            }
        } catch let error as PartFileError {
            await upload.writer.flush()
            switch error {
            case .noSpace:
                await transfers.uploadFailed(id: id, .noSpace)
                throw HTTPError(.init(code: 507))
            case .io:
                await transfers.uploadFailed(id: id, .io)
                throw HTTPError(.internalServerError)
            case .superseded:
                throw HTTPError(.conflict)
            case .tooLarge:
                throw HTTPError(.badRequest)
            }
        } catch {
            await upload.writer.flush() // the phone went away: keep what arrived for the resume
            throw error
        }
        await upload.writer.flush()
        return Response(status: .ok, headers: [uploadOffsetField: String(await upload.writer.size)])
    }

    private static func serveDownload(_ request: Request, context: MacDroidRequestContext, transfers: TransferManager) async throws -> Response {
        let id = try context.parameters.require("id")
        var start: Int64 = 0
        if let header = request.headers[.range] {
            guard let value = ByteRanges.parseRangeStart(header) else { throw HTTPError(.badRequest) }
            start = value
        }
        let download: TransferManager.Download
        do {
            download = try await transfers.beginDownload(id: id, from: start)
        } catch TransferManager.RouteError.rangeNotSatisfiable {
            throw HTTPError(.rangeNotSatisfiable)
        } catch TransferManager.RouteError.sourceUnavailable {
            throw HTTPError(.gone)
        } catch {
            throw HTTPError(.notFound)
        }
        let file = download.file
        let reader: SourceReader
        do {
            reader = try SourceReader(url: file.url, offset: start)
        } catch {
            file.stop()
            await transfers.sourceFailed(id: id)
            throw HTTPError(.gone)
        }
        guard reader.size == download.size else {
            reader.close()
            file.stop()
            await transfers.sourceFailed(id: id)
            throw HTTPError(.gone)
        }
        var headers: HTTPFields = [:]
        if start > 0, start < download.size {
            headers[.contentRange] = "bytes \(start)-\(download.size - 1)/\(download.size)"
        }
        let body = ResponseBody(contentLength: Int(download.size - start)) { writer in
            defer {
                reader.close()
                file.stop()
            }
            while let chunk = try reader.next() {
                try await writer.write(ByteBuffer(bytes: chunk))
                guard await transfers.progress(id: id, bytesDone: reader.position) else { throw CancellationError() }
            }
            try await writer.finish(nil)
            await transfers.downloadCompleted(id: id, sha256: reader.digest())
        }
        return Response(status: start > 0 ? .partialContent : .ok, headers: headers, body: body)
    }
}
```

- [ ] **Step 5: Accept the manager in `MacDroidServer`**

Add a stored property `private let transfers: TransferManager?`. Add `transfers: TransferManager? = nil` as the last `init` parameter and assign it. In `makeRouter()`, after `router.get("/v1/info")…`, add:

```swift
        if let transfers { Self.addFileRoutes(to: router, transfers: transfers) }
```

(`let transfers = self.transfers` at the top of `makeRouter()` if the compiler asks for a local capture, like `pairing` and `macName`.)

- [ ] **Step 6: Run the tests and the app build**

Run: `cd mac/MacDroidCore && swift test` → all pass.
Run: `cd mac && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build` → BUILD SUCCEEDED, no warnings. The app doesn't pass a manager yet; Plan 2b does.

- [ ] **Step 7: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 8: `HEAD`/`PUT`/`GET /v1/files/:id` with Upload-Offset resume, 409 on offset mismatch, Range/206, empty 206 at the end, 404 for wrong direction. Verified: `FileRouteTests`."
  - **ARCHITECTURE.md:** in the topology block, add `──── HEAD/PUT/GET /v1/files/{id} ──▶ TransferManager (bytes; control over WS)`. Add `Server/FileRoutes.swift` to the Server row.

```bash
git add mac/MacDroidCore docs/handoff
git commit -m "feat(mac): /v1/files routes with resumable upload and ranged download"
```

---
### Task 9: Kotlin pure helpers — `FileNaming`, `TransferText`, `TransferRate`

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/transfer/FileNaming.kt`, `TransferText.kt`, `TransferRate.kt`
- Test: create `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferPureTest.kt`

**Interfaces:**
- Produces:
  - `object FileNaming` with `MAX_BYTES`, `PART_SUFFIX`, `sanitize(raw)`, `unique(name, isTaken)`, and internal `splitExtension`.
  - `object TransferText` with `bytes(n: Long)` and `line(done: Long, total: Long, bytesPerSecond: Double?)`.
  - `class TransferRate` with `record(bytes: Long, atNanos: Long)` and `bytesPerSecond: Double?`.
  - All behave exactly like the Swift versions (same shared vectors).

- [ ] **Step 1: Write the failing tests** (`TransferPureTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferPureTest {
    private val naming = Json.parseToJsonElement(Fixtures.text("file-naming.json")).jsonObject
    private val text = Json.parseToJsonElement(Fixtures.text("transfer-text.json")).jsonObject

    @Test fun sharedSanitizeVectors() {
        for (vector in naming.getValue("sanitize").jsonArray) {
            val input = vector.jsonObject.getValue("in").jsonPrimitive.content
            assertEquals("sanitize($input)", vector.jsonObject.getValue("out").jsonPrimitive.content, FileNaming.sanitize(input))
        }
    }

    @Test fun sharedUniqueVectors() {
        for (vector in naming.getValue("unique").jsonArray) {
            val v = vector.jsonObject
            val taken = v.getValue("taken").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(v.getValue("out").jsonPrimitive.content, FileNaming.unique(v.getValue("name").jsonPrimitive.content) { it in taken })
        }
    }

    @Test fun longNamesKeepTheirExtensionWithin200Bytes() {
        assertEquals("a".repeat(196) + ".txt", FileNaming.sanitize("a".repeat(250) + ".txt"))
        val multibyte = FileNaming.sanitize("é".repeat(150) + ".jpg")
        assertEquals("é".repeat(98) + ".jpg", multibyte)
        assertEquals(200, multibyte.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun uniqueStaysWithin200Bytes() {
        val long = "b".repeat(196) + ".txt"
        assertEquals("b".repeat(192) + " (1).txt", FileNaming.unique(long) { it == long })
    }

    @Test fun sharedTextVectors() {
        for (vector in text.getValue("bytes").jsonArray) {
            val v = vector.jsonObject
            assertEquals(v.getValue("out").jsonPrimitive.content, TransferText.bytes(v.getValue("in").jsonPrimitive.long))
        }
        for (vector in text.getValue("lines").jsonArray) {
            val v = vector.jsonObject
            assertEquals(
                v.getValue("out").jsonPrimitive.content,
                TransferText.line(v.getValue("done").jsonPrimitive.long, v.getValue("total").jsonPrimitive.long, v.getValue("rate").jsonPrimitive.doubleOrNull),
            )
        }
    }

    @Test fun rateIsSmoothedAndSampledAtMostTwiceASecond() {
        val rate = TransferRate()
        rate.record(0, 0)
        assertNull(rate.bytesPerSecond)
        rate.record(500_000, 200_000_000) // 0.2 s later: ignored
        assertNull(rate.bytesPerSecond)
        rate.record(1_000_000, 1_000_000_000)
        assertEquals(1_000_000.0, rate.bytesPerSecond!!, 0.001)
        rate.record(3_000_000, 2_000_000_000)
        assertEquals(1_300_000.0, rate.bytesPerSecond!!, 0.001)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android && ./gradlew :core:test --tests '*TransferPureTest*'` → compile errors.

- [ ] **Step 3: Implement `FileNaming.kt`**

```kotlin
package dev.macdroid.core.transfer

/** Safe, collision-free names for received files (spec §2.3). Mirrors the Swift version; shared vectors: `file-naming.json`. */
object FileNaming {
    const val MAX_BYTES = 200
    const val PART_SUFFIX = ".macdroid-part"
    private const val MAX_EXTENSION_BYTES = 20
    private val unsafe = setOf(':'.code, '*'.code, '?'.code, '"'.code, '<'.code, '>'.code, '|'.code)

    fun sanitize(raw: String): String {
        val last = raw.split('/', '\\').last()
        val cleaned = StringBuilder()
        last.codePoints().forEach { cp -> if (cp >= 0x20 && cp != 0x7F && cp !in unsafe) cleaned.appendCodePoint(cp) }
        val name = cleaned.toString().trim(' ').trimStart('.').trim(' ')
        if (name.isEmpty()) return "file"
        if (utf8Length(name) <= MAX_BYTES) return name
        val (base, ext) = splitExtension(name)
        return truncateUtf8(base, MAX_BYTES - utf8Length(ext)) + ext
    }

    /** [name], or `name (1)`, `name (2)`, … (before the extension), whichever [isTaken] rejects first. */
    fun unique(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val (base, ext) = splitExtension(name)
        var counter = 1
        while (true) {
            val suffix = " ($counter)"
            val candidate = truncateUtf8(base, MAX_BYTES - utf8Length(suffix) - utf8Length(ext)) + suffix + ext
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }

    /** Splits at the last dot. A leading dot or an extension over 20 bytes counts as no extension. */
    internal fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to ""
        val ext = name.substring(dot)
        if (utf8Length(ext) > MAX_EXTENSION_BYTES) return name to ""
        return name.substring(0, dot) to ext
    }

    private fun utf8Length(text: String) = text.toByteArray(Charsets.UTF_8).size

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val length = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + length > maxBytes) break
            out.appendCodePoint(cp)
            bytes += length
            index += Character.charCount(cp)
        }
        return out.toString()
    }
}
```

- [ ] **Step 4: Implement `TransferText.kt` and `TransferRate.kt`**

```kotlin
package dev.macdroid.core.transfer

import java.util.Locale
import kotlin.math.ceil

/** "1.3 of 2.1 GB · 48 MB/s · 17 s left" (decimal units). Mirrors the Swift version; vectors: `transfer-text.json`. */
object TransferText {
    private class Unit(val divisor: Double, val label: String)

    fun bytes(count: Long): String {
        val unit = unitFor(count) ?: return "$count bytes"
        return "${oneDecimal(count / unit.divisor)} ${unit.label}"
    }

    fun line(done: Long, total: Long, bytesPerSecond: Double?): String {
        val parts = mutableListOf(progress(done, total))
        if (bytesPerSecond != null && bytesPerSecond > 0) {
            parts += speed(bytesPerSecond)
            val remaining = total - done
            if (remaining > 0) parts += timeLeft(remaining / bytesPerSecond)
        }
        return parts.joinToString(" · ")
    }

    private fun progress(done: Long, total: Long): String {
        val unit = unitFor(total) ?: return "$done of $total bytes"
        return "${oneDecimal(done / unit.divisor)} of ${oneDecimal(total / unit.divisor)} ${unit.label}"
    }

    private fun speed(rate: Double): String = when {
        rate >= 1_000_000 -> (rate / 1_000_000).let { if (it >= 10) "${noDecimals(it)} MB/s" else "${oneDecimal(it)} MB/s" }
        rate >= 1_000 -> "${noDecimals(rate / 1_000)} KB/s"
        else -> "${rate.toLong()} bytes/s"
    }

    private fun timeLeft(seconds: Double): String {
        val total = ceil(seconds).toLong()
        return when {
            total < 60 -> "$total s left"
            total < 3600 -> "${ceil(total / 60.0).toLong()} min left"
            else -> "${total / 3600} h ${ceil((total % 3600) / 60.0).toLong()} min left"
        }
    }

    private fun unitFor(count: Long): Unit? = when {
        count >= 1_000_000_000 -> Unit(1e9, "GB")
        count >= 1_000_000 -> Unit(1e6, "MB")
        count >= 1_000 -> Unit(1e3, "KB")
        else -> null
    }

    private fun oneDecimal(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun noDecimals(value: Double) = String.format(Locale.ROOT, "%.0f", value)
}
```

```kotlin
package dev.macdroid.core.transfer

/** Smoothed speed from (bytes so far, monotonic time) samples, sampled at most twice a second. Not thread-safe. */
class TransferRate {
    private var lastBytes = 0L
    private var lastNanos = 0L
    private var started = false

    var bytesPerSecond: Double? = null
        private set

    fun record(bytes: Long, atNanos: Long) {
        if (!started) {
            started = true
            lastBytes = bytes
            lastNanos = atNanos
            return
        }
        val elapsed = (atNanos - lastNanos) / 1e9
        if (elapsed < 0.5) return
        val instant = (bytes - lastBytes) / elapsed
        bytesPerSecond = bytesPerSecond?.let { 0.7 * it + 0.3 * instant } ?: instant
        lastBytes = bytes
        lastNanos = atNanos
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :core:test` → BUILD SUCCESSFUL.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 9: Kotlin `FileNaming`, `TransferText`, `TransferRate`, passing the same shared vectors as Swift. Verified: `:core:test`."

```bash
git add android/core docs/handoff
git commit -m "feat(android): file naming, progress text and rate (shared vectors)"
```

---

### Task 10: Kotlin `TransferClient` and `FileEndpoint`

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferClient.kt`, `FileEndpoint.kt`
- Test: create `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferClientTest.kt`

**Interfaces:**
- Consumes: `TlsClientFactory.pinned`, `PairingStore`, `ClientIdentity`; test support `MockMac`.
- Produces:
  - `interface FileTransport`:
    - `suspend uploadOffset(id): Long`
    - `suspend upload(id, size, offset, input, digest, onProgress): Long`
    - `suspend download(id, size, offset, output, digest, onProgress): Long`
  - `class TransferClient(endpoint: () -> Endpoint?) : FileTransport`, with `data class Endpoint(http: OkHttpClient, baseUrl: HttpUrl)`, `BUFFER_SIZE`, and `forFiles(pinned)`.
  - `class LocalIoException(cause) : IOException`: a local read or write failed.
  - `class TransferHttpException(code, uploadOffset)`: an unexpected HTTP status.
  - `class FileEndpoint(store, identity, base)` with `current(): Endpoint?` (caches the pinned client per Mac fingerprint).
  - `fun MessageDigest.hex(): String` (internal).

- [ ] **Step 1: Write the failing tests** (`TransferClientTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.MockMac
import dev.macdroid.core.tls.TlsClientFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferClientTest {
    private val mac = MockMac()
    private lateinit var client: TransferClient
    private val data = ByteArray(3_145_728) { (it * 31 + 7).toByte() }

    @Before fun setUp() {
        mac.start()
        val http = TransferClient.forFiles(TlsClientFactory.pinned(mac.phoneIdentity, mac.fingerprint))
        client = TransferClient { TransferClient.Endpoint(http, mac.server.url("/")) }
    }

    @After fun tearDown() = mac.close()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").apply { update(bytes) }.hex()
    private fun digestOf(prefix: ByteArray) = MessageDigest.getInstance("SHA-256").apply { update(prefix) }

    @Test fun uploadOffsetReadsHead() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", "42").build())
        assertEquals(42L, client.uploadOffset("t1"))
        val request = mac.server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/v1/files/t1", request.target)
    }

    @Test fun uploadSendsTheWholeFileWithoutContentRange() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", data.size.toString()).build())
        val digest = MessageDigest.getInstance("SHA-256")
        val reported = client.upload("t1", data.size.toLong(), 0, ByteArrayInputStream(data), digest) {}
        assertEquals(data.size.toLong(), reported)
        val request = mac.server.takeRequest()
        assertEquals("PUT", request.method)
        assertNull(request.headers["Content-Range"])
        assertArrayEquals(data, request.body!!.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    // Review Focus #2
    @Test fun resumedUploadSendsOnlyTheTailWithContentRange() = runBlocking {
        val offset = 1_048_583
        mac.server.enqueue(MockResponse.Builder().code(200).addHeader("Upload-Offset", data.size.toString()).build())
        val digest = digestOf(data.copyOfRange(0, offset))
        client.upload("t1", data.size.toLong(), offset.toLong(), ByteArrayInputStream(data, offset, data.size - offset), digest) {}
        val request = mac.server.takeRequest()
        assertEquals("bytes $offset-${data.size - 1}/${data.size}", request.headers["Content-Range"])
        assertArrayEquals(data.copyOfRange(offset, data.size), request.body!!.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    @Test fun conflictCarriesTheMacOffset() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(409).addHeader("Upload-Offset", "10").build())
        val error = runCatching { client.upload("t1", 20, 5, ByteArrayInputStream(ByteArray(15)), MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue(error is TransferHttpException)
        assertEquals(409, (error as TransferHttpException).code)
        assertEquals(10L, error.uploadOffset)
    }

    @Test fun downloadFromAnOffsetSendsRangeAndHashesEverything() = runBlocking {
        val offset = 2_000_000
        mac.server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(data, offset, data.size - offset)).build())
        val out = ByteArrayOutputStream()
        val digest = digestOf(data.copyOfRange(0, offset))
        val total = client.download("t1", data.size.toLong(), offset.toLong(), out, digest) {}
        assertEquals(data.size.toLong(), total)
        assertEquals("bytes=$offset-", mac.server.takeRequest().headers["Range"])
        assertArrayEquals(data.copyOfRange(offset, data.size), out.toByteArray())
        assertEquals(sha(data), digest.hex())
    }

    @Test fun downloadFromZeroSendsNoRange() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).build())
        client.download("t1", data.size.toLong(), 0, ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256")) {}
        assertNull(mac.server.takeRequest().headers["Range"])
    }

    @Test fun droppedConnectionIsANetworkErrorAndKeepsWhatArrived() = runBlocking {
        mac.server.enqueue(
            MockResponse.Builder().code(200).body(Buffer().write(data))
                .throttleBody(64 * 1024, 5, TimeUnit.MILLISECONDS)
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build(),
        )
        val out = ByteArrayOutputStream()
        val error = runCatching { client.download("t1", data.size.toLong(), 0, out, MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertFalse(error is LocalIoException)
        assertTrue(out.size() < data.size)
    }

    @Test fun localWriteFailureIsLocal() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).build())
        val broken = object : OutputStream() { override fun write(b: Int) = throw IOException("No space left on device") }
        val error = runCatching { client.download("t1", data.size.toLong(), 0, broken, MessageDigest.getInstance("SHA-256")) {} }.exceptionOrNull()
        assertTrue(error is LocalIoException)
    }

    @Test fun notFoundIsReported() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(404).build())
        val error = runCatching { client.uploadOffset("gone") }.exceptionOrNull()
        assertEquals(404, (error as TransferHttpException).code)
    }

    @Test fun cancellingStopsADownloadPromptly() = runBlocking {
        mac.server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(data)).throttleBody(1024, 100, TimeUnit.MILLISECONDS).build())
        val job = async { client.download("t1", data.size.toLong(), 0, ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256")) {} }
        delay(300)
        job.cancel()
        withTimeout(2_000) { runCatching { job.await() } }
        assertTrue(job.isCancelled)
    }

    @Test fun noEndpointMeansNotPaired() {
        val unpaired = TransferClient { null }
        assertThrows(IOException::class.java) { runBlocking { unpaired.uploadOffset("t1") } }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android && ./gradlew :core:test --tests '*TransferClientTest*'` → compile errors.

- [ ] **Step 3: Implement `TransferClient.kt`**

```kotlin
package dev.macdroid.core.transfer

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** A local read or write failed (not the network), so the transfer fails instead of pausing. */
class LocalIoException(cause: Throwable) : IOException(cause.message, cause)

/** The Mac answered with an unexpected status. [uploadOffset] is its `Upload-Offset`, when it sent one. */
class TransferHttpException(val code: Int, val uploadOffset: Long? = null) : IOException("HTTP $code")

/** Moves file bytes over HTTPS (spec §2.2). The engine depends on this interface so it can be tested without a network. */
interface FileTransport {
    /** HEAD: how many bytes of [id] the Mac already has. */
    suspend fun uploadOffset(id: String): Long

    /**
     * PUT bytes `[offset, size)` read from [input], which is already positioned at [offset]. [digest] must already cover
     * the bytes before [offset]; it's updated with every byte sent. Returns the Mac's offset afterwards.
     */
    suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long

    /**
     * GET from [offset], appending to [output]. [digest] must already cover the bytes before [offset].
     * Returns the bytes now present (offset + received).
     */
    suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long
}

/** The phone always makes the request: PUT for phone → Mac, GET for Mac → phone. Cancelling the coroutine cancels the call. */
class TransferClient(private val endpoint: () -> Endpoint?) : FileTransport {
    data class Endpoint(val http: OkHttpClient, val baseUrl: HttpUrl)

    companion object {
        const val BUFFER_SIZE = 1 shl 20

        /** No call timeout (files can take minutes); 60 s per read or write, so a dead connection is still noticed. */
        fun forFiles(pinned: OkHttpClient): OkHttpClient = pinned.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    override suspend fun uploadOffset(id: String): Long {
        val (http, request) = request(id) { head() }
        return execute(http, request) { response ->
            if (!response.isSuccessful) throw TransferHttpException(response.code)
            response.header("Upload-Offset")?.toLongOrNull() ?: throw TransferHttpException(response.code)
        }
    }

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength() = size - offset
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(BUFFER_SIZE)
                var sent = offset
                while (sent < size) {
                    val count = try {
                        input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                    } catch (e: IOException) {
                        throw LocalIoException(e)
                    }
                    if (count < 0) throw LocalIoException(EOFException("source ended at $sent of $size bytes"))
                    sink.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    sent += count
                    onProgress(sent)
                }
            }
        }
        val (http, request) = request(id) {
            put(body)
            if (offset > 0) header("Content-Range", "bytes $offset-${size - 1}/$size")
        }
        return execute(http, request) { response ->
            val reported = response.header("Upload-Offset")?.toLongOrNull()
            if (!response.isSuccessful) throw TransferHttpException(response.code, reported)
            reported ?: size
        }
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        val (http, request) = request(id) {
            get()
            if (offset > 0) header("Range", "bytes=$offset-")
        }
        return execute(http, request) { response ->
            if (!response.isSuccessful) throw TransferHttpException(response.code)
            val source = response.body.byteStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var have = offset
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (have + count > size) throw TransferHttpException(response.code) // more than was offered
                try {
                    output.write(buffer, 0, count)
                } catch (e: IOException) {
                    throw LocalIoException(e)
                }
                digest.update(buffer, 0, count)
                have += count
                onProgress(have)
            }
            try {
                output.flush()
            } catch (e: IOException) {
                throw LocalIoException(e)
            }
            have
        }
    }

    private fun request(id: String, configure: Request.Builder.() -> Unit): Pair<OkHttpClient, Request> {
        val endpoint = endpoint() ?: throw IOException("not paired with a Mac")
        val url = endpoint.baseUrl.newBuilder().addPathSegments("v1/files").addPathSegment(id).build()
        return endpoint.http to Request.Builder().url(url).apply(configure).build()
    }

    /** Runs the blocking call on IO; cancelling the coroutine cancels the call, which unblocks any read or write. */
    private suspend fun <T> execute(http: OkHttpClient, request: Request, handle: (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(handle) }
        } finally {
            canceller.cancel()
        }
    }
}

/** Lowercase hex of the digest (consumes it). */
internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
```

- [ ] **Step 4: Implement `FileEndpoint.kt`**

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.pairing.PairingStore
import dev.macdroid.core.tls.ClientIdentity
import dev.macdroid.core.tls.TlsClientFactory
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** The paired Mac's file endpoint: a pinned mTLS client for file calls, rebuilt only when the Mac's fingerprint changes. */
class FileEndpoint(
    private val store: PairingStore,
    private val identity: () -> ClientIdentity,
    private val base: OkHttpClient = OkHttpClient(),
) {
    private var cached: Pair<String, OkHttpClient>? = null

    @Synchronized
    fun current(): TransferClient.Endpoint? {
        val mac = store.paired.value ?: return null
        val http = cached?.takeIf { it.first == mac.fingerprint }?.second
            ?: TransferClient.forFiles(TlsClientFactory.pinned(identity(), mac.fingerprint, base)).also { cached = mac.fingerprint to it }
        return TransferClient.Endpoint(http, HttpUrl.Builder().scheme("https").host(mac.host).port(mac.port).build())
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd android && ./gradlew :core:test --tests '*TransferClientTest*'` → pass. Then `./gradlew :core:test` → BUILD SUCCESSFUL, no warnings.

- [ ] **Step 6: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 10: Kotlin `TransferClient` (HEAD/PUT/GET with resume headers, running SHA-256, cancel, local vs network errors) and `FileEndpoint`. Verified: `TransferClientTest` against the mTLS MockWebServer."
  - **ARCHITECTURE.md:** add a "transfer" row to the `android/core` table: `TransferClient.kt`, `FileEndpoint.kt` — "File bytes over pinned mTLS HTTPS".

```bash
git add android/core docs/handoff
git commit -m "feat(android): TransferClient with resumable upload/download"
```

---
### Task 11: Kotlin `TransferEngine` — model, stores, `FolderSaveTarget`, receiving (Mac → phone)

**Files:**
- Create: `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferModel.kt`, `TransferStore.kt`, `SaveTarget.kt`, `FolderSaveTarget.kt`, `TransferEngine.kt`
- Test: create `android/core/src/test/kotlin/dev/macdroid/core/transfer/FakeMacFiles.kt`, `TransferStoreTest.kt`, `TransferEngineReceiveTest.kt`

**Interfaces:**
- Consumes: `FileTransport`, `LocalIoException`, `TransferHttpException`, `hex()` (Task 10); `FileNaming`, `TransferRate` (Task 9); protocol bodies (Task 2).
- Produces:
  - `enum Direction { ToMac, FromMac }`
  - `enum Phase { Queued, Offered, Active, Paused, Verifying, Done, Failed, Cancelled }`
  - `enum Failure { ChecksumMismatch, NoSpace, Io, Declined, Invalid, SourceUnavailable, Expired }`
  - `@Serializable data class TransferRecord(...)`, with `isFinished` and `occupiesSlot`
  - `data class TransferSnapshot(active, history, rates)`; `sealed interface TransferEvent { Received(record) }`; `data class OutgoingFile(source, name, size, mime)`
  - `interface TransferStore { load(): StoredTransfers; save(StoredTransfers) }`, with `StoredTransfers(active, history)`, `InMemoryTransferStore` and `JsonFileTransferStore(file)`
  - `interface SaveTarget { freeBytes(): Long?; suspend createPart(name, mime, transferId): PartFile? }`
  - `interface PartFile { handle; length(); openRead(); openAppend(); finish(): String; delete() }`
  - `interface SourceOpener { open(source): InputStream }`, with `FileSourceOpener`
  - `class FolderSaveTarget(folder)`, with `companion reopen(handle): PartFile?`
  - `class TransferEngine(scope, store, files, sources, saveTarget: () -> SaveTarget, parts: (String) -> PartFile?, send: (Message) -> Boolean, clock, retryDelayMs, maxRetries)`:
    - `snapshot: StateFlow<TransferSnapshot>`, `events: SharedFlow<TransferEvent>`
    - `start(incoming: Flow<Message>, connected: Flow<Boolean>)`
    - `cancel(id)`, `cancelAll()`
- Task 12 adds `enqueue`, `retry` and the sending handlers to the same class.

- [ ] **Step 1: Write the test doubles** (`FakeMacFiles.kt`)

```kotlin
package dev.macdroid.core.transfer

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred

/** An in-memory Mac for engine tests: serves files for downloads and keeps uploads, with injectable failures. */
class FakeMacFiles : FileTransport {
    val served = ConcurrentHashMap<String, ByteArray>()
    val uploaded = ConcurrentHashMap<String, ByteArrayOutputStream>()
    val downloadOffsets = CopyOnWriteArrayList<Long>()
    val uploadOffsets = CopyOnWriteArrayList<Long>()
    /** The next byte transfer throws a network IOException after this many bytes. */
    @Volatile var failNextAfter: Int? = null
    /** Downloads wait for this before sending bytes. */
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var headStatus: Int? = null

    override suspend fun uploadOffset(id: String): Long {
        headStatus?.let { throw TransferHttpException(it) }
        return uploaded.getOrPut(id) { ByteArrayOutputStream() }.size().toLong()
    }

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        uploadOffsets += offset
        val sink = uploaded.getOrPut(id) { ByteArrayOutputStream() }
        if (sink.size().toLong() != offset) throw TransferHttpException(409, sink.size().toLong())
        val limit = failNextAfter.also { failNextAfter = null }
        val buffer = ByteArray(64 * 1024)
        var have = offset
        while (have < size) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - have).toInt())
            if (count < 0) throw LocalIoException(EOFException())
            if (limit != null && have - offset + count > limit) {
                sink.write(buffer, 0, (limit - (have - offset)).toInt())
                throw IOException("connection dropped")
            }
            sink.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            have += count
            onProgress(have)
        }
        return have
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        downloadOffsets += offset
        gate?.await()
        val bytes = served[id] ?: throw TransferHttpException(404)
        val limit = failNextAfter.also { failNextAfter = null }
        var have = offset
        while (have < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - have.toInt())
            if (limit != null && have - offset + count > limit) {
                val partial = (limit - (have - offset)).toInt()
                output.write(bytes, have.toInt(), partial)
                throw IOException("connection dropped")
            }
            try {
                output.write(bytes, have.toInt(), count)
            } catch (e: IOException) {
                throw LocalIoException(e)
            }
            digest.update(bytes, have.toInt(), count)
            have += count
            onProgress(have)
        }
        output.flush()
        return have
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").apply { update(bytes) }.hex()

fun testBytes(count: Int, seed: Int = 7) = ByteArray(count) { (it * 31 + seed).toByte() }
```

- [ ] **Step 2: Write the failing store test** (`TransferStoreTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferStoreTest {
    private val dir: File = Files.createTempDirectory("store").toFile()

    @Test fun jsonFileStoreRoundTrips() {
        val store = JsonFileTransferStore(File(dir, "transfers.json"))
        assertEquals(StoredTransfers(), store.load())
        val record = TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 1, mime = "x", phase = Phase.Failed,
            failure = Failure.ChecksumMismatch, part = "/p", createdAt = 1, updatedAt = 2)
        store.save(StoredTransfers(active = listOf(record.copy(phase = Phase.Paused, failure = null)), history = listOf(record)))
        assertEquals(StoredTransfers(listOf(record.copy(phase = Phase.Paused, failure = null)), listOf(record)), store.load())
    }

    @Test fun corruptFileLoadsEmpty() {
        File(dir, "bad.json").writeText("{nope")
        assertEquals(StoredTransfers(), JsonFileTransferStore(File(dir, "bad.json")).load())
    }

    // Review Focus #4
    @Test fun folderTargetNeverReusesAName() = kotlinx.coroutines.runBlocking {
        val target = FolderSaveTarget(dir)
        File(dir, "a.txt").writeText("old")
        val first = target.createPart("a.txt", "text/plain", "t1")!!
        val second = target.createPart("a.txt", "text/plain", "t2")!!
        assertEquals(setOf("a (1).txt.macdroid-part", "a (2).txt.macdroid-part"), setOf(File(first.handle).name, File(second.handle).name))
        first.openAppend().use { it.write(byteArrayOf(1)) }
        File(dir, "a (1).txt").writeText("someone else")
        val saved = File(java.net.URI(first.finish()))
        assertEquals("a (1) (1).txt", saved.name) // the final name was taken meanwhile: pick another, never overwrite
    }
}
```

- [ ] **Step 3: Write the failing engine tests** (`TransferEngineReceiveTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferFinishBody
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wires a TransferEngine to fakes: an in-memory Mac, a temp Download folder and recorded control messages. */
class EngineHarness(
    val store: TransferStore = InMemoryTransferStore(),
    val dir: File = Files.createTempDirectory("engine").toFile(),
    target: SaveTarget? = null,
    var now: Long = 1_000_000_000L,
) {
    val mac = FakeMacFiles()
    val sent = CopyOnWriteArrayList<Message>()
    @Volatile var online = true
    val incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val connected = MutableStateFlow(true)
    val folder = File(dir, "Download/MacDroid")
    private val saveTarget = target ?: FolderSaveTarget(folder)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = TransferEngine(
        scope = scope, store = store, files = mac, sources = FileSourceOpener(),
        saveTarget = { saveTarget }, parts = { FolderSaveTarget.reopen(it) },
        send = { message -> if (online) sent.add(message) else false },
        clock = { now }, retryDelayMs = 20, maxRetries = 3,
    )

    fun start() {
        engine.start(incoming, connected)
        eventually { incoming.subscriptionCount.value > 0 }
    }

    fun deliver(message: Message) = runBlocking { incoming.emit(message) }
    fun offer(id: String, name: String, bytes: ByteArray) {
        mac.served[id] = bytes
        deliver(Message.TransferOffer(TransferOfferBody(id, name, bytes.size.toLong(), "application/octet-stream")))
    }
    /** Like the real Mac, only sends finish after the phone accepted. */
    fun finish(id: String, bytes: ByteArray) {
        eventually { sentOf<Message.TransferAccept>().any { it.body.transferId == id } }
        deliver(Message.TransferFinish(TransferFinishBody(id, sha256(bytes))))
    }
    inline fun <reified T : Message> sentOf(): List<T> = sent.filterIsInstance<T>()
    fun history() = engine.snapshot.value.history
    fun active() = engine.snapshot.value.active
    fun close() = scope.cancel()
}

class TransferEngineReceiveTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    @Test fun offerIsAcceptedDownloadedAndVerifiedOnFinish() {
        h.start()
        val events = CopyOnWriteArrayList<TransferEvent>()
        h.scope.launch { h.engine.events.collect { events += it } }
        val bytes = testBytes(300_000)
        h.offer("t1", "photo.jpg", bytes)
        eventually { h.sentOf<Message.TransferAccept>().any { it.body.transferId == "t1" } }
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", ok = true) } }
        assertArrayEquals(bytes, File(h.folder, "photo.jpg").readBytes())
        assertFalse(File(h.folder, "photo.jpg.macdroid-part").exists())
        assertEquals(Phase.Done, h.history().first().phase)
        eventually { events.size == 1 }
    }

    @Test fun finishBeforeTheBytesWaitsForThem() {
        h.start()
        h.mac.gate = CompletableDeferred()
        val bytes = testBytes(10_000)
        h.offer("t1", "a.bin", bytes)
        h.finish("t1", bytes)
        Thread.sleep(100)
        assertTrue(h.sentOf<Message.TransferResult>().isEmpty())
        h.mac.gate!!.complete(Unit)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
    }

    @Test fun checksumMismatchDeletesThePart() {
        h.start()
        h.offer("t1", "a.bin", testBytes(1_000))
        h.finish("t1", testBytes(1_000, seed = 9))
        eventually { h.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", false, TransferResultError.ChecksumMismatch) } }
        assertFalse(File(h.folder, "a.bin.macdroid-part").exists())
        assertFalse(File(h.folder, "a.bin").exists())
    }

    @Test fun notEnoughSpaceIsRejected() {
        val small = object : SaveTarget {
            override fun freeBytes() = 10L
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = error("must not create a part")
        }
        val harness = EngineHarness(target = small)
        try {
            harness.start()
            harness.offer("t1", "big.bin", testBytes(100))
            eventually { harness.sentOf<Message.TransferReject>().any { it.body == TransferRejectBody("t1", TransferRejectReason.NoSpace) } }
            assertEquals(Failure.NoSpace, harness.history().first().failure)
        } finally {
            harness.close()
        }
    }

    @Test fun noPartMeansDeclined() {
        val declining = object : SaveTarget {
            override fun freeBytes(): Long? = null
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = null
        }
        val harness = EngineHarness(target = declining)
        try {
            harness.start()
            harness.offer("t1", "a.bin", testBytes(1))
            eventually { harness.sentOf<Message.TransferReject>().any { it.body.reason == TransferRejectReason.Declined } }
        } finally {
            harness.close()
        }
    }

    // Review Focus #2
    @Test fun interruptedDownloadResumesFromPartLength() {
        h.start()
        h.mac.failNextAfter = 100_000
        val bytes = testBytes(400_000)
        h.offer("t1", "big.bin", bytes)
        eventually { h.mac.downloadOffsets.size == 2 }
        assertEquals(listOf(0L, 100_000L), h.mac.downloadOffsets.toList())
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertArrayEquals(bytes, File(h.folder, "big.bin").readBytes())
    }

    @Test fun disconnectPausesAndTheMacsReofferResumes() {
        h.start()
        h.mac.gate = CompletableDeferred()
        val bytes = testBytes(50_000)
        h.offer("t1", "a.bin", bytes)
        eventually { h.mac.downloadOffsets.size == 1 }
        h.connected.value = false
        eventually { h.active().firstOrNull()?.phase == Phase.Paused }
        h.mac.gate!!.complete(Unit)
        h.connected.value = true
        h.offer("t1", "a.bin", bytes) // the Mac re-offers after reconnecting
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertEquals(2, h.sentOf<Message.TransferAccept>().size)
    }

    // Review Focus #1
    @Test fun reofferOfDoneIdReplaysResult() {
        h.start()
        val bytes = testBytes(1_000)
        h.offer("t1", "a.bin", bytes)
        h.finish("t1", bytes)
        eventually { h.sentOf<Message.TransferResult>().size == 1 }
        h.offer("t1", "a.bin", bytes)
        eventually { h.sentOf<Message.TransferResult>().size == 2 }
        assertFalse(File(h.folder, "a (1).bin.macdroid-part").exists())
        assertEquals(1, h.sentOf<Message.TransferAccept>().size)
    }

    @Test fun peerCancelDeletesThePart() {
        h.start()
        h.mac.gate = CompletableDeferred()
        h.offer("t1", "a.bin", testBytes(1_000))
        eventually { File(h.folder, "a.bin.macdroid-part").exists() }
        h.deliver(Message.TransferCancel(TransferIdBody("t1")))
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.history().first().cancelledByPeer)
        eventually { !File(h.folder, "a.bin.macdroid-part").exists() }
    }

    @Test fun localWriteFailureFailsWithIo() {
        val broken = object : SaveTarget {
            override fun freeBytes(): Long? = null
            override suspend fun createPart(name: String, mime: String, transferId: String): PartFile = object : PartFile {
                override val handle = "broken"
                override fun length() = 0L
                override fun openRead() = java.io.ByteArrayInputStream(ByteArray(0))
                override fun openAppend(): OutputStream = object : OutputStream() { override fun write(b: Int) = throw IOException("EIO") }
                override fun finish() = error("unreachable")
                override fun delete() {}
            }
        }
        val harness = EngineHarness(target = broken)
        try {
            harness.start()
            harness.offer("t1", "a.bin", testBytes(100_000))
            eventually { harness.sentOf<Message.TransferResult>().any { it.body == TransferResultBody("t1", false, TransferResultError.Io) } }
        } finally {
            harness.close()
        }
    }

    // Review Focus #5
    @Test fun zeroByteDownloadCompletes() {
        h.start()
        h.offer("t1", "empty.txt", ByteArray(0))
        h.finish("t1", ByteArray(0))
        eventually { h.sentOf<Message.TransferResult>().any { it.body.ok } }
        assertTrue(File(h.folder, "empty.txt").exists())
        assertEquals(listOf(0L), h.mac.downloadOffsets.toList())
    }

    @Test fun stateOlderThan24HoursIsCleanedAtStart() {
        val part = File(h.dir, "old.bin.macdroid-part").apply { writeText("x") }
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "old", direction = Direction.FromMac, name = "old.bin", size = 5, mime = "x", phase = Phase.Active,
                part = part.path, createdAt = 0, updatedAt = 0),
        )))
        val harness = EngineHarness(store = store, dir = h.dir, now = 25L * 3600 * 1000)
        try {
            harness.start()
            eventually { harness.history().firstOrNull()?.failure == Failure.Expired }
            assertTrue(harness.active().isEmpty())
            assertFalse(part.exists())
        } finally {
            harness.close()
        }
    }

    @Test fun interruptedStateComesBackPaused() {
        val store = InMemoryTransferStore(StoredTransfers(active = listOf(
            TransferRecord(id = "t1", direction = Direction.FromMac, name = "a", size = 5, mime = "x", phase = Phase.Active, createdAt = 0, updatedAt = 0),
        )))
        val harness = EngineHarness(store = store, now = 3_600_000)
        try {
            harness.start()
            eventually { harness.active().firstOrNull()?.phase == Phase.Paused }
        } finally {
            harness.close()
        }
    }
}
```

- [ ] **Step 4: Run them to verify they fail**

Run: `cd android && ./gradlew :core:test --tests '*TransferStoreTest*' --tests '*TransferEngineReceiveTest*'` → compile errors.

- [ ] **Step 5: Implement `TransferModel.kt`**

```kotlin
package dev.macdroid.core.transfer

import kotlinx.serialization.Serializable

@Serializable enum class Direction { ToMac, FromMac }

@Serializable enum class Phase { Queued, Offered, Active, Paused, Verifying, Done, Failed, Cancelled }

@Serializable enum class Failure { ChecksumMismatch, NoSpace, Io, Declined, Invalid, SourceUnavailable, Expired }

@Serializable
data class TransferRecord(
    val id: String,
    val direction: Direction,
    /** ToMac: the file's name. FromMac: the sanitised name (the final name may get a " (1)" when saved). */
    val name: String,
    val size: Long,
    val mime: String,
    val phase: Phase,
    val bytesDone: Long = 0,
    val failure: Failure? = null,
    val cancelledByPeer: Boolean = false,
    /** ToMac: the content URI or file path to read. */
    val source: String? = null,
    /** FromMac: the part file's handle (see [PartFile.handle]). */
    val part: String? = null,
    /** FromMac: the SHA-256 from `transfer.finish`, kept until every byte is in. */
    val expectedSha256: String? = null,
    /** FromMac: where the finished file was saved (a URI string), for Open. */
    val savedUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFinished: Boolean get() = phase == Phase.Done || phase == Phase.Failed || phase == Phase.Cancelled

    /** Counts toward the 3-active-outgoing limit. */
    val occupiesSlot: Boolean get() = phase == Phase.Offered || phase == Phase.Active || phase == Phase.Paused || phase == Phase.Verifying
}

data class TransferSnapshot(
    val active: List<TransferRecord> = emptyList(),
    /** Finished transfers, newest first (at most 100). */
    val history: List<TransferRecord> = emptyList(),
    /** Smoothed bytes per second for transfers moving data right now, by id. */
    val rates: Map<String, Double> = emptyMap(),
)

sealed interface TransferEvent {
    /** A file from the Mac was verified and saved ([TransferRecord.savedUri] is set). */
    data class Received(val record: TransferRecord) : TransferEvent
}

/** A file the user chose to send. [source] is a content URI or an absolute path. */
data class OutgoingFile(val source: String, val name: String, val size: Long, val mime: String)
```

- [ ] **Step 6: Implement `TransferStore.kt` and `SaveTarget.kt`**

```kotlin
package dev.macdroid.core.transfer

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StoredTransfers(val active: List<TransferRecord> = emptyList(), val history: List<TransferRecord> = emptyList())

/** Persists transfer state across process death (spec §4.1). */
interface TransferStore {
    fun load(): StoredTransfers
    fun save(state: StoredTransfers)
}

class InMemoryTransferStore(@Volatile var state: StoredTransfers = StoredTransfers()) : TransferStore {
    override fun load() = state
    override fun save(state: StoredTransfers) { this.state = state }
}

/** JSON in one file, replaced atomically. A missing or unreadable file loads as empty. */
class JsonFileTransferStore(private val file: File) : TransferStore {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun load(): StoredTransfers =
        runCatching { json.decodeFromString(StoredTransfers.serializer(), file.readText()) }.getOrDefault(StoredTransfers())

    override fun save(state: StoredTransfers) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(StoredTransfers.serializer(), state))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
```

```kotlin
package dev.macdroid.core.transfer

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Where files from the Mac go. The app has MediaStore, SAF-folder and ask-each-time versions (Plan 2b). */
interface SaveTarget {
    /** Free bytes at the destination, or null if unknown. */
    fun freeBytes(): Long?

    /**
     * Creates the part file for [name]. May suspend while the user picks a place ("Ask where to save").
     * Null declines the file (no place chosen, or the destination is unavailable).
     */
    suspend fun createPart(name: String, mime: String, transferId: String): PartFile?
}

/** An incoming file until it's verified. Every call may block on I/O. */
interface PartFile {
    /** Survives restarts; the engine's `parts` function turns it back into a PartFile. */
    val handle: String
    fun length(): Long
    fun openRead(): InputStream
    fun openAppend(): OutputStream
    /** Makes the verified file visible under its final name; returns a URI string to open it. */
    fun finish(): String
    fun delete()
}

/** Opens files the user chose to send. */
fun interface SourceOpener {
    fun open(source: String): InputStream
}

/** Opens absolute paths (tests and the cross-platform run; the app adds content URIs). */
class FileSourceOpener : SourceOpener {
    override fun open(source: String): InputStream = File(source).inputStream()
}
```

- [ ] **Step 7: Implement `FolderSaveTarget.kt`**

```kotlin
package dev.macdroid.core.transfer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Saves into a plain folder: `<name>.macdroid-part` while receiving, then renamed to a free final name. */
class FolderSaveTarget(private val folder: File) : SaveTarget {
    private val lock = Any()

    override fun freeBytes(): Long? = folder.takeIf { it.exists() }?.usableSpace

    // Choosing the name and creating the part happen under one lock, so two offers can't claim the same name.
    override suspend fun createPart(name: String, mime: String, transferId: String): PartFile? = synchronized(lock) {
        if (!folder.isDirectory && !folder.mkdirs()) return@synchronized null
        val finalName = FileNaming.unique(name) { taken(it) }
        val part = File(folder, finalName + FileNaming.PART_SUFFIX)
        if (part.createNewFile()) FolderPart(part) else null
    }

    private fun taken(name: String) = File(folder, name).exists() || File(folder, name + FileNaming.PART_SUFFIX).exists()

    companion object {
        fun reopen(handle: String): PartFile? = File(handle).takeIf { it.isFile }?.let(::FolderPart)
    }
}

private class FolderPart(private val file: File) : PartFile {
    override val handle: String = file.path
    override fun length() = file.length()
    override fun openRead() = file.inputStream()
    override fun openAppend() = FileOutputStream(file, true)

    override fun finish(): String {
        val folder = file.parentFile
        val wanted = file.name.removeSuffix(FileNaming.PART_SUFFIX)
        val name = FileNaming.unique(wanted) { File(folder, it).exists() }
        val target = File(folder, name)
        if (!file.renameTo(target)) throw IOException("couldn't rename ${file.name} to $name")
        return target.toURI().toString()
    }

    override fun delete() {
        file.delete()
    }
}
```

- [ ] **Step 8: Implement `TransferEngine.kt` (receiving)**

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferFinishBody
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferOfferBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs file transfers with the Mac (spec §2): the control handshake, the HTTP byte streams, resume, checksums,
 * the 3-outgoing limit, persistence (24 h) and history (last 100).
 * Decisions run on one confined dispatcher ([control]); byte copying runs on IO and only reports progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferEngine(
    private val scope: CoroutineScope,
    private val store: TransferStore,
    private val files: FileTransport,
    private val sources: SourceOpener,
    private val saveTarget: () -> SaveTarget,
    private val parts: (String) -> PartFile?,
    private val send: (Message) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 3_000,
    private val maxRetries: Int = 5,
) {
    companion object {
        const val MAX_ACTIVE_OUTGOING = 3
        const val HISTORY_LIMIT = 100
        const val STATE_LIFETIME_MS = 24L * 60 * 60 * 1000
    }

    private val control = Dispatchers.Default.limitedParallelism(1)
    private val _snapshot = MutableStateFlow(TransferSnapshot())
    val snapshot: StateFlow<TransferSnapshot> = _snapshot.asStateFlow()
    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    // Touched only on [control].
    private val jobs = HashMap<String, Job>()
    private val deciding = HashMap<String, Job>()
    private val completedDigests = HashMap<String, String>()
    private val outbox = ArrayList<Message>()
    private var connected = false
    // Touched from IO threads (one per transfer).
    private val rates = ConcurrentHashMap<String, TransferRate>()

    private sealed interface Outcome {
        data class Complete(val sha256: String) : Outcome
        data class Local(val failure: Failure) : Outcome
        data object Network : Outcome
        data object Conflict : Outcome
        data object Gone : Outcome
    }

    fun start(incoming: Flow<Message>, connected: Flow<Boolean>) {
        scope.launch(control) {
            load()
            launch { incoming.collect { onMessage(it) } }
            launch { connected.distinctUntilChanged().collect { onConnectionChanged(it) } }
        }
    }

    fun cancel(id: String) {
        scope.launch(control) { cancelLocally(id) }
    }

    fun cancelAll() {
        scope.launch(control) { active.map { it.id }.forEach { cancelLocally(it) } }
    }

    // region State

    private val active get() = _snapshot.value.active
    private fun record(id: String) = active.firstOrNull { it.id == id }
    private fun past(id: String) = _snapshot.value.history.firstOrNull { it.id == id }

    private fun add(record: TransferRecord) = _snapshot.update { it.copy(active = it.active + record) }

    private fun update(id: String, change: (TransferRecord) -> TransferRecord) {
        _snapshot.update { s -> s.copy(active = s.active.map { if (it.id == id) change(it).copy(updatedAt = clock()) else it }) }
    }

    /** Moves a finished record to the front of history. */
    private fun archive(record: TransferRecord) {
        rates.remove(record.id)
        _snapshot.update { s ->
            s.copy(
                active = s.active.filterNot { it.id == record.id },
                history = (listOf(record.copy(updatedAt = clock())) + s.history.filterNot { it.id == record.id }).take(HISTORY_LIMIT),
                rates = s.rates - record.id,
            )
        }
        persist()
    }

    private fun persist() {
        val s = _snapshot.value
        runCatching { store.save(StoredTransfers(s.active, s.history)) }
    }

    private fun progress(id: String, bytes: Long) {
        val rate = rates.getOrPut(id) { TransferRate() }.apply { record(bytes, System.nanoTime()) }.bytesPerSecond
        _snapshot.update { s ->
            s.copy(
                active = s.active.map { if (it.id == id) it.copy(bytesDone = bytes) else it },
                rates = if (rate != null) s.rates + (id to rate) else s.rates,
            )
        }
    }

    private fun clearRate(id: String) {
        rates.remove(id)
        _snapshot.update { it.copy(rates = it.rates - id) }
    }

    /** Restores persisted state: anything older than 24 h is cleaned up; interrupted transfers wait to resume. */
    private suspend fun load() {
        val stored = runCatching { store.load() }.getOrDefault(StoredTransfers())
        val cutoff = clock() - STATE_LIFETIME_MS
        val history = stored.history.toMutableList()
        val restored = mutableListOf<TransferRecord>()
        for (record in stored.active) {
            when {
                record.updatedAt < cutoff -> {
                    record.part?.let { handle -> withContext(Dispatchers.IO) { runCatching { parts(handle)?.delete() } } }
                    history.add(0, record.copy(phase = Phase.Failed, failure = Failure.Expired, updatedAt = clock()))
                }
                record.phase == Phase.Queued -> restored += record
                else -> restored += record.copy(phase = Phase.Paused)
            }
        }
        _snapshot.value = TransferSnapshot(restored, history.take(HISTORY_LIMIT))
        persist()
    }

    // endregion

    private suspend fun onMessage(message: Message) {
        when (message) {
            is Message.TransferOffer -> onOffer(message.body)
            is Message.TransferFinish -> onFinish(message.body)
            is Message.TransferCancel -> onPeerCancel(message.body.transferId)
            else -> Unit
        }
    }

    private fun onConnectionChanged(isConnected: Boolean) {
        connected = isConnected
        if (!isConnected) {
            // Stop moving bytes; transfers resume after the reconnect (spec §5 "Paused — waiting for connection").
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            for (record in active) {
                if (record.phase == Phase.Active || record.phase == Phase.Offered) update(record.id) { it.copy(phase = Phase.Paused) }
                clearRate(record.id)
            }
            persist()
            return
        }
        val queued = outbox.toList()
        outbox.clear()
        queued.forEach(::sendOrQueue)
    }

    private fun sendOrQueue(message: Message) {
        if (!send(message)) outbox += message
    }

    private suspend fun cancelLocally(id: String) {
        val record = record(id) ?: return
        jobs.remove(id)?.cancelAndJoin()
        if (record.direction == Direction.FromMac) deletePart(record)
        archive(record.copy(phase = Phase.Cancelled, cancelledByPeer = false))
        if (record.phase != Phase.Queued) sendOrQueue(Message.TransferCancel(TransferIdBody(id)))
    }

    private suspend fun onPeerCancel(id: String) {
        deciding.remove(id)?.cancel()
        val record = record(id) ?: return
        jobs.remove(id)?.cancelAndJoin()
        if (record.direction == Direction.FromMac) deletePart(record)
        archive(record.copy(phase = Phase.Cancelled, cancelledByPeer = true))
    }

    // region Receiving (Mac → phone)

    private fun onOffer(offer: TransferOfferBody) {
        val id = offer.transferId
        val known = record(id)
        if (known != null) {
            if (known.direction != Direction.FromMac) return
            // A re-offer after a reconnect: accept again and resume from the part file's length.
            update(id) { it.copy(phase = Phase.Active) }
            send(Message.TransferAccept(TransferIdBody(id)))
            startDownload(id)
            return
        }
        past(id)?.let { replay(it); return }
        if (offer.size < 0) {
            send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Invalid)))
            return
        }
        if (id in deciding) return
        // Deciding may wait for the user ("Ask where to save"), so it runs on its own.
        deciding[id] = scope.launch(control) {
            try {
                decide(offer)
            } finally {
                deciding.remove(id)
            }
        }
    }

    private suspend fun decide(offer: TransferOfferBody) {
        val id = offer.transferId
        val name = FileNaming.sanitize(offer.name)
        val target = saveTarget()
        val free = withContext(Dispatchers.IO) { runCatching { target.freeBytes() }.getOrNull() }
        if (free != null && free < offer.size) return reject(offer, name, TransferRejectReason.NoSpace)
        val part = try {
            target.createPart(name, offer.mime, id)
        } catch (e: IOException) {
            null
        } ?: return reject(offer, name, TransferRejectReason.Declined)
        add(TransferRecord(id = id, direction = Direction.FromMac, name = name, size = offer.size, mime = offer.mime,
            phase = Phase.Active, part = part.handle, createdAt = clock(), updatedAt = clock()))
        persist()
        send(Message.TransferAccept(TransferIdBody(id)))
        startDownload(id)
    }

    private fun reject(offer: TransferOfferBody, name: String, reason: TransferRejectReason) {
        val failure = when (reason) {
            TransferRejectReason.NoSpace -> Failure.NoSpace
            TransferRejectReason.Declined -> Failure.Declined
            TransferRejectReason.Invalid -> Failure.Invalid
        }
        archive(TransferRecord(id = offer.transferId, direction = Direction.FromMac, name = name, size = offer.size, mime = offer.mime,
            phase = Phase.Failed, failure = failure, createdAt = clock(), updatedAt = clock()))
        send(Message.TransferReject(TransferRejectBody(offer.transferId, reason)))
    }

    /** The Mac re-offered an id we already finished (its result got lost): tell it the outcome again. */
    private fun replay(record: TransferRecord) {
        val id = record.id
        when (record.phase) {
            Phase.Done -> send(Message.TransferResult(TransferResultBody(id, ok = true)))
            Phase.Failed -> when (record.failure) {
                Failure.Declined -> send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Declined)))
                Failure.Invalid -> send(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Invalid)))
                else -> send(Message.TransferResult(TransferResultBody(id, ok = false, error = record.failure.resultError())))
            }
            Phase.Cancelled -> send(Message.TransferCancel(TransferIdBody(id)))
            else -> Unit
        }
    }

    private fun startDownload(id: String) {
        val previous = jobs.remove(id)
        jobs[id] = scope.launch(control) {
            previous?.cancelAndJoin() // never let two downloads write the same part file
            runDownload(id)
        }
    }

    private suspend fun runDownload(id: String) {
        var attempts = 0
        while (true) {
            val record = record(id) ?: return
            val part = record.part?.let { handle -> withContext(Dispatchers.IO) { parts(handle) } } ?: return failIncoming(id, Failure.Io)
            when (val outcome = withContext(Dispatchers.IO) { downloadOnce(record, part) }) {
                is Outcome.Complete -> {
                    completedDigests[id] = outcome.sha256
                    update(id) { it.copy(bytesDone = it.size, phase = Phase.Verifying) }
                    clearRate(id)
                    jobs.remove(id)
                    return verifyIfReady(id)
                }
                is Outcome.Local -> return failIncoming(id, outcome.failure)
                Outcome.Gone -> return failIncoming(id, Failure.Io)
                Outcome.Network, Outcome.Conflict -> if (!pauseForRetry(id, ++attempts)) return
            }
        }
    }

    private suspend fun downloadOnce(record: TransferRecord, part: PartFile): Outcome = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val have = local { part.openRead().use { digestAll(it, digest) } } // re-hash what's already on disk
        if (have > record.size) throw LocalIoException(IOException("part file is longer than the offer"))
        val output = local { part.openAppend() }.buffered(TransferClient.BUFFER_SIZE)
        val total = try {
            files.download(record.id, record.size, have, output, digest) { progress(record.id, it) }
        } finally {
            local { output.close() }
        }
        if (total < record.size) Outcome.Network else Outcome.Complete(digest.hex())
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalIoException) {
        Outcome.Local(if (e.isNoSpace()) Failure.NoSpace else Failure.Io)
    } catch (e: TransferHttpException) {
        when (e.code) {
            404, 410 -> Outcome.Gone
            409 -> Outcome.Conflict
            else -> Outcome.Network
        }
    } catch (e: IOException) {
        Outcome.Network
    }

    private suspend fun onFinish(body: TransferFinishBody) {
        val record = record(body.transferId)?.takeIf { it.direction == Direction.FromMac } ?: return
        update(record.id) { it.copy(expectedSha256 = body.sha256) }
        persist()
        verifyIfReady(record.id)
    }

    /** Verifies once every byte is in and the Mac's checksum has arrived, in whichever order they came. */
    private suspend fun verifyIfReady(id: String) {
        val record = record(id) ?: return
        val actual = completedDigests[id] ?: return
        val expected = record.expectedSha256 ?: return
        completedDigests.remove(id)
        if (actual != expected.lowercase()) return failIncoming(id, Failure.ChecksumMismatch)
        val saved = try {
            withContext(Dispatchers.IO) { (record.part?.let(parts) ?: throw IOException("part file is gone")).finish() }
        } catch (e: IOException) {
            return failIncoming(id, Failure.Io)
        }
        val done = record.copy(phase = Phase.Done, bytesDone = record.size, savedUri = saved)
        archive(done)
        _events.tryEmit(TransferEvent.Received(done))
        send(Message.TransferResult(TransferResultBody(id, ok = true)))
    }

    private suspend fun failIncoming(id: String, failure: Failure) {
        val record = record(id) ?: return
        jobs.remove(id)
        completedDigests.remove(id)
        deletePart(record)
        archive(record.copy(phase = Phase.Failed, failure = failure))
        send(Message.TransferResult(TransferResultBody(id, ok = false, error = failure.resultError())))
    }

    private suspend fun deletePart(record: TransferRecord) {
        val handle = record.part ?: return
        withContext(Dispatchers.IO) { runCatching { parts(handle)?.delete() } }
    }

    // endregion

    /** Marks [id] paused. Returns true when it's worth trying again now (still connected, retries left). */
    private suspend fun pauseForRetry(id: String, attempts: Int): Boolean {
        update(id) { it.copy(phase = Phase.Paused) }
        clearRate(id)
        persist()
        if (!connected || attempts > maxRetries) return false
        delay(retryDelayMs)
        if (!connected || record(id) == null) return false
        update(id) { it.copy(phase = Phase.Active) }
        return true
    }
}

/** Wraps local I/O so its failures fail the transfer instead of pausing it. */
internal inline fun <T> local(block: () -> T): T = try {
    block()
} catch (e: LocalIoException) {
    throw e
} catch (e: IOException) {
    throw LocalIoException(e)
} catch (e: SecurityException) {
    throw LocalIoException(e)
}

internal fun digestAll(input: InputStream, digest: MessageDigest, limit: Long = Long.MAX_VALUE): Long {
    val buffer = ByteArray(TransferClient.BUFFER_SIZE)
    var total = 0L
    while (total < limit) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (count < 0) break
        digest.update(buffer, 0, count)
        total += count
    }
    return total
}

internal fun Throwable.isNoSpace(): Boolean =
    generateSequence(this) { it.cause }.any { error -> error.message?.let { "ENOSPC" in it || "No space left" in it } == true }

internal fun Failure?.resultError(): TransferResultError = when (this) {
    Failure.ChecksumMismatch -> TransferResultError.ChecksumMismatch
    Failure.NoSpace -> TransferResultError.NoSpace
    else -> TransferResultError.Io
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd android && ./gradlew :core:test --tests '*TransferStoreTest*' --tests '*TransferEngineReceiveTest*'` → pass. Then `./gradlew :core:test` → BUILD SUCCESSFUL, no warnings.

- [ ] **Step 10: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 11: `TransferEngine` receiving (offer → part → GET with resume → verify in either order → finish), replay, cancel, 24 h cleanup; `JsonFileTransferStore`, `FolderSaveTarget`. Verified: `TransferEngineReceiveTest`, `TransferStoreTest`."
  - **ARCHITECTURE.md:** extend the `transfer` row with `TransferEngine.kt` (confined-dispatcher state machine), `SaveTarget`/`PartFile`/`SourceOpener` (platform seams for Plan 2b), and `FolderSaveTarget`.

```bash
git add android/core docs/handoff
git commit -m "feat(android): TransferEngine receiving with resume, verify and replay"
```

---
### Task 12: Kotlin `TransferEngine` — sending (phone → Mac)

**Files:**
- Modify: `android/core/src/main/kotlin/dev/macdroid/core/transfer/TransferEngine.kt`
- Test: create `android/core/src/test/kotlin/dev/macdroid/core/transfer/TransferEngineSendTest.kt`

**Interfaces:**
- Consumes: Task 11's engine internals and `EngineHarness`.
- Produces:
  - `TransferEngine.enqueue(files: List<OutgoingFile>): List<String>`
  - `TransferEngine.retry(id: String): String?`
  - Outgoing handling of `transfer.accept`, `transfer.reject` and `transfer.result`
  - Re-offer on reconnect

- [ ] **Step 1: Write the failing tests** (`TransferEngineSendTest.kt`)

```kotlin
package dev.macdroid.core.transfer

import dev.macdroid.core.eventually
import dev.macdroid.core.protocol.Message
import dev.macdroid.core.protocol.TransferIdBody
import dev.macdroid.core.protocol.TransferRejectBody
import dev.macdroid.core.protocol.TransferRejectReason
import dev.macdroid.core.protocol.TransferResultBody
import dev.macdroid.core.protocol.TransferResultError
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEngineSendTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun file(name: String, bytes: ByteArray): OutgoingFile {
        val file = File(h.dir, name).apply { writeBytes(bytes) }
        return OutgoingFile(file.path, name, bytes.size.toLong(), "application/octet-stream")
    }

    private fun offers() = h.sentOf<Message.TransferOffer>().map { it.body.transferId }
    private fun accept(id: String) = h.deliver(Message.TransferAccept(TransferIdBody(id)))

    @Test fun offersAtMostThreeInOrderAndQueuesTheRest() {
        h.start()
        val ids = h.engine.enqueue((1..5).map { file("f$it.bin", testBytes(10)) })
        eventually { offers().size == 3 }
        assertEquals(ids.take(3), offers())
        eventually { h.active().count { it.phase == Phase.Queued } == 2 }
    }

    @Test fun acceptUploadsAndSendsTheWholeFileChecksum() {
        h.start()
        val bytes = testBytes(300_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().any { it.body.transferId == id } }
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
        assertArrayEquals(bytes, h.mac.uploaded.getValue(id).toByteArray())
        assertEquals(Phase.Verifying, h.active().single().phase)
        h.deliver(Message.TransferResult(TransferResultBody(id, ok = true)))
        eventually { h.history().firstOrNull()?.phase == Phase.Done }
    }

    // Review Focus #2
    @Test fun resumedUploadSendsOnlyTheTail() {
        h.start()
        val bytes = testBytes(300_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        h.mac.uploaded[id] = ByteArrayOutputStream().apply { write(bytes, 0, 120_000) } // the Mac kept these from before
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
        assertEquals(listOf(120_000L), h.mac.uploadOffsets.toList())
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
        assertArrayEquals(bytes, h.mac.uploaded.getValue(id).toByteArray())
    }

    @Test fun droppedUploadRetriesFromTheMacsOffset() {
        h.start()
        h.mac.failNextAfter = 100_000
        val bytes = testBytes(400_000)
        val id = h.engine.enqueue(listOf(file("a.bin", bytes))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
        assertEquals(listOf(0L, 100_000L), h.mac.uploadOffsets.toList())
        assertEquals(sha256(bytes), h.sentOf<Message.TransferFinish>().single().body.sha256)
    }

    // Review Focus #5
    @Test fun macAlreadyHasEverythingGoesStraightToFinish() {
        h.start()
        val empty = h.engine.enqueue(listOf(file("empty.txt", ByteArray(0)))).single()
        eventually { offers() == listOf(empty) }
        accept(empty)
        eventually { h.sentOf<Message.TransferFinish>().any { it.body.transferId == empty } }
        assertTrue(h.mac.uploadOffsets.isEmpty())
        assertEquals(sha256(ByteArray(0)), h.sentOf<Message.TransferFinish>().single().body.sha256)
    }

    @Test fun reconnectReoffersUnfinishedTransfersUnderTheSameId() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.connected.value = false
        eventually { h.active().single().phase == Phase.Paused }
        h.connected.value = true
        eventually { offers() == listOf(id, id) }
    }

    @Test fun rejectFailsAndFreesASlot() {
        h.start()
        val ids = h.engine.enqueue((1..4).map { file("f$it.bin", testBytes(10)) })
        eventually { offers().size == 3 }
        h.deliver(Message.TransferReject(TransferRejectBody(ids[0], TransferRejectReason.NoSpace)))
        eventually { offers().size == 4 }
        assertEquals(Failure.NoSpace, h.history().first { it.id == ids[0] }.failure)
    }

    @Test fun failedResultIsRecorded() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.deliver(Message.TransferResult(TransferResultBody(id, ok = false, error = TransferResultError.ChecksumMismatch)))
        eventually { h.history().firstOrNull()?.failure == Failure.ChecksumMismatch }
    }

    @Test fun localCancelIsSentOrQueuedWhenOffline() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.online = false
        h.engine.cancel(id)
        eventually { h.history().firstOrNull()?.phase == Phase.Cancelled }
        assertTrue(h.sentOf<Message.TransferCancel>().isEmpty())
        h.online = true
        h.connected.value = false
        h.connected.value = true
        eventually { h.sentOf<Message.TransferCancel>().any { it.body.transferId == id } }
    }

    @Test fun unreadableSourceFailsAndCancels() {
        h.start()
        val gone = OutgoingFile(File(h.dir, "missing.bin").path, "missing.bin", 10, "x")
        val id = h.engine.enqueue(listOf(gone)).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { h.history().firstOrNull()?.failure == Failure.SourceUnavailable }
        assertTrue(h.sentOf<Message.TransferCancel>().any { it.body.transferId == id })
    }

    @Test fun macThatForgotTheIdGetsItOfferedAgain() {
        h.start()
        h.mac.headStatus = 404
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        accept(id)
        eventually { offers() == listOf(id, id) }
        h.mac.headStatus = null
        accept(id)
        eventually { h.sentOf<Message.TransferFinish>().isNotEmpty() }
    }

    @Test fun retryQueuesANewTransferForTheSameFile() {
        h.start()
        val id = h.engine.enqueue(listOf(file("a.bin", testBytes(10)))).single()
        eventually { offers() == listOf(id) }
        h.deliver(Message.TransferReject(TransferRejectBody(id, TransferRejectReason.Declined)))
        eventually { h.history().isNotEmpty() }
        val again = h.engine.retry(id)
        assertNotEquals(id, again)
        eventually { offers() == listOf(id, again) }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd android && ./gradlew :core:test --tests '*TransferEngineSendTest*'` → compile errors (`enqueue` not defined).

- [ ] **Step 3: Add sending to `TransferEngine`**

Add imports `dev.macdroid.core.protocol.TransferResultError` (already there), `java.io.EOFException`, `java.util.UUID`. Then:

1. In `onMessage`, add three cases:

```kotlin
            is Message.TransferAccept -> onAccept(message.body.transferId)
            is Message.TransferReject -> onReject(message.body)
            is Message.TransferResult -> onResult(message.body)
```

2. At the end of `onConnectionChanged` (the connected branch, after the outbox loop), add:

```kotlin
        // Offer every unfinished outgoing transfer again under its id (spec §2.2), then fill free slots.
        for (record in active.filter { it.direction == Direction.ToMac && it.occupiesSlot }) {
            jobs.remove(record.id)?.cancel()
            update(record.id) { it.copy(phase = Phase.Offered) }
            offer(record)
        }
        pump()
```

3. At the end of `cancelLocally` and of `onPeerCancel`, add `pump()`.

4. Add this region inside the class:

```kotlin
    // region Sending (phone → Mac)

    /** Queues files to send to the Mac. Returns their transfer ids right away. */
    fun enqueue(files: List<OutgoingFile>): List<String> {
        val now = clock()
        val records = files.map {
            TransferRecord(id = UUID.randomUUID().toString(), direction = Direction.ToMac, name = it.name, size = it.size, mime = it.mime,
                phase = Phase.Queued, source = it.source, createdAt = now, updatedAt = now)
        }
        scope.launch(control) {
            records.forEach(::add)
            persist()
            pump()
        }
        return records.map { it.id }
    }

    /** Sends a failed or cancelled outgoing file again as a new transfer; null if there's nothing to retry. */
    fun retry(id: String): String? {
        val old = _snapshot.value.history.firstOrNull {
            it.id == id && it.direction == Direction.ToMac && it.source != null && (it.phase == Phase.Failed || it.phase == Phase.Cancelled)
        } ?: return null
        return enqueue(listOf(OutgoingFile(old.source!!, old.name, old.size, old.mime))).single()
    }

    /** Offers queued files while fewer than 3 outgoing transfers hold a slot (spec §2.3). */
    private fun pump() {
        if (!connected) return
        while (active.count { it.direction == Direction.ToMac && it.occupiesSlot } < MAX_ACTIVE_OUTGOING) {
            val next = active.firstOrNull { it.direction == Direction.ToMac && it.phase == Phase.Queued } ?: break
            update(next.id) { it.copy(phase = Phase.Offered) }
            offer(next)
        }
        persist()
    }

    private fun offer(record: TransferRecord) {
        val sent = send(Message.TransferOffer(TransferOfferBody(record.id, record.name, record.size, record.mime)))
        if (!sent) update(record.id) { it.copy(phase = Phase.Paused) }
    }

    private fun onAccept(id: String) {
        val record = record(id)?.takeIf { it.direction == Direction.ToMac && !it.isFinished } ?: return
        update(record.id) { it.copy(phase = Phase.Active) }
        val previous = jobs.remove(id)
        jobs[id] = scope.launch(control) {
            previous?.cancelAndJoin()
            runUpload(id)
        }
    }

    private suspend fun runUpload(id: String) {
        var attempts = 0
        while (true) {
            val record = record(id) ?: return
            when (val outcome = withContext(Dispatchers.IO) { uploadOnce(record) }) {
                is Outcome.Complete -> {
                    update(id) { it.copy(phase = Phase.Verifying, bytesDone = it.size) }
                    clearRate(id)
                    persist()
                    jobs.remove(id)
                    send(Message.TransferFinish(TransferFinishBody(id, outcome.sha256)))
                    return
                }
                is Outcome.Local -> return failOutgoing(id, Failure.SourceUnavailable)
                Outcome.Gone -> {
                    // The Mac forgot this id (e.g. its 24 h cleanup): offer again, which starts over (spec §2.2).
                    jobs.remove(id)
                    update(id) { it.copy(phase = Phase.Offered) }
                    offer(record)
                    return
                }
                // 409: the Mac's offset moved (a stale request). Re-HEAD right away, a few times at most.
                Outcome.Conflict -> if (++attempts > maxRetries) {
                    pauseForRetry(id, attempts) // gives up until the next reconnect's re-offer
                    return
                }
                Outcome.Network -> if (!pauseForRetry(id, ++attempts)) return
            }
        }
    }

    private suspend fun uploadOnce(record: TransferRecord): Outcome = try {
        val offset = files.uploadOffset(record.id)
        if (offset > record.size) {
            Outcome.Gone
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            local { sources.open(record.source ?: throw IOException("no source")) }.use { input ->
                val hashed = local { digestAll(input, digest, limit = offset) } // re-hash the bytes the Mac already has
                if (hashed < offset) throw LocalIoException(EOFException("the file got shorter"))
                if (offset < record.size) {
                    val macHas = files.upload(record.id, record.size, offset, input, digest) { progress(record.id, it) }
                    if (macHas < record.size) return Outcome.Network
                }
            }
            Outcome.Complete(digest.hex())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LocalIoException) {
        Outcome.Local(Failure.SourceUnavailable)
    } catch (e: TransferHttpException) {
        when (e.code) {
            404 -> Outcome.Gone
            409 -> Outcome.Conflict
            else -> Outcome.Network
        }
    } catch (e: IOException) {
        Outcome.Network
    }

    private fun failOutgoing(id: String, failure: Failure) {
        val record = record(id) ?: return
        jobs.remove(id)
        archive(record.copy(phase = Phase.Failed, failure = failure))
        sendOrQueue(Message.TransferCancel(TransferIdBody(id)))
        pump()
    }

    private fun onReject(body: TransferRejectBody) {
        val record = record(body.transferId)?.takeIf { it.direction == Direction.ToMac } ?: return
        val failure = when (body.reason) {
            TransferRejectReason.NoSpace -> Failure.NoSpace
            TransferRejectReason.Declined -> Failure.Declined
            TransferRejectReason.Invalid -> Failure.Invalid
        }
        jobs.remove(record.id)?.cancel()
        archive(record.copy(phase = Phase.Failed, failure = failure))
        pump()
    }

    private fun onResult(body: TransferResultBody) {
        val record = record(body.transferId)?.takeIf { it.direction == Direction.ToMac } ?: return
        jobs.remove(record.id)?.cancel()
        archive(
            if (body.ok) {
                record.copy(phase = Phase.Done, bytesDone = record.size)
            } else {
                record.copy(phase = Phase.Failed, failure = when (body.error) {
                    TransferResultError.ChecksumMismatch -> Failure.ChecksumMismatch
                    TransferResultError.NoSpace -> Failure.NoSpace
                    TransferResultError.Io, null -> Failure.Io
                })
            },
        )
        pump()
    }

    // endregion
```

`return Outcome.Network` inside `use { }` returns from `uploadOnce` (a non-local return from an inline lambda), which is intended.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew :core:test` → BUILD SUCCESSFUL. All engine tests (receive and send) pass, no warnings.

- [ ] **Step 5: Handoff and commit**
  - **CHANGELOG.md:** add the bullet: "Task 12: `TransferEngine` sending: FIFO with 3 slots, HEAD-offset resume with prefix re-hash, straight-to-finish when the Mac has everything, re-offer on reconnect and on 404, retry, queued cancels. Verified: `TransferEngineSendTest`."

```bash
git add android/core docs/handoff
git commit -m "feat(android): TransferEngine sending with queue, resume and retry"
```

---

### Task 13: Cross-platform — real files both ways, killed mid-file

This is the real Swift server against the real Kotlin engine. A 24 MiB file goes each way at the same time. The phone drops its connection once both are a few MB in, and both transfers must resume and verify.

**Files:**
- Modify: `mac/MacDroidCore/Sources/MacDroidDevServer/main.swift`
- Modify: `android/core/src/test/kotlin/dev/macdroid/core/CrossPlatformTest.kt`
- Modify: `android/core/build.gradle.kts`
- Modify: `scripts/cross-platform-test.sh`
- Modify: `docs/handoff/DEV-GUIDE.md`, `STATUS.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: `TransferManager`, `MacDroidServer(transfers:)`, `TransferEngine`, `TransferClient`, `FileEndpoint`, `FolderSaveTarget`, `FileSourceOpener`.
- Produces:
  - Dev server flags: `--receive-dir <dir>` and `--send-on-connect <file>`.
  - Dev server log lines: `RECEIVED <name> size=<n>` and `SENT <name>`.
  - System properties: `macdroidPhoneFile` and `macdroidPhoneReceiveDir`.

- [ ] **Step 1: Give the dev server a `TransferManager`** (`main.swift`)

Replace everything after `let hub = …` with:

```swift
let receiveDir = URL(fileURLWithPath: value(after: "--receive-dir") ?? NSTemporaryDirectory() + "macdroid-received")
let sendOnConnect = value(after: "--send-on-connect").map { URL(fileURLWithPath: $0) }
try FileManager.default.createDirectory(at: receiveDir, withIntermediateDirectories: true)

struct FolderLocation: ReceiveLocationProvider {
    let folder: URL
    func saveLocation(forName name: String, size: Int64) async -> SaveLocation? { .folder(folder) }
}

let transfers = TransferManager(
    stateDirectory: URL(fileURLWithPath: NSTemporaryDirectory()).appending(path: "macdroid-devserver-\(UUID().uuidString)"),
    location: FolderLocation(folder: receiveDir),
    send: { try await hub.send($0) }
)
await transfers.attach(to: hub)

let window = await pairing.openWindow()
print("PAIRING_URI \(PairingInvite(host: host, port: port, fingerprint: identity.fingerprint, code: window.code, name: macName).uri)")

let statusPrinter = Task {
    var sentFile = false
    for await status in hub.statusUpdates {
        switch status {
        case .disconnected:
            print("STATUS disconnected")
        case .connected(let peer):
            print("STATUS connected device=\(peer.device) battery=\(peer.battery.map(String.init) ?? "-")")
            if !sentFile, let sendOnConnect {
                sentFile = true
                _ = await transfers.send(files: [sendOnConnect])
            }
        }
    }
}

let transferPrinter = Task {
    for await event in transfers.events {
        if case .received(let record) = event { print("RECEIVED \(record.name) size=\(record.size)") }
    }
}

let sentPrinter = Task {
    var printed = Set<String>()
    for await snapshot in transfers.snapshots {
        for record in snapshot.history where record.direction == .toPhone && record.phase == .done && !printed.contains(record.id) {
            printed.insert(record.id)
            print("SENT \(record.name)")
        }
    }
}

let server = MacDroidServer(identity: identity, pairing: pairing, hub: hub, macName: macName,
                            configuration: ServerConfiguration(host: "0.0.0.0", port: port), transfers: transfers)
try await server.run { boundPort in print("LISTENING \(boundPort)") }
statusPrinter.cancel()
transferPrinter.cancel()
sentPrinter.cancel()
```

(The `PAIRING_URI` print moves below the manager set-up; remove the old copy.)

- [ ] **Step 2: Pass the new environment variables to the tests** (`android/core/build.gradle.kts`, inside `tasks.test { }`)

```kotlin
    providers.environmentVariable("MACDROID_PHONE_FILE").orNull?.let { systemProperty("macdroidPhoneFile", it) }
    providers.environmentVariable("MACDROID_PHONE_RECEIVE_DIR").orNull?.let { systemProperty("macdroidPhoneReceiveDir", it) }
```

- [ ] **Step 3: Extend `CrossPlatformTest`**

The existing test pairs one phone and connects it. File transfer runs inside that same test and connection, because the pairing window closes after the first pairing. Add this helper class at the bottom of `CrossPlatformTest.kt`:

```kotlin
/** A slowed-down transport, so the test can cut the connection mid-file. Records which offset each attempt started from. */
private class SlowTransport(private val delegate: FileTransport) : FileTransport {
    val uploadStarts = CopyOnWriteArrayList<Long>()
    val downloadStarts = CopyOnWriteArrayList<Long>()

    override suspend fun uploadOffset(id: String) = delegate.uploadOffset(id)

    override suspend fun upload(id: String, size: Long, offset: Long, input: InputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        uploadStarts += offset
        val slow = object : FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                return super.read(b, off, minOf(len, 32 * 1024))
            }
        }
        return delegate.upload(id, size, offset, slow, digest, onProgress)
    }

    override suspend fun download(id: String, size: Long, offset: Long, output: OutputStream, digest: MessageDigest, onProgress: (Long) -> Unit): Long {
        downloadStarts += offset
        val slow = object : FilterOutputStream(output) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                Thread.sleep(5)
                out.write(b, off, len)
            }
        }
        return delegate.download(id, size, offset, slow, digest, onProgress)
    }
}
```

In `pairsConnectsAndReportsBatteryToSwiftServer`, replace everything from `val manager = ConnectionManager(` to the end of the method with:

```kotlin
        val store = InMemoryPairingStore(result.mac)
        val manager = ConnectionManager(store, { phone }, MacLocator.None, { HelloBody(app = "test", device = "MacDroid Test Phone") })
        val phoneFile = System.getProperty("macdroidPhoneFile")
        val receiveDir = System.getProperty("macdroidPhoneReceiveDir")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = SlowTransport(TransferClient(FileEndpoint(store, { phone })::current))
        val engine = TransferEngine(
            scope = scope, store = InMemoryTransferStore(), files = transport, sources = FileSourceOpener(),
            saveTarget = { FolderSaveTarget(File(receiveDir ?: System.getProperty("java.io.tmpdir"))) },
            parts = { FolderSaveTarget.reopen(it) }, send = manager::send, retryDelayMs = 200,
        )
        engine.start(manager.incoming, manager.state.map { it is ConnectionState.Connected }) // before connecting: incoming has no replay
        manager.start()
        try {
            assertEquals(ConnectionState.Connected("MacDroid Dev Server"), withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } })
            assertTrue(manager.send(Message.DeviceStatus(DeviceStatusBody(77, false))))
            delay(500)
            if (phoneFile != null && receiveDir != null) sendFilesBothWaysWithADrop(manager, engine, transport, File(phoneFile))
        } finally {
            manager.stop()
            scope.cancel()
        }
    }

    /** The dev server sends its file when the phone connects; the phone sends one back. Cut the link mid-file and expect both to resume. */
    private suspend fun sendFilesBothWaysWithADrop(manager: ConnectionManager, engine: TransferEngine, transport: SlowTransport, file: File) {
        engine.enqueue(listOf(OutgoingFile(file.path, file.name, file.length(), "application/octet-stream")))
        withTimeout(30_000) {
            engine.snapshot.first { s ->
                s.active.any { it.direction == Direction.ToMac && it.bytesDone > 2_000_000 } &&
                    s.active.any { it.direction == Direction.FromMac && it.bytesDone > 2_000_000 }
            }
        }
        manager.stop()
        delay(500)
        manager.start()
        withTimeout(120_000) { engine.snapshot.first { s -> s.history.count { it.phase == Phase.Done } == 2 } }
        assertTrue("upload resumed: ${transport.uploadStarts}", transport.uploadStarts.any { it > 0 })
        assertTrue("download resumed: ${transport.downloadStarts}", transport.downloadStarts.any { it > 0 })
    }
```

Add these imports: `dev.macdroid.core.transfer.*`, `java.io.File`, `java.io.FilterInputStream`, `java.io.FilterOutputStream`, `java.io.InputStream`, `java.io.OutputStream`, `java.security.MessageDigest`, `java.util.concurrent.CopyOnWriteArrayList`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.cancel`, `kotlinx.coroutines.flow.map`.

Run without the script, the file part is skipped (`phoneFile` is null), so `./gradlew :core:test` stays fast.

- [ ] **Step 4: Extend `scripts/cross-platform-test.sh`**

Replace the dev-server start line and the `trap` with:

```bash
WORK="$(mktemp -d)"
head -c 25165824 /dev/urandom > "$WORK/mac-to-phone.bin"
head -c 25165824 /dev/urandom > "$WORK/phone-to-mac.bin"
mkdir -p "$WORK/mac-received" "$WORK/phone-received"

"$ROOT/mac/MacDroidCore/.build/debug/macdroid-devserver" --host 127.0.0.1 --port 47999 \
  --receive-dir "$WORK/mac-received" --send-on-connect "$WORK/mac-to-phone.bin" >"$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; rm -rf "$LOG" "$WORK"' EXIT
```

Keep the `LISTENING` wait and the `URI=` extraction. Replace the Gradle line and the checks after it with:

```bash
(cd "$ROOT/android" && MACDROID_DEV_URI="$URI" MACDROID_PHONE_FILE="$WORK/phone-to-mac.bin" MACDROID_PHONE_RECEIVE_DIR="$WORK/phone-received" \
  ./gradlew -q :core:test --tests 'dev.macdroid.core.CrossPlatformTest' --rerun)

grep -q 'STATUS connected device=MacDroid Test Phone' "$LOG" || { echo "server never saw the phone"; cat "$LOG"; exit 1; }
grep -q 'battery=77' "$LOG" || { echo "server never got the battery update"; cat "$LOG"; exit 1; }
grep -q 'RECEIVED phone-to-mac.bin size=25165824' "$LOG" || { echo "Mac didn't receive the phone's file"; cat "$LOG"; exit 1; }
grep -q 'SENT mac-to-phone.bin' "$LOG" || { echo "the phone never confirmed the Mac's file"; cat "$LOG"; exit 1; }
cmp "$WORK/phone-to-mac.bin" "$WORK/mac-received/phone-to-mac.bin" || { echo "phone → Mac file differs"; exit 1; }
cmp "$WORK/mac-to-phone.bin" "$WORK/phone-received/mac-to-phone.bin" || { echo "Mac → phone file differs"; exit 1; }
echo "cross-platform test passed"
```

- [ ] **Step 5: Run it**

Run: `./scripts/cross-platform-test.sh`
Expected: `cross-platform test passed`. The log shows `RECEIVED phone-to-mac.bin size=25165824` and `SENT mac-to-phone.bin`, and both `cmp` checks are silent.

- [ ] **Step 6: Handoff and commit**
  - **DEV-GUIDE.md:** under "Build and test", change the cross-platform comment to `# Swift server ↔ Kotlin client end to end (pairing, battery, and 24 MiB files both ways with a mid-file disconnect; ~1 min)`. Document the dev server's `--receive-dir` and `--send-on-connect` flags.
  - **STATUS.md:** under "Working and verified → Automated tests", add "Cross-platform file transfer both ways with resume after a dropped connection".
  - **CHANGELOG.md:** add the bullet: "Task 13: cross-platform test sends 24 MiB each way, drops the connection mid-file, and checks both resumed and match byte for byte. Verified: `./scripts/cross-platform-test.sh`."

```bash
git add mac/MacDroidCore android/core scripts docs/handoff
git commit -m "test: cross-platform file transfer both ways with mid-file disconnect"
```

---

### Task 14: Final gates and handoff for Plan 2a

**Files:**
- Modify: `docs/handoff/CHANGELOG.md`, `STATUS.md`, `ROADMAP.md`, `README.md`

- [ ] **Step 1: Run every gate**

```bash
cd mac/MacDroidCore && swift test
cd ../ && xcodegen generate && xcodebuild -project MacDroid.xcodeproj -scheme MacDroid -configuration Debug -derivedDataPath build build
cd ../android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
cd .. && ./scripts/cross-platform-test.sh
```

Expected: every command succeeds with no warnings. Record the Swift test count.

- [ ] **Step 2: Update the handoff**
  - **CHANGELOG.md:** rename the branch entry's heading to `## 2026-10-13 — Plan 2a: file transfer engine (done on feat/file-transfer)`. Add a "Verified" line with the four gates and the Swift test count.
  - **STATUS.md:**
    - In flight: "Plan 2a (engine) done on `feat/file-transfer`; Plan 2b (UI and platform glue) next. Nothing user-visible yet, so the branch isn't merged."
    - Update the Swift test count.
  - **ROADMAP.md:** in "Next", replace the plan step with "Plan 2a done; write and run Plan 2b (`docs/superpowers/plans/2026-10-13-macdroid-file-transfer-2b-ui.md`) on the same branch, then the owner's hands-on check, then merge".
  - **README.md:** set the Plan 2 row to "**Engine done (2a) on `feat/file-transfer`; UI (2b) next**".

- [ ] **Step 3: Commit**

```bash
git add docs/handoff
git commit -m "docs(handoff): Plan 2a engine complete"
```
