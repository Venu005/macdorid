import Foundation

/// What one transfer row shows on the Files page and in the popover.
public struct TransferRowModel: Equatable, Sendable {
    public var title: String
    public var directionLabel: String
    public var detail: String
    /// 0…1 while bytes are moving or paused; nil when there's no bar to show.
    public var progress: Double?
    public var isError: Bool
    public var canCancel: Bool
    public var canRetry: Bool
    public var canOpen: Bool
}

/// Pure transfer → text mapping for the Mac UI. The Android app mirrors it with "phone"/"Mac" swapped.
public enum TransferPresentation {
    public static func row(_ record: TransferRecord, rate: Double?) -> TransferRowModel {
        let toPhone = record.direction == .toPhone
        let fraction = record.size > 0 ? min(1, Double(record.bytesDone) / Double(record.size)) : nil
        let canRetry = toPhone && record.sourceBookmark != nil
        var row = TransferRowModel(
            title: record.name, directionLabel: toPhone ? "→ phone" : "← phone", detail: "", progress: nil,
            isError: false, canCancel: !record.phase.isFinished, canRetry: false, canOpen: false
        )
        switch record.phase {
        case .queued:
            row.detail = "Waiting to send"
        case .offered:
            row.detail = toPhone ? "Waiting for your phone" : "Waiting for you to choose where to save"
        case .active:
            row.detail = TransferText.line(done: record.bytesDone, total: record.size, bytesPerSecond: rate)
            row.progress = fraction
        case .paused:
            row.detail = "Paused — waiting for connection"
            row.progress = fraction
        case .verifying:
            row.detail = "Checking…"
            row.progress = 1
        case .done:
            row.detail = "\(toPhone ? "Sent" : "Received") · \(TransferText.bytes(record.size))"
            row.canOpen = !toPhone && record.savedURL != nil
        case .failed(let failure):
            row.detail = failureText(failure, toPhone: toPhone)
            row.isError = true
            row.canRetry = canRetry
        case .cancelled(let byPeer):
            row.detail = byPeer ? "Cancelled on your phone" : "Cancelled on your Mac"
            row.canRetry = canRetry
        }
        return row
    }

    /// The popover shows up to `limit` running transfers, otherwise the last received file.
    public static func popoverItems(_ snapshot: TransferSnapshot, limit: Int = 2) -> [TransferRecord] {
        if !snapshot.active.isEmpty { return Array(snapshot.active.prefix(limit)) }
        return Array(snapshot.history.filter { $0.direction == .fromPhone && $0.phase == .done }.prefix(1))
    }

    /// "3 files · 12.4 MB", shown while files are dragged over the popover.
    public static func dropSummary(count: Int, totalBytes: Int64) -> String {
        "\(count) \(count == 1 ? "file" : "files") · \(TransferText.bytes(totalBytes))"
    }

    static func failureText(_ failure: TransferFailure, toPhone: Bool) -> String {
        switch failure {
        case .checksumMismatch: "Transfer failed — file was corrupted in transit"
        case .noSpace: toPhone ? "Not enough space on your phone" : "Not enough space on this Mac"
        case .declined: toPhone ? "Declined on your phone" : "Not saved"
        case .sourceUnavailable: "The original file is no longer available"
        case .expired: "Expired — not finished within 24 hours"
        case .io, .invalid: "Transfer failed"
        }
    }
}
