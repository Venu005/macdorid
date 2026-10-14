import Foundation

public enum PhotoFetchError: Error, Equatable, Sendable { case gone, denied, noResponse, notConnected, unsupported, failed }

public struct PhotosState: Equatable, Sendable {
    public var connected = false
    public var phoneFeatures: Set<String> = []
    /// What the phone granted, from its last album list or page; nil until it answers.
    public var access: PhotoAccess?
    public var albums: [PhotoAlbum] = []
    public var albumId = "all"
    public var kind: PhotoKindFilter = .all
    /// Newest first (DATE_ADDED, then id).
    public var items: [PhotoItem] = []
    public var hasMore = false
    public var loading = false

    public var supported: Bool { connected && phoneFeatures.contains(Feature.photos) }
    public init() {}
    public static let empty = PhotosState()
}

public enum PhotosEvent: Equatable, Sendable {
    /// A thumbnail arrived and is in the cache.
    case thumbnail(id: String, url: URL)
}

/// What `PhotosClient` needs from `TransferManager`, as closures so tests can stand in.
public struct PhotoTransfers: Sendable {
    public var expect: @Sendable (_ transferId: String, _ toCache: Bool) async -> Void
    public var forget: @Sendable (_ transferId: String) async -> Void
    public var isActive: @Sendable (_ transferId: String) async -> Bool

    public init(
        expect: @escaping @Sendable (String, Bool) async -> Void,
        forget: @escaping @Sendable (String) async -> Void,
        isActive: @escaping @Sendable (String) async -> Bool
    ) {
        self.expect = expect
        self.forget = forget
        self.isActive = isActive
    }

    public static func manager(_ manager: TransferManager) -> PhotoTransfers {
        PhotoTransfers(
            expect: { await manager.expectPhoto(transferId: $0, toCache: $1) },
            forget: { await manager.forgetPhoto(transferId: $0) },
            isActive: { await manager.hasTransfer($0) }
        )
    }
}

