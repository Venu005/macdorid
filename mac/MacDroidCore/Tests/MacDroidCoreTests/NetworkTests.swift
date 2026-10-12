import Testing
@testable import MacDroidCore

@Suite struct NetworkTests {
    typealias C = LocalAddress.Candidate

    // Review Focus #4
    @Test func prefersEthernetLikeInterface() {
        let candidates = [
            C(interface: "lo0", address: "127.0.0.1"),
            C(interface: "utun3", address: "10.8.0.2"),
            C(interface: "bridge100", address: "192.168.64.1"),
            C(interface: "en1", address: "192.168.1.31"),
            C(interface: "en0", address: "192.168.1.20"),
        ]
        #expect(LocalAddress.pick(from: candidates) == "192.168.1.20")
    }

    @Test func skipsLinkLocal() {
        let candidates = [C(interface: "en0", address: "169.254.10.1"), C(interface: "en5", address: "192.168.1.30")]
        #expect(LocalAddress.pick(from: candidates) == "192.168.1.30")
    }

    @Test func fallsBackToOtherInterfaces() {
        let candidates = [C(interface: "lo0", address: "127.0.0.1"), C(interface: "ipsec0", address: "10.0.0.2"), C(interface: "foo0", address: "10.1.1.1")]
        #expect(LocalAddress.pick(from: candidates) == "10.1.1.1")
    }

    @Test func nilWhenOnlyLoopback() {
        #expect(LocalAddress.pick(from: [C(interface: "lo0", address: "127.0.0.1")]) == nil)
    }

    @Test func enumeratesInterfacesWithoutCrashing() {
        // Whatever this machine's interfaces are, a non-nil result must be a dotted-quad IPv4 address.
        guard let address = LocalAddress.primaryIPv4() else { return }
        let parts = address.split(separator: ".", omittingEmptySubsequences: false)
        #expect(parts.count == 4)
        for part in parts {
            #expect(Int(part).map { (0...255).contains($0) } == true, "invalid octet \(part) in \(address)")
        }
    }

    @Test func txtRecordEncoding() {
        let bytes = BonjourAdvertiser.encodeTXT(["v": "1", "fp": "8a3ba983c4388481"])
        #expect(bytes == [19] + Array("fp=8a3ba983c4388481".utf8) + [3] + Array("v=1".utf8))
    }

    @Test func advertiserRegistersAndStops() throws {
        let advertiser = BonjourAdvertiser()
        try advertiser.start(name: "MacDroid Test", port: 47_998, txt: ["v": "1"])
        advertiser.stop()
    }
}
