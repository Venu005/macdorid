import Foundation

/// Text and position for the Now Playing card (spec §4).
public enum MediaPresentation {
    /// The playback position now, projected from when the Mac received the state at the phone's playback speed;
    /// capped at the duration.
    public static func position(_ state: MediaState, receivedAt: Date, now: Date) -> Int64 {
        let base = state.positionMs ?? 0
        let speed = (state.speed ?? 1) > 0 ? state.speed ?? 1 : 1
        let elapsed = state.playing == true ? Int64(max(0, now.timeIntervalSince(receivedAt)) * 1_000 * speed) : 0
        let duration = state.durationMs ?? 0
        let projected = base + elapsed
        return duration > 0 ? min(projected, duration) : projected
    }

    /// A seek the user let go of: the bar stays there until the phone's position catches up (Plan 11: it snapped back).
    public struct PendingSeek: Equatable, Sendable {
        public let targetMs: Int64
        public let at: Date
        public init(targetMs: Int64, at: Date) {
            self.targetMs = targetMs
            self.at = at
        }
    }

    public static let seekCloseEnoughMs: Int64 = 2_000
    public static let seekGiveUp: TimeInterval = 3

    public static func shown(projected: Int64, pending: PendingSeek?, now: Date) -> Int64 {
        guard let pending, pendingStillHolds(pending, projected: projected, now: now) else { return projected }
        return pending.targetMs
    }

    public static func pendingStillHolds(_ pending: PendingSeek, projected: Int64, now: Date) -> Bool {
        abs(projected - pending.targetMs) > seekCloseEnoughMs && now.timeIntervalSince(pending.at) < seekGiveUp
    }

    public static func timeLabel(_ ms: Int64) -> String {
        let total = max(0, ms / 1_000)
        let hours = total / 3_600, minutes = (total % 3_600) / 60, seconds = total % 60
        return hours > 0
            ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
            : String(format: "%d:%02d", minutes, seconds)
    }

    public static func subtitle(_ state: MediaState) -> String {
        let artist = (state.artist ?? "").trimmingCharacters(in: .whitespaces)
        let app = state.appName ?? ""
        return artist.isEmpty ? app : "\(artist) · \(app)"
    }
}
