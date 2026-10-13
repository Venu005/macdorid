import Crypto
import Foundation
import NIOSSL
import SwiftASN1
import X509

public enum Fingerprint {
    /// Lowercase hex SHA-256 — the value both apps pin.
    public static func sha256Hex<Bytes: Sequence>(_ bytes: Bytes) -> String where Bytes.Element == UInt8 {
        SHA256.hash(data: Array(bytes)).map { String(format: "%02x", $0) }.joined()
    }

    public static func of(_ certificate: NIOSSLCertificate) throws -> String {
        sha256Hex(try certificate.toDERBytes())
    }

    /// First 16 hex characters; used in the Bonjour TXT record.
    public static func short(_ fingerprint: String) -> String {
        String(fingerprint.prefix(16))
    }

    /// Grouped form both apps display for manual comparison, e.g. "8A3B A983 C438 8481".
    public static func display(_ fingerprint: String) -> String {
        let characters = Array(short(fingerprint).uppercased())
        return stride(from: 0, to: characters.count, by: 4)
            .map { String(characters[$0..<min($0 + 4, characters.count)]) }
            .joined(separator: " ")
    }
}

/// The Mac's long-lived TLS identity: a P-256 key and a self-signed certificate.
public struct Identity: Sendable {
    public let privateKey: P256.Signing.PrivateKey
    public let certificateDER: [UInt8]

    public init(privateKey: P256.Signing.PrivateKey, certificateDER: [UInt8]) {
        self.privateKey = privateKey
        self.certificateDER = certificateDER
    }

    public static func generate(commonName: String, now: Date = Date()) throws -> Identity {
        let key = P256.Signing.PrivateKey()
        let name = try DistinguishedName { CommonName(commonName) }
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: .init(key.publicKey),
            notValidBefore: now.addingTimeInterval(-86_400),
            notValidAfter: now.addingTimeInterval(86_400 * 365 * 20),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: try Certificate.Extensions {
                Critical(BasicConstraints.notCertificateAuthority)
            },
            issuerPrivateKey: .init(key)
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return Identity(privateKey: key, certificateDER: serializer.serializedBytes)
    }

    public var fingerprint: String { Fingerprint.sha256Hex(certificateDER) }

    public func nioCertificate() throws -> NIOSSLCertificate {
        try NIOSSLCertificate(bytes: certificateDER, format: .der)
    }

    public func nioPrivateKey() throws -> NIOSSLPrivateKey {
        try NIOSSLPrivateKey(bytes: Array(privateKey.derRepresentation), format: .der)
    }

    public func certificatePEM() throws -> String {
        try Certificate(derEncoded: certificateDER).serializeAsPEM().pemString
    }
}
