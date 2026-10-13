import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferSendTests {
    func sources(_ f: TransferFixture, count: Int, size: Int = 1_000) throws -> [URL] {
        let dir = f.state.deletingLastPathComponent().appending(path: "outbox", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try (0..<count).map { index in
            let url = dir.appending(path: "file\(index).jpg")
            try testBytes(size, seed: UInt8(index)).write(to: url)
            return url
        }
    }

    @Test func offersAtMostThreeAndQueuesTheRest() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 5))
        #expect(ids.count == 5)
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == Array(ids.prefix(3)))
        #expect(await manager.snapshot.active.map(\.phase) == [.offered, .offered, .offered, .queued, .queued])
        #expect(await f.phone.sent.compactMap(\.offerBody).first?.mime == "image/jpeg")
    }

    @Test func finishingOneStartsTheNextInOrder() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 4))
        await manager.handle(.transferAccept(TransferID(transferId: ids[0])))
        await manager.handle(.transferResult(TransferResult(transferId: ids[0], ok: true)))
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == ids)
        #expect(await manager.snapshot.history.first?.phase == .done)
    }

    @Test func nothingIsOfferedUntilConnected() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        _ = await manager.send(files: try sources(f, count: 2))
        #expect(await f.phone.sent.isEmpty)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 2)
    }

    @Test func reconnectReoffersTheSameId() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        await manager.handle(.transferAccept(TransferID(transferId: id)))
        await manager.connectionChanged(false)
        #expect(await manager.snapshot.active.first?.phase == .paused)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.compactMap(\.offerBody).map(\.transferId) == [id, id])
    }

    @Test func downloadThenFinishCarriesTheWholeFileChecksum() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = try sources(f, count: 1, size: 50_000)[0]
        let id = try #require(await manager.send(files: [source]).first)
        await manager.handle(.transferAccept(TransferID(transferId: id)))
        let download = try await manager.beginDownload(id: id, from: 20_000)
        let reader = try SourceReader(url: download.file.url, offset: 20_000)
        while try reader.next() != nil {}
        await manager.downloadCompleted(id: id, sha256: reader.digest())
        let expected = Checksum.sha256Hex(try Data(contentsOf: source))
        #expect(await f.phone.sent.compactMap(\.finishBody) == [TransferFinish(transferId: id, sha256: expected)])
        #expect(await manager.snapshot.active.first?.phase == .verifying)
        await manager.handle(.transferResult(TransferResult(transferId: id, ok: false, error: .checksumMismatch)))
        #expect(await manager.snapshot.history.first?.phase == .failed(.checksumMismatch))
    }

    @Test func getMayArriveBeforeTheAcceptIsProcessed() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        _ = try await manager.beginDownload(id: id, from: 0) // still .offered: allowed
        #expect(await manager.snapshot.active.first?.phase == .active)
    }

    @Test func downloadRangeBeyondTheSizeIsRejected() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1, size: 10)).first)
        await #expect(throws: TransferManager.RouteError.rangeNotSatisfiable) { _ = try await manager.beginDownload(id: id, from: 11) }
    }

    @Test func wrongDirectionOrUnknownIdIsNotFound() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("incoming", size: 5))
        try await eventually { await manager.snapshot.active.count == 1 }
        await #expect(throws: TransferManager.RouteError.notFound) { _ = try await manager.beginDownload(id: "incoming", from: 0) }
        await #expect(throws: TransferManager.RouteError.notFound) { _ = try await manager.beginDownload(id: "nope", from: 0) }
    }

    @Test func missingSourceFailsAndTellsThePhone() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let source = try sources(f, count: 1)[0]
        let id = try #require(await manager.send(files: [source]).first)
        try FileManager.default.removeItem(at: source)
        await #expect(throws: TransferManager.RouteError.sourceUnavailable) { _ = try await manager.beginDownload(id: id, from: 0) }
        #expect(await manager.snapshot.history.first?.phase == .failed(.sourceUnavailable))
        #expect(await f.phone.sent.contains { $0.cancelledID == id })
    }

    @Test func rejectFailsTheTransferAndFreesTheSlot() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 4))
        await manager.handle(.transferReject(TransferReject(transferId: ids[1], reason: .noSpace)))
        #expect(await manager.snapshot.history.first?.phase == .failed(.noSpace))
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 4)
    }

    @Test func peerCancelAndLocalCancelFreeSlots() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let ids = await manager.send(files: try sources(f, count: 5))
        await manager.handle(.transferCancel(TransferID(transferId: ids[0])))
        await manager.cancel(id: ids[1])
        #expect(await f.phone.sent.contains { $0.cancelledID == ids[1] })
        #expect(await f.phone.sent.compactMap(\.offerBody).count == 5)
        #expect(await manager.snapshot.history.map(\.phase).contains(.cancelled(byPeer: true)))
    }

    @Test func retryRequeuesWithANewId() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        let id = try #require(await manager.send(files: try sources(f, count: 1)).first)
        await manager.handle(.transferReject(TransferReject(transferId: id, reason: .declined)))
        await manager.retry(id: id)
        let offers = await f.phone.sent.compactMap(\.offerBody)
        #expect(offers.count == 2 && offers[1].transferId != id && offers[1].name == offers[0].name)
    }

    @Test func foldersAreSkipped() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        #expect(await manager.send(files: [f.downloads]).isEmpty)
    }

    @Test func mimeComesFromTheExtension() {
        #expect(TransferManager.mimeType(for: URL(fileURLWithPath: "/x/a.pdf")) == "application/pdf")
        #expect(TransferManager.mimeType(for: URL(fileURLWithPath: "/x/noext")) == "application/octet-stream")
    }
}
