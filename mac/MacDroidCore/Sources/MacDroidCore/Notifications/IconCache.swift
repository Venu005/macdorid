import Foundation

/// App icons from the phone, cached on disk by hash (spec §2.2). The hash becomes a file name,
/// so only exactly 16 lowercase hex characters are accepted.
public struct IconCache: Sendable {
    public let directory: URL
    static let maxBytes = 256 * 1024

    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public static func isValid(hash: String) -> Bool {
        hash.count == 16 && hash.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }

    @discardableResult
    public func save(hash: String, pngBase64: String) -> Bool {
        guard Self.isValid(hash: hash),
              let data = Data(base64Encoded: pngBase64), !data.isEmpty, data.count <= Self.maxBytes else { return false }
        let url = file(hash)
        if FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) {
            // Seen again: counts as recent for `prune`.
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path(percentEncoded: false))
            return true
        }
        do {
            try data.write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    public func url(for hash: String) -> URL? {
        guard Self.isValid(hash: hash) else { return nil }
        let url = file(hash)
        return FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) ? url : nil
    }

    /// Deletes all but the `keeping` most recently saved images.
    public func prune(keeping: Int) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        let dated = files.map { ($0, (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast) }
        for (url, _) in dated.sorted(by: { $0.1 > $1.1 }).dropFirst(keeping) { try? fm.removeItem(at: url) }
    }

    private func file(_ hash: String) -> URL { directory.appending(path: hash + ".png") }
}
