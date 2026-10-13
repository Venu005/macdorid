import Foundation
import NIOConcurrencyHelpers
import Testing
@testable import MacDroidCore

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

/// Collects values from a stream so tests can poll them with `eventually`.
actor Collector<T: Sendable> {
    private(set) var items: [T] = []
    func append(_ item: T) { items.append(item) }
}

func makeTemporaryDirectory() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appending(path: "macdroid-tests-\(UUID().uuidString)", directoryHint: .isDirectory)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// Deterministic bytes, so failures are reproducible.
func testBytes(_ count: Int, seed: UInt8 = 7) -> Data {
    Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ Int(seed)) })
}

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
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? { location }
}

/// A provider that waits until the test answers, like a save panel. Records the offers it was asked about and which were cancelled.
actor GateLocation: ReceiveLocationProvider {
    private(set) var asked: [IncomingOffer] = []
    private(set) var cancelled: [String] = []
    private var waiting: [String: CheckedContinuation<SaveLocation?, Never>] = [:]

    func saveLocation(for offer: IncomingOffer) async -> SaveLocation? {
        asked.append(offer)
        let id = offer.id
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                if cancelled.contains(id) { continuation.resume(returning: nil) } else { waiting[id] = continuation }
            }
        } onCancel: {
            Task { await self.cancelQuestion(id) }
        }
    }

    func answer(_ id: String, with location: SaveLocation?) { waiting.removeValue(forKey: id)?.resume(returning: location) }

    private func cancelQuestion(_ id: String) {
        cancelled.append(id)
        waiting.removeValue(forKey: id)?.resume(returning: nil)
    }
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

/// True once `count` transfers are active and every incoming one has been decided (it's no longer waiting for a save location).
func decided(_ manager: TransferManager, count: Int = 1) async -> Bool {
    let active = await manager.snapshot.active
    return active.count == count && active.allSatisfy { $0.phase != .offered || $0.direction == .toPhone }
}
