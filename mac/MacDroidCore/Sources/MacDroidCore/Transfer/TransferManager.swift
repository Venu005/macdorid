import Foundation
import Logging

/// Where an incoming file goes.
public enum SaveLocation: Equatable, Sendable {
    /// Pick a free name in this folder.
    case folder(URL)
    /// The user chose this exact file in a save panel; replace it if it exists.
    case file(URL)
}

/// An offer from the phone that needs a place to be saved.
public struct IncomingOffer: Equatable, Sendable {
    public let id: String
    /// Already sanitised (spec §2.3).
    public let name: String
    public let size: Int64
    public let mime: String

    public init(id: String, name: String, size: Int64, mime: String) {
        self.id = id
        self.name = name
        self.size = size
        self.mime = mime
    }
}

/// Decides where incoming files are saved; nil declines the file.
/// Called inside a Task that is cancelled if the offer is cancelled, so a save panel should close on cancellation.
public protocol ReceiveLocationProvider: Sendable {
    func saveLocation(for offer: IncomingOffer) async -> SaveLocation?
}

/// A resolved source file plus the call that ends access to it (security scope in the sandboxed app).
public struct AccessedFile: Sendable {
    public let url: URL
    public let stop: @Sendable () -> Void

    public init(url: URL, stop: @escaping @Sendable () -> Void) {
        self.url = url
        self.stop = stop
    }
}

/// Keeps access to outgoing files across relaunches: bookmark when queued, resolve when the phone downloads.
public protocol SourceAccess: Sendable {
    func bookmark(for url: URL) throws -> Data
    func resolve(_ bookmark: Data) throws -> AccessedFile
}

/// Plain bookmarks (tests, dev server). The sandboxed app uses security-scoped ones.
public struct PlainSourceAccess: SourceAccess {
    public init() {}
    public func bookmark(for url: URL) throws -> Data { try url.bookmarkData() }
    public func resolve(_ bookmark: Data) throws -> AccessedFile {
        var stale = false
        return AccessedFile(url: try URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &stale), stop: {})
    }
}

