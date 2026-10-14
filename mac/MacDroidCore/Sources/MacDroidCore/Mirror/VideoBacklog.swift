/// What to do with an arriving video frame (spec §6.2: never queue stale video).
public enum VideoDecision: Equatable, Sendable { case show, drop, dropAndRequestKeyFrame }

/// Frames depend on earlier ones, so the Mac never drops one frame alone: with more than 3 waiting to be shown,
/// it drops everything up to the next key frame and asks the phone for one.
public struct VideoBacklog: Sendable {
    public static let maxWaiting = 3
    private var dropping = false

    public init() {}

    public mutating func admit(keyFrame: Bool, waiting: Int) -> VideoDecision {
        if keyFrame {
            dropping = false
            return .show
        }
        if dropping { return .drop }
        if waiting > Self.maxWaiting {
            dropping = true
            return .dropAndRequestKeyFrame
        }
        return .show
    }

    /// After a decoder failure or a new format: show nothing until the next key frame.
    public mutating func resync() { dropping = true }
}
