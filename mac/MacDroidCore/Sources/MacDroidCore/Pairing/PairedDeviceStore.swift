import Foundation
import NIOConcurrencyHelpers

public struct PairedDevice: Codable, Equatable, Sendable {
    public var fingerprint: String
    public var name: String
    public var model: String
    public var pairedAt: Date

    public init(fingerprint: String, name: String, model: String, pairedAt: Date) {
        self.fingerprint = fingerprint
        self.name = name
        self.model = model
        self.pairedAt = pairedAt
    }
}

public protocol PairedDeviceStore: Sendable {
    func load() -> PairedDevice?
    func save(_ device: PairedDevice?)
}

public final class InMemoryPairedDeviceStore: PairedDeviceStore {
    private let box: NIOLockedValueBox<PairedDevice?>

    public init(initial: PairedDevice? = nil) { box = NIOLockedValueBox(initial) }
    public func load() -> PairedDevice? { box.withLockedValue { $0 } }
    public func save(_ device: PairedDevice?) { box.withLockedValue { $0 = device } }
}

/// `UserDefaults` is documented as thread-safe, hence `@unchecked Sendable`.
public final class UserDefaultsPairedDeviceStore: PairedDeviceStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults = .standard, key: String = "pairedDevice") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> PairedDevice? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(PairedDevice.self, from: $0) }
    }

    public func save(_ device: PairedDevice?) {
        if let device, let data = try? JSONEncoder().encode(device) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}
