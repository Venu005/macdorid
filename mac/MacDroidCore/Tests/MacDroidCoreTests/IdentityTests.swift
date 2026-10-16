import Foundation
import Testing
import X509
@testable import MacDroidCore

@Suite struct IdentityTests {
    @Test func fingerprintOfKnownBytes() {
        #expect(Fingerprint.sha256Hex(Array("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #expect(Fingerprint.short("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == "ba7816bf8f01cfea")
        #expect(Fingerprint.display("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") == "BA78 16BF 8F01 CFEA")
    }

    @Test func generatedIdentityIsSelfSignedWithCommonName() throws {
        let identity = try Identity.generate(commonName: "Test Mac")
        let certificate = try Certificate(derEncoded: identity.certificateDER)
        #expect(certificate.subject == certificate.issuer)
        #expect(certificate.subject.description.contains("Test Mac"))
        #expect(identity.fingerprint.count == 64)
        #expect(identity.fingerprint == identity.fingerprint.lowercased())
    }

    @Test func nioConversionsKeepTheFingerprint() throws {
        let identity = try Identity.generate(commonName: "Test Mac")
        #expect(try Fingerprint.of(identity.nioCertificate()) == identity.fingerprint)
        _ = try identity.nioPrivateKey()
        #expect(try identity.certificatePEM().hasPrefix("-----BEGIN CERTIFICATE-----"))
    }

    @Test func identitiesAreUnique() throws {
        #expect(try Identity.generate(commonName: "A").fingerprint != Identity.generate(commonName: "A").fingerprint)
    }

    @Test func loadOrCreateReturnsTheSameIdentity() throws {
        let store = InMemoryIdentityStore()
        let first = try store.loadOrCreate(commonName: "Mac")
        let second = try store.loadOrCreate(commonName: "Mac")
        #expect(first.fingerprint == second.fingerprint)
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["CI"] == nil, "touches the login keychain"))
    func keychainRoundTrip() throws {
        let store = KeychainIdentityStore(service: "dev.macdroid.tests.\(UUID().uuidString)")
        defer { store.delete() }
        #expect(try store.load() == nil)
        let identity = try Identity.generate(commonName: "Keychain Test")
        try store.save(identity)
        let loaded = try #require(try store.load())
        #expect(loaded.fingerprint == identity.fingerprint)
        #expect(loaded.privateKey.rawRepresentation == identity.privateKey.rawRepresentation)
    }

    // Plan 11: saving over an existing identity updates it in place (no delete-then-add window).
    @Test(.enabled(if: ProcessInfo.processInfo.environment["CI"] == nil, "touches the login keychain"))
    func keychainOverwriteKeepsTheItem() throws {
        let store = KeychainIdentityStore(service: "dev.macdroid.tests.\(UUID().uuidString)")
        defer { store.delete() }
        try store.save(try Identity.generate(commonName: "First"))
        let second = try Identity.generate(commonName: "Second")
        try store.save(second)
        #expect(try store.load()?.fingerprint == second.fingerprint)
    }
}
