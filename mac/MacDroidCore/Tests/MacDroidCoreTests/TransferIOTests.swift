import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferModelTests {
    @Test func recordRoundTripsThroughJSONFileStore() throws {
        let dir = try makeTemporaryDirectory()
        let store = JSONFileStore<[TransferRecord]>(url: dir.appending(path: "t.json"))
        #expect(store.load() == nil)
        let record = TransferRecord(id: "t1", direction: .fromPhone, name: "a.txt", size: 3, mime: "text/plain",
                                    phase: .failed(.checksumMismatch), folder: dir, createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 2))
        try store.save([record, TransferRecord(id: "t2", direction: .toPhone, name: "b", size: 0, mime: "x", phase: .cancelled(byPeer: true),
                                               createdAt: Date(timeIntervalSince1970: 1), updatedAt: Date(timeIntervalSince1970: 2))])
        #expect(store.load()?.map(\.phase) == [.failed(.checksumMismatch), .cancelled(byPeer: true)])
        #expect(store.load()?.first?.partURL == dir.appending(path: "a.txt.macdroid-part"))
    }

    @Test func corruptStoreLoadsAsNil() throws {
        let url = try makeTemporaryDirectory().appending(path: "t.json")
        try Data("not json".utf8).write(to: url)
        #expect(JSONFileStore<[TransferRecord]>(url: url).load() == nil)
    }

    @Test func phaseFlags() {
        #expect(TransferPhase.done.isFinished && TransferPhase.failed(.io).isFinished && TransferPhase.cancelled(byPeer: false).isFinished)
        #expect(!TransferPhase.paused.isFinished)
        let slotPhases: [TransferPhase] = [.offered, .active, .paused, .verifying]
        #expect(slotPhases.allSatisfy { $0.occupiesSlot })
        #expect(!TransferPhase.queued.occupiesSlot)
    }

    @Test func rateIsSmoothedAndSampledAtMostTwiceASecond() {
        var rate = TransferRate()
        let start = Date(timeIntervalSince1970: 0)
        rate.record(bytes: 0, at: start)
        #expect(rate.bytesPerSecond == nil)
        rate.record(bytes: 500_000, at: start.addingTimeInterval(0.2)) // too soon: ignored
        #expect(rate.bytesPerSecond == nil)
        rate.record(bytes: 1_000_000, at: start.addingTimeInterval(1))
        #expect(rate.bytesPerSecond == 1_000_000)
        rate.record(bytes: 3_000_000, at: start.addingTimeInterval(2))
        #expect(abs((rate.bytesPerSecond ?? 0) - 1_300_000) < 0.001) // 0.7 × 1 MB/s + 0.3 × 2 MB/s
    }
}

@Suite struct PartFileTests {
    @Test func writerHashesEverythingItAppends() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.bin.macdroid-part")
        let data = testBytes(3_000_000)
        let writer = PartFileWriter(url: url, expectedSize: Int64(data.count))
        let session = try await writer.beginSession()
        try await writer.append(data.prefix(1_000_000), session: session)
        try await writer.append(data.dropFirst(1_000_000), session: session)
        await writer.flush()
        #expect(await writer.size == 3_000_000)
        #expect(await writer.digest() == Checksum.sha256Hex(data))
        #expect(try Data(contentsOf: url) == data)
    }

    @Test func reopenedWriterRehashesExistingBytes() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.bin.macdroid-part")
        let data = testBytes(2_500_000)
        try data.prefix(1_234_567).write(to: url)
        let writer = PartFileWriter(url: url, expectedSize: Int64(data.count))
        let session = try await writer.beginSession()
        #expect(await writer.size == 1_234_567)
        try await writer.append(data.dropFirst(1_234_567), session: session)
        #expect(await writer.digest() == Checksum.sha256Hex(data))
    }

    @Test func newSessionSupersedesOldOne() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.macdroid-part")
        let writer = PartFileWriter(url: url, expectedSize: 10)
        let old = try await writer.beginSession()
        _ = try await writer.beginSession()
        await #expect(throws: PartFileError.superseded) { try await writer.append(Data([1]), session: old) }
    }

    @Test func refusesMoreThanTheOfferedSize() async throws {
        let url = try makeTemporaryDirectory().appending(path: "a.macdroid-part")
        let writer = PartFileWriter(url: url, expectedSize: 2)
        let session = try await writer.beginSession()
        await #expect(throws: PartFileError.tooLarge) { try await writer.append(Data([1, 2, 3]), session: session) }
    }

    @Test func diskFullErrorsAreRecognised() {
        #expect(PartFileError.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))) == .noSpace)
        #expect(PartFileError.classify(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)) == .noSpace)
        let wrapped = NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError,
                              userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))])
        #expect(PartFileError.classify(wrapped) == .noSpace)
        #expect(PartFileError.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(EIO))) == .io)
    }

    @Test func readerFromOffsetStillDigestsTheWholeFile() throws {
        let url = try makeTemporaryDirectory().appending(path: "src.bin")
        let data = testBytes(2_200_000)
        try data.write(to: url)
        let reader = try SourceReader(url: url, offset: 1_500_000)
        var tail = Data()
        while let chunk = try reader.next() { tail.append(chunk) }
        #expect(tail == data.dropFirst(1_500_000))
        #expect(reader.position == 2_200_000)
        #expect(reader.digest() == Checksum.sha256Hex(data))
        reader.close()
    }

    @Test func readerAtEndReturnsNothingButFullDigest() throws {
        let url = try makeTemporaryDirectory().appending(path: "src.bin")
        let data = testBytes(10)
        try data.write(to: url)
        let reader = try SourceReader(url: url, offset: 10)
        #expect(try reader.next() == nil)
        #expect(reader.digest() == Checksum.sha256Hex(data))
    }
}
