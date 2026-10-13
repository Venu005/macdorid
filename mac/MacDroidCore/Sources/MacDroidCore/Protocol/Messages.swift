import Foundation

public enum ProtocolInfo {
    public static let version = 1
    public static let serviceType = "_macdroid._tcp"
}

public struct Hello: Codable, Equatable, Sendable {
    public var `protocol`: Int
    public var app: String
    public var device: String
    public var features: [String]

    public init(protocol protocolVersion: Int = ProtocolInfo.version, app: String, device: String, features: [String]) {
        self.protocol = protocolVersion
        self.app = app
        self.device = device
        self.features = features
    }
}

public struct DeviceStatus: Codable, Equatable, Sendable {
    public var battery: Int
    public var charging: Bool

    public init(battery: Int, charging: Bool) {
        self.battery = battery
        self.charging = charging
    }
}

public struct PairRequest: Codable, Equatable, Sendable {
    public var code: String
    public var deviceName: String
    public var model: String

    public init(code: String, deviceName: String, model: String) {
        self.code = code
        self.deviceName = deviceName
        self.model = model
    }
}

public struct PairResponse: Codable, Equatable, Sendable {
    public var macName: String

    public init(macName: String) { self.macName = macName }
}

public struct ServerInfo: Codable, Equatable, Sendable {
    public var macName: String
    public var `protocol`: Int

    public init(macName: String, protocol protocolVersion: Int = ProtocolInfo.version) {
        self.macName = macName
        self.protocol = protocolVersion
    }
}

public struct TransferOffer: Codable, Equatable, Sendable {
    public var transferId: String
    public var name: String
    public var size: Int64
    public var mime: String

    public init(transferId: String, name: String, size: Int64, mime: String) {
        self.transferId = transferId
        self.name = name
        self.size = size
        self.mime = mime
    }
}

/// Body of `transfer.accept` and `transfer.cancel`.
public struct TransferID: Codable, Equatable, Sendable {
    public var transferId: String

    public init(transferId: String) { self.transferId = transferId }
}

public enum TransferRejectReason: String, Codable, Sendable {
    case noSpace = "no_space"
    case declined
    case invalid
}

public struct TransferReject: Codable, Equatable, Sendable {
    public var transferId: String
    public var reason: TransferRejectReason

    public init(transferId: String, reason: TransferRejectReason) {
        self.transferId = transferId
        self.reason = reason
    }
}

public struct TransferFinish: Codable, Equatable, Sendable {
    public var transferId: String
    /// Lowercase hex SHA-256 of the whole file.
    public var sha256: String

    public init(transferId: String, sha256: String) {
        self.transferId = transferId
        self.sha256 = sha256
    }
}

public enum TransferResultError: String, Codable, Sendable {
    case checksumMismatch = "checksum_mismatch"
    case noSpace = "no_space"
    case io
}

public struct TransferResult: Codable, Equatable, Sendable {
    public var transferId: String
    public var ok: Bool
    public var error: TransferResultError?

    public init(transferId: String, ok: Bool, error: TransferResultError? = nil) {
        self.transferId = transferId
        self.ok = ok
        self.error = error
    }
}

/// A control-channel message. Later plans add cases (notifications, clipboard, transfers).
public enum Message: Equatable, Sendable {
    case hello(Hello)
    case deviceStatus(DeviceStatus)
    case transferOffer(TransferOffer)
    case transferAccept(TransferID)
    case transferReject(TransferReject)
    case transferFinish(TransferFinish)
    case transferResult(TransferResult)
    case transferCancel(TransferID)
    /// A type this version doesn't understand. Decoded so callers can ignore it; never encoded.
    case unknown(type: String)
}
