import Crypto
import Foundation

/// A macOS notification category: a reply field and/or up to 3 buttons.
public struct NotificationCategorySpec: Hashable, Sendable {
    public let identifier: String
    public let canReply: Bool
    public let buttons: [String]
}

/// Category and action identifiers for mirrored notifications (spec §3.2). One category per distinct
/// (reply, button titles) combination, so the same combination reuses the same registered category.
public enum NotificationCategories {
    public static let prefix = "dev.macdroid.phone."
    public static let replyAction = "reply"
    static let actionPrefix = "action."

    public static func spec(for posted: NotificationPosted) -> NotificationCategorySpec {
        let buttons = posted.actions.prefix(3).map(\.title)
        let signature = (posted.canReply ? "r" : "n") + "|" + buttons.joined(separator: "\u{1F}")
        let digest = SHA256.hash(data: Data(signature.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        return NotificationCategorySpec(identifier: prefix + digest, canReply: posted.canReply, buttons: Array(buttons))
    }

    /// The Android action index for each button position in `spec(for:)`.
    public static func actionIndices(for posted: NotificationPosted) -> [Int] {
        posted.actions.prefix(3).map(\.index)
    }

    public static func actionIdentifier(position: Int) -> String { actionPrefix + String(position) }

    public static func position(ofAction identifier: String) -> Int? {
        guard identifier.hasPrefix(actionPrefix) else { return nil }
        return Int(identifier.dropFirst(actionPrefix.count))
    }
}
