import Foundation

public enum StatusTone: Equatable, Sendable {
    case connected
    case waiting
    case notPaired
}

/// What the menu bar, popover and Phone page show for the paired phone.
public struct PhoneStatus: Equatable, Sendable {
    public var symbol: String
    public var title: String
    public var subtitle: String
    public var tone: StatusTone

    public init(symbol: String, title: String, subtitle: String, tone: StatusTone) {
        self.symbol = symbol
        self.title = title
        self.subtitle = subtitle
        self.tone = tone
    }
}

/// Pure mapping from connection state to display text. No UI framework imports.
public enum StatusPresentation {
    public static func phoneStatus(paired: PairedDevice?, connection: ConnectionStatus) -> PhoneStatus {
        guard let paired else {
            return PhoneStatus(symbol: "iphone.slash", title: "No phone paired", subtitle: "Not paired", tone: .notPaired)
        }
        switch connection {
        case .connected(let peer):
            var parts = ["Connected"]
            if let battery = peer.battery { parts.append("\(battery)%") }
            if peer.charging == true { parts.append("charging") }
            return PhoneStatus(symbol: "iphone.radiowaves.left.and.right", title: peer.device,
                               subtitle: parts.joined(separator: " · "), tone: .connected)
        case .disconnected:
            return PhoneStatus(symbol: "iphone", title: paired.name, subtitle: "Not connected", tone: .waiting)
        }
    }

    public static func batteryText(_ connection: ConnectionStatus) -> String {
        guard case .connected(let peer) = connection, let battery = peer.battery else { return "—" }
        return peer.charging == true ? "\(battery)% · charging" : "\(battery)%"
    }

    /// "m:ss", rounding up partial seconds and clamping at 0:00.
    public static func countdown(remaining: TimeInterval) -> String {
        let seconds = max(0, Int(remaining.rounded(.up)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
