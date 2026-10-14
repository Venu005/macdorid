import Foundation
import NIOConcurrencyHelpers
import Testing
@testable import MacDroidCore

private let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]).base64EncodedString()

private func item(_ id: Int, _ seconds: Int64, kind: PhotoKind = .photo, size: Int64 = 1_000) -> PhotoItem {
    PhotoItem(id: String(id), kind: kind, name: "IMG_\(id).jpg", mime: "image/jpeg", takenAt: seconds * 1000, size: size, width: 4, height: 3)
}

@Suite struct ThumbnailCacheTests {
    // Review Focus #3
    @Test func invalidIdsAreRejected() throws {
        let cache = ThumbnailCache(directory: try makeTemporaryDirectory())
        for bad in ["../x", "12/../3", "", "1a", String(repeating: "1", count: 21), "１２"] {
            #expect(!ThumbnailCache.isValid(id: bad))
            #expect(cache.save(id: bad, jpegBase64: jpeg) == nil)
            #expect(cache.url(for: bad) == nil)
        }
        #expect(cache.save(id: "1000", jpegBase64: jpeg) != nil)
        #expect(cache.url(for: "1000") != nil)
    }

    @Test func onlySmallJpegsAreKept() throws {
        let cache = ThumbnailCache(directory: try makeTemporaryDirectory())
        #expect(cache.save(id: "1", jpegBase64: Data("not a jpeg".utf8).base64EncodedString()) == nil)
        #expect(cache.save(id: "2", jpegBase64: "%%%") == nil)
        #expect(cache.save(id: "3", jpegBase64: (Data([0xFF, 0xD8]) + Data(count: ThumbnailCache.maxThumbBytes)).base64EncodedString()) == nil)
    }

    // Final review I5: the grid checks the cache on the main thread for every cell, so that check must not write.
    @Test func checkingWithoutTouchingKeepsTheLastUse() throws {
        let cache = ThumbnailCache(directory: try makeTemporaryDirectory())
        let url = try #require(cache.save(id: "7", jpegBase64: jpeg))
        let old = Date(timeIntervalSince1970: 1)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: url.path(percentEncoded: false))
        #expect(cache.cachedURL(for: "7") == url)
        #expect(cache.cachedURL(for: "8") == nil)
        #expect(cache.cachedURL(for: "../7") == nil)
        let date = try url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
        #expect(date == old)
    }

    @Test func trimDeletesTheLeastRecentlyUsed() throws {
        let directory = try makeTemporaryDirectory()
        let cache = ThumbnailCache(directory: directory, limit: 2_500)
        let body = (Data([0xFF, 0xD8]) + Data(count: 998)).base64EncodedString() // 1,000 bytes each
        for (n, id) in ["1", "2", "3"].enumerated() {
            let url = try #require(cache.save(id: id, jpegBase64: body))
            try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: TimeInterval(n + 1))], ofItemAtPath: url.path(percentEncoded: false))
        }
        _ = cache.url(for: "1") // used just now: no longer the oldest
        cache.trim()
        #expect(cache.url(for: "1") != nil)
        #expect(cache.url(for: "2") == nil)
        #expect(cache.url(for: "3") != nil)
    }
}

@Suite struct PhotosPresentationTests {
    let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()
    let locale = Locale(identifier: "en_GB")
    let now = Date(timeIntervalSince1970: 1_791_892_800) // 2026-10-13 08:00 UTC

    @Test func daysAreGroupedAndTitled() {
        let items = [item(5, 1_791_892_000), item(4, 1_791_880_000), item(3, 1_791_800_000), item(2, 1_791_600_000), item(1, 1_760_000_000)]
        let days = PhotosPresentation.days(items, now: now, calendar: calendar, locale: locale)
        #expect(days.map(\.title) == ["Today", "Yesterday", "10 Oct", "9 Oct 2025"])
        #expect(days.map { $0.items.map(\.id) } == [["5", "4"], ["3"], ["2"], ["1"]])
    }

    @Test func durationsAndSelection() {
        #expect(PhotosPresentation.duration(ms: 42_000) == "0:42")
        #expect(PhotosPresentation.duration(ms: 3_723_000) == "1:02:03")
        #expect(PhotosPresentation.selection(count: 1, bytes: 3_100_000) == "1 selected · 3.1 MB")
    }

