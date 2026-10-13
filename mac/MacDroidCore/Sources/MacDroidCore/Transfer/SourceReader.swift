import Crypto
import Foundation

enum SourceReaderError: Error {
    case badOffset
    /// The file is shorter than when the transfer started.
    case changed
}

/// Reads an outgoing file from `offset` onward. The running SHA-256 already covers bytes before `offset`,
/// so `digest()` is the whole file's checksum once reading reaches the end.
/// Used by one response-writer task at a time.
final class SourceReader: @unchecked Sendable {
    let size: Int64
    private(set) var position: Int64
    private let handle: FileHandle
    private var hasher = SHA256()

    init(url: URL, offset: Int64) throws {
        handle = try FileHandle(forReadingFrom: url)
        size = Int64(try handle.seekToEnd())
        guard offset >= 0, offset <= size else { throw SourceReaderError.badOffset }
        try handle.seek(toOffset: 0)
        var remaining = offset
        while remaining > 0 {
            let chunk = try handle.read(upToCount: Int(min(remaining, 1 << 20))) ?? Data()
            if chunk.isEmpty { throw SourceReaderError.changed }
            hasher.update(data: chunk)
            remaining -= Int64(chunk.count)
        }
        position = offset
    }

    /// The next chunk (up to `maxBytes`), or nil at the end of the file.
    func next(maxBytes: Int = 1 << 20) throws -> Data? {
        guard position < size else { return nil }
        let chunk = try handle.read(upToCount: Int(min(Int64(maxBytes), size - position))) ?? Data()
        if chunk.isEmpty { throw SourceReaderError.changed }
        hasher.update(data: chunk)
        position += Int64(chunk.count)
        return chunk
    }

    func digest() -> String { hasher.finalize().hex }

    func close() { try? handle.close() }
}