/// Runs file transfers with the phone (spec §2–3): the control handshake, part files, checksums,
/// the 3-outgoing limit, persistence (24 h) and history (last 100).
public actor TransferManager {
    public static let maxActiveOutgoing = 3
    public static let historyLimit = 100
    public static let stateLifetime: TimeInterval = 24 * 60 * 60

    enum RouteError: Error, Equatable {
        case notFound
        case offsetMismatch(Int64)
        case rangeNotSatisfiable
        case sourceUnavailable
    }

    struct Upload: Sendable {
        let writer: PartFileWriter
        let session: Int
    }

    struct Download: Sendable {
        let file: AccessedFile
        let size: Int64
    }

    public nonisolated var snapshots: AsyncStream<TransferSnapshot> { snapshotBroadcast.stream() }
    public nonisolated var events: AsyncStream<TransferEvent> { eventBroadcast.stream() }
    public private(set) var snapshot: TransferSnapshot

    private let snapshotBroadcast: Broadcast<TransferSnapshot>
    let eventBroadcast = Broadcast<TransferEvent>(replayLatest: false)
    private let activeStore: JSONFileStore<[TransferRecord]>
    private let historyStore: JSONFileStore<[TransferRecord]>
    private let location: any ReceiveLocationProvider
    private let clipboardDirectory: URL?
    private let photoCacheDirectory: URL?
    private let photoSaveFolder: @Sendable () async -> URL?
    /// Photo fetches the Mac asked for: transfer id → true for the hidden cache, false for the save folder.
    private var photoRequests: [String: Bool] = [:]
    let sourceAccess: any SourceAccess
    private let freeSpace: @Sendable (URL) -> Int64?
    let now: @Sendable () -> Date
    let send: @Sendable (Message) async throws -> Void
    let logger = Logger(label: "dev.macdroid.transfers")

    var active: [TransferRecord]
    var history: [TransferRecord]
    var isConnected = false
    var outbox: [Message] = []
    private var writers: [String: PartFileWriter] = [:]
    private var rates: [String: TransferRate] = [:]
    /// Undecided incoming offers: the task asking where to save, cancelled if the offer is cancelled.
    private var decisions: [String: Task<Void, Never>] = [:]
    private var lastPublish = Date.distantPast
    private var tasks: [Task<Void, Never>] = []

    public init(
        stateDirectory: URL,
        location: any ReceiveLocationProvider,
        sourceAccess: any SourceAccess = PlainSourceAccess(),
        freeSpace: @escaping @Sendable (URL) -> Int64? = TransferManager.volumeFreeSpace,
        clipboardDirectory: URL? = nil,
        photoCacheDirectory: URL? = nil,
        photoSaveFolder: @escaping @Sendable () async -> URL? = { nil },
        now: @escaping @Sendable () -> Date = { Date() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        try? FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true)
        let activeStore = JSONFileStore<[TransferRecord]>(url: stateDirectory.appending(path: "transfers.json"))
        let historyStore = JSONFileStore<[TransferRecord]>(url: stateDirectory.appending(path: "history.json"))
        let (active, history) = Self.restore(active: activeStore.load() ?? [], history: historyStore.load() ?? [], now: now())
        try? activeStore.save(active)
        try? historyStore.save(history)
        let snapshot = TransferSnapshot(active: active, history: history, rates: [:])
        self.activeStore = activeStore
        self.historyStore = historyStore
        self.location = location
        if let clipboardDirectory { try? FileManager.default.createDirectory(at: clipboardDirectory, withIntermediateDirectories: true) }
        self.clipboardDirectory = clipboardDirectory
        if let photoCacheDirectory { try? FileManager.default.createDirectory(at: photoCacheDirectory, withIntermediateDirectories: true) }
        self.photoCacheDirectory = photoCacheDirectory
        self.photoSaveFolder = photoSaveFolder
        self.sourceAccess = sourceAccess
        self.freeSpace = freeSpace
        self.now = now
        self.send = send
        self.active = active
        self.history = history
        self.snapshot = snapshot
        self.snapshotBroadcast = Broadcast(replayLatest: true, initial: snapshot, bufferingPolicy: .bufferingNewest(1))
    }

    /// Free bytes on the volume holding `url` (for the "important" usage class, which counts purgeable space).
    public static func volumeFreeSpace(_ url: URL) -> Int64? {
        (try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
    }

    /// Restores persisted state: anything older than 24 h is cleaned up, and interrupted transfers wait to resume.
    static func restore(active stored: [TransferRecord], history storedHistory: [TransferRecord], now: Date) -> ([TransferRecord], [TransferRecord]) {
        var active: [TransferRecord] = []
        var history = storedHistory
        let cutoff = now.addingTimeInterval(-stateLifetime)
        for var record in stored {
            // An offer still waiting for "where to save" when MacDroid quit was never accepted; the phone's re-offer starts fresh.
            if record.direction == .fromPhone && record.folder == nil { continue }
            // Clipboard content and cached photos are never resumed later: by then they're stale (spec §4.1 "not queued").
            if record.isHidden {
                if record.direction == .fromPhone, let part = record.partURL { try? FileManager.default.removeItem(at: part) }
                continue
            }
            if record.updatedAt < cutoff {
                if let part = record.partURL { try? FileManager.default.removeItem(at: part) }
                record.phase = .failed(.expired)
                record.updatedAt = now
                history.insert(record, at: 0)
            } else {
                if record.phase != .queued { record.phase = .paused }
                active.append(record)
            }
        }
        return (active, Array(history.prefix(historyLimit)))
    }

    /// Feeds the hub's control messages and connection changes into this manager.
    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses {
                if case .connected = status { await self?.connectionChanged(true) } else { await self?.connectionChanged(false) }
            }
        })
    }

    func handle(_ message: Message) async {
        switch message {
        case .transferOffer(let offer): await receiveOffer(offer)
        case .transferAccept(let body): accepted(body.transferId)
        case .transferReject(let body): await rejected(body)
        case .transferFinish(let body): await receiveFinish(body)
        case .transferResult(let body): await resulted(body)
        case .transferCancel(let body): await peerCancelled(body.transferId)
        default: break
        }
    }

    /// Battery updates also arrive as status changes, so only real transitions count.
    func connectionChanged(_ connected: Bool) async {
        guard connected != isConnected else { return }
        isConnected = connected
        guard connected else {
            // Clipboard content isn't queued while disconnected (spec §4.1); the phone hears the cancel on reconnect.
            for record in active where record.isClipboard && record.direction == .toPhone {
                await cancel(id: record.id)
            }
            // The Mac stops waiting for photos when the phone goes (the user is told), and the phone drops them on restart;
            // resuming one later would land a file nobody is waiting for (final review I4).
            for record in active where record.purpose == .photo {
                await cancel(id: record.id)
            }
            for index in active.indices where active[index].phase == .active || active[index].phase == .verifying {
                active[index].phase = .paused
            }
            rates.removeAll()
            persist()
            publish(force: true)
            return
        }
        let queued = outbox
        outbox.removeAll()
        for message in queued { await sendOrQueue(message) }
        await resumeOutgoing()
    }

    /// The Mac asked the phone for a photo under this transfer id (spec §3 `photos.fetch`); only such offers are accepted.
    public func expectPhoto(transferId: String, toCache: Bool) { photoRequests[transferId] = toCache }
    /// The Mac stopped waiting for this photo: refuse its offer, and stop it if it already started (follow-up M1).
    public func forgetPhoto(transferId: String) async {
        photoRequests[transferId] = nil
        if active.contains(where: { $0.id == transferId && $0.purpose == .photo }) { await cancel(id: transferId) }
    }
    /// Bytes received so far (and the total) for a photo transfer in progress; nil when there is none.
    public func photoProgress(_ id: String) -> (done: Int64, size: Int64)? {
        active.first { $0.id == id && $0.purpose == .photo }.map { ($0.bytesDone, $0.size) }
    }
    /// True while a transfer with this id is in progress (offered, active, paused or verifying).
    public func hasTransfer(_ id: String) -> Bool { active.contains { $0.id == id } }

    /// Local cancel from the UI.
    public func cancel(id: String) async {
        decisions.removeValue(forKey: id)?.cancel()
        guard let record = active.first(where: { $0.id == id }) else { return }
        await discard(record, as: .cancelled(byPeer: false))
        if record.phase != .queued { await sendOrQueue(.transferCancel(TransferID(transferId: id))) }
        await pump()
    }

    // MARK: Receiving (phone → Mac)

    private func receiveOffer(_ offer: TransferOffer) async {
        let id = offer.transferId
        if let record = active.first(where: { $0.id == id }) {
            guard record.direction == .fromPhone, record.folder != nil else { return } // undecided: the question is still open
            // A re-offer after a reconnect: accept again; the phone resumes from what's on disk (HEAD).
            update(id) { $0.phase = .active }
            await sendOrDrop(.transferAccept(TransferID(transferId: id)))
            return
        }
        if let past = history.first(where: { $0.id == id }) {
            await replayOutcome(of: past)
            return
        }
        if offer.purpose == .photo {
            // Only photos the Mac asked for are written anywhere (Review Focus #1).
            guard let toCache = photoRequests[id], offer.size >= 0, !toCache || photoCacheDirectory != nil else {
                await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .invalid)))
                return
            }
            let name = FileNaming.sanitize(offer.name)
            active.append(TransferRecord(
                id: id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime, phase: .offered,
                createdAt: now(), updatedAt: now(), purpose: .photo, hidden: toCache
            ))
            persist()
            publish(force: true)
            let incoming = IncomingOffer(id: id, name: name, size: offer.size, mime: offer.mime)
            let cache = photoCacheDirectory
            let saveFolder = photoSaveFolder
            // Saves go to the received-files folder without asking (spec §9.2). If it's unusable (nil),
            // `decide` falls back to the normal location provider.
            decisions[id] = Task {
                let folder: URL? = toCache ? cache : await saveFolder()
                await self.decide(incoming, fixed: folder.map(SaveLocation.folder))
            }
            return
        }
        let isClipboard = offer.purpose == .clipboard
        guard offer.size >= 0, !isClipboard || (offer.size <= Int64(ClipboardLimits.maxImageBytes) && clipboardDirectory != nil) else {
            await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .invalid)))
            return
        }
        let name = FileNaming.sanitize(offer.name)
        active.append(TransferRecord(
            id: id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime, phase: .offered,
            createdAt: now(), updatedAt: now(), purpose: offer.purpose
        ))
        persist()
        publish(force: true)
        // Clipboard images never ask (spec §2.3); files may wait on a save panel, so deciding runs on its own.
        let fixed: SaveLocation? = isClipboard ? clipboardDirectory.map(SaveLocation.folder) : nil
        decisions[id] = Task { await self.decide(IncomingOffer(id: id, name: name, size: offer.size, mime: offer.mime), fixed: fixed) }
    }

    private func decide(_ offer: IncomingOffer, fixed: SaveLocation? = nil) async {
        let id = offer.id
        let decision: SaveLocation?
        if let fixed { decision = fixed } else { decision = await location.saveLocation(for: offer) }
        decisions[id] = nil
        // Cancelled meanwhile (by the phone or locally): the record is already gone.
        guard !Task.isCancelled, active.contains(where: { $0.id == id && $0.phase == .offered }) else { return }
        guard let decision else {
            await reject(offer, name: offer.name, reason: .declined)
            return
        }
        let folder: URL
        let finalName: String
        let replaces: Bool
        switch decision {
        case .folder(let url):
            folder = url
            finalName = FileNaming.unique(offer.name) { isTaken($0, in: url) }
            replaces = false
        case .file(let url):
            folder = url.deletingLastPathComponent()
            if isClaimed(url.lastPathComponent, in: folder) {
                // Two save panels picked the same file: never let two transfers share one part file.
                finalName = FileNaming.unique(url.lastPathComponent) { isTaken($0, in: folder) }
                replaces = false
            } else {
                finalName = url.lastPathComponent
                replaces = true
                // Written in place: start from an empty file (the user chose to replace whatever was there).
                FileManager.default.createFile(atPath: url.path(percentEncoded: false), contents: nil)
            }
        }
        if let free = freeSpace(folder), free < offer.size {
            await reject(offer, name: finalName, reason: .noSpace)
            return
        }
        // Claim the name before any await, so a concurrent decision picks another.
        update(id) {
            $0.name = finalName
            $0.folder = folder
            $0.replacesExisting = replaces
            $0.phase = .active
        }
        guard let writer = incomingWriter(id) else { return }
        do {
            try await writer.open()
        } catch {
            logger.warning("can't create part file in \(folder.path(percentEncoded: false)): \(error)")
            writers[id] = nil
            await reject(offer, name: finalName, reason: .declined)
            return
        }
        await sendOrDrop(.transferAccept(TransferID(transferId: id)))
    }

    private func isTaken(_ name: String, in folder: URL) -> Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: folder.appending(path: name).path(percentEncoded: false))
            || fm.fileExists(atPath: folder.appending(path: name + FileNaming.partSuffix).path(percentEncoded: false))
            || isClaimed(name, in: folder)
    }

    /// Another incoming transfer already writes to this name. APFS is case-insensitive by default, so compare that way.
    private func isClaimed(_ name: String, in folder: URL) -> Bool {
        active.contains { $0.direction == .fromPhone && $0.folder == folder && $0.name.caseInsensitiveCompare(name) == .orderedSame }
    }

    private func reject(_ offer: IncomingOffer, name: String, reason: TransferRejectReason) async {
        archive(TransferRecord(
            id: offer.id, direction: .fromPhone, name: name, size: offer.size, mime: offer.mime,
            phase: .failed(TransferFailure(reason)), createdAt: now(), updatedAt: now()
        ))
        await sendOrDrop(.transferReject(TransferReject(transferId: offer.id, reason: reason)))
    }

    /// The sender re-offered an id we already finished (its result got lost): tell it the outcome again.
    private func replayOutcome(of record: TransferRecord) async {
        let id = record.id
        switch record.phase {
        case .done:
            await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: true)))
        case .failed(.declined):
            await sendOrDrop(.transferReject(TransferReject(transferId: id, reason: .declined)))
        case .failed(let failure):
            await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: false, error: failure.resultError)))
        case .cancelled:
            await sendOrDrop(.transferCancel(TransferID(transferId: id)))
        default:
            break
        }
    }

    private func incomingWriter(_ id: String) -> PartFileWriter? {
        if let writer = writers[id] { return writer }
        guard let record = active.first(where: { $0.id == id && $0.direction == .fromPhone }),
              !record.phase.isFinished, let part = record.partURL else { return nil }
        let writer = PartFileWriter(url: part, expectedSize: record.size)
        writers[id] = writer
        return writer
    }

    /// HEAD: bytes of an incoming file already on disk. Supersedes any upload still running for it.
    func uploadOffset(id: String) async -> Int64? {
        guard let writer = incomingWriter(id) else { return nil }
        do {
            _ = try await writer.beginSession()
        } catch {
            forget(id) // the part file is unreachable: a re-offer will start over
            return nil
        }
        return await writer.size
    }

    /// PUT: starts a new upload session at `start`, which must equal the bytes already on disk.
    func beginUpload(id: String, start: Int64) async throws -> Upload {
        guard let writer = incomingWriter(id) else { throw RouteError.notFound }
        let session: Int
        do {
            session = try await writer.beginSession()
        } catch {
            forget(id)
            throw RouteError.notFound
        }
        let current = await writer.size
        guard current == start else { throw RouteError.offsetMismatch(current) }
        update(id) {
            $0.phase = .active
            $0.bytesDone = current
        }
        return Upload(writer: writer, session: session)
    }

    /// Bytes moved (either direction). Returns false once the transfer is gone (cancelled), so the route stops.
    func progress(id: String, bytesDone: Int64) -> Bool {
        guard let index = active.firstIndex(where: { $0.id == id }) else { return false }
        active[index].bytesDone = bytesDone
        rates[id, default: TransferRate()].record(bytes: bytesDone, at: now())
        publish(force: false)
        return true
    }

    func uploadFailed(id: String, _ failure: TransferFailure) async {
        await failIncoming(id, failure)
    }

    private func receiveFinish(_ body: TransferFinish) async {
        let id = body.transferId
        guard let record = active.first(where: { $0.id == id && $0.direction == .fromPhone }),
              record.phase != .verifying, let writer = incomingWriter(id) else { return }
        update(id) {
            $0.phase = .verifying
            $0.expectedSHA256 = body.sha256
        }
        do {
            try await writer.open()
        } catch {
            await failIncoming(id, .io)
            return
        }
        await writer.flush()
        let size = await writer.size
        let digest = await writer.digest()
        guard size == record.size else {
            await failIncoming(id, .io)
            return
        }
        guard digest == body.sha256.lowercased() else {
            await failIncoming(id, .checksumMismatch)
            return
        }
        await writer.close()
        writers[id] = nil
        guard let current = active.first(where: { $0.id == id }), let folder = current.folder, let part = current.partURL else { return }
        let fm = FileManager.default
        var name = current.name
        do {
            // A save-panel file was written in place and is already final; a part file is renamed to a free name.
            if !current.replacesExisting {
                if fm.fileExists(atPath: folder.appending(path: name).path(percentEncoded: false)) {
                    name = FileNaming.unique(name) { isTaken($0, in: folder) }
                }
                try fm.moveItem(at: part, to: folder.appending(path: name))
            }
        } catch {
            await failIncoming(id, .io)
            return
        }
        guard var done = active.first(where: { $0.id == id }) else { return }
        done.name = name
        done.phase = .done
        done.bytesDone = done.size
        done.savedURL = folder.appending(path: name)
        archive(done)
        let savedAt = folder.appending(path: name)
        if done.purpose == .photo {
            if !done.isHidden { eventBroadcast.yield(.received(done)) }
            eventBroadcast.yield(.photoReceived(transferId: id, url: savedAt))
        } else {
            eventBroadcast.yield(done.isClipboard ? .clipboardImageReceived(savedAt) : .received(done))
        }
        await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: true)))
    }

    private func failIncoming(_ id: String, _ failure: TransferFailure) async {
        guard let record = active.first(where: { $0.id == id }) else { return }
        if let writer = writers.removeValue(forKey: id) { await writer.close() }
        if let part = record.partURL { try? FileManager.default.removeItem(at: part) }
        var failed = record
        failed.phase = .failed(failure)
        archive(failed)
        await sendOrDrop(.transferResult(TransferResult(transferId: id, ok: false, error: failure.resultError)))
    }

    private func peerCancelled(_ id: String) async {
        decisions.removeValue(forKey: id)?.cancel() // closes a save panel that's still open
        guard let record = active.first(where: { $0.id == id }) else { return }
        await discard(record, as: .cancelled(byPeer: true))
        await pump()
    }

    /// Drops a record without an outcome, so a re-offer of its id starts over.
    private func forget(_ id: String) {
        writers[id] = nil
        active.removeAll { $0.id == id }
        persist()
        publish(force: true)
    }

    // MARK: Shared helpers

    func discard(_ record: TransferRecord, as phase: TransferPhase) async {
        if let writer = writers.removeValue(forKey: record.id) { await writer.close() }
        if record.direction == .fromPhone, let part = record.partURL { try? FileManager.default.removeItem(at: part) }
        var ended = record
        ended.phase = phase
        archive(ended)
    }

    func update(_ id: String, _ change: (inout TransferRecord) -> Void) {
        guard let index = active.firstIndex(where: { $0.id == id }) else { return }
        change(&active[index])
        active[index].updatedAt = now()
        persist()
        publish(force: true)
    }

    /// Moves a finished record from active to the front of history.
    func archive(_ record: TransferRecord) {
        if record.purpose == .photo {
            photoRequests[record.id] = nil
            if record.phase != .done { eventBroadcast.yield(.photoFailed(transferId: record.id)) }
        }
        if record.isHidden {
            active.removeAll { $0.id == record.id }
            rates[record.id] = nil
            persist()
            publish(force: true)
            return
        }
        var ended = record
        ended.updatedAt = now()
        active.removeAll { $0.id == record.id }
        history.removeAll { $0.id == record.id }
        history.insert(ended, at: 0)
        if history.count > Self.historyLimit { history.removeLast(history.count - Self.historyLimit) }
        rates[record.id] = nil
        persist()
        publish(force: true)
    }

    func persist() {
        try? activeStore.save(active)
        try? historyStore.save(history)
    }

    /// Progress publishes at most 4 times a second; phase changes publish immediately.
    func publish(force: Bool) {
        let time = now()
        guard force || time.timeIntervalSince(lastPublish) >= 0.25 else { return }
        lastPublish = time
        snapshot = TransferSnapshot(
            active: active.filter { !$0.isHidden }, history: history,
            rates: rates.filter { id, _ in active.contains { $0.id == id && !$0.isHidden } }.compactMapValues(\.bytesPerSecond)
        )
        snapshotBroadcast.yield(snapshot)
    }

    func clearRate(_ id: String) { rates[id] = nil }

    func sendOrDrop(_ message: Message) async {
        try? await send(message)
    }

    /// Cancels must reach the phone eventually, so they wait for the next connection.
    func sendOrQueue(_ message: Message) async {
        do {
            try await send(message)
        } catch {
            outbox.append(message)
        }
    }
}
