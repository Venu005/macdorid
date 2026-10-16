import dnssd
import Foundation

public enum BonjourError: Error, Equatable {
    case registrationFailed(Int32)
}

/// Advertises the server with DNS-SD. All state is confined to `queue`, hence `@unchecked Sendable`.
public final class BonjourAdvertiser: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.macdroid.bonjour")
    private var service: DNSServiceRef?
    /// A registration that fails after `start` returned (mDNSResponder restarted, …). Called on the advertiser's queue.
    /// Name conflicts are resolved by DNS-SD's automatic rename, so they don't arrive here.
    public var onError: (@Sendable (String) -> Void)?

    public init() {}

    public func start(name: String, port: UInt16, txt: [String: String], serviceType: String = ProtocolInfo.serviceType) throws {
        try queue.sync {
            stopLocked()
            let record = Self.encodeTXT(txt)
            var reference: DNSServiceRef?
            let context = Unmanaged.passUnretained(self).toOpaque()
            let error = record.withUnsafeBytes { buffer in
                DNSServiceRegister(&reference, 0, 0, name, serviceType, nil, nil, port.bigEndian,
                                   UInt16(buffer.count), buffer.baseAddress, Self.registered, context)
            }
            guard error == kDNSServiceErr_NoError, let reference else {
                throw BonjourError.registrationFailed(error)
            }
            let attached = DNSServiceSetDispatchQueue(reference, queue)
            guard attached == kDNSServiceErr_NoError else {
                DNSServiceRefDeallocate(reference)
                throw BonjourError.registrationFailed(attached)
            }
            service = reference
        }
    }

    public func stop() {
        queue.sync { stopLocked() }
    }

    private func stopLocked() {
        if let service { DNSServiceRefDeallocate(service) }
        service = nil
    }

    deinit {
        if let service { DNSServiceRefDeallocate(service) }
    }

    /// The registration result, on `queue` (the advertiser outlives its service: `stop`/`deinit` deallocate it first).
    private static let registered: DNSServiceRegisterReply = { _, _, error, _, _, _, context in
        guard error != kDNSServiceErr_NoError, let context else { return }
        let advertiser = Unmanaged<BonjourAdvertiser>.fromOpaque(context).takeUnretainedValue()
        advertiser.onError?("registration failed (\(error))")
    }

    /// DNS-SD TXT wire format: length-prefixed "key=value" strings, keys sorted for stable output. Each is at most
    /// 255 bytes, cut on a character boundary (Plan 11).
    static func encodeTXT(_ entries: [String: String]) -> [UInt8] {
        entries.sorted { $0.key < $1.key }.flatMap { key, value -> [UInt8] in
            var text = "\(key)=\(value)"
            while text.utf8.count > 255 { text.removeLast() }
            let bytes = Array(text.utf8)
            return [UInt8(bytes.count)] + bytes
        }
    }
}
