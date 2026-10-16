import Foundation

/// Whether received files are still where they were saved. Rows use a cached answer, so a long history doesn't stat
/// every file on every update; actions (Open, Reveal) always look again and refresh what the rows show.
public struct ReceivedFileChecks {
    private let fileExists: (URL) -> Bool
    private var cache: [String: (url: URL, exists: Bool)] = [:]

    public init(fileExists: @escaping (URL) -> Bool = { FileManager.default.fileExists(atPath: $0.path(percentEncoded: false)) }) {
        self.fileExists = fileExists
    }

    public mutating func rowSaysExists(id: String, url: URL) -> Bool {
        if let cached = cache[id], cached.url == url { return cached.exists }
        return existsNow(id: id, url: url)
    }

    public mutating func existsNow(id: String, url: URL) -> Bool {
        let exists = fileExists(url)
        cache[id] = (url, exists)
        return exists
    }

    public mutating func clear() { cache.removeAll() }
}
