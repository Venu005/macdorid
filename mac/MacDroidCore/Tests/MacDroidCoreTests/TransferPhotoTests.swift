import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferPhotoTests {
    func manager(_ f: TransferFixture, cache: URL) -> TransferManager {
        let phone = f.phone, clock = f.clock, save = f.downloads
        return TransferManager(
            stateDirectory: f.state, location: GateLocation(), freeSpace: { _ in nil },
            photoCacheDirectory: cache, photoSaveFolder: { save },
            now: { clock.now }, send: { try await phone.send($0) }
        )
    }

    func photoOffer(_ id: String, size: Int64) -> Message {
        .transferOffer(TransferOffer(transferId: id, name: "IMG_1.jpg", size: size, mime: "image/jpeg", purpose: .photo))
    }

    func listen(_ m: TransferManager) -> (Collector<TransferEvent>, Task<Void, Never>) {
        let events = Collector<TransferEvent>()
        let stream = m.events
        return (events, Task { for await e in stream { await events.append(e) } })
    }

    // Review Focus #1
    @Test func unrequestedPhotoOffersAreRejected() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        await m.handle(photoOffer("p1", size: 10))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "p1", reason: .invalid) } }
        #expect(await m.snapshot.active.isEmpty)
        #expect(await !m.hasTransfer("p1"))
    }

    @Test func cacheFetchesAreHiddenAndAnnounced() async throws {
        let f = try TransferFixture()
        let cache = try makeTemporaryDirectory()
        let m = manager(f, cache: cache)
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p1", toCache: true)
        let data = testBytes(2_000)
        await m.handle(photoOffer("p1", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p1" } }
        #expect(await m.snapshot.active.isEmpty)
        #expect(await m.hasTransfer("p1"))
        try await upload(data, to: m, id: "p1")
        await m.handle(.transferFinish(TransferFinish(transferId: "p1", sha256: Checksum.sha256Hex(data))))
        try await eventually { await events.items.count == 1 }
        guard case .photoReceived("p1", let url) = await events.items[0] else { Issue.record("wrong event"); return }
        #expect(url.deletingLastPathComponent().standardizedFileURL == cache.standardizedFileURL)
        #expect(await m.snapshot.history.isEmpty)
        listener.cancel()
    }

    @Test func savesGoToTheSaveFolderWithoutAskingAndShowInHistory() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p2", toCache: false)
        let data = testBytes(1_000)
        await m.handle(photoOffer("p2", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p2" } } // GateLocation never answers: nothing asked
        try await upload(data, to: m, id: "p2")
        await m.handle(.transferFinish(TransferFinish(transferId: "p2", sha256: Checksum.sha256Hex(data))))
        try await eventually { await events.items.count == 2 }
        #expect(f.exists("IMG_1.jpg"))
        #expect(await m.snapshot.history.first?.name == "IMG_1.jpg")
        listener.cancel()
    }

    @Test func failedPhotoTransfersAreAnnounced() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.expectPhoto(transferId: "p3", toCache: true)
        let data = testBytes(500)
        await m.handle(photoOffer("p3", size: Int64(data.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "p3" } }
        try await upload(data, to: m, id: "p3")
        await m.handle(.transferFinish(TransferFinish(transferId: "p3", sha256: String(repeating: "0", count: 64))))
        try await eventually { await events.items.contains(.photoFailed(transferId: "p3")) }
        #expect(await !m.hasTransfer("p3"))
        listener.cancel()
    }

    @Test func cacheFetchesAreCancelledOnDisconnect() async throws {
        let f = try TransferFixture()
        let m = manager(f, cache: try makeTemporaryDirectory())
        let (events, listener) = listen(m)
        await m.connectionChanged(true)
        await m.expectPhoto(transferId: "p4", toCache: true)
        await m.handle(photoOffer("p4", size: 100))
        try await eventually {
            let has = await m.hasTransfer("p4")
            let sent = await f.phone.sent
            return has && sent.contains { $0.acceptedID == "p4" }
        }
        await m.connectionChanged(false)
        try await eventually { await events.items.contains(.photoFailed(transferId: "p4")) }
        #expect(await !m.hasTransfer("p4"))
        listener.cancel()
    }

    @Test func photoRecordsInTheCacheAreNotRestoredAfterARelaunch() {
        let now = Date()
        let record = TransferRecord(id: "p1", direction: .fromPhone, name: "a.jpg", size: 1, mime: "image/jpeg", phase: .active,
                                    folder: URL(fileURLWithPath: "/tmp"), createdAt: now, updatedAt: now, purpose: .photo, hidden: true)
        #expect(TransferManager.restore(active: [record], history: [], now: now).0.isEmpty)
    }
}
