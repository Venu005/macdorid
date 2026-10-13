import Foundation

/// One Codable value in a JSON file, written atomically. A missing or unreadable file loads as nil.
public struct JSONFileStore<Value: Codable>: Sendable {
    public let url: URL

    public init(url: URL) { self.url = url }

    public func load() -> Value? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Value.self, from: data)
    }

    public func save(_ value: Value) throws {
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
    }
}
