import Foundation

/// The phone's 256 px thumbnails (spec §5), in App Support/PhotoThumbs, trimmed least-recently-used first.
/// Stateless apart from the files themselves, so it's safe to share.
public final class ThumbnailCache: Sendable {
    public static let defaultLimit: Int64 = 200_000_000
    public static let maxThumbBytes = 512 * 1024
    public let directory: URL
    private let limit: Int64

    public init(directory: URL, limit: Int64 = ThumbnailCache.defaultLimit) {
        self.directory = directory
        self.limit = limit
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// MediaStore ids are ASCII decimal; anything else never becomes a path (Review Focus #3).
    public static func isValid(id: String) -> Bool {
        (1...20).contains(id.utf8.count) && id.utf8.allSatisfy { (48...57).contains($0) }
    }

    @discardableResult
    public func save(id: String, jpegBase64: String) -> URL? {
        guard Self.isValid(id: id), let data = Data(base64Encoded: jpegBase64), data.count <= Self.maxThumbBytes,
              data.starts(with: [0xFF, 0xD8]) else { return nil }
        let url = file(id)
        do { try data.write(to: url, options: .atomic) } catch { return nil }
        return url
    }

    /// The cached thumbnail, marked as just used.
    public func url(for id: String) -> URL? {
        guard Self.isValid(id: id) else { return nil }
        let url = file(id)
        let path = url.path(percentEncoded: false)
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: path)
        return url
    }

    /// Deletes the least recently used thumbnails until the folder fits the limit.
    public func trim() {
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .fileSizeKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys)) else { return }
        var entries = files.compactMap { url -> (url: URL, used: Date, size: Int64)? in
            guard let values = try? url.resourceValues(forKeys: keys) else { return nil }
            return (url, values.contentModificationDate ?? .distantPast, Int64(values.fileSize ?? 0))
        }
        var total = entries.reduce(Int64(0)) { $0 + $1.size }
        guard total > limit else { return }
        entries.sort { $0.used < $1.used }
        for entry in entries where total > limit {
            try? FileManager.default.removeItem(at: entry.url)
            total -= entry.size
        }
    }

    /// Forget every thumbnail (another phone was paired).
    public func clear() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for url in files { try? FileManager.default.removeItem(at: url) }
    }

    private func file(_ id: String) -> URL { directory.appending(path: id + ".jpg") }
}
