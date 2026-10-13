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
