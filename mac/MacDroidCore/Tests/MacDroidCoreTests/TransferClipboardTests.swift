import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferClipboardTests {
    func clipboardOffer(_ id: String, size: Int64) -> Message {
        .transferOffer(TransferOffer(transferId: id, name: "clipboard.png", size: size, mime: "image/png", purpose: .clipboard))
    }

    func manager(_ f: TransferFixture, clipboard: URL?, location: any ReceiveLocationProvider) -> TransferManager {
        let phone = f.phone, clock = f.clock
        return TransferManager(
            stateDirectory: f.state, location: location, freeSpace: { _ in nil }, clipboardDirectory: clipboard,
            now: { clock.now }, send: { try await phone.send($0) }
        )
    }

    // Review Focus #4
    @Test func clipboardOfferSkipsAskModeAndHistory() async throws {
        let f = try TransferFixture()
        let clipboard = try makeTemporaryDirectory()
        let ask = GateLocation() // would wait forever if asked
        let m = manager(f, clipboard: clipboard, location: ask)
        let events = Collector<TransferEvent>()
        let stream = m.events
        let listener = Task { for await event in stream { await events.append(event) } }
        let png = testBytes(5_000)
        await m.handle(clipboardOffer("c1", size: Int64(png.count)))
        try await eventually { await f.phone.sent.contains { $0.acceptedID == "c1" } }
        #expect(await ask.asked.isEmpty)
        #expect(await m.snapshot.active.isEmpty) // hidden from the Files UI
        try await upload(png, to: m, id: "c1")
        await m.handle(.transferFinish(TransferFinish(transferId: "c1", sha256: Checksum.sha256Hex(png))))
        try await eventually { await events.items.count == 1 }
        guard case .clipboardImageReceived(let url) = await events.items[0] else { Issue.record("wrong event"); return }
        #expect(url.deletingLastPathComponent().standardizedFileURL == clipboard.standardizedFileURL)
        #expect(try Data(contentsOf: url) == png)
        #expect(await m.snapshot.history.isEmpty)
        #expect(await f.phone.sent.contains { $0.result == TransferResult(transferId: "c1", ok: true) })
        listener.cancel()
    }

    @Test func oversizedClipboardOfferIsRejectedAsInvalid() async throws {
        let f = try TransferFixture()
        let m = manager(f, clipboard: try makeTemporaryDirectory(), location: FixedLocation(.folder(f.downloads)))
        await m.handle(clipboardOffer("c1", size: Int64(ClipboardLimits.maxImageBytes) + 1))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "c1", reason: .invalid) } }
        #expect(await m.snapshot.history.isEmpty)
    }

    @Test func clipboardOfferWithoutAClipboardFolderIsRejected() async throws {
        let f = try TransferFixture()
        let m = manager(f, clipboard: nil, location: FixedLocation(.folder(f.downloads)))
        await m.handle(clipboardOffer("c1", size: 10))
        try await eventually { await f.phone.sent.contains { $0.rejection == TransferReject(transferId: "c1", reason: .invalid) } }
    }

    @Test func sendingAClipboardImageOffersItWithThePurposeAndHidesIt() async throws {
        let f = try TransferFixture()
        let m = manager(f, clipboard: try makeTemporaryDirectory(), location: FixedLocation(.folder(f.downloads)))
        await m.connectionChanged(true)
        let file = f.file("shot.png")
        try testBytes(2_000).write(to: file)
        #expect(await m.sendClipboardImage(file))
        try await eventually { await f.phone.sent.contains { $0.offerBody?.purpose == .clipboard } }
        let offer = try #require(await f.phone.sent.compactMap(\.offerBody).first)
        #expect(offer.mime == "image/png")
        #expect(await m.snapshot.active.isEmpty)
        await m.handle(.transferResult(TransferResult(transferId: offer.transferId, ok: true)))
        #expect(await m.snapshot.history.isEmpty)
    }

    @Test func oversizedClipboardImagesAreNotSent() async throws {
        let f = try TransferFixture()
        let m = manager(f, clipboard: try makeTemporaryDirectory(), location: FixedLocation(.folder(f.downloads)))
        let file = f.file("big.png")
        try Data(count: ClipboardLimits.maxImageBytes + 1).write(to: file)
        #expect(await m.sendClipboardImage(file) == false)
    }
}
