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
