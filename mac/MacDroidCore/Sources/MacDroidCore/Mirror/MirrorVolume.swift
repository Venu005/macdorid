/// Where the mirror window's Volume −/+ go (owner ruling 2026-10-16): the Mac player while it plays the phone's sound.
public enum MirrorVolumeRoute: Equatable, Sendable { case mac, phone }

public enum MirrorVolume {
    /// 16 steps, like the macOS volume keys.
    public static let steps: Float = 16

    public static func route(audioOnMac: Bool) -> MirrorVolumeRoute { audioOnMac ? .mac : .phone }

    public static func step(_ current: Float, up: Bool) -> Float {
        let level = (current * steps).rounded() + (up ? 1 : -1)
        return min(max(level, 0), steps) / steps
    }
}
