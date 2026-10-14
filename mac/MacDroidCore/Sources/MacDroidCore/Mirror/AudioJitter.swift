public enum AudioDecision: Equatable, Sendable { case schedule(startPlayback: Bool), drop }

/// PCM scheduling (spec §6.4): start once 40 ms are queued; past 80 ms, drop chunks until the queue is back at 40 ms
/// (sound stays close to the picture instead of sitting at the ceiling); rebuild the cushion after running dry.
public struct AudioJitter: Sendable {
    public static let targetMillis = 40.0
    public static let maxMillis = 80.0
    public private(set) var playing = false
    private var catchingUp = false

    public init() {}

    public mutating func decide(bufferedMillis: Double, chunkMillis: Double) -> AudioDecision {
        if playing && bufferedMillis <= 0 { playing = false }
        if bufferedMillis > Self.maxMillis { catchingUp = true }
        if catchingUp {
            if bufferedMillis > Self.targetMillis { return .drop }
            catchingUp = false
        }
        if !playing && bufferedMillis + chunkMillis >= Self.targetMillis {
            playing = true
            return .schedule(startPlayback: true)
        }
        return .schedule(startPlayback: false)
    }
}
