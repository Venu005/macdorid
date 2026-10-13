import Foundation

public enum TransferDirection: String, Codable, Sendable {
    case toPhone, fromPhone
}

public enum TransferFailure: String, Codable, Sendable {
    case checksumMismatch, noSpace, io, declined, invalid, sourceUnavailable, expired

    /// The `transfer.result` error that reports this failure to the sender.
    var resultError: TransferResultError {
        switch self {
        case .checksumMismatch: .checksumMismatch
        case .noSpace: .noSpace
        default: .io
        }
    }

    init(_ reason: TransferRejectReason) {
        switch reason {
        case .noSpace: self = .noSpace
        case .declined: self = .declined
        case .invalid: self = .invalid
        }
    }

    init(_ error: TransferResultError?) {
        switch error {
        case .checksumMismatch: self = .checksumMismatch
        case .noSpace: self = .noSpace
        case .io, nil: self = .io
        }
    }
}

public enum TransferPhase: Codable, Equatable, Sendable {
    /// Outgoing, waiting for one of the 3 slots.
    case queued
    /// Outgoing: offer sent, waiting for the phone. Incoming: deciding where to save.
    case offered
    case active
    /// Disconnected; resumes when the phone is back.
    case paused
    /// All bytes moved; waiting for the checksum verdict.
    case verifying
    case done
    case failed(TransferFailure)
    case cancelled(byPeer: Bool)

    public var isFinished: Bool {
        switch self {
        case .done, .failed, .cancelled: true
        default: false
        }
    }

    /// Counts toward the 3-active-outgoing limit.
    public var occupiesSlot: Bool {
        switch self {
        case .offered, .active, .paused, .verifying: true
        default: false
        }
    }
}

public struct TransferRecord: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var direction: TransferDirection
    /// Outgoing: the file's name. Incoming: the final file name in `folder`.
    public var name: String
    public var size: Int64
    public var mime: String
    public var phase: TransferPhase
    public var bytesDone: Int64
    /// Incoming: the SHA-256 from `transfer.finish`.
    public var expectedSHA256: String?
    /// Outgoing: a bookmark to the source file (security-scoped in the app), so a resume survives a relaunch.
    public var sourceBookmark: Data?
    /// Incoming: the destination folder.
    public var folder: URL?
    /// Incoming: the user picked this exact file in a save panel, so an existing file there is replaced.
    public var replacesExisting: Bool
    /// Incoming: the finished file.
    public var savedURL: URL?
    /// nil for files; `.clipboard` for clipboard images (spec §2.3).
    public var purpose: TransferPurpose?
    public var createdAt: Date
    public var updatedAt: Date

    public init(
        id: String, direction: TransferDirection, name: String, size: Int64, mime: String, phase: TransferPhase,
        bytesDone: Int64 = 0, expectedSHA256: String? = nil, sourceBookmark: Data? = nil, folder: URL? = nil,
        replacesExisting: Bool = false, savedURL: URL? = nil, createdAt: Date, updatedAt: Date, purpose: TransferPurpose? = nil
    ) {
        self.id = id
        self.direction = direction
        self.name = name
        self.size = size
        self.mime = mime
        self.phase = phase
        self.bytesDone = bytesDone
        self.expectedSHA256 = expectedSHA256
        self.sourceBookmark = sourceBookmark
        self.folder = folder
        self.replacesExisting = replacesExisting
        self.savedURL = savedURL
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.purpose = purpose
    }

    /// A clipboard image (spec §2.3): never shown in Files, dropped instead of archived.
    public var isClipboard: Bool { purpose == .clipboard }

    /// Incoming: where bytes land until the checksum is verified. A file chosen in a save panel is written in place:
    /// the sandbox grants access to that exact file only, not to a sibling part file.
    public var partURL: URL? {
        guard let folder else { return nil }
        return replacesExisting ? folder.appending(path: name) : folder.appending(path: name + FileNaming.partSuffix)
    }
}

public struct TransferSnapshot: Equatable, Sendable {
    public var active: [TransferRecord]
    /// Finished transfers, newest first (at most 100).
    public var history: [TransferRecord]
    /// Smoothed bytes per second for transfers moving data right now, by id.
    public var rates: [String: Double]

    public static let empty = TransferSnapshot(active: [], history: [], rates: [:])
}

public enum TransferEvent: Equatable, Sendable {
    /// A file from the phone was verified and saved (`savedURL` is set).
    case received(TransferRecord)
    /// A clipboard image from the phone was verified; the file is in the clipboard folder.
    case clipboardImageReceived(URL)
}

/// What `TransferManager.sendReporting(files:)` queued, and what it couldn't.
public struct SendReport: Equatable, Sendable {
    public struct Skipped: Equatable, Sendable {
        public let name: String
        public let reason: String
    }

    public let ids: [String]
    public let skipped: [Skipped]
}
