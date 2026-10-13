import Foundation

public struct NotificationEntry: Equatable, Sendable, Identifiable {
    public var posted: NotificationPosted
    public var receivedAt: Date
    public var id: String { posted.key }

    public init(posted: NotificationPosted, receivedAt: Date) {
        self.posted = posted
        self.receivedAt = receivedAt
    }
}

public struct NotificationGroup: Equatable, Sendable, Identifiable {
    public var packageName: String
    public var appName: String
    public var iconHash: String
    /// Newest first.
    public var entries: [NotificationEntry]
    public var id: String { packageName }

    public init(packageName: String, appName: String, iconHash: String, entries: [NotificationEntry]) {
        self.packageName = packageName
        self.appName = appName
        self.iconHash = iconHash
        self.entries = entries
    }
}

/// The Mac's list of phone notifications (spec §3.3): newest first, at most 50, in memory only.
public struct NotificationStore: Equatable, Sendable {
    public static let limit = 50
    public private(set) var entries: [NotificationEntry] = []

    public init() {}

    /// Adds a notification, or replaces the one with the same key, and moves it to the top.
    public mutating func upsert(_ posted: NotificationPosted, at date: Date) {
        entries.removeAll { $0.posted.key == posted.key }
        entries.insert(NotificationEntry(posted: posted, receivedAt: date), at: 0)
        if entries.count > Self.limit { entries.removeLast(entries.count - Self.limit) }
    }

    @discardableResult
    public mutating func remove(key: String) -> NotificationEntry? {
        guard let index = entries.firstIndex(where: { $0.posted.key == key }) else { return nil }
        return entries.remove(at: index)
    }

    /// Keeps only `keys` (spec §2.2 `notification.sync`); returns the keys removed.
    public mutating func retain(keys: Set<String>) -> [String] {
        let removed = entries.filter { !keys.contains($0.posted.key) }.map(\.posted.key)
        entries.removeAll { !keys.contains($0.posted.key) }
        return removed
    }

    /// Removes everything; returns the keys removed.
    public mutating func clear() -> [String] {
        let keys = entries.map(\.posted.key)
        entries.removeAll()
        return keys
    }

    public func entry(key: String) -> NotificationEntry? {
        entries.first { $0.posted.key == key }
    }

    /// One group per app, ordered by each app's newest notification.
    public var groups: [NotificationGroup] {
        var order: [String] = []
        var byPackage: [String: NotificationGroup] = [:]
        for entry in entries {
            let posted = entry.posted
            if byPackage[posted.packageName] == nil {
                order.append(posted.packageName)
                byPackage[posted.packageName] = NotificationGroup(
                    packageName: posted.packageName, appName: posted.appName, iconHash: posted.iconHash, entries: []
                )
            }
            byPackage[posted.packageName]?.entries.append(entry)
        }
        return order.compactMap { byPackage[$0] }
    }
}