/// Browses the phone's photos (spec §3, §5): albums, pages, thumbnails, and fetching originals over the transfer engine.
public actor PhotosClient {
    public static let pageSize = 200
    public static let thumbBatch = 60
    public static let thumbExpiry: TimeInterval = 20
    /// The phone offers at most 3 uploads at once (`TransferEngine.MAX_ACTIVE_OUTGOING`); asking for more would time out.
    public static let maxConcurrentFetches = 3
    static let trimEvery = 500

    public nonisolated var states: AsyncStream<PhotosState> { stateBroadcast.stream() }
    public nonisolated var events: AsyncStream<PhotosEvent> { eventBroadcast.stream() }
    public private(set) var state = PhotosState.empty

    private enum PageMode { case replace, append, merge }
    private struct Pending {
        let photoId: String
        let toCache: Bool
        let continuation: CheckedContinuation<Result<URL, PhotoFetchError>, Never>
    }

    private let stateBroadcast = Broadcast<PhotosState>(replayLatest: true, initial: .empty)
    private let eventBroadcast = Broadcast<PhotosEvent>(replayLatest: false)
    private let thumbnails: ThumbnailCache
    private let transfers: PhotoTransfers
    private let now: @Sendable () -> Date
    private let fetchTimeout: Duration
    private let makeId: @Sendable () -> String
    private let send: @Sendable (Message) async throws -> Void
    private var tasks: [Task<Void, Never>] = []
    private var albumRequest: String?
    private var pageRequest: (id: String, mode: PageMode)?
    private var cursor: String?
    private var thumbsInFlight: [String: Date] = [:]
    private var thumbsSinceTrim = 0
    private var pending: [String: Pending] = [:] // by transfer id
    private var waiting: [String] = [] // transfer ids not asked for yet; previews and drags first
    private var asking: Set<String> = [] // transfer ids the phone was asked for
    private var cachedOriginals: [String: URL] = [:] // photo id → file in the photo cache

    public init(
        thumbnails: ThumbnailCache,
        transfers: PhotoTransfers,
        now: @escaping @Sendable () -> Date = { Date() },
        fetchTimeout: Duration = .seconds(30),
        makeId: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        send: @escaping @Sendable (Message) async throws -> Void
    ) {
        self.thumbnails = thumbnails
        self.transfers = transfers
        self.now = now
        self.fetchTimeout = fetchTimeout
        self.makeId = makeId
        self.send = send
    }

    public func attach(to hub: ConnectionHub) {
        guard tasks.isEmpty else { return }
        let messages = hub.messages
        let statuses = hub.statusUpdates
        tasks.append(Task { [weak self] in
            for await message in messages { await self?.handle(message) }
        })
        tasks.append(Task { [weak self] in
            for await status in statuses { await self?.connectionChanged(status) }
        })
    }

    // MARK: Browsing

    public func select(album: String) async {
        guard album != state.albumId else { return }
        update { $0.albumId = album }
        await reload()
    }

    public func setKind(_ kind: PhotoKindFilter) async {
        guard kind != state.kind else { return }
        update { $0.kind = kind }
        await reload()
    }

    public func loadMore() async {
        guard state.supported, pageRequest == nil, let cursor else { return }
        await requestPage(cursor: cursor, mode: .append)
    }

    /// Asks for thumbnails not cached and not already asked for in the last 20 s, at most 60 per message.
    public func requestThumbnails(_ ids: [String]) async {
        guard state.supported else { return }
        let at = now()
        thumbsInFlight = thumbsInFlight.filter { at.timeIntervalSince($0.value) < Self.thumbExpiry }
        var seen = Set<String>()
        let wanted = ids.filter {
            ThumbnailCache.isValid(id: $0) && thumbsInFlight[$0] == nil && seen.insert($0).inserted && thumbnails.url(for: $0) == nil
        }
        for id in wanted { thumbsInFlight[id] = at }
        for start in stride(from: 0, to: wanted.count, by: Self.thumbBatch) {
            let batch = Array(wanted[start..<min(start + Self.thumbBatch, wanted.count)])
            try? await send(.photosThumbs(PhotosThumbs(ids: batch)))
        }
    }

    /// Forgets everything about the current phone (it was unpaired).
    public func reset() {
        albumRequest = nil
        pageRequest = nil
        cursor = nil
        thumbsInFlight = [:]
        cachedOriginals = [:]
        thumbnails.clear()
        let connected = state.connected, features = state.phoneFeatures
        update {
            $0 = .empty
            $0.connected = connected
            $0.phoneFeatures = features
        }
    }

    // MARK: Originals

    /// Fetches the original into the hidden cache (preview, drag) or the save folder (Save to Mac).
    public func fetchFile(_ id: String, toCache: Bool) async -> Result<URL, PhotoFetchError> {
        guard state.connected else { return .failure(.notConnected) }
        guard state.supported else { return .failure(.unsupported) }
        if toCache, let url = cachedOriginals[id], FileManager.default.fileExists(atPath: url.path(percentEncoded: false)) {
            return .success(url)
        }
        let transferId = makeId()
        await transfers.expect(transferId, toCache)
        return await withCheckedContinuation { continuation in
            pending[transferId] = Pending(photoId: id, toCache: toCache, continuation: continuation)
            if toCache {
                // Someone is waiting to see this one: ahead of queued saves.
                let firstSave = waiting.firstIndex { !(pending[$0]?.toCache ?? false) } ?? waiting.endIndex
                waiting.insert(transferId, at: firstSave)
            } else {
                waiting.append(transferId)
            }
            startWaiting()
        }
    }

    public func transferFinished(transferId: String, url: URL) async {
        await finish(transferId, .success(url))
    }

    public func transferFailed(transferId: String) async {
        await finish(transferId, .failure(.failed))
    }

    // MARK: Messages

    func handle(_ message: Message) async {
        switch message {
        case .photosAlbumList(let body):
            guard body.requestId == albumRequest else { return }
            albumRequest = nil
            let keep = body.albums.contains { $0.id == state.albumId }
            update {
                $0.access = body.access
                $0.albums = body.albums
            }
            if !keep {
                update { $0.albumId = "all" }
                await reload()
            }
        case .photosPage(let body):
            guard let request = pageRequest, request.id == body.requestId else { return }
            pageRequest = nil
            let items = body.items.filter { ThumbnailCache.isValid(id: $0.id) }
            var nextCursor = body.nextCursor
            update { s in
                s.access = body.access
                s.loading = false
                switch request.mode {
                case .replace:
                    s.items = items
                case .append:
                    let known = Set(s.items.map(\.id))
                    s.items += items.filter { !known.contains($0.id) }
                case .merge:
                    let merged = Self.merge(firstPage: items, firstCursor: body.nextCursor, old: s.items, oldCursor: self.cursor)
                    s.items = merged.items
                    nextCursor = merged.cursor
                }
                s.hasMore = nextCursor != nil
            }
            cursor = nextCursor
        case .photosThumb(let body):
            thumbsInFlight[body.id] = nil
            guard let url = thumbnails.save(id: body.id, jpegBase64: body.jpeg) else { return }
            eventBroadcast.yield(.thumbnail(id: body.id, url: url))
            thumbsSinceTrim += 1
            if thumbsSinceTrim >= Self.trimEvery {
                thumbsSinceTrim = 0
                thumbnails.trim()
            }
        case .photosFetchFailed(let body):
            if body.reason == .denied { update { $0.access = PhotoAccess.none } }
            await finish(body.transferId, .failure(body.reason == .gone ? .gone : .denied))
        case .photosChanged:
            guard state.supported else { return }
            await requestAlbums()
            await requestPage(cursor: nil, mode: .merge)
        default:
            break
        }
    }

    func connectionChanged(_ status: ConnectionStatus) async {
        switch status {
        case .connected(let peer):
            let wasSupported = state.supported
            update {
                $0.connected = true
                $0.phoneFeatures = Set(peer.features)
            }
            // Battery updates also arrive as status changes: only a fresh connection reloads.
            guard state.supported, !wasSupported else { return }
            await requestAlbums()
            await requestPage(cursor: nil, mode: state.items.isEmpty ? .replace : .merge)
        case .disconnected:
            albumRequest = nil
            pageRequest = nil
            thumbsInFlight = [:]
            update {
                $0.connected = false
                $0.loading = false
            }
            for transferId in Array(pending.keys) { await finish(transferId, .failure(.notConnected)) }
        }
    }

    /// New photos at the top: the fresh first page, then every loaded item older than its last one (Review Focus #2).
    static func merge(firstPage: [PhotoItem], firstCursor: String?, old: [PhotoItem], oldCursor: String?) -> (items: [PhotoItem], cursor: String?) {
        guard let last = firstPage.last, firstCursor != nil else { return (firstPage, firstCursor) }
        let older = old.filter { isOlder($0, than: last) }
        return older.isEmpty ? (firstPage, firstCursor) : (firstPage + older, oldCursor)
    }

    static func isOlder(_ a: PhotoItem, than b: PhotoItem) -> Bool {
        a.takenAt != b.takenAt ? a.takenAt < b.takenAt : (Int64(a.id) ?? 0) < (Int64(b.id) ?? 0)
    }

    // MARK: Helpers

    private func reload() async {
        cursor = nil
        update {
            $0.items = []
            $0.hasMore = false
        }
        await requestPage(cursor: nil, mode: .replace)
    }

    private func requestAlbums() async {
        let id = makeId()
        albumRequest = id
        try? await send(.photosAlbums(PhotosAlbumsRequest(requestId: id)))
    }

    private func requestPage(cursor: String?, mode: PageMode) async {
        guard state.supported else { return }
        let id = makeId()
        pageRequest = (id, mode)
        update { $0.loading = true }
        do {
            try await send(.photosQuery(PhotosQuery(requestId: id, albumId: state.albumId, kind: state.kind, cursor: cursor, limit: Self.pageSize)))
        } catch {
            if pageRequest?.id == id {
                pageRequest = nil
                update { $0.loading = false }
            }
        }
    }

    /// Asks for up to three originals at a time; each one's 30 s clock starts when the phone is asked.
    private func startWaiting() {
        while asking.count < Self.maxConcurrentFetches, !waiting.isEmpty {
            let transferId = waiting.removeFirst()
            guard let fetch = pending[transferId] else { continue }
            asking.insert(transferId)
            let timeout = fetchTimeout
            Task { await self.sendFetch(id: fetch.photoId, transferId: transferId) }
            Task {
                try? await Task.sleep(for: timeout)
                await self.checkTimeout(transferId)
            }
        }
    }

    private func sendFetch(id: String, transferId: String) async {
        do {
            try await send(.photosFetch(PhotosFetch(id: id, transferId: transferId)))
        } catch {
            await finish(transferId, .failure(.notConnected))
        }
    }

    /// 30 s after asking: an offer that never arrived means the phone didn't respond (Review Focus #5).
    private func checkTimeout(_ transferId: String) async {
        guard pending[transferId] != nil, !(await transfers.isActive(transferId)) else { return }
        await finish(transferId, .failure(.noResponse))
    }

    private func finish(_ transferId: String, _ result: Result<URL, PhotoFetchError>) async {
        guard let fetch = pending.removeValue(forKey: transferId) else { return }
        waiting.removeAll { $0 == transferId }
        asking.remove(transferId)
        defer { startWaiting() }
        switch result {
        case .success(let url):
            if fetch.toCache { cachedOriginals[fetch.photoId] = url }
        case .failure(let error):
            await transfers.forget(transferId)
            if error == .gone { update { $0.items.removeAll { $0.id == fetch.photoId } } }
        }
        fetch.continuation.resume(returning: result)
    }

    private func update(_ change: (inout PhotosState) -> Void) {
        var next = state
        change(&next)
        guard next != state else { return }
        state = next
        stateBroadcast.yield(state)
    }
}
