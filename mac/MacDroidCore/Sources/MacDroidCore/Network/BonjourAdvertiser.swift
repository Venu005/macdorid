import dnssd
import Foundation

public enum BonjourError: Error, Equatable {
    case registrationFailed(Int32)
}

/// Advertises the server with DNS-SD. All state is confined to `queue`, hence `@unchecked Sendable`.
public final class BonjourAdvertiser: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.macdroid.bonjour")
    private var service: DNSServiceRef?

    public init() {}

    public func start(name: String, port: UInt16, txt: [String: String], serviceType: String = ProtocolInfo.serviceType) throws {
        try queue.sync {
            stopLocked()
            let record = Self.encodeTXT(txt)
            var reference: DNSServiceRef?
            let error = record.withUnsafeBytes { buffer in
                DNSServiceRegister(&reference, 0, 0, name, serviceType, nil, nil, port.bigEndian,
                                   UInt16(buffer.count), buffer.baseAddress, nil, nil)
            }
            guard error == kDNSServiceErr_NoError, let reference else {
                throw BonjourError.registrationFailed(error)
            }
            DNSServiceSetDispatchQueue(reference, queue)
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

    /// DNS-SD TXT wire format: length-prefixed "key=value" strings, keys sorted for stable output.
    static func encodeTXT(_ entries: [String: String]) -> [UInt8] {
        entries.sorted { $0.key < $1.key }.flatMap { key, value -> [UInt8] in
            let bytes = Array("\(key)=\(value)".utf8.prefix(255))
            return [UInt8(bytes.count)] + bytes
        }
    }
}
