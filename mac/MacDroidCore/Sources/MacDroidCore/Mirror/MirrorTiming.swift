import Foundation

/// The Mac's monotonic clock for mirroring latency, in µs (spec §6.6).
public enum MonotonicClock {
    public static func micros() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
}

/// Phone clock − Mac clock, from `mirror.ping`/`pong` round trips: the median of the last 5 (spec §6.6).
public struct ClockOffset: Sendable {
    private var samples: [Int64] = []
    public init() {}

    public mutating func add(t0: Int64, t1: Int64, t2: Int64, t3: Int64) {
        samples.append(((t1 - t0) + (t2 - t3)) / 2)
        if samples.count > 5 { samples.removeFirst(samples.count - 5) }
    }

    public var micros: Int64? {
        guard !samples.isEmpty else { return nil }
        let sorted = samples.sorted()
        return sorted[sorted.count / 2]
    }
}

/// Capture-to-display latency over the last 30 frames. Written from the video queue, read by the UI.
public final class LatencyMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var offset: Int64?
    private var samples: [Int64] = []

    public init() {}

    public func setOffset(_ micros: Int64?) { lock.withLock { offset = micros } }

    public func record(captureMicros: Int64, displayedMicros: Int64) {
        lock.withLock {
            guard let offset else { return }
            samples.append(displayedMicros - (captureMicros - offset))
            if samples.count > 30 { samples.removeFirst(samples.count - 30) }
        }
    }

    public var averageMillis: Int? {
        lock.withLock {
            guard offset != nil, !samples.isEmpty else { return nil }
            return Int(samples.reduce(0, +) / Int64(samples.count) / 1000)
        }
    }

    public func reset() {
        lock.withLock {
            offset = nil
            samples = []
        }
    }
}
