import Foundation

/// What the QR code carries. Must stay byte-identical with `protocol/fixtures/pairing-uri.txt`.
public struct PairingInvite: Equatable, Sendable {
    public var host: String
    public var port: Int
    public var fingerprint: String
    public var code: String
    public var name: String

    public init(host: String, port: Int, fingerprint: String, code: String, name: String) {
        self.host = host
        self.port = port
        self.fingerprint = fingerprint
        self.code = code
        self.name = name
    }

    public var uri: String {
        let fields = [("host", host), ("port", String(port)), ("fp", fingerprint), ("code", code), ("name", name)]
        let query = fields.map { "\($0.0)=\(Self.percentEncode($0.1))" }.joined(separator: "&")
        return "macdroid://pair?\(query)"
    }

    /// Code in groups of four for typing on the phone, e.g. "MZXW 6YTB …".
    public var displayCode: String {
        let characters = Array(code)
        return stride(from: 0, to: characters.count, by: 4)
            .map { String(characters[$0..<min($0 + 4, characters.count)]) }
            .joined(separator: " ")
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    /// Encodes everything outside RFC 3986 "unreserved" (so `+` becomes `%2B`, never a space).
    static func percentEncode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
    }
}
