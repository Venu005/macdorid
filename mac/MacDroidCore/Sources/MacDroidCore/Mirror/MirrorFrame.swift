import Foundation

public enum MirrorFrameType: UInt8, Sendable { case config = 1, video = 2, audio = 3 }

public enum MirrorFrameError: Error, Equatable { case tooShort, tooLarge, unknownType(UInt8) }

/// One binary message on the media WebSocket (spec §5.2): a 12-byte big-endian header, then the payload.
public struct MirrorFrame: Equatable, Sendable {
    public static let headerSize = 12
    public static let maxSize = 4 * 1024 * 1024

    public var type: MirrorFrameType
    public var keyFrame: Bool
    /// Phone monotonic clock, µs.
    public var captureMicros: Int64
    public var payload: Data

    public init(type: MirrorFrameType, keyFrame: Bool, captureMicros: Int64, payload: Data) {
        self.type = type
        self.keyFrame = keyFrame
        self.captureMicros = captureMicros
        self.payload = payload
    }

    public func encoded() -> Data {
        var data = Data(capacity: Self.headerSize + payload.count)
        data.append(type.rawValue)
        data.append(keyFrame ? 1 : 0)
        data.append(contentsOf: [0, 0])
        withUnsafeBytes(of: captureMicros.bigEndian) { data.append(contentsOf: $0) }
        data.append(payload)
        return data
    }

    public static func decode(_ data: Data) throws -> MirrorFrame {
        guard data.count <= maxSize else { throw MirrorFrameError.tooLarge }
        guard data.count >= headerSize else { throw MirrorFrameError.tooShort }
        let bytes = [UInt8](data.prefix(headerSize))
        guard let type = MirrorFrameType(rawValue: bytes[0]) else { throw MirrorFrameError.unknownType(bytes[0]) }
        var micros: Int64 = 0
        for byte in bytes[4..<12] { micros = micros << 8 | Int64(byte) }
        return MirrorFrame(type: type, keyFrame: bytes[1] & 1 == 1, captureMicros: micros, payload: Data(data.dropFirst(headerSize)))
    }
}
