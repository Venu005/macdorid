import Foundation

public struct PairingWindow: Equatable, Sendable {
    public let code: String
    public let expiresAt: Date
}

public enum PairingError: Error, Equatable {
    case windowClosed
    case codeMismatch
}

public enum PairingEvent: Equatable, Sendable {
    case windowOpened(PairingWindow)
    case windowClosed
    case paired(PairedDevice)
    case forgotten
}

/// Owns who the Mac trusts. Exactly one paired phone; re-pairing replaces it.
public actor PairingManager {
    /// Single-consumer stream of state changes (the app model listens).
    public nonisolated let events: AsyncStream<PairingEvent>
    private let continuation: AsyncStream<PairingEvent>.Continuation
    private let store: any PairedDeviceStore
    private let windowDuration: TimeInterval
    private let now: @Sendable () -> Date
    private var window: PairingWindow?
    public private(set) var pairedDevice: PairedDevice?

    public init(
        store: any PairedDeviceStore,
        windowDuration: TimeInterval = 120,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        let (stream, continuation) = AsyncStream.makeStream(of: PairingEvent.self)
        self.events = stream
        self.continuation = continuation
        self.store = store
        self.windowDuration = windowDuration
        self.now = now
        self.pairedDevice = store.load()
    }

    @discardableResult
    public func openWindow() -> PairingWindow {
        let opened = PairingWindow(code: Self.makeCode(), expiresAt: now().addingTimeInterval(windowDuration))
        window = opened
        continuation.yield(.windowOpened(opened))
        return opened
    }

    public func closeWindow() {
        guard window != nil else { return }
        window = nil
        continuation.yield(.windowClosed)
    }

    /// The open window, or nil. Expiry is detected lazily here.
    public func currentWindow() -> PairingWindow? {
        guard let window else { return nil }
        if now() >= window.expiresAt {
            closeWindow()
            return nil
        }
        return window
    }

    public func isTrusted(fingerprint: String) -> Bool {
        pairedDevice?.fingerprint == fingerprint
    }

    /// TLS-level gate: the paired phone always, anyone else only while pairing is open.
    public func acceptsHandshake(fingerprint: String) -> Bool {
        isTrusted(fingerprint: fingerprint) || currentWindow() != nil
    }

    public func completePairing(code: String, fingerprint: String, deviceName: String, model: String) throws -> PairedDevice {
        guard let window = currentWindow() else { throw PairingError.windowClosed }
        guard Self.constantTimeEquals(code, window.code) else { throw PairingError.codeMismatch }
        let device = PairedDevice(fingerprint: fingerprint, name: deviceName, model: model, pairedAt: now())
        pairedDevice = device
        store.save(device)
        closeWindow()
        continuation.yield(.paired(device))
        return device
    }

    public func forget() {
        pairedDevice = nil
        store.save(nil)
        continuation.yield(.forgotten)
    }

    public static func makeCode() -> String {
        var generator = SystemRandomNumberGenerator()
        return base32((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }

    /// RFC 4648 base32, no padding.
    static func base32(_ bytes: [UInt8]) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var output = ""
        var buffer = 0
        var bits = 0
        for byte in bytes {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                output.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
            buffer &= (1 << bits) - 1
        }
        if bits > 0 { output.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return output
    }

    static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for index in a.indices { difference |= a[index] ^ b[index] }
        return difference == 0
    }
}
