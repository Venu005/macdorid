import Foundation

/// Text and position for the Now Playing card (spec §4).
public enum MediaPresentation {
    /// The playback position now, projected from when the Mac received the state; capped at the duration.
    public static func position(_ state: MediaState, receivedAt: Date, now: Date) -> Int64 {
        let base = state.positionMs ?? 0
        let elapsed = state.playing == true ? Int64(max(0, now.timeIntervalSince(receivedAt)) * 1_000) : 0
        let duration = state.durationMs ?? 0
        let projected = base + elapsed
        return duration > 0 ? min(projected, duration) : projected
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
