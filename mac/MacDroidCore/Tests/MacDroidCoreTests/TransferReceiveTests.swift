import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferReceiveTests {
    @Test func acceptsOfferIntoAPartFile() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "IMG.jpg", size: 10))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "t1" } }
        #expect(f.exists("IMG.jpg.macdroid-part"))
        #expect(await manager.snapshot.active.map(\.phase) == [.active])
    }

    @Test func uploadThenFinishVerifiesRenamesAndReports() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        let events = Collector<TransferEvent>()
        let stream = manager.events
        let listener = Task { for await event in stream { await events.append(event) } }
        let data = testBytes(300_000)
        await manager.handle(makeOffer("t1", name: "a.bin", size: Int64(data.count)))
        try await eventually { await decided(manager) }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("a.bin")) == data)
        #expect(!f.exists("a.bin.macdroid-part"))
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: true) })
        #expect(await manager.snapshot.history.first?.phase == .done)
        #expect(await manager.snapshot.history.first?.savedURL == f.file("a.bin"))
        try await eventually { await events.items.count == 1 }
        listener.cancel()
    }

    @Test func checksumMismatchDeletesThePartAndReportsIt() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 4))
        try await eventually { await decided(manager) }
        try await upload(Data([1, 2, 3, 4]), to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: String(repeating: "0", count: 64))))
        #expect(!f.exists("a.txt.macdroid-part") && !f.exists("a.txt"))
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: false, error: .checksumMismatch) })
        #expect(await manager.snapshot.history.first?.phase == .failed(.checksumMismatch))
    }

    @Test func rejectsWhenThereIsNotEnoughSpace() async throws {
        let f = try TransferFixture()
        let manager = f.manager(freeSpace: { _ in 10 })
        await manager.handle(makeOffer("t1", size: 100))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "t1", reason: .noSpace) } }
        #expect(await manager.snapshot.history.first?.phase == .failed(.noSpace))
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    @Test func declinedWhenNoLocationIsChosen() async throws {
        let f = try TransferFixture()
        let manager = f.manager(location: FixedLocation(nil))
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "t1", reason: .declined) } }
    }

    // Review Focus #3
    @Test func hostileNameLandsInsideFolder() async throws {
        let f = try TransferFixture()
        try Data("old".utf8).write(to: f.file("passwd"))
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "../../etc/passwd", size: 1))
        try await eventually { await manager.snapshot.active.first?.name == "passwd (1)" }
        #expect(f.exists("passwd (1).macdroid-part"))
    }

    // Review Focus #4
    @Test func simultaneousSameNameOffersGetDistinctNames() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "same.txt", size: 1))
        await manager.handle(makeOffer("t2", name: "same.txt", size: 1))
        try await eventually { await decided(manager, count: 2) }
        #expect(Set(await manager.snapshot.active.map(\.name)) == ["same.txt", "same (1).txt"])
    }

    // Final review: APFS is case-insensitive, so names differing only in case must never share a part file.
    @Test func caseOnlyDifferentNamesGetDistinctParts() async throws {
        for _ in 0..<20 {
            let f = try TransferFixture()
            let manager = f.manager()
            await manager.handle(makeOffer("t1", name: "IMG_1.JPG", size: 1))
            await manager.handle(makeOffer("t2", name: "img_1.jpg", size: 1))
            try await eventually { await decided(manager, count: 2) }
            let names = await manager.snapshot.active.map { $0.name.lowercased() }
            #expect(Set(names).count == 2, "names: \(names)")
        }
    }

    @Test func twoSavePanelsPickingTheSameFileDoNotShareAPart() async throws {
        let f = try TransferFixture()
        let manager = f.manager(location: FixedLocation(.file(f.file("same.txt"))))
        await manager.handle(makeOffer("t1", name: "a.txt", size: 1))
        await manager.handle(makeOffer("t2", name: "b.txt", size: 1))
        try await eventually { await decided(manager, count: 2) }
        #expect(Set(await manager.snapshot.active.map(\.name)) == ["same.txt", "same (1).txt"])
    }

    // Final review (Critical): a save panel only grants access to the chosen file, so bytes go straight into it — no sibling part file.
    @Test func savePanelChoiceIsWrittenInPlaceWithoutAPartFile() async throws {
        let f = try TransferFixture()
        let chosen = f.file("picked.txt")
        try Data("old contents".utf8).write(to: chosen)
        let manager = f.manager(location: FixedLocation(.file(chosen)))
        let data = Data("new".utf8)
        await manager.handle(makeOffer("t1", name: "whatever.txt", size: 3))
        try await eventually { await decided(manager) }
        #expect(!f.exists("picked.txt.macdroid-part"))
        #expect(try Data(contentsOf: chosen).isEmpty) // replaced, not appended to
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: chosen) == data)
        #expect(await manager.snapshot.history.first?.savedURL == chosen)
    }

    @Test func savePanelChoiceReplacesThatFile() async throws {
        let f = try TransferFixture()
        try Data("old".utf8).write(to: f.file("chosen.txt"))
        let manager = f.manager(location: FixedLocation(.file(f.file("chosen.txt"))))
        let data = Data("new".utf8)
        await manager.handle(makeOffer("t1", name: "whatever.txt", size: 3))
        try await eventually { await decided(manager) }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("chosen.txt")) == data)
    }

    @Test func uploadOffsetReportsBytesOnDiskAndRejectsAWrongStart() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await decided(manager) }
        try await upload(testBytes(100), to: manager, id: "t1")
        #expect(await manager.uploadOffset(id: "t1") == 100)
        await #expect(throws: TransferManager.RouteError.offsetMismatch(100)) { _ = try await manager.beginUpload(id: "t1", start: 50) }
        #expect(await manager.uploadOffset(id: "unknown") == nil)
    }

    @Test func headSupersedesAnOlderUpload() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await decided(manager) }
        let stale = try await manager.beginUpload(id: "t1", start: 0)
        _ = await manager.uploadOffset(id: "t1")
        await #expect(throws: PartFileError.superseded) { try await stale.writer.append(Data([1]), session: stale.session) }
    }

    @Test func reofferOfAKnownIdAcceptsAgainAndKeepsBytes() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await decided(manager) }
        try await upload(testBytes(120), to: manager, id: "t1")
        await manager.connectionChanged(false)
        #expect(await manager.snapshot.active.first?.phase == .paused)
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 200))
        try await eventually { await f.phone.sent.filter { $0.acceptedID == "t1" }.count == 2 }
        #expect(await manager.uploadOffset(id: "t1") == 120)
        #expect(await manager.snapshot.active.first?.phase == .active)
    }

    // Review Focus #1
    @Test func reofferOfFinishedIdReplaysResult() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        let data = Data("hi".utf8)
        await manager.handle(makeOffer("t1", size: 2))
        try await eventually { await decided(manager) }
        try await upload(data, to: manager, id: "t1")
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        await manager.handle(makeOffer("t1", size: 2))
        try await eventually { await f.phone.sent.filter { $0.result?.ok == true }.count == 2 }
        #expect(!f.exists("a (1).txt.macdroid-part"))
        #expect(await manager.snapshot.active.isEmpty)
    }

    @Test func peerCancelDeletesThePart() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await decided(manager) }
        await manager.handle(.transferCancel(TransferID(transferId: "t1")))
        #expect(!f.exists("a.txt.macdroid-part"))
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: true))
    }

    @Test func localCancelWhileOfflineIsSentAfterReconnect() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.connectionChanged(true)
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await decided(manager) }
        await f.phone.setOnline(false)
        await manager.connectionChanged(false)
        await manager.cancel(id: "t1")
        #expect(await !f.phone.sent.contains { $0.cancelledID == "t1" })
        await f.phone.setOnline(true)
        await manager.connectionChanged(true)
        #expect(await f.phone.sent.contains { $0.cancelledID == "t1" })
    }

    @Test func diskFullMidTransferReportsNoSpace() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", size: 10))
        try await eventually { await decided(manager) }
        await manager.uploadFailed(id: "t1", .noSpace)
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "t1", ok: false, error: .noSpace) })
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    // Review Focus #5
    @Test func zeroByteFileCompletes() async throws {
        let f = try TransferFixture()
        let manager = f.manager()
        await manager.handle(makeOffer("t1", name: "empty.txt", size: 0))
        try await eventually { await decided(manager) }
        await manager.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(Data()))))
        #expect(f.exists("empty.txt"))
        #expect(await manager.snapshot.history.first?.phase == .done)
    }

    // Review Focus #2
    @Test func relaunchResumesAndChecksumStillMatches() async throws {
        let f = try TransferFixture()
        let data = testBytes(500_000)
        do {
            let first = f.manager()
            await first.handle(makeOffer("t1", name: "big.bin", size: Int64(data.count)))
            try await eventually { await decided(first) }
            try await upload(data.prefix(200_000), to: first, id: "t1")
        }
        f.clock.advance(3600)
        let second = f.manager() // relaunch: state comes back from disk
        #expect(await second.snapshot.active.map(\.phase) == [.paused])
        await second.handle(makeOffer("t1", name: "big.bin", size: Int64(data.count)))
        try await eventually { await f.phone.sent.filter { $0.acceptedID == "t1" }.count == 2 }
        #expect(await second.uploadOffset(id: "t1") == 200_000)
        try await upload(data.dropFirst(200_000), to: second, id: "t1", from: 200_000)
        await second.handle(.transferFinish(TransferFinish(transferId: "t1", sha256: Checksum.sha256Hex(data))))
        #expect(try Data(contentsOf: f.file("big.bin")) == data)
    }

    @Test func stateOlderThan24HoursIsCleanedAtLaunch() async throws {
        let f = try TransferFixture()
        do {
            let first = f.manager()
            await first.handle(makeOffer("t1", size: 10))
            try await eventually { await decided(first) }
        }
        f.clock.advance(25 * 3600)
        let second = f.manager()
        #expect(await second.snapshot.active.isEmpty)
        #expect(await second.snapshot.history.first?.phase == .failed(.expired))
        #expect(!f.exists("a.txt.macdroid-part"))
    }

    @Test func historyKeepsTheLatest100() async throws {
        let f = try TransferFixture()
        let manager = f.manager(location: FixedLocation(nil))
        for index in 0..<105 { await manager.handle(makeOffer("t\(index)", size: 1)) }
        try await eventually { await manager.snapshot.history.count == 100 } // 105 declined; the oldest 5 dropped
    }

    @Test func undecidedOfferIsVisibleAndTheProviderGetsItsDetails() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", name: "../IMG.jpg", size: 42, mime: "image/jpeg"))
        try await eventually { await gate.asked.count == 1 }
        #expect(await gate.asked == [IncomingOffer(id: "t1", name: "IMG.jpg", size: 42, mime: "image/jpeg")])
        let pending = try #require(await manager.snapshot.active.first)
        #expect(pending.phase == .offered && pending.direction == .fromPhone && pending.folder == nil && pending.name == "IMG.jpg")
        await gate.answer("t1", with: .folder(f.downloads))
        try await eventually { await manager.snapshot.active.first?.phase == .active }
        #expect(await f.phone.sent.contains { $0.acceptedID == "t1" })
        #expect(await manager.snapshot.active.count == 1) // the pending record became the transfer, not a second row
    }

    // Review Focus #1
    @Test func peerCancelWhileDecidingCancelsTheQuestion() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.handle(.transferCancel(TransferID(transferId: "t1")))
        try await eventually { await gate.cancelled == ["t1"] }
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: true))
        #expect(await manager.snapshot.active.isEmpty)
        #expect(await !f.phone.sent.contains { $0.acceptedID == "t1" || $0.rejection != nil })
    }

    @Test func localCancelWhileDecidingTellsThePhone() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.cancel(id: "t1")
        try await eventually { await gate.cancelled == ["t1"] }
        #expect(await f.phone.sent.contains { $0.cancelledID == "t1" })
        #expect(await manager.snapshot.history.first?.phase == .cancelled(byPeer: false))
    }

    @Test func reofferWhileDecidingIsIgnored() async throws {
        let f = try TransferFixture()
        let gate = GateLocation()
        let manager = f.manager(location: gate)
        await manager.handle(makeOffer("t1", size: 1))
        try await eventually { await gate.asked.count == 1 }
        await manager.handle(makeOffer("t1", size: 1))
        #expect(await gate.asked.count == 1)
        #expect(await f.phone.sent.isEmpty)
    }

    // Review Focus #5
    @Test func relaunchForgetsUndecidedOffers() async throws {
        let f = try TransferFixture()
        do {
            let first = f.manager(location: GateLocation())
            await first.handle(makeOffer("t1", size: 1))
            try await eventually { await first.snapshot.active.count == 1 } // undecided on purpose
        }
        let second = f.manager()
        #expect(await second.snapshot.active.isEmpty)
        await second.handle(makeOffer("t1", size: 1)) // the phone re-offers after reconnecting: a fresh decision
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "t1" } }
    }
}
