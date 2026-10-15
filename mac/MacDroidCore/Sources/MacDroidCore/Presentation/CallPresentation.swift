import Foundation

/// Words for the call panel, the popover line, notices and dialing (Plan 10 spec §3).
public enum CallPresentation {
    public static func title(_ call: CallInfo) -> String { call.name ?? call.number ?? "Unknown caller" }

    /// The number under the name, when both are known and differ.
    public static func detail(_ call: CallInfo) -> String? {
        guard let name = call.name, let number = call.number, name != number else { return nil }
        return number
    }

    public static func duration(from start: Date, to now: Date) -> String {
        let total = max(0, Int(now.timeIntervalSince(start)))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    public static func errorNotice(_ reason: CallErrorReason, action: CallActionKind?) -> String {
        if reason == .noPermission { return "Allow MacDroid to make calls on your phone (phone Settings → Calls)" }
        switch action {
        case .decline: return "Couldn't decline — use your phone"
        case .hangUp: return "Couldn't hang up — use your phone"
        case .answer, nil: return "Couldn't answer — use your phone"
        }
    }

    /// Settings → Calls: which app opens `tel:` links. The sandbox doesn't let MacDroid make itself the handler.
    public static func phoneLinksLine(handler: String?) -> String {
        guard let handler else { return "No app opens phone links yet." }
        if handler == "MacDroid" { return "Phone links open in MacDroid." }
        return "Phone links open in \(handler). macOS doesn't let MacDroid change this itself: choose MacDroid as the default for calls in \(handler)'s settings."
    }

    public static func confirmTitle(number: String) -> String { "Call \(number) on your phone?" }

    public static func dialProblem(_ result: DialResult) -> String? {
        switch result {
        case .sent: nil
        case .notConnected: "Your phone isn't connected"
        case .unsupported: "Update MacDroid on your phone to use calls"
        case .invalidNumber: "That doesn't look like a phone number"
        }
    }
}