    @Test func accessCopy() {
        #expect(PhotosPresentation.banner(access: .full, visibleCount: 10) == nil)
        #expect(PhotosPresentation.banner(access: .partial, visibleCount: 24) == "MacDroid can see 24 selected photos. To see all, open MacDroid on your phone → Settings → Photos.")
        #expect(PhotosPresentation.banner(access: PhotoAccess.none, visibleCount: 0) == "Photos are off on your phone. Open MacDroid on your phone → Settings → Photos.")
        #expect(PhotosPresentation.phoneLine(.full) == "All")
        #expect(PhotosPresentation.phoneLine(.partial) == "Selected")
        #expect(PhotosPresentation.phoneLine(PhotoAccess.none) == "Off on phone")
        #expect(PhotosPresentation.phoneLine(nil) == nil)
        #expect(PhotosPresentation.message(.gone) == "This photo is no longer on your phone")
        #expect(PhotosPresentation.message(.noResponse) == "Your phone didn't respond")
        #expect(PhotosPresentation.message(.unsupported) == "Update MacDroid on your phone to use this.")
    }
}

@Suite struct PhotoSelectionTests {
    let order = ["5", "4", "3", "2", "1"]

    @Test func plainClickSelectsOnlyThatItem() {
        let r = PhotoSelection.click("3", command: false, shift: false, selection: ["5", "4"], anchor: "5", order: order)
        #expect(r.selection == ["3"])
        #expect(r.anchor == "3")
    }

    @Test func commandClickToggles() {
        var r = PhotoSelection.click("3", command: true, shift: false, selection: ["5"], anchor: "5", order: order)
        #expect(r.selection == ["5", "3"])
        r = PhotoSelection.click("5", command: true, shift: false, selection: r.selection, anchor: r.anchor, order: order)
        #expect(r.selection == ["3"])
    }

    @Test func shiftClickSelectsTheRangeFromTheAnchor() {
        let r = PhotoSelection.click("2", command: false, shift: true, selection: ["4"], anchor: "4", order: order)
        #expect(r.selection == ["4", "3", "2"])
        #expect(r.anchor == "4")
        let back = PhotoSelection.click("5", command: false, shift: true, selection: r.selection, anchor: "4", order: order)
        #expect(back.selection == ["5", "4"])
        let noAnchor = PhotoSelection.click("2", command: false, shift: true, selection: [], anchor: nil, order: order)
        #expect(noAnchor.selection == ["2"])
    }
}

@Suite struct PhotosClientTests {
    final class Harness: Sendable {
        let client: PhotosClient
        let sent: Collector<Message>
        let expected: Collector<String>
        let forgotten: Collector<String>
        let thumbs: Collector<PhotosEvent>
        /// Transfers in progress and their bytes so far.
        let active: NIOLockedValueBox<[String: Int64]>
        let clock: FakeClock
        let listener: Task<Void, Never>

        init(timeout: Duration = .seconds(30)) throws {
            let sent = Collector<Message>(), expected = Collector<String>(), forgotten = Collector<String>(), thumbs = Collector<PhotosEvent>()
            let active = NIOLockedValueBox<[String: Int64]>([:])
            let clock = FakeClock()
            let counter = NIOLockedValueBox(0)
            let client = PhotosClient(
                thumbnails: ThumbnailCache(directory: try makeTemporaryDirectory()),
                transfers: PhotoTransfers(
                    expect: { id, _ in await expected.append(id) },
                    forget: { await forgotten.append($0) },
                    progress: { id in active.withLockedValue { $0[id].map { (done: $0, size: 1_000) } } }
                ),
                now: { clock.now },
                fetchTimeout: timeout,
                makeId: { counter.withLockedValue { $0 += 1; return "id\($0)" } },
                send: { await sent.append($0) }
            )
            self.client = client
            self.sent = sent
            self.expected = expected
            self.forgotten = forgotten
            self.thumbs = thumbs
            self.active = active
            self.clock = clock
            let stream = client.events
            listener = Task { for await event in stream { await thumbs.append(event) } }
        }

