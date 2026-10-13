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
