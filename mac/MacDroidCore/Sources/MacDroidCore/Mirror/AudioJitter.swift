public enum AudioDecision: Equatable, Sendable { case schedule(startPlayback: Bool), drop }

/// PCM scheduling (spec §6.4): start once 40 ms are queued, skip chunks while more than 100 ms are queued
/// (sound stays in sync instead of drifting), and rebuild the cushion after running dry.
public struct AudioJitter: Sendable {
    public static let targetMillis = 40.0
    public static let maxMillis = 100.0
    public private(set) var playing = false

    public init() {}

    public mutating func decide(bufferedMillis: Double, chunkMillis: Double) -> AudioDecision {
        if playing && bufferedMillis <= 0 { playing = false }
        if bufferedMillis > Self.maxMillis { return .drop }
        if !playing && bufferedMillis + chunkMillis >= Self.targetMillis {
            playing = true
            return .schedule(startPlayback: true)
        }
        return .schedule(startPlayback: false)
    }
}
