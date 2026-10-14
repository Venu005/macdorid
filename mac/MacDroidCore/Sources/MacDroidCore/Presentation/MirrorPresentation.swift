/// Text for the mirror window (spec §6.3, §8).
public enum MirrorPresentation {
    public enum LatencyTone: Equatable, Sendable { case good, fair, poor }

    /// While streaming but no frame has arrived for over a second (final review I5).
    public static func status(_ state: MirrorState, stalled: Bool) -> String? {
        if stalled, case .streaming = state.phase { return "Reconnecting…" }
        return status(state)
    }

    public static func status(_ state: MirrorState) -> String? {
        switch state.phase {
        case .idle, .streaming: nil
        case .starting(let waitingForTap): waitingForTap ? "Tap Start on your phone to begin mirroring." : "Starting…"
        case .stopped(.declined): "Mirroring was declined on the phone."
        case .stopped(.consentTimeout), .stopped(.noPermission): "Mirroring didn't start"
        case .stopped(.disconnected): "Phone not connected"
        case .stopped: "Mirroring stopped"
        }
    }

    public static func audioNotice(_ state: MirrorState, requested: Bool) -> String? {
        guard requested, case .streaming = state.phase, state.audio == false else { return nil }
        return "Phone audio is off: allow the microphone permission for MacDroid on your phone"
    }

    /// Green under 100 ms, orange under 150, red above (spec §6.3).
    public static func latencyTone(_ ms: Int) -> LatencyTone { ms < 100 ? .good : ms < 150 ? .fair : .poor }
}
