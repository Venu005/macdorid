import Foundation
import Testing
@testable import MacDroidCore

@Suite struct TransferPresentationTests {
    func record(_ direction: TransferDirection, _ phase: TransferPhase, size: Int64 = 2_100_000_000, done: Int64 = 0,
                bookmark: Data? = nil, saved: URL? = nil) -> TransferRecord {
        TransferRecord(id: "t", direction: direction, name: "clip.mov", size: size, mime: "video/quicktime", phase: phase,
                       bytesDone: done, sourceBookmark: bookmark, savedURL: saved,
                       createdAt: Date(timeIntervalSince1970: 0), updatedAt: Date(timeIntervalSince1970: 0))
    }

    @Test func activeRowShowsProgressSpeedAndTimeLeft() {
        let row = TransferPresentation.row(record(.toPhone, .active, done: 1_300_000_000), rate: 48_000_000)
        #expect(row.title == "clip.mov")
        #expect(row.directionLabel == "→ phone")
        #expect(row.detail == "1.3 of 2.1 GB · 48 MB/s · 17 s left")
        #expect(abs((row.progress ?? 0) - 1_300_000_000.0 / 2_100_000_000.0) < 0.0001)
        #expect(row.canCancel && !row.canRetry && !row.isError)
    }

    @Test func waitingStates() {
        #expect(TransferPresentation.row(record(.toPhone, .queued), rate: nil).detail == "Waiting to send")
        #expect(TransferPresentation.row(record(.toPhone, .offered), rate: nil).detail == "Waiting for your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .offered), rate: nil).detail == "Waiting for you to choose where to save")
        #expect(TransferPresentation.row(record(.fromPhone, .paused, done: 1_050_000_000), rate: nil).detail == "Paused — waiting for connection")
        #expect(TransferPresentation.row(record(.fromPhone, .paused, done: 1_050_000_000), rate: nil).progress == 0.5)
        #expect(TransferPresentation.row(record(.fromPhone, .verifying), rate: nil).detail == "Checking…")
        #expect(TransferPresentation.row(record(.fromPhone, .active), rate: nil).directionLabel == "← phone")
    }

    @Test func finishedRows() {
        let received = TransferPresentation.row(record(.fromPhone, .done, size: 3_145_728, saved: URL(fileURLWithPath: "/x/clip.mov")), rate: nil)
        #expect(received.detail == "Received · 3.1 MB")
        #expect(received.canOpen && !received.canCancel && !received.canRetry)
        #expect(TransferPresentation.row(record(.toPhone, .done, size: 512), rate: nil).detail == "Sent · 512 bytes")
        #expect(TransferPresentation.row(record(.toPhone, .cancelled(byPeer: true)), rate: nil).detail == "Cancelled on your phone")
        #expect(TransferPresentation.row(record(.toPhone, .cancelled(byPeer: false)), rate: nil).detail == "Cancelled on your Mac")
    }

    @Test func failuresAreErrorsAndOutgoingOnesCanBeRetried() {
        let corrupted = TransferPresentation.row(record(.toPhone, .failed(.checksumMismatch), bookmark: Data([1])), rate: nil)
        #expect(corrupted.detail == "Transfer failed — file was corrupted in transit")
        #expect(corrupted.isError && corrupted.canRetry)
        #expect(!TransferPresentation.row(record(.fromPhone, .failed(.io)), rate: nil).canRetry) // only the sender has the file
        #expect(TransferPresentation.row(record(.toPhone, .failed(.noSpace)), rate: nil).detail == "Not enough space on your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .failed(.noSpace)), rate: nil).detail == "Not enough space on this Mac")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.declined)), rate: nil).detail == "Declined on your phone")
        #expect(TransferPresentation.row(record(.fromPhone, .failed(.declined)), rate: nil).detail == "Not saved")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.sourceUnavailable)), rate: nil).detail == "The original file is no longer available")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.expired)), rate: nil).detail == "Expired — not finished within 24 hours")
        #expect(TransferPresentation.row(record(.toPhone, .failed(.io)), rate: nil).detail == "Transfer failed")
    }

    @Test func popoverShowsActiveTransfersElseTheLastReceivedFile() {
        let a = record(.toPhone, .active), b = record(.fromPhone, .active), c = record(.toPhone, .queued)
        #expect(TransferPresentation.popoverItems(TransferSnapshot(active: [a, b, c], history: [], rates: [:])).count == 2)
        let sent = record(.toPhone, .done), received = record(.fromPhone, .done)
        #expect(TransferPresentation.popoverItems(TransferSnapshot(active: [], history: [sent, received], rates: [:])) == [received])
        #expect(TransferPresentation.popoverItems(.empty).isEmpty)
    }

    @Test func dropSummary() {
        #expect(TransferPresentation.dropSummary(count: 3, totalBytes: 12_400_000) == "3 files · 12.4 MB")
        #expect(TransferPresentation.dropSummary(count: 1, totalBytes: 512) == "1 file · 512 bytes")
    }
}
