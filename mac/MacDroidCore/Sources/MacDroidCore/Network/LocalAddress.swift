import Darwin

public enum LocalAddress {
    public struct Candidate: Equatable, Sendable {
        public var interface: String
        public var address: String

        public init(interface: String, address: String) {
            self.interface = interface
            self.address = address
        }
    }

    /// The IPv4 address the phone should dial: a LAN interface, never VPN/loopback/link-local.
    public static func primaryIPv4() -> String? { pick(from: interfaces()) }

    static let excludedPrefixes = ["lo", "utun", "ipsec", "ppp", "awdl", "llw", "bridge", "gif", "stf", "anpi", "ap"]

    static func pick(from candidates: [Candidate]) -> String? {
        let usable = candidates.filter { candidate in
            !candidate.address.hasPrefix("127.")
                && !candidate.address.hasPrefix("169.254.")
                && !excludedPrefixes.contains { candidate.interface.hasPrefix($0) }
        }
        let ethernetLike = usable
            .filter { $0.interface.hasPrefix("en") }
            .sorted { interfaceIndex($0.interface) < interfaceIndex($1.interface) }
        return (ethernetLike.first ?? usable.first)?.address
    }

    static func interfaceIndex(_ name: String) -> Int {
        Int(name.drop { !$0.isNumber }) ?? Int.max
    }

    static func interfaces() -> [Candidate] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var result: [Candidate] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(pointer.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0,
                  let address = pointer.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let hostString = String(decoding: host.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            result.append(Candidate(interface: String(cString: pointer.pointee.ifa_name), address: hostString))
        }
        return result
    }
}
