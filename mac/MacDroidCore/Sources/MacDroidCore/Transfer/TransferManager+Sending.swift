import Foundation
import UniformTypeIdentifiers

extension TransferManager {
    /// Queues files to send to the phone and returns their transfer ids. Folders and unreadable files are skipped.
    public func send(files urls: [URL]) async -> [String] {
        await sendReporting(files: urls).ids
    }

    /// Like `send(files:)`, but also says which files couldn't be queued and why, so the app can tell the user.
    public func sendReporting(files urls: [URL]) async -> SendReport {
        var ids: [String] = []
        var skipped: [SendReport.Skipped] = []
        for url in urls {
            let name = url.lastPathComponent
            let values: URLResourceValues
            let bookmark: Data
            do {
                values = try url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
                guard values.isDirectory != true else {
                    skipped.append(.init(name: name, reason: "folders can't be sent yet"))
                    continue
                }
                bookmark = try sourceAccess.bookmark(for: url)
            } catch {
                logger.warning("can't send \(url.path(percentEncoded: false)): \(error)")
                skipped.append(.init(name: name, reason: error.localizedDescription))
                continue
            }
            guard let size = values.fileSize else {
                skipped.append(.init(name: name, reason: "its size can't be read"))
                continue
            }
            let record = TransferRecord(
                id: Self.newID(), direction: .toPhone, name: url.lastPathComponent, size: Int64(size),
                mime: Self.mimeType(for: url), phase: .queued, sourceBookmark: bookmark, createdAt: now(), updatedAt: now()
            )
            active.append(record)
            ids.append(record.id)
        }
        persist()
        publish(force: true)
        await pump()
        return SendReport(ids: ids, skipped: skipped)
    }

    /// Queues a clipboard image (a PNG the app wrote to its clipboard folder) for the phone (spec §2.3).
    /// Returns false if it can't be read or is over the 10 MB limit.
    public func sendClipboardImage(_ url: URL) async -> Bool {
        guard let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize,
              size <= ClipboardLimits.maxImageBytes,
              let bookmark = try? sourceAccess.bookmark(for: url) else { return false }
        active.append(TransferRecord(
            id: Self.newID(), direction: .toPhone, name: "clipboard.png", size: Int64(size), mime: "image/png",
            phase: .queued, sourceBookmark: bookmark, createdAt: now(), updatedAt: now(), purpose: .clipboard
        ))
        persist()
        publish(force: true)
        await pump()
        return true
    }

    /// Sends a failed or cancelled outgoing file again, as a new transfer.
    public func retry(id: String) async {
        guard let old = history.first(where: { $0.id == id && $0.direction == .toPhone }), let bookmark = old.sourceBookmark else { return }
        active.append(TransferRecord(
            id: Self.newID(), direction: .toPhone, name: old.name, size: old.size, mime: old.mime,
            phase: .queued, sourceBookmark: bookmark, createdAt: now(), updatedAt: now()
        ))
        persist()
        publish(force: true)
        await pump()
    }

    /// Offers queued files while fewer than 3 outgoing transfers hold a slot (spec §2.3).
    func pump() async {
        while isConnected,
              active.filter({ $0.direction == .toPhone && $0.phase.occupiesSlot }).count < Self.maxActiveOutgoing,
              let next = active.first(where: { $0.direction == .toPhone && $0.phase == .queued }) {
            update(next.id) { $0.phase = .offered }
            await offer(next)
        }
    }

    /// After a reconnect: offer every unfinished outgoing transfer again under its id, then fill free slots.
    func resumeOutgoing() async {
        for record in active where record.direction == .toPhone && record.phase.occupiesSlot {
            update(record.id) { $0.phase = .offered }
            await offer(record)
        }
        await pump()
    }

    private func offer(_ record: TransferRecord) async {
        do {
            try await send(.transferOffer(TransferOffer(transferId: record.id, name: record.name, size: record.size, mime: record.mime, purpose: record.purpose)))
        } catch {
            update(record.id) { $0.phase = .paused }
        }
    }

    func accepted(_ id: String) {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }),
              record.phase == .offered || record.phase == .paused else { return }
        update(id) { $0.phase = .active }
    }

    func rejected(_ body: TransferReject) async {
        guard var record = active.first(where: { $0.id == body.transferId && $0.direction == .toPhone }) else { return }
        record.phase = .failed(TransferFailure(body.reason))
        archive(record)
        await pump()
    }

    func resulted(_ body: TransferResult) async {
        guard var record = active.first(where: { $0.id == body.transferId && $0.direction == .toPhone }) else { return }
        if body.ok {
            record.phase = .done
            record.bytesDone = record.size
        } else {
            record.phase = .failed(TransferFailure(body.error))
        }
        archive(record)
        await pump()
    }

    /// GET: resolves the source for a download from `start`. The phone may GET before we've processed its accept.
    func beginDownload(id: String, from start: Int64) async throws -> Download {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }),
              record.phase != .queued, !record.phase.isFinished else { throw RouteError.notFound }
        guard start >= 0, start <= record.size else { throw RouteError.rangeNotSatisfiable }
        let file: AccessedFile
        do {
            guard let bookmark = record.sourceBookmark else { throw CocoaError(.fileNoSuchFile) }
            file = try sourceAccess.resolve(bookmark)
        } catch {
            logger.warning("can't reopen \(record.name) for the phone: \(error)")
            await sourceFailed(id: id)
            throw RouteError.sourceUnavailable
        }
        let size = (try? file.url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init)
        guard size == record.size else {
            file.stop()
            await sourceFailed(id: id)
            throw RouteError.sourceUnavailable
        }
        update(id) {
            $0.phase = .active
            $0.bytesDone = start
        }
        return Download(file: file, size: record.size)
    }

    /// A GET reached the end of the file: tell the phone the checksum. Sent after every complete GET (spec §2.2).
    func downloadCompleted(id: String, sha256: String) async {
        guard let record = active.first(where: { $0.id == id && $0.direction == .toPhone }), !record.phase.isFinished else { return }
        update(id) {
            $0.phase = .verifying
            $0.bytesDone = $0.size
        }
        clearRate(id)
        await sendOrDrop(.transferFinish(TransferFinish(transferId: id, sha256: sha256)))
    }

    /// The source file is gone or changed: fail the transfer and tell the phone to stop.
    func sourceFailed(id: String) async {
        guard var record = active.first(where: { $0.id == id }) else { return }
        record.phase = .failed(.sourceUnavailable)
        archive(record)
        await sendOrQueue(.transferCancel(TransferID(transferId: id)))
        await pump()
    }

    static func mimeType(for url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    static func newID() -> String { UUID().uuidString.lowercased() }
}
