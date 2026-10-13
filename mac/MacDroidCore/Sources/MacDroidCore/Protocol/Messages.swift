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

/// A control-channel message. Later plans add cases (notifications, clipboard, transfers).
public enum Message: Equatable, Sendable {
    case hello(Hello)
    case deviceStatus(DeviceStatus)
    /// A type this version doesn't understand. Decoded so callers can ignore it; never encoded.
    case unknown(type: String)
}
