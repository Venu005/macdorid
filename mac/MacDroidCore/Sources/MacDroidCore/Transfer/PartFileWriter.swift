import Crypto
import Foundation

enum PartFileError: Error, Equatable {
    /// A newer upload session took over this file.
    case superseded
    /// More bytes than the offered size.
    case tooLarge
    case noSpace
    case io

    static func classify(_ error: any Error) -> PartFileError {
        let ns = error as NSError
        if ns.domain == NSPOSIXErrorDomain, ns.code == Int(ENOSPC) { return .noSpace }
        if ns.domain == NSCocoaErrorDomain, ns.code == NSFileWriteOutOfSpaceError { return .noSpace }
        if let underlying = ns.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSPOSIXErrorDomain, underlying.code == Int(ENOSPC) {
            return .noSpace
        }
        return .io
    }
}

/// Appends to an incoming `.macdroid-part` file and keeps a running SHA-256 of everything in it.
/// Each upload request is a session. A new session (HEAD or PUT) supersedes the old one, whose writes then fail,
/// so a stale connection can never interleave bytes with a resumed one.
actor PartFileWriter {
    let url: URL
    let expectedSize: Int64
    private var handle: FileHandle?
    private var hasher = SHA256()
    private(set) var size: Int64 = 0
    private var session = 0

    init(url: URL, expectedSize: Int64) {
        self.url = url
        self.expectedSize = expectedSize
    }

    /// Opens the file, creating it if needed, and re-hashes bytes already there (a resume after a relaunch).
    func open() throws {
        guard handle == nil else { return }
        let path = url.path(percentEncoded: false)
        if !FileManager.default.fileExists(atPath: path) {
            guard FileManager.default.createFile(atPath: path, contents: nil) else { throw PartFileError.io }
        }
        do {
            let handle = try FileHandle(forUpdating: url)
            var hasher = SHA256()
            var total: Int64 = 0
            while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
                hasher.update(data: chunk)
                total += Int64(chunk.count)
            }
            self.handle = handle
            self.hasher = hasher
            self.size = total
        } catch {
            throw PartFileError.classify(error)
        }
    }

    func beginSession() throws -> Int {
        try open()
        session += 1
        return session
    }

    func append(_ data: Data, session: Int) throws {
        guard session == self.session else { throw PartFileError.superseded }
        guard size + Int64(data.count) <= expectedSize else { throw PartFileError.tooLarge }
        guard let handle else { throw PartFileError.io }
        do {
            try handle.write(contentsOf: data)
        } catch {
            throw PartFileError.classify(error)
        }
        hasher.update(data: data)
        size += Int64(data.count)
    }

    func flush() { try? handle?.synchronize() }

    func digest() -> String { hasher.finalize().hex }

    func close() {
        try? handle?.close()
        handle = nil
    }
}