        func connect(features: [String] = ProtocolInfo.features) async {
            await client.connectionChanged(.connected(PeerInfo(device: "Phone", app: "0.6.0", features: features)))
        }

        /// Connects and answers the first page (request "id2") with `items`.
        func connectWith(_ items: [PhotoItem], next: String? = nil) async {
            await connect()
            await client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: items, nextCursor: next)))
        }

        func fetches() async -> [PhotosFetch] {
            await sent.items.compactMap { if case .photosFetch(let body) = $0 { return body }; return nil }
        }
    }

    @Test func connectingLoadsAlbumsAndTheFirstPage() async throws {
        let h = try Harness()
        await h.connect()
        #expect(await h.sent.items == [
            .photosAlbums(PhotosAlbumsRequest(requestId: "id1")),
            .photosQuery(PhotosQuery(requestId: "id2", albumId: "all", kind: .all, cursor: nil, limit: 200)),
        ])
        await h.client.handle(.photosAlbumList(PhotosAlbumList(requestId: "id1", access: .full, albums: [PhotoAlbum(id: "all", name: "All Photos", count: 2)])))
        await h.client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: [item(9, 100), item(8, 90)], nextCursor: "90:8")))
        let state = await h.client.state
        #expect(state.items.map(\.id) == ["9", "8"])
        #expect(state.hasMore)
        #expect(!state.loading)
        #expect(state.access == .full)
        #expect(state.albums.map(\.id) == ["all"])
    }

    @Test func aPhoneWithoutPhotosIsUnsupported() async throws {
        let h = try Harness()
        await h.connect(features: ["files"])
        #expect(await h.sent.items.isEmpty)
        #expect(await h.client.fetchFile("9", toCache: true) == .failure(.unsupported))
    }

    @Test func staleResponsesAreIgnored() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.select(album: "-17")
        await h.client.handle(.photosPage(PhotosPage(requestId: "id2", access: .full, items: [item(9, 100)])))
        #expect(await h.client.state.items.isEmpty)
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(5, 50)])))
        #expect(await h.client.state.items.map(\.id) == ["5"])
        #expect(await h.client.state.albumId == "-17")
    }

    @Test func loadMoreUsesTheCursorAndSkipsDuplicates() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)], next: "90:8")
        await h.client.loadMore()
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id3", albumId: "all", kind: .all, cursor: "90:8", limit: 200)))
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(8, 90), item(7, 80)])))
        #expect(await h.client.state.items.map(\.id) == ["9", "8", "7"])
        #expect(await !h.client.state.hasMore)
        let count = await h.sent.items.count
        await h.client.loadMore()
        #expect(await h.sent.items.count == count)
    }

    @Test func thumbnailsAreRequestedOnceAndExpireAfterTwentySeconds() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.requestThumbnails(["1", "2", "../x", "1"])
        #expect(await h.sent.items.last == .photosThumbs(PhotosThumbs(ids: ["1", "2"])))
        let count = await h.sent.items.count
        await h.client.requestThumbnails(["1", "2"])
        #expect(await h.sent.items.count == count)
        await h.client.handle(.photosThumb(PhotosThumb(id: "1", jpeg: jpeg)))
        try await eventually { await h.thumbs.items.count == 1 }
        h.clock.advance(21)
        await h.client.requestThumbnails(["1", "2"])
        #expect(await h.sent.items.last == .photosThumbs(PhotosThumbs(ids: ["2"]))) // 1 is cached now
    }

    // Follow-up M9: the phone can't fill the Mac's disk with thumbnails nobody asked for.
    @Test func unrequestedThumbnailsAreDropped() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.handle(.photosThumb(PhotosThumb(id: "5", jpeg: jpeg)))
        await h.client.requestThumbnails(["6"])
        await h.client.handle(.photosThumb(PhotosThumb(id: "6", jpeg: jpeg)))
        try await eventually { await h.thumbs.items.count == 1 }
        #expect(await h.thumbs.items.map { if case .thumbnail(let id, _) = $0 { return id }; return "" } == ["6"])
        await h.client.requestThumbnails(["5"]) // not cached: still asked for
        #expect(await h.sent.items.last == .photosThumbs(PhotosThumbs(ids: ["5"])))
    }

    @Test func thumbnailRequestsAreBatchedBySixty() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.requestThumbnails((1...130).map(String.init))
        let batches = await h.sent.items.compactMap { if case .photosThumbs(let b) = $0 { return b.ids.count }; return nil }
        #expect(batches == [60, 60, 10])
    }

    @Test func aFetchCompletesWithTheFileAndIsReusedFromTheCache() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches() == [PhotosFetch(id: "9", transferId: "id3")] }
        #expect(await h.expected.items == ["id3"])
        let file = try makeTemporaryDirectory().appending(path: "IMG_9.jpg")
        try Data([1]).write(to: file)
        await client.transferFinished(transferId: "id3", url: file)
        #expect(await fetch.value == .success(file))
        #expect(await client.fetchFile("9", toCache: true) == .success(file))
        #expect(await h.fetches().count == 1)
    }

    // Follow-up M2: double-click plus Space (or a drag during a preview) fetches the original once.
    @Test func concurrentCacheFetchesOfOnePhotoShareATransfer() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let first = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        let second = Task { await client.fetchFile("9", toCache: true) }
        try await Task.sleep(for: .milliseconds(50))
        #expect(await h.fetches().count == 1)
        let file = try makeTemporaryDirectory().appending(path: "IMG_9.jpg")
        try Data([1]).write(to: file)
        await client.transferFinished(transferId: "id3", url: file)
        #expect(await first.value == .success(file))
        #expect(await second.value == .success(file))
    }

    // Review Focus #4
    @Test func goneFetchRemovesTheItemAndExplains() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: false) }
        try await eventually { await h.fetches().count == 1 }
        await client.handle(.photosFetchFailed(PhotosFetchFailed(transferId: "id3", reason: .gone)))
        #expect(await fetch.value == .failure(.gone))
        #expect(await client.state.items.map(\.id) == ["8"])
        #expect(await h.forgotten.items == ["id3"])
        #expect(PhotosPresentation.message(.gone) == "This photo is no longer on your phone")
    }

    @Test func aDeniedFetchTurnsAccessOff() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.handle(.photosFetchFailed(PhotosFetchFailed(transferId: "id3", reason: .denied)))
        #expect(await fetch.value == .failure(.denied))
        #expect(await client.state.access == PhotoAccess.none)
    }

    // Review Focus #5
    @Test func fetchesTimeOutWhenNoOfferArrives() async throws {
        let h = try Harness(timeout: .milliseconds(100))
        await h.connectWith([item(9, 100)])
        #expect(await h.client.fetchFile("9", toCache: true) == .failure(.noResponse))
        #expect(await h.forgotten.items == ["id3"])
    }

    @Test func aStartedTransferDoesNotTimeOut() async throws {
        let h = try Harness(timeout: .milliseconds(100))
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: false) }
        // The transfer keeps moving, past several 100 ms checks.
        for bytes in stride(from: Int64(10), through: 120, by: 10) {
            h.active.withLockedValue { $0["id3"] = bytes }
            try await Task.sleep(for: .milliseconds(25))
        }
        let file = try makeTemporaryDirectory().appending(path: "IMG_9.jpg")
        await client.transferFinished(transferId: "id3", url: file)
        #expect(await fetch.value == .success(file))
    }

    @Test func onlyThreeFetchesAreAskedForAtOnceAndPreviewsGoFirst() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90), item(7, 80), item(6, 70), item(5, 60)])
        let client = h.client
        let saves = (6...9).map { id in Task { await client.fetchFile(String(id), toCache: false) } } // id3…id6
        try await eventually {
            let asked = await h.fetches().count
            let expected = await h.expected.items.count
            return asked == 3 && expected == 4
        }
        let preview = Task { await client.fetchFile("5", toCache: true) } // id7: jumps the waiting save
        try await eventually { await h.expected.items.count == 5 }
        let first = try #require(await h.fetches().first)
        await client.transferFinished(transferId: first.transferId, url: try makeTemporaryDirectory().appending(path: "a.jpg"))
        try await eventually { await h.fetches().count == 4 }
        #expect(await h.fetches().last?.id == "5")
        for fetch in await h.fetches() { await client.transferFailed(transferId: fetch.transferId) }
        try await eventually { await h.fetches().count == 5 }
        await client.connectionChanged(.disconnected)
        _ = await preview.value
        for save in saves { _ = await save.value }
    }

    // Follow-up M1: an offer arrived but the bytes stopped moving (no disconnect): don't spin forever.
    @Test func aStalledTransferFailsAndIsForgotten() async throws {
        let h = try Harness(timeout: .milliseconds(100))
        await h.connectWith([item(9, 100)])
        h.active.withLockedValue { $0["id3"] = 10 }
        #expect(await h.client.fetchFile("9", toCache: true) == .failure(.failed))
        #expect(await h.forgotten.items == ["id3"])
    }

    @Test func resetAnswersPendingFetches() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.reset()
        #expect(await fetch.value == .failure(.notConnected))
        #expect(await client.state.items.isEmpty)
    }

    @Test func aDisconnectFailsPendingFetches() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.connectionChanged(.disconnected)
        #expect(await fetch.value == .failure(.notConnected))
        #expect(await client.fetchFile("9", toCache: true) == .failure(.notConnected))
    }

    @Test func aFailedTransferFailsTheFetch() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100)])
        let client = h.client
        let fetch = Task { await client.fetchFile("9", toCache: true) }
        try await eventually { await h.fetches().count == 1 }
        await client.transferFailed(transferId: "id3")
        #expect(await fetch.value == .failure(.failed))
    }

    // Review Focus #2 (Mac side): new photos at the top don't drop what's already loaded.
    @Test func changedMergesTheNewFirstPageAndKeepsOlderPages() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)], next: "90:8")
        await h.client.loadMore()
        await h.client.handle(.photosPage(PhotosPage(requestId: "id3", access: .full, items: [item(7, 80)], nextCursor: "80:7")))
        await h.client.handle(.photosChanged)
        #expect(await h.sent.items.suffix(2) == [
            .photosAlbums(PhotosAlbumsRequest(requestId: "id4")),
            .photosQuery(PhotosQuery(requestId: "id5", albumId: "all", kind: .all, cursor: nil, limit: 200)),
        ])
        await h.client.handle(.photosPage(PhotosPage(requestId: "id5", access: .full, items: [item(10, 110), item(9, 100)], nextCursor: "100:9")))
        #expect(await h.client.state.items.map(\.id) == ["10", "9", "8", "7"])
        await h.client.loadMore()
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id6", albumId: "all", kind: .all, cursor: "80:7", limit: 200)))
    }

    // Final review I1: more than a page of new photos must not leave a hole between the new page and the old items.
    @Test func aChangeWithMoreThanAPageOfNewPhotosStartsOver() async throws {
        let h = try Harness()
        await h.connectWith([item(9, 100), item(8, 90)], next: "90:8")
        await h.client.handle(.photosChanged) // id3 albums, id4 merge page
        // The new first page is entirely newer than what was loaded: photos between 200 and 100 are not loaded yet.
        await h.client.handle(.photosPage(PhotosPage(requestId: "id4", access: .full, items: [item(301, 301), item(200, 200)], nextCursor: "200:200")))
        #expect(await h.client.state.items.map(\.id) == ["301", "200"])
        await h.client.loadMore()
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id5", albumId: "all", kind: .all, cursor: "200:200", limit: 200)))
    }

    @Test func anAlbumThatDisappearedFallsBackToAllPhotos() async throws {
        let h = try Harness()
        await h.connect()
        await h.client.select(album: "-17") // id3
        await h.client.handle(.photosChanged) // id4 albums, id5 merge page
        await h.client.handle(.photosAlbumList(PhotosAlbumList(requestId: "id4", access: .full, albums: [PhotoAlbum(id: "all", name: "All Photos", count: 1)])))
        #expect(await h.client.state.albumId == "all")
        #expect(await h.sent.items.last == .photosQuery(PhotosQuery(requestId: "id6", albumId: "all", kind: .all, cursor: nil, limit: 200)))
    }
}
