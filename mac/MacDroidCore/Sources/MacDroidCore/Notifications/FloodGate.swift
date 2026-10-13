import Foundation

/// Limits banners from chatty apps (spec §8.6): an app's notification shows a banner only if that app
/// sent at most 10 in the last 5 seconds, counting this one. The rest still reach the Notifications page.
public struct FloodGate: Sendable {
    public static let window: TimeInterval = 5
    public static let limit = 10
    private var recent: [String: [Date]] = [:]

    public init() {}

    public mutating func allowsBanner(packageName: String, at date: Date) -> Bool {
        var times = (recent[packageName] ?? []).filter { date.timeIntervalSince($0) < Self.window }
        times.append(date)
        recent[packageName] = times
        return times.count <= Self.limit
    }
}
