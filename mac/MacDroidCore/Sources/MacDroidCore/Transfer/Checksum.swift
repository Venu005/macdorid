import Crypto
import Foundation

enum Checksum {
    static func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).hex }
}

extension SHA256.Digest {
    /// Lowercase hex, the form used on the wire.
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
