import Foundation

/// Which links travel between the devices (spec §2): the first http(s) URL in a text, trailing punctuation
/// trimmed, at most 8 KB. Mirrors Kotlin `LinkExtractor` exactly.
/// Links the phone opens on the Mac: at most 3 in any 10 s, since any app on the phone can use "Open on Mac".
public struct LinkThrottle: Sendable {
    public static let limit = 3
    public static let window: TimeInterval = 10
    private var recent: [Date] = []
    public init() {}

    public mutating func allows(at now: Date) -> Bool {
        recent.removeAll { now.timeIntervalSince($0) >= Self.window }
        guard recent.count < Self.limit else { return false }
        recent.append(now)
        return true
    }
}

public enum LinkRules {
    public static let maxBytes = 8_192
    private static let pattern = try! NSRegularExpression(pattern: #"(?i)\bhttps?://[^\s<>"']+"#)
    private static let openers: [Character: Character] = [")": "(", "]": "[", "}": "{"]
    private static let trailing: Set<Character> = [".", ",", ";", ":", "!", "?", ")", "]", "}", "'", "\""]

    public static func firstLink(in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = pattern.firstMatch(in: text, range: range), let found = Range(match.range, in: text) else { return nil }
        var link = String(text[found])
        while let last = link.last, trailing.contains(last) {
            // A closing bracket the link itself opened (Wikipedia's "Mercury_(planet)") belongs to it.
            if let opener = openers[last], link.filter({ $0 == opener }).count >= link.filter({ $0 == last }).count { break }
            link.removeLast()
        }
        return allowedURL(link) == nil ? nil : link
    }

    /// The URL if it's http/https with a host and within the size limit; nil otherwise.
    public static func allowedURL(_ string: String) -> URL? {
        guard string.utf8.count <= maxBytes, let url = URL(string: string),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host(), !host.isEmpty else { return nil }
        return url
    }
}
